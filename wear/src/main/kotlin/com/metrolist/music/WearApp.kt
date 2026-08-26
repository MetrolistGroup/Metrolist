/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.webkit.CookieManager
import android.widget.Toast
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistConjunctions
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.kugou.KuGou
import com.metrolist.lastfm.LastFM
import com.metrolist.music.core.BuildConfig as CoreBuildConfig
import com.metrolist.music.core.R
import com.metrolist.music.constants.*
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.extensions.toInetSocketAddress
import com.metrolist.music.utils.CrashHandler
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.utils.reportException
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class WearApp :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Volatile
    private var cachedCoilCacheSize: Int? = null

    override fun onCreate() {
        super.onCreate()

        // CrashActivity runs in a separate process. Starting the full app there can make that
        // process claim WebView's data directory and crash the next main-process WebView.
        if (!isMainProcess()) return

        // Plant logging
        Timber.plant(Timber.DebugTree())

        // Install crash handler first
        CrashHandler.install(this)
        ArtistNameAliases.initialize(this)

        // preferencesDataStore uses filesDir/datastore; proactive mkdir reduces failures on odd ROM states
        try {
            val datastoreDir = File(filesDir, "datastore")
            if (!datastoreDir.isDirectory && !datastoreDir.mkdirs()) {
                Timber.w("Could not create DataStore directory at ${datastoreDir.path}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to ensure DataStore directory")
        }

        // Initialize cipher deobfuscator for WEB_REMIX streaming
        CipherDeobfuscator.initialize(this)

        // Pre-read Coil cache size on background to avoid runBlocking in newImageLoader
        applicationScope.launch(Dispatchers.IO) {
            cachedCoilCacheSize = dataStore.data.map { it[MaxImageCacheSizeKey] ?: 512 }.first()
        }

        applicationScope.launch {
            // Apply settings (incl. YouTube.proxy) FIRST
            initializeSettings()

            // Prewarm cipher and PoToken
            launch(Dispatchers.IO) {
                delay(1500)
                runCatching { CipherDeobfuscator.prewarm() }
            }

            launch(Dispatchers.IO) {
                delay(2500)
                var waitedMs = 0
                while (YouTube.visitorData == null && waitedMs < 12_000) {
                    delay(500)
                    waitedMs += 500
                }
                runCatching { YTPlayerUtils.prewarmPoToken() }
            }

            observeSettingsChanges()
        }
    }

    private suspend fun initializeSettings() {
        val settings = dataStore.data.first()
        val locale = Locale.getDefault()
        val languageTag = locale.language

        ArtistConjunctions.conjunctions = listOf(
            R.string.and,
        ).mapNotNull { id ->
            runCatching { getString(id) }.getOrNull()
        }

        YouTube.locale =
            YouTubeLocale(
                gl =
                    settings[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }
                        ?: locale.country.takeIf { it in CountryCodeToName }
                        ?: "US",
                hl =
                    settings[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }
                        ?: locale.language.takeIf { it in LanguageCodeToName }
                        ?: languageTag.takeIf { it in LanguageCodeToName }
                        ?: "en",
            )

        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }

        // Initialize LastFM with API keys from BuildConfig
        LastFM.initialize(
            apiKey = CoreBuildConfig.LASTFM_API_KEY.takeIf { it.isNotEmpty() } ?: "",
            secret = CoreBuildConfig.LASTFM_SECRET.takeIf { it.isNotEmpty() } ?: "",
        )

        if (settings[ProxyEnabledKey] == true) {
            val username = settings[ProxyUsernameKey].orEmpty()
            val password = settings[ProxyPasswordKey].orEmpty()
            val type = settings[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP)

            if (username.isNotEmpty() || password.isNotEmpty()) {
                if (type == Proxy.Type.HTTP) {
                    YouTube.proxyAuth = Credentials.basic(username, password)
                } else {
                    Authenticator.setDefault(
                        object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication =
                                PasswordAuthentication(username, password.toCharArray())
                        },
                    )
                }
            }
            try {
                settings[ProxyUrlKey]?.let {
                    YouTube.proxy = Proxy(type, it.toInetSocketAddress())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WearApp, getString(R.string.failed_to_parse_proxy), Toast.LENGTH_SHORT).show()
                }
                reportException(e)
            }
        }

        YouTube.useLoginForBrowse = settings[UseLoginForBrowse] ?: true

        val channel =
            NotificationChannel(
                "updates",
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.update_channel_desc)
            }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun observeSettingsChanges() {
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[VisitorDataKey] }
                .distinctUntilChanged()
                .collect { visitorData ->
                    YouTube.visitorData = visitorData?.takeIf { it != "null" }
                        ?: YouTube.visitorData().getOrNull()?.also { newVisitorData ->
                            try {
                                safeDataStoreEdit { settings ->
                                    settings[VisitorDataKey] = newVisitorData
                                }
                            } catch (e: IOException) {
                                Timber.e(e, "DataStore write failed for visitor data")
                                reportException(e)
                            }
                        }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[DataSyncIdKey] }
                .distinctUntilChanged()
                .collect { dataSyncId ->
                    YouTube.dataSyncId =
                        dataSyncId?.let {
                            it.takeIf { !it.contains("||") }
                                ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                                ?: it.substringAfter("||")
                        }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[InnerTubeAuthUserKey] ?: "0" }
                .distinctUntilChanged()
                .collect { authUser ->
                    YouTube.authUser = authUser
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { cookie ->
                    try {
                        YouTube.cookie = cookie
                    } catch (e: Exception) {
                        Timber.e(e, "Could not parse cookie. Clearing existing cookie.")
                        forgetAccount(this@WearApp)
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[LastFMSessionKey] }
                .distinctUntilChanged()
                .collect { session ->
                    try {
                        LastFM.sessionKey = session
                    } catch (e: Exception) {
                        Timber.e("Error while loading last.fm session key. %s", e.message)
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { Triple(it[ContentCountryKey], it[ContentLanguageKey], it[AppLanguageKey]) }
                .distinctUntilChanged()
                .collect { (contentCountry, contentLanguage, appLanguage) ->
                    val systemLocale = Locale.getDefault()
                    val effectiveAppLocale =
                        appLanguage
                            ?.takeUnless { it == SYSTEM_DEFAULT }
                            ?.let { Locale.forLanguageTag(it) }
                            ?: systemLocale

                    YouTube.locale =
                        YouTubeLocale(
                            gl =
                                contentCountry?.takeIf { it != SYSTEM_DEFAULT }
                                    ?: effectiveAppLocale.country.takeIf { it in CountryCodeToName }
                                    ?: systemLocale.country.takeIf { it in CountryCodeToName }
                                    ?: "US",
                            hl =
                                contentLanguage?.takeIf { it != SYSTEM_DEFAULT }
                                    ?: effectiveAppLocale.toLanguageTag().takeIf { it in LanguageCodeToName }
                                    ?: effectiveAppLocale.language.takeIf { it in LanguageCodeToName }
                                    ?: "en",
                        )
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val cacheSize = cachedCoilCacheSize ?: runBlocking {
            dataStore.data.map { it[MaxImageCacheSizeKey] ?: 512 }.first()
        }
        return ImageLoader
            .Builder(this)
            .apply {
                crossfade(true)
                allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                memoryCache {
                    MemoryCache
                        .Builder()
                        .maxSizePercent(context, 0.15)
                        .build()
                }
                if (cacheSize == 0) {
                    diskCachePolicy(CachePolicy.DISABLED)
                } else {
                    diskCache(
                        DiskCache
                            .Builder()
                            .directory(cacheDir.resolve("coil"))
                            .maxSizeBytes(cacheSize * 1024 * 1024L)
                            .build(),
                    )
                    networkCachePolicy(CachePolicy.ENABLED)
                }
            }.build()
    }

    private fun isMainProcess(): Boolean {
        val processName =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                runCatching {
                    File("/proc/self/cmdline").readText().substringBefore('\u0000')
                }.getOrNull()?.takeIf(String::isNotBlank)
                    ?: run {
                        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        activityManager.runningAppProcesses
                            ?.firstOrNull { it.pid == Process.myPid() }
                            ?.processName
                    }
            }
        return processName == packageName
    }

    companion object {
        suspend fun forgetAccount(context: Context) {
            context.safeDataStoreEdit { settings ->
                settings.remove(InnerTubeCookieKey)
                settings.remove(VisitorDataKey)
                settings.remove(DataSyncIdKey)
                settings.remove(InnerTubeAuthUserKey)
                settings.remove(AccountNameKey)
                settings.remove(AccountEmailKey)
                settings.remove(AccountChannelHandleKey)
            }
            YouTube.cookie = null
            YouTube.visitorData = null
            YouTube.dataSyncId = null
            YouTube.authUser = "0"
            withContext(Dispatchers.Main) {
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
            }
        }
    }
}

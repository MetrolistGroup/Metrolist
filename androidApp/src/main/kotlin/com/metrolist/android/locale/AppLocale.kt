package com.metrolist.android.locale

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import com.metrolist.android.BuildConfig
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.canonicalAppLanguageTag
import com.metrolist.shared.utils.SystemLocale
import java.util.Locale

object AppLocale {
    const val SYSTEM = "SYSTEM"
    private const val PREFERENCES = "app_locale"
    private const val LANGUAGE = "language"

    fun normalize(value: String?): String {
        val raw = value.orEmpty().trim()
        if (raw.isEmpty() || raw.equals(SYSTEM, true) || raw.equals("SYSTEM_DEFAULT", true)) return SYSTEM
        return Locale.forLanguageTag(canonicalAppLanguageTag(raw.replace('_', '-'))).toLanguageTag().takeUnless { it == "und" } ?: SYSTEM
    }

    fun systemLocale(context: Context): Locale {
        val resourceLocale = Resources.getSystem().configuration.locales[0]
        val managedLocale =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context
                    .getSystemService(LocaleManager::class.java)
                    .systemLocales
                    .takeUnless(LocaleList::isEmpty)
                    ?.get(0)
            } else {
                null
            }
        return selectSystemLocale(managedLocale, resourceLocale)
    }

    fun applySelection(
        context: Context,
        value: String?,
    ) {
        SystemLocale.current = systemLocale(context)
        val normalized = normalize(value)
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit {
                putString(LANGUAGE, normalized)
            }
        val locale = resolveSelectedLocale(normalized, BuildConfig.BUNDLED_LANGUAGE_TAGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                locale?.let(::LocaleList) ?: LocaleList.getEmptyLocaleList()
        }
        Locale.setDefault(locale ?: systemLocale(context))
    }

    fun sync(context: Context) = applySelection(context, Settings.shared.appLanguage)

    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(context: Context): Context {
        SystemLocale.current = systemLocale(context)
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE, SYSTEM)
        val locale = resolveSelectedLocale(saved, BuildConfig.BUNDLED_LANGUAGE_TAGS) ?: return context
        Locale.setDefault(locale)
        // Inherit display and font settings from the launching window.
        val configuration = Configuration().apply { fontScale = 0f }
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }

    internal fun selectSystemLocale(
        managedLocale: Locale?,
        resourceLocale: Locale,
    ): Locale = managedLocale ?: resourceLocale

    internal fun resolveSelectedLocale(
        value: String?,
        bundledLanguageTags: String,
    ): Locale? {
        val normalized = normalize(value)
        if (normalized == SYSTEM) return null
        val bundled = bundledLanguageTags.split(',').filter(String::isNotBlank)
        val matched =
            bundled.firstOrNull { it.equals(normalized, ignoreCase = true) }
                ?: Locale
                    .forLanguageTag(normalized)
                    .language
                    .takeIf(String::isNotBlank)
                    ?.let { language -> bundled.firstOrNull { Locale.forLanguageTag(it).language == language } }
        return matched?.let(Locale::forLanguageTag)
    }
}

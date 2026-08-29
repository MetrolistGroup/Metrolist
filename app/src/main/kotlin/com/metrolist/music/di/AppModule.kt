/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.di

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.metrolist.music.constants.MaxSongCacheSizeKey
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.listentogether.ListenTogetherClient
import com.metrolist.music.listentogether.ListenTogetherManager
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet
import javax.inject.Singleton

private class LazyCache(
    private val create: () -> SimpleCache,
) : Cache {
    private val lock = Any()

    @Volatile private var cache: SimpleCache? = null

    private fun delegate(): SimpleCache = cache ?: synchronized(lock) { cache ?: create().also { cache = it } }

    override fun addListener(
        key: String,
        listener: Cache.Listener,
    ) = delegate().addListener(key, listener)

    override fun removeListener(
        key: String,
        listener: Cache.Listener,
    ) = delegate().removeListener(key, listener)

    override fun getCachedSpans(key: String): NavigableSet<CacheSpan> = delegate().getCachedSpans(key)

    override fun getKeys(): NavigableSet<String> = TreeSet(delegate().keys)

    override fun getCacheSpace(): Long = delegate().cacheSpace

    override fun getUid(): Long = delegate().uid

    override fun getCachedLength(
        key: String,
        position: Long,
        length: Long,
    ): Long = delegate().getCachedLength(key, position, length)

    override fun getCachedBytes(
        key: String,
        position: Long,
        length: Long,
    ): Long = delegate().getCachedBytes(key, position, length)

    override fun applyContentMetadataMutations(
        key: String,
        mutations: ContentMetadataMutations,
    ) = delegate().applyContentMetadataMutations(key, mutations)

    override fun getContentMetadata(key: String): ContentMetadata = delegate().getContentMetadata(key)

    override fun startReadWrite(
        key: String,
        position: Long,
        length: Long,
    ): CacheSpan = delegate().startReadWrite(key, position, length)

    override fun startReadWriteNonBlocking(
        key: String,
        position: Long,
        length: Long,
    ): CacheSpan? = delegate().startReadWriteNonBlocking(key, position, length)

    override fun startFile(
        key: String,
        position: Long,
        maxLength: Long,
    ): File = delegate().startFile(key, position, maxLength)

    override fun commitFile(
        file: File,
        length: Long,
    ) = delegate().commitFile(file, length)

    override fun releaseHoleSpan(holeSpan: CacheSpan) = delegate().releaseHoleSpan(holeSpan)

    override fun removeSpan(span: CacheSpan) = delegate().removeSpan(span)

    override fun removeResource(key: String) = delegate().removeResource(key)

    override fun isCached(
        key: String,
        position: Long,
        length: Long,
    ): Boolean = delegate().isCached(key, position, length)

    override fun release() {
        val cacheToRelease =
            synchronized(lock) {
                cache.also { cache = null }
            }
        cacheToRelease?.release()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Singleton
    @Provides
    fun provideDao(
        database: InternalDatabase,
    ) = database.dao

    @Singleton
    @Provides
    fun provideInternalDatabase(
        @ApplicationContext context: Context,
    ): InternalDatabase = InternalDatabase.newInternalDatabaseInstance(context)

    @Singleton
    @Provides
    fun provideDatabase(
        internalDatabase: InternalDatabase,
    ): MusicDatabase = MusicDatabase(internalDatabase)

    @Singleton
    @Provides
    fun provideDatabaseProvider(
        @ApplicationContext context: Context,
    ): DatabaseProvider = StandaloneDatabaseProvider(context)

    @Singleton
    @Provides
    @PlayerCache
    fun providePlayerCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): Cache =
        LazyCache {
            val cacheSize = context.dataStore[MaxSongCacheSizeKey] ?: 1024
            val evictor =
                when (cacheSize) {
                    -1 -> NoOpCacheEvictor()
                    else -> LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
                }
            SimpleCache(
                context.filesDir.resolve("exoplayer"),
                evictor,
                databaseProvider,
            )
        }

    @Singleton
    @Provides
    @DownloadCache
    fun provideDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): Cache =
        LazyCache {
            SimpleCache(
                context.filesDir.resolve("download"),
                NoOpCacheEvictor(),
                databaseProvider,
            )
        }

    @Singleton
    @Provides
    fun provideListenTogetherClient(
        @ApplicationContext context: Context,
    ): ListenTogetherClient = ListenTogetherClient(context)

    @Singleton
    @Provides
    fun provideListenTogetherManager(
        @ApplicationContext context: Context,
        client: ListenTogetherClient,
    ): ListenTogetherManager = ListenTogetherManager(client, context)
}

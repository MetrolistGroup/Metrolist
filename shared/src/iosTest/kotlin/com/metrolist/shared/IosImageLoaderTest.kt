package com.metrolist.shared

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.memory.MemoryCache
import org.jetbrains.skia.Bitmap
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(DelicateCoilApi::class)
class IosImageLoaderTest {
    @Test
    fun memoryWarningsClearTheCurrentSingletonWithoutReplacingIt() {
        val first = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val second = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        try {
            SingletonImageLoader.setUnsafe(first)
            configureIosImageLoader()
            configureIosImageLoader()
            val key = MemoryCache.Key("cover")
            val image = Bitmap().apply { allocN32Pixels(8, 8) }.asImage()
            val firstCache = assertNotNull(first.memoryCache)
            firstCache[key] = MemoryCache.Value(image)
            SingletonImageLoader.setUnsafe(second)
            val secondCache = assertNotNull(second.memoryCache)
            secondCache[key] = MemoryCache.Value(image)
            assertTrue(secondCache.size > 0L)
            val diskCache = second.diskCache
            NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationDidReceiveMemoryWarningNotification, `object` = null)
            assertEquals(0L, secondCache.size)
            assertTrue(firstCache.size > 0L)
            assertSame(second, SingletonImageLoader.get(PlatformContext.INSTANCE))
            assertSame(diskCache, second.diskCache)
        } finally {
            SingletonImageLoader.reset()
            first.shutdown()
            second.shutdown()
        }
    }
}

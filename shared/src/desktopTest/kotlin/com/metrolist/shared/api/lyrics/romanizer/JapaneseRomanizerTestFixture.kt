package com.metrolist.shared.api.lyrics.romanizer

import java.net.JarURLConnection

internal object JapaneseRomanizerTestFixture {
    fun installDictionary() {
        val resource =
            checkNotNull(
                JapaneseRomanizerTestFixture::class.java.classLoader.getResource(
                    "com/atilika/kuromoji/ipadic/characterDefinitions.bin",
                ),
            )
        val artifactUrl = (resource.openConnection() as JarURLConnection).jarFileURL
        val installer = JapaneseDictionaryInstaller(japaneseDictionaryStorageDirectory(), { artifactUrl.openStream() })
        checkNotNull(installer.install())
    }
}

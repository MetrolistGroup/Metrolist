/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.backup

import java.io.File

private const val PRISM_ARCHIVE_DIRECTORY = ".prism"
private const val PRISM_ARTIST_PREFERENCES = "shared_prefs/artist_prefs.xml"

internal fun prismArchiveFile(
    directory: File,
    entry: String,
): File? =
    if (isPrismBackupDataEntry(entry) || entry == PRISM_ARTIST_PREFERENCES) {
        directory.resolve(PRISM_ARCHIVE_DIRECTORY).resolve(entry)
    } else {
        null
    }

internal fun ForeignLibrary.withPrismArchive(directory: File): ForeignLibrary {
    if (family != ForeignLibraryFamily.PRISM) return this
    val enhanced = withPrismBackupData { entry -> prismArchiveFile(directory, entry)?.takeIf(File::isFile)?.readText() }
    val preferences = prismArchiveFile(directory, PRISM_ARTIST_PREFERENCES)?.takeIf(File::isFile) ?: return enhanced
    return enhanced.withPrismArtistPreferences(preferences.readText())
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

operator fun File.div(child: String): File = File(this, child)

fun InputStream.zipInputStream(): ZipInputStream = ZipInputStream(this)

fun OutputStream.zipOutputStream(): ZipOutputStream = ZipOutputStream(this)

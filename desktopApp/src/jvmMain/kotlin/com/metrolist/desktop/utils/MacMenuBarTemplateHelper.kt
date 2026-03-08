/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.menu_about_metrolist
import com.metrolist.shared.generated.resources.menu_add_to_playlist
import com.metrolist.shared.generated.resources.menu_add_to_queue
import com.metrolist.shared.generated.resources.menu_application_logs
import com.metrolist.shared.generated.resources.menu_back
import com.metrolist.shared.generated.resources.menu_close_window
import com.metrolist.shared.generated.resources.menu_copy
import com.metrolist.shared.generated.resources.menu_copy_link
import com.metrolist.shared.generated.resources.menu_create_station
import com.metrolist.shared.generated.resources.menu_cut
import com.metrolist.shared.generated.resources.menu_decrease_volume
import com.metrolist.shared.generated.resources.menu_download
import com.metrolist.shared.generated.resources.menu_enter_full_screen
import com.metrolist.shared.generated.resources.menu_exit_full_screen
import com.metrolist.shared.generated.resources.menu_forward
import com.metrolist.shared.generated.resources.menu_fullscreen_player
import com.metrolist.shared.generated.resources.menu_go_to_current_song
import com.metrolist.shared.generated.resources.menu_hide_lyrics
import com.metrolist.shared.generated.resources.menu_hide_playing_next
import com.metrolist.shared.generated.resources.menu_history
import com.metrolist.shared.generated.resources.menu_increase_volume
import com.metrolist.shared.generated.resources.menu_keyboard_shortcuts
import com.metrolist.shared.generated.resources.menu_library
import com.metrolist.shared.generated.resources.menu_love_song
import com.metrolist.shared.generated.resources.menu_minimize
import com.metrolist.shared.generated.resources.menu_miniplayer
import com.metrolist.shared.generated.resources.menu_mute
import com.metrolist.shared.generated.resources.menu_new_playlist
import com.metrolist.shared.generated.resources.menu_next
import com.metrolist.shared.generated.resources.menu_paste
import com.metrolist.shared.generated.resources.menu_pause
import com.metrolist.shared.generated.resources.menu_play
import com.metrolist.shared.generated.resources.menu_play_next
import com.metrolist.shared.generated.resources.menu_previous
import com.metrolist.shared.generated.resources.menu_redo
import com.metrolist.shared.generated.resources.menu_reload
import com.metrolist.shared.generated.resources.menu_remove_download
import com.metrolist.shared.generated.resources.menu_repeat
import com.metrolist.shared.generated.resources.menu_report_issue
import com.metrolist.shared.generated.resources.menu_select_all
import com.metrolist.shared.generated.resources.menu_set_volume_maximum
import com.metrolist.shared.generated.resources.menu_set_volume_minimum
import com.metrolist.shared.generated.resources.menu_share
import com.metrolist.shared.generated.resources.menu_show_album_in_library
import com.metrolist.shared.generated.resources.menu_show_artist_in_library
import com.metrolist.shared.generated.resources.menu_show_in_youtube_music
import com.metrolist.shared.generated.resources.menu_show_lyrics
import com.metrolist.shared.generated.resources.menu_show_playing_next
import com.metrolist.shared.generated.resources.menu_shuffle
import com.metrolist.shared.generated.resources.menu_sign_in
import com.metrolist.shared.generated.resources.menu_sign_out
import com.metrolist.shared.generated.resources.menu_stop
import com.metrolist.shared.generated.resources.menu_switch_account
import com.metrolist.shared.generated.resources.menu_undo
import com.metrolist.shared.generated.resources.menu_unlove_song
import com.metrolist.shared.generated.resources.menu_unmute
import com.metrolist.shared.generated.resources.menu_zoom
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.generated.resources.statistics
import com.metrolist.shared.generated.resources.user
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.OsUtil
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds

object MacMenuBarTemplateHelper {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var syncJob: Job? = null

    private var cachedAvatarUrl: String? = null
    private var cachedAvatarBytes: ByteArray? = null

    @Volatile
    private var cachedUserLabel: String? = null

    interface DispatchCallback : Callback {
        fun callback(context: Pointer?)
    }

    interface MenuWillOpenCallback : Callback {
        fun callback(
            self: Pointer?,
            _cmd: Pointer?,
            menu: Pointer?,
        )
    }

    @Suppress("FunctionName")
    interface LibDispatch : Library {
        fun dispatch_async_f(
            queue: Pointer?,
            context: Pointer?,
            work: DispatchCallback,
        )
    }

    @Suppress("FunctionName")
    interface LibObjC : Library {
        fun objc_getClass(className: String): Pointer?

        fun sel_registerName(selectorName: String): Pointer?

        fun objc_allocateClassPair(
            superclass: Pointer?,
            name: String,
            extraBytes: Int,
        ): Pointer?

        fun class_addMethod(
            cls: Pointer?,
            name: Pointer?,
            imp: Callback,
            types: String,
        ): Boolean

        fun objc_registerClassPair(cls: Pointer?)
    }

    private val libObjC: LibObjC? by lazy {
        if (!OsUtil.isMac) {
            null
        } else {
            runCatching { Native.load("objc", LibObjC::class.java) }.getOrNull()
        }
    }

    private val msgSendFunc by lazy {
        if (!OsUtil.isMac) {
            null
        } else {
            runCatching {
                NativeLibrary.getInstance("objc").getFunction("objc_msgSend")
            }.getOrNull()
        }
    }

    private fun invokeMsgSendLong(
        receiver: Pointer?,
        selector: Pointer?,
    ): Long {
        if (receiver == null || selector == null) return 0L
        return runCatching { msgSendFunc?.invokeLong(arrayOf(receiver, selector)) ?: 0L }.getOrDefault(0L)
    }

    private fun invokeMsgSendPointer(
        receiver: Pointer?,
        selector: Pointer?,
        vararg args: Any?,
    ): Pointer? {
        if (receiver == null || selector == null) return null
        val fullArgs = arrayOf(receiver, selector, *args)
        return runCatching { msgSendFunc?.invokePointer(fullArgs) }.getOrNull()
    }

    private val libDispatch: LibDispatch? by lazy {
        if (!OsUtil.isMac) {
            null
        } else {
            runCatching { Native.load("System", LibDispatch::class.java) }.getOrNull()
        }
    }

    private val mainQueue: Pointer? by lazy {
        if (!OsUtil.isMac) {
            null
        } else {
            runCatching {
                NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q")
            }.getOrNull()
        }
    }

    // Keep references so JNA doesn't GC callback pointers
    private var activeCallback: DispatchCallback? = null
    private var menuDelegateInstance: Pointer? = null
    private var menuWillOpenCallbackHolder: MenuWillOpenCallback? = null

    @Volatile
    private var sfSymbolMap: Map<String, String> = emptyMap()

    private val symbolResources =
        mapOf(
            Res.string.menu_play to "play.fill",
            Res.string.menu_pause to "pause.fill",
            Res.string.menu_stop to "stop.fill",
            Res.string.menu_next to "forward.end.fill",
            Res.string.menu_previous to "backward.end.fill",
            Res.string.menu_go_to_current_song to "music.note",
            Res.string.menu_set_volume_maximum to "speaker.wave.3.fill",
            Res.string.menu_set_volume_minimum to "speaker.wave.1.fill",
            Res.string.menu_increase_volume to "speaker.wave.3",
            Res.string.menu_decrease_volume to "speaker.wave.1",
            Res.string.menu_mute to "speaker.slash.fill",
            Res.string.menu_unmute to "speaker.wave.2.fill",
            Res.string.menu_shuffle to "shuffle",
            Res.string.menu_repeat to "repeat",
            Res.string.menu_back to "chevron.left",
            Res.string.menu_forward to "chevron.right",
            Res.string.menu_new_playlist to "music.badge.plus",
            Res.string.menu_add_to_playlist to "plus.circle",
            Res.string.menu_share to "square.and.arrow.up",
            Res.string.menu_copy_link to "link",
            Res.string.menu_play_next to "text.line.first.and.arrowtriangle.forward",
            Res.string.menu_add_to_queue to "text.badge.plus",
            Res.string.menu_create_station to "dot.radiowaves.left.and.right",
            Res.string.menu_show_artist_in_library to "person",
            Res.string.menu_show_album_in_library to "opticaldisc",
            Res.string.menu_show_in_youtube_music to "music.note",
            Res.string.menu_love_song to "heart",
            Res.string.menu_unlove_song to "heart.fill",
            Res.string.menu_download to "arrow.down.circle",
            Res.string.menu_remove_download to "checkmark.circle.fill",
            Res.string.menu_show_lyrics to "quote.bubble",
            Res.string.menu_hide_lyrics to "quote.bubble.fill",
            Res.string.menu_show_playing_next to "list.bullet",
            Res.string.menu_hide_playing_next to "list.bullet",
            Res.string.menu_fullscreen_player to "arrow.up.left.and.arrow.down.right",
            Res.string.home to "house",
            Res.string.menu_library to "books.vertical",
            Res.string.menu_history to "clock",
            Res.string.statistics to "chart.bar",
            Res.string.menu_enter_full_screen to "arrow.up.left.and.arrow.down.right",
            Res.string.menu_exit_full_screen to "arrow.down.right.and.arrow.up.left",
            Res.string.search to "magnifyingglass",
            Res.string.menu_reload to "arrow.clockwise",
            Res.string.settings to "gearshape",
            Res.string.menu_close_window to "xmark",
            Res.string.menu_undo to "arrow.uturn.backward",
            Res.string.menu_redo to "arrow.uturn.forward",
            Res.string.menu_cut to "scissors",
            Res.string.menu_copy to "doc.on.doc",
            Res.string.menu_paste to "doc.on.clipboard",
            Res.string.menu_select_all to "selection.pin.in.out",
            Res.string.menu_minimize to "minus",
            Res.string.menu_zoom to "arrow.up.left.and.arrow.down.right",
            Res.string.menu_miniplayer to "pip",
            Res.string.menu_switch_account to "arrow.triangle.2.circlepath",
            Res.string.menu_sign_out to "rectangle.portrait.and.arrow.right",
            Res.string.menu_sign_in to "person.crop.circle.badge.plus",
            Res.string.menu_keyboard_shortcuts to "command",
            Res.string.menu_application_logs to "doc.text",
            Res.string.menu_report_issue to "ladybug",
            Res.string.menu_about_metrolist to "info.circle",
        )

    fun applyNativeIconsAndTemplates() {
        if (!OsUtil.isMac) return
        syncJob?.cancel()
        syncJob =
            scope.launch {
                sfSymbolMap = symbolResources.entries.associate { (resource, symbol) -> getString(resource) to symbol }
                cachedUserLabel = getString(Res.string.user)
                val avatarUrl = AuthState.profilePicUrl ?: Settings.shared.accountThumbnail.takeIf { it.isNotBlank() }
                if (!avatarUrl.isNullOrBlank()) {
                    if (cachedAvatarUrl != avatarUrl || cachedAvatarBytes == null) {
                        try {
                            val img = ImageIO.read(URI(avatarUrl).toURL())
                            if (img != null) {
                                val size = 36
                                val rounded = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                                val g = rounded.createGraphics()
                                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                g.clip = Ellipse2D.Float(0f, 0f, size.toFloat(), size.toFloat())
                                g.drawImage(img, 0, 0, size, size, null)
                                g.dispose()

                                val baos = ByteArrayOutputStream()
                                ImageIO.write(rounded, "PNG", baos)
                                cachedAvatarBytes = baos.toByteArray()
                                cachedAvatarUrl = avatarUrl
                            }
                        } catch (_: Throwable) {
                        }
                    }
                } else {
                    cachedAvatarBytes = null
                    cachedAvatarUrl = null
                }

                // Yield briefly for AWT / Compose to populate NSApp mainMenu
                delay(50.milliseconds)
                val dispatch = libDispatch
                val queue = mainQueue
                if (dispatch != null && queue != null) {
                    val cb =
                        object : DispatchCallback {
                            override fun callback(context: Pointer?) {
                                executeApply(cachedAvatarBytes)
                            }
                        }
                    activeCallback = cb
                    dispatch.dispatch_async_f(queue, null, cb)
                }
            }
    }

    private fun getOrCreateMenuDelegate(): Pointer? {
        if (menuDelegateInstance != null) return menuDelegateInstance
        val objc = libObjC ?: return null
        val nsObjectClass = objc.objc_getClass("NSObject") ?: return null

        val className = "MetrolistMenuDelegate"
        var cls = objc.objc_getClass(className)
        if (cls == null) {
            cls = objc.objc_allocateClassPair(nsObjectClass, className, 0)
            if (cls != null) {
                val menuWillOpenSel = objc.sel_registerName("menuWillOpen:")
                val cb =
                    object : MenuWillOpenCallback {
                        override fun callback(
                            self: Pointer?,
                            _cmd: Pointer?,
                            menu: Pointer?,
                        ) {
                            if (menu != null) {
                                executeApplySingleMenu(menu, cachedAvatarBytes)
                            }
                        }
                    }
                menuWillOpenCallbackHolder = cb
                objc.class_addMethod(cls, menuWillOpenSel, cb, "v@:@")
                objc.objc_registerClassPair(cls)
            }
        }

        if (cls != null) {
            val allocSel = objc.sel_registerName("alloc")
            val initSel = objc.sel_registerName("init")
            val uninit = invokeMsgSendPointer(cls, allocSel)
            menuDelegateInstance = invokeMsgSendPointer(uninit, initSel)
        }
        return menuDelegateInstance
    }

    private fun executeApply(avatarBytes: ByteArray?) {
        val objc = libObjC ?: return

        try {
            val nsAppClass = objc.objc_getClass("NSApplication") ?: return
            val sharedAppSel = objc.sel_registerName("sharedApplication") ?: return
            val app = invokeMsgSendPointer(nsAppClass, sharedAppSel) ?: return

            val mainMenuSel = objc.sel_registerName("mainMenu") ?: return
            val mainMenu = invokeMsgSendPointer(app, mainMenuSel) ?: return

            val delegate = getOrCreateMenuDelegate()
            val setDelegateSel = objc.sel_registerName("setDelegate:")

            fun processTree(menu: Pointer?) {
                if (menu == null) return
                if (delegate != null && setDelegateSel != null) {
                    invokeMsgSendPointer(menu, setDelegateSel, delegate)
                }
                executeApplySingleMenu(menu, avatarBytes)

                val itemArraySel = objc.sel_registerName("itemArray") ?: return
                val countSel = objc.sel_registerName("count") ?: return
                val objectAtIndexSel = objc.sel_registerName("objectAtIndex:") ?: return
                val submenuSel = objc.sel_registerName("submenu") ?: return

                val items = invokeMsgSendPointer(menu, itemArraySel) ?: return
                val count = invokeMsgSendLong(items, countSel)
                for (i in 0 until count) {
                    val item = invokeMsgSendPointer(items, objectAtIndexSel, i) ?: continue
                    val sub = invokeMsgSendPointer(item, submenuSel)
                    if (sub != null) {
                        processTree(sub)
                    }
                }
            }

            processTree(mainMenu)
        } catch (e: Throwable) {
            AppLogger.warning("MacMenuBar", "Failed to apply native symbols/templates: ${e.message}")
        }
    }

    private fun executeApplySingleMenu(
        menu: Pointer?,
        avatarBytes: ByteArray?,
    ) {
        val objc = libObjC ?: return
        if (menu == null) return

        var userAvatarNsImage: Pointer? = null
        try {
            val itemArraySel = objc.sel_registerName("itemArray") ?: return
            val countSel = objc.sel_registerName("count") ?: return
            val objectAtIndexSel = objc.sel_registerName("objectAtIndex:") ?: return
            val titleSel = objc.sel_registerName("title") ?: return
            val utf8StringSel = objc.sel_registerName("UTF8String") ?: return
            val imageSel = objc.sel_registerName("image") ?: return
            val setImageSel = objc.sel_registerName("setImage:") ?: return
            val setTemplateSel = objc.sel_registerName("setTemplate:") ?: return

            val nsStringClass = objc.objc_getClass("NSString") ?: return
            val stringWithUTF8Sel = objc.sel_registerName("stringWithUTF8String:") ?: return

            val nsImageClass = objc.objc_getClass("NSImage") ?: return
            val symbolSel = objc.sel_registerName("imageWithSystemSymbolName:accessibilityDescription:")

            val nsDataClass = objc.objc_getClass("NSData")
            val dataWithBytesSel = objc.sel_registerName("dataWithBytes:length:")
            val allocSel = objc.sel_registerName("alloc")
            val initWithDataSel = objc.sel_registerName("initWithData:")

            fun toNSString(str: String): Pointer? {
                val bytes = str.toByteArray(Charsets.UTF_8)
                return Memory(bytes.size.toLong() + 1).use { mem ->
                    mem.write(0, bytes, 0, bytes.size)
                    mem.setByte(bytes.size.toLong(), 0.toByte())
                    invokeMsgSendPointer(nsStringClass, stringWithUTF8Sel, mem)
                }
            }

            fun getSystemSymbol(name: String): Pointer? {
                if (symbolSel == null) return null
                val nameNsStr = toNSString(name) ?: return null
                return invokeMsgSendPointer(nsImageClass, symbolSel, nameNsStr, null)
            }

            fun createAvatarImage(): Pointer? {
                if (avatarBytes == null || avatarBytes.isEmpty()) return null
                if (nsDataClass == null || dataWithBytesSel == null || allocSel == null || initWithDataSel == null) return null
                val nsData =
                    Memory(avatarBytes.size.toLong()).use { mem ->
                        mem.write(0, avatarBytes, 0, avatarBytes.size)
                        invokeMsgSendPointer(nsDataClass, dataWithBytesSel, mem, avatarBytes.size.toLong())
                    } ?: return null
                val uninitImg = invokeMsgSendPointer(nsImageClass, allocSel) ?: return null
                return invokeMsgSendPointer(uninitImg, initWithDataSel, nsData)
            }

            val items = invokeMsgSendPointer(menu, itemArraySel) ?: return
            val count = invokeMsgSendLong(items, countSel)

            for (i in 0 until count) {
                val item = invokeMsgSendPointer(items, objectAtIndexSel, i) ?: continue

                val titleNsStr = invokeMsgSendPointer(item, titleSel)
                val utf8Ptr = if (titleNsStr != null) invokeMsgSendPointer(titleNsStr, utf8StringSel) else null
                val rawTitle = utf8Ptr?.getString(0, "UTF-8")

                var appliedSfSymbol = false
                if (!rawTitle.isNullOrBlank()) {
                    val cleanedTitle = rawTitle.replace("…", "").replace("...", "").trim()
                    val currentAccount = Settings.shared.accountName
                    val isUserAccountItem =
                        (currentAccount.isNotBlank() && rawTitle == currentAccount) ||
                            rawTitle == cachedUserLabel

                    if (isUserAccountItem && userAvatarNsImage == null) userAvatarNsImage = createAvatarImage()
                    if (isUserAccountItem && userAvatarNsImage != null) {
                        invokeMsgSendPointer(userAvatarNsImage, setTemplateSel, false)
                        invokeMsgSendPointer(item, setImageSel, userAvatarNsImage)
                        appliedSfSymbol = true
                    } else {
                        val matchedSymbol =
                            sfSymbolMap.entries
                                .firstOrNull { entry ->
                                    val key = entry.key
                                    key.equals(rawTitle, ignoreCase = true) ||
                                        key.equals(cleanedTitle, ignoreCase = true)
                                }?.value
                        val symbolName =
                            sfSymbolMap[rawTitle]
                                ?: sfSymbolMap[cleanedTitle]
                                ?: matchedSymbol
                                ?: if (isUserAccountItem) "person.crop.circle" else null

                        if (symbolName != null) {
                            val symbolImg = getSystemSymbol(symbolName)
                            if (symbolImg != null) {
                                invokeMsgSendPointer(symbolImg, setTemplateSel, true)
                                invokeMsgSendPointer(item, setImageSel, symbolImg)
                                appliedSfSymbol = true
                            }
                        }
                    }
                }

                if (!appliedSfSymbol) {
                    val currentImg = invokeMsgSendPointer(item, imageSel)
                    if (currentImg != null) {
                        invokeMsgSendPointer(currentImg, setTemplateSel, true)
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            invokeMsgSendPointer(userAvatarNsImage, objc.sel_registerName("release"))
        }
    }
}

package com.metrolist.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.SkiaLayer
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.beans.PropertyChangeListener
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

@Composable
internal fun ObserveHyprlandRenderVisibility(
    window: ComposeWindow,
    onVisibilityChanged: (Boolean) -> Unit,
) {
    val instance = System.getenv("HYPRLAND_INSTANCE_SIGNATURE")?.takeIf(String::isNotBlank) ?: return
    val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf(String::isNotBlank) ?: return
    val scope = rememberCoroutineScope()
    DisposableEffect(window, instance, runtime, onVisibilityChanged) {
        val title = AtomicReference(window.title)
        val listener = PropertyChangeListener { title.set(window.title) }
        window.addPropertyChangeListener("title", listener)
        val socket = Path.of(runtime, "hypr", instance, ".socket.sock")
        val events = socket.resolveSibling(".socket2.sock")
        val pid = ProcessHandle.current().pid()
        var retainedFrame: HyprlandRetainedFrame? = null
        val job =
            scope.launch(Dispatchers.IO) {
                suspend fun setVisible(visible: Boolean) {
                    withContext(Dispatchers.Main) {
                        onVisibilityChanged(visible)
                        window.findSkiaLayer()?.let { layer ->
                            if (visible) {
                                if (!layer.isVisible) {
                                    layer.isVisible = true
                                    layer.needRender()
                                }
                                retainedFrame?.close()
                                retainedFrame = null
                            } else if (layer.isShowing) {
                                val image = layer.screenshot()?.use(::copyHyprlandFrame)
                                if (image != null) retainedFrame = HyprlandRetainedFrame(window, layer, image)
                                layer.isVisible = false
                            }
                        }
                    }
                }
                var retryDelay = 250L
                while (isActive) {
                    try {
                        collectHyprlandVisibility(
                            events = hyprlandVisibilityEvents(events),
                            queryVisibility = {
                                retryDelay = 250L
                                runCatching {
                                    hyprlandWindowVisible(
                                        readHyprlandResponse(socket, "j/clients"),
                                        readHyprlandResponse(socket, "j/monitors"),
                                        pid,
                                        title.get(),
                                    )
                                }.getOrNull() ?: true
                            },
                            setVisible = ::setVisible,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        setVisible(true)
                    }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(8_000L)
                }
            }
        onDispose {
            job.cancel()
            retainedFrame?.close()
            window.removePropertyChangeListener("title", listener)
            window.findSkiaLayer()?.isVisible = true
        }
    }
}

internal suspend fun collectHyprlandVisibility(
    events: Flow<Unit>,
    queryVisibility: () -> Boolean,
    setVisible: suspend (Boolean) -> Unit,
) {
    events.collect {
        setVisible(queryVisibility())
    }
}

internal fun copyHyprlandFrame(bitmap: Bitmap): BufferedImage? {
    // Skiko's toBufferedImage shares native pixels, which closing the screenshot frees.
    val info = ImageInfo(bitmap.width, bitmap.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    val pixels = bitmap.readPixels(info, info.minRowBytes, 0, 0) ?: return null
    return BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB_PRE).also { image ->
        ByteBuffer
            .wrap(pixels)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()
            .get((image.raster.dataBuffer as DataBufferInt).data)
    }
}

private class HyprlandRetainedFrame(
    private val window: ComposeWindow,
    private val layer: SkiaLayer,
    private val image: BufferedImage,
) : JComponent(),
    AutoCloseable {
    private val boundsListener =
        object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateBounds()

            override fun componentMoved(event: ComponentEvent) = updateBounds()
        }

    init {
        isOpaque = true
        isFocusable = false
        updateBounds()
        window.layeredPane.add(this, JLayeredPane.DRAG_LAYER)
        layer.addComponentListener(boundsListener)
        paintImmediately(0, 0, width, height)
    }

    private fun updateBounds() {
        bounds = SwingUtilities.convertRectangle(layer.parent, layer.bounds, window.layeredPane)
    }

    override fun paintComponent(graphics: Graphics) {
        graphics.drawImage(image, 0, 0, width, height, null)
    }

    override fun close() {
        layer.removeComponentListener(boundsListener)
        window.layeredPane.remove(this)
        window.layeredPane.repaint(bounds)
        image.flush()
    }
}

internal fun hyprlandVisibilityEvents(socket: Path) =
    channelFlow {
        try {
            runInterruptible(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socket))
                    trySend(Unit)
                    val reader = Channels.newInputStream(channel).bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: throw EOFException("Hyprland event socket closed")
                        if (hyprlandEventAffectsVisibility(line)) trySend(Unit)
                    }
                }
            }
        } catch (interrupted: ClosedByInterruptException) {
            ensureActive()
            throw interrupted
        }
    }.conflate()

internal fun hyprlandEventAffectsVisibility(line: String): Boolean =
    when (line.substringBefore(">>", "").removeSuffix("v2")) {
        "workspace", "focusedmon", "activespecial", "movewindow", "openwindow", "closewindow",
        "changefloatingmode", "pin", "fullscreen", "moveworkspace", "monitoradded", "monitorremoved",
        "createworkspace", "destroyworkspace", "renameworkspace", "activewindow", "windowtitle",
        "togglegroup", "moveintogroup", "moveoutofgroup", "configreloaded", "minimized",
        -> true

        else -> false
    }

internal fun hyprlandWindowVisible(
    clientsJson: String,
    monitorsJson: String,
    pid: Long,
    title: String,
): Boolean? {
    val clients = Json.parseToJsonElement(clientsJson) as? JsonArray ?: return null
    val monitors = Json.parseToJsonElement(monitorsJson) as? JsonArray ?: return null
    val client =
        clients.mapNotNull { it as? JsonObject }.singleOrNull {
            it["pid"]?.jsonPrimitive?.longOrNull == pid && it["title"]?.jsonPrimitive?.contentOrNull == title
        } ?: return null
    if (client["mapped"]?.jsonPrimitive?.booleanOrNull != true) return null
    if (client["hidden"]?.jsonPrimitive?.booleanOrNull == true) return false
    val pinned = client["pinned"]?.jsonPrimitive?.booleanOrNull ?: return null
    if (pinned) return true
    val workspace = (client["workspace"] as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull ?: return null
    if (monitors.isEmpty()) return null
    val visibleWorkspaces =
        monitors.flatMap { element ->
            val monitor = element as? JsonObject ?: return null
            listOf("activeWorkspace", "specialWorkspace").map { key ->
                (monitor[key] as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull ?: return null
            }
        }
    return workspace in visibleWorkspaces
}

internal fun readHyprlandResponse(
    socket: Path,
    request: String,
): String {
    SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
        channel.configureBlocking(false)
        Selector.open().use { selector ->
            channel.register(selector, SelectionKey.OP_CONNECT)
            val deadline = System.nanoTime() + 500_000_000L

            fun awaitReady(operation: Int) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                check(remainingMs > 0) { "Hyprland IPC timed out" }
                channel.keyFor(selector).interestOps(operation)
                selector.select(remainingMs.coerceAtLeast(1))
                selector.selectedKeys().clear()
            }
            if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                while (!channel.finishConnect()) awaitReady(SelectionKey.OP_CONNECT)
            }
            val input = ByteBuffer.wrap(request.toByteArray(Charsets.UTF_8))
            while (input.hasRemaining()) {
                if (channel.write(input) == 0) awaitReady(SelectionKey.OP_WRITE)
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    awaitReady(SelectionKey.OP_READ)
                } else {
                    check(output.size() + count <= 1024 * 1024) { "Hyprland IPC response too large" }
                    output.write(buffer.array(), 0, count)
                    buffer.clear()
                }
            }
            return output.toString(Charsets.UTF_8)
        }
    }
}

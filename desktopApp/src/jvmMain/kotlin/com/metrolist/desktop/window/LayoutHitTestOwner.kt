package com.metrolist.desktop.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.util.packFloats
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.ui.navigation.PopupSourceResetHandler

@Suppress("UNCHECKED_CAST")
@OptIn(InternalComposeUiApi::class)
private val localComposeScene =
    Class
        .forName("androidx.compose.ui.scene.ComposeScene_skikoKt")
        .getMethod("getLocalComposeScene")
        .invoke(null) as ProvidableCompositionLocal<ComposeScene?>

@OptIn(InternalComposeUiApi::class)
@Composable
fun rememberLayoutHitTestOwner(): LayoutHitTestOwner {
    val scene = localComposeScene.current ?: error("no compose scene")
    return remember(scene) {
        when (scene::class.qualifiedName) {
            "androidx.compose.ui.scene.CanvasLayersComposeSceneImpl" -> {
                CanvasLayersLayoutHitTestOwner(scene)
            }

            "androidx.compose.ui.scene.PlatformLayersComposeSceneImpl" -> {
                PlatformLayersLayoutHitTestOwner(scene)
            }

            else -> {
                error("unsupported compose scene")
            }
        }
    }
}

interface LayoutHitTestOwner {
    fun hitTest(
        x: Float,
        y: Float,
    ): Boolean = false
}

private val Any?.isPopupSourceResetObserver: Boolean
    get() = this is SuspendingPointerInputModifierNode && pointerInputEventHandler is PopupSourceResetHandler

internal fun hasInteractivePointerInput(nodes: List<*>): Boolean =
    nodes.any { it is PointerInputModifierNode && !it.isPopupSourceResetObserver }

internal abstract class ReflectLayoutHitTestOwner : LayoutHitTestOwner {
    @OptIn(InternalComposeUiApi::class)
    protected val classLoader = ComposeScene::class.java.classLoader!!
    private val rootNodeOwnerClass = classLoader.loadClass("androidx.compose.ui.node.RootNodeOwner")
    private val ownerClass = classLoader.loadClass("androidx.compose.ui.node.Owner")
    private val layoutNodeClass = classLoader.loadClass("androidx.compose.ui.node.LayoutNode")
    private val hitTestResultConstructor =
        classLoader
            .loadClass("androidx.compose.ui.node.HitTestResult")
            .getDeclaredConstructor()
    private val rootNodeOwnerGetOwner = rootNodeOwnerClass.getMethod("getOwner")
    private val ownerGetRoot = ownerClass.getMethod("getRoot")
    private val layoutNodeHitTestMethod =
        layoutNodeClass.declaredMethods
            .single { it.name.startsWith("hitTest-") && it.name.endsWith($$"$default") }
    private var loggedPassiveObserver = false
    private var loggedHitTestFailure = false

    protected fun getLayoutNode(rootNodeOwner: Any): Any = ownerGetRoot.invoke(rootNodeOwnerGetOwner.invoke(rootNodeOwner))

    protected fun Any.layoutNodeHitTest(
        x: Float,
        y: Float,
    ): Boolean {
        try {
            val result = hitTestResultConstructor.newInstance()
            layoutNodeHitTestMethod.invoke(null, this, packFloats(x, y), result, 0, false, 12, null)
            // The last hit-test node can be layout or semantics even when an interactive child handled the point.
            val nodes = result as List<*>
            val interactive = hasInteractivePointerInput(nodes)
            if (!interactive && !loggedPassiveObserver && nodes.any { it.isPopupSourceResetObserver }) {
                loggedPassiveObserver = true
                AppLogger.info("WindowsWindowFrame", "Caption hit test ignored passive popup-source observer")
            }
            return interactive
        } catch (error: Exception) {
            if (!loggedHitTestFailure) {
                loggedHitTestFailure = true
                AppLogger.warning("WindowsWindowFrame", "Compose caption hit test failed", error)
            }
            return false
        }
    }
}

@OptIn(InternalComposeUiApi::class)
internal class PlatformLayersLayoutHitTestOwner(
    scene: ComposeScene,
) : ReflectLayoutHitTestOwner() {
    private val sceneClass = classLoader.loadClass("androidx.compose.ui.scene.PlatformLayersComposeSceneImpl")

    private val mainOwnerRef =
        sceneClass.getDeclaredMethod("getMainOwner").let {
            it.trySetAccessible()
            it.invoke(scene)
        }

    override fun hitTest(
        x: Float,
        y: Float,
    ): Boolean = getLayoutNode(mainOwnerRef).layoutNodeHitTest(x, y)
}

@OptIn(InternalComposeUiApi::class)
internal class CanvasLayersLayoutHitTestOwner(
    private val scene: ComposeScene,
) : ReflectLayoutHitTestOwner() {
    private val sceneClass = classLoader.loadClass("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl")
    private val layerClass =
        sceneClass.declaredClasses.first {
            it.name ==
                $$"androidx.compose.ui.scene.CanvasLayersComposeSceneImpl$AttachedComposeSceneLayer"
        }

    private val mainOwnerRef =
        sceneClass.getDeclaredField("mainOwner").let {
            it.trySetAccessible()
            it.get(scene)
        }

    private val layers =
        sceneClass.getDeclaredField("_layersCopyCache").let {
            it.trySetAccessible()
            it.get(scene)
        }

    private val layersWithCopyMethod =
        layers.javaClass.getMethod("withCopy", Function1::class.java).apply {
            trySetAccessible()
        }

    private val focusedLayerField =
        sceneClass.getDeclaredField("focusedLayer").apply {
            trySetAccessible()
        }

    private val layerOwnerField =
        layerClass
            .getDeclaredField("owner")
            .apply {
                trySetAccessible()
            }

    private val layerIsInBoundMethod =
        layerClass
            .declaredMethods
            .firstOrNull { it.name.startsWith("isInBounds") }
            ?.apply {
                trySetAccessible()
            }

    override fun hitTest(
        x: Float,
        y: Float,
    ): Boolean {
        var layerHitResult: Boolean? = null
        layersWithCopyMethod.invoke(
            layers,
            { copy: List<*> ->
                for (layer in copy.asReversed()) {
                    if (layerIsInBoundMethod?.invoke(layer, packFloats(x, y)) == true) {
                        layerHitResult = getLayoutNode(layerOwnerField.get(layer)).layoutNodeHitTest(x, y)
                        break
                    } else if (layer == focusedLayerField.get(scene)) {
                        layerHitResult = false
                        break
                    }
                }
            },
        )
        return layerHitResult ?: getLayoutNode(mainOwnerRef).layoutNodeHitTest(x, y)
    }
}

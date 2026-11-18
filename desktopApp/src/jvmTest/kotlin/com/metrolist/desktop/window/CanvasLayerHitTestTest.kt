package com.metrolist.desktop.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasLayerHitTestTest {
    @Test
    fun hitTestKeepsItsSnapshotWhenSceneLayersChange() {
        val layers = mutableListOf("bottom", null, "top")
        val visited = mutableListOf<Any>()
        val result =
            hitTestSceneLayers(
                layers = layers,
                focusedLayer = null,
                isInBounds = { layer ->
                    visited += layer
                    layers.clear()
                    layer == "bottom"
                },
                hitTest = { true },
            )

        assertEquals(listOf<Any>("top", "bottom"), visited)
        assertEquals(true, result)
        assertTrue(layers.isEmpty())
    }

    @Test
    fun focusedPopupBlocksLowerLayersAndTheMainOwner() {
        val layers = listOf("bottom", "modal", "top")
        val visited = mutableListOf<Any>()
        assertEquals(
            false,
            hitTestSceneLayers(layers, "modal", {
                visited += it
                false
            }, { error("Outside every layer") }),
        )
        assertEquals(listOf<Any>("top", "modal"), visited)
        assertEquals(listOf("bottom", "modal", "top"), layers)
    }

    @Test
    fun topLayerOwnsTheHitEvenWithoutInteractiveContent() {
        val layers = listOf("bottom", "top")
        for (interactive in listOf(false, true)) {
            assertEquals(
                interactive,
                hitTestSceneLayers(layers, null, { true }, { layer ->
                    assertEquals("top", layer)
                    interactive
                }),
            )
        }
        assertNull(hitTestSceneLayers(layers, null, { false }, { error("Outside every layer") }))
        assertNull(hitTestSceneLayers(emptyList<Any>(), null, { error("No layer") }, { error("No layer") }))
    }

    @Test
    fun sceneLayerFieldExistsInThePinnedComposeVersion() {
        val sceneClass = Class.forName("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl")
        val layers = sceneClass.getDeclaredField("layers")
        assertTrue(List::class.java.isAssignableFrom(layers.type))
    }
}

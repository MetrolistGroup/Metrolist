package com.metrolist.android.ui.glass

import com.metrolist.android.ui.glass.backdrop.Backdrop

/** A child-free rendered surface that retains its material instead of substituting its raw input. */
internal class RenderedMaterialBackdrop(source: Backdrop) : Backdrop by source

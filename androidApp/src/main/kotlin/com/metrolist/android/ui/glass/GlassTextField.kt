package com.metrolist.android.ui.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.theme.uiRoundnessShape

@Composable
private fun Modifier.inputGlass(settings: GlassSettings, shape: Shape, role: Color): Modifier {
    val area = GlassArea.SearchBars
    return if (settings.usesGlassMaterial(area)) {
        val background = colorPalette().background0
        glassMaterial(settings.forArea(area), shape = shape, filmColor = role, drawShadow = false,
            ownBackdrop = LocalGlassMaterialSource.current ?: LocalGlassBackdrop.current ?: rememberInlineGlassBackdrop(background))
    } else glassAreaContour(settings, area, shape)
}

private fun TextFieldColors.clearContainer(): TextFieldColors = copy(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
)

/** Resolve only enabled neutral ink; semantic colors and disabled roles retain their meaning. */
private fun TextFieldColors.onGlass(surface: Color, darkness: Float): TextFieldColors {
    fun text(color: Color) = glassPaneForeground(color, surface, TextContrast, darkness)
    fun glyph(color: Color) = glassPaneForeground(color, surface, IconContrast, darkness, glyph = true)
    return clearContainer().copy(
        focusedTextColor = text(focusedTextColor), unfocusedTextColor = text(unfocusedTextColor),
        errorTextColor = text(errorTextColor), cursorColor = glyph(cursorColor),
        errorCursorColor = glyph(errorCursorColor),
        focusedLeadingIconColor = glyph(focusedLeadingIconColor),
        unfocusedLeadingIconColor = glyph(unfocusedLeadingIconColor),
        errorLeadingIconColor = glyph(errorLeadingIconColor),
        focusedTrailingIconColor = glyph(focusedTrailingIconColor),
        unfocusedTrailingIconColor = glyph(unfocusedTrailingIconColor),
        errorTrailingIconColor = glyph(errorTrailingIconColor),
        focusedLabelColor = text(focusedLabelColor), unfocusedLabelColor = text(unfocusedLabelColor),
        errorLabelColor = text(errorLabelColor),
        focusedPlaceholderColor = text(focusedPlaceholderColor),
        unfocusedPlaceholderColor = text(unfocusedPlaceholderColor),
        errorPlaceholderColor = text(errorPlaceholderColor),
        focusedSupportingTextColor = text(focusedSupportingTextColor),
        unfocusedSupportingTextColor = text(unfocusedSupportingTextColor),
        errorSupportingTextColor = text(errorSupportingTextColor),
        focusedIndicatorColor = glyph(focusedIndicatorColor),
        unfocusedIndicatorColor = glyph(unfocusedIndicatorColor),
        errorIndicatorColor = glyph(errorIndicatorColor),
    )
}

@Composable
private fun InputGlassContent(
    settings: GlassSettings,
    material: Boolean,
    role: Color,
    colors: TextFieldColors,
    textStyle: TextStyle,
    enabled: Boolean,
    content: @Composable (TextFieldColors, TextStyle) -> Unit,
) {
    val surface = if (material) effectiveSurfaceColor(surfaceBackdropColor(),
        glassFilmColor(settings.forArea(GlassArea.SearchBars), role, menu = false)) else null
    if (surface == null) {
        content(colors, textStyle)
        return
    }
    val darkness = glassSurfaceDarkness(settings)
    SurfaceForegroundContent(surface, glassInkDarkness = darkness) {
        val ink = if (enabled && textStyle.color.isSpecified)
            textStyle.copy(color = glassPaneForeground(textStyle.color, surface, TextContrast, darkness)) else textStyle
        content(colors.onGlass(surface, darkness), ink)
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = uiRoundnessShape(),
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    val glass = rememberGlassSettings()
    val material = glass.usesGlassMaterial(GlassArea.SearchBars)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val role = (if (!enabled) colors.disabledContainerColor else if (isError) colors.errorContainerColor
        else if (focused) colors.focusedContainerColor else colors.unfocusedContainerColor)
        .takeIf { it.isSpecified && it.alpha > 0f } ?: colorPalette().background1
    InputGlassContent(glass, material, role, colors, textStyle, enabled) { fieldColors, fieldStyle ->
        TextField(
            value = value, onValueChange = onValueChange,
            modifier = modifier.inputGlass(glass, shape, role),
            enabled = enabled, readOnly = readOnly, textStyle = fieldStyle, label = label, placeholder = placeholder,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon, isError = isError,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape,
            colors = fieldColors, interactionSource = interactions,
        )
    }
}

@Composable
fun GlassTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = uiRoundnessShape(),
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    val glass = rememberGlassSettings()
    val material = glass.usesGlassMaterial(GlassArea.SearchBars)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val role = (if (!enabled) colors.disabledContainerColor else if (isError) colors.errorContainerColor
        else if (focused) colors.focusedContainerColor else colors.unfocusedContainerColor)
        .takeIf { it.isSpecified && it.alpha > 0f } ?: colorPalette().background1
    InputGlassContent(glass, material, role, colors, textStyle, enabled) { fieldColors, fieldStyle ->
        TextField(
            value = value, onValueChange = onValueChange,
            modifier = modifier.inputGlass(glass, shape, role),
            enabled = enabled, readOnly = readOnly, textStyle = fieldStyle, label = label, placeholder = placeholder,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon, isError = isError,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape,
            colors = fieldColors, interactionSource = interactions,
        )
    }
}

@Composable
fun GlassOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = uiRoundnessShape(),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val glass = rememberGlassSettings()
    val material = glass.usesGlassMaterial(GlassArea.SearchBars)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val role = (if (!enabled) colors.disabledContainerColor else if (isError) colors.errorContainerColor
        else if (focused) colors.focusedContainerColor else colors.unfocusedContainerColor)
        .takeIf { it.isSpecified && it.alpha > 0f } ?: colorPalette().background1
    InputGlassContent(glass, material, role, colors, textStyle, enabled) { fieldColors, fieldStyle ->
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = modifier.inputGlass(glass, shape, role),
            enabled = enabled, readOnly = readOnly, textStyle = fieldStyle, label = label, placeholder = placeholder,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon, isError = isError,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape,
            colors = fieldColors, interactionSource = interactions,
        )
    }
}

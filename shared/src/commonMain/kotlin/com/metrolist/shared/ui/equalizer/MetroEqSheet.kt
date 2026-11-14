package com.metrolist.shared.ui.equalizer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.playback_speed
import com.metrolist.shared.generated.resources.tempo_and_pitch
import com.metrolist.shared.ui.components.ModalBottomSheet
import com.metrolist.shared.ui.components.PlayerMenuActionGroup
import com.metrolist.shared.ui.components.rememberModalSheetState
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.tempoPitchMenuAction
import com.metrolist.shared.ui.navigation.PredictiveNavigationContent
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetroEqSheet(
    onDismiss: () -> Unit,
    varispeed: Boolean,
    rememberProfilePicker: (@Composable (onPicked: (fileName: String, content: String) -> Unit) -> () -> Unit)? = null,
    isAudioProcessingAvailable: Boolean = true,
    initialAutoEqSearch: Boolean = false,
    tempoPitchContent: @Composable (onBack: () -> Unit) -> Unit,
) {
    var showTempoPitch by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalSheetState(enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        PredictiveNavigationContent(
            targetState = showTempoPitch,
            previousState = false,
            backEnabled = showTempoPitch,
            onBack = { showTempoPitch = false },
            isPop = !showTempoPitch,
            modifier = Modifier.heightIn(max = 800.dp).fillMaxHeight(0.9f),
        ) { tempoPitch ->
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tempoPitch) {
                        IconButton(onClick = { showTempoPitch = false }, shapes = IconButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                        }
                    }
                    Text(
                        stringResource(
                            if (tempoPitch) {
                                if (varispeed) Res.string.playback_speed else Res.string.tempo_and_pitch
                            } else {
                                Res.string.equalizer
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.Default.Close, stringResource(Res.string.close))
                    }
                }
                if (tempoPitch) {
                    Box(Modifier.weight(1f).padding(16.dp)) {
                        tempoPitchContent { showTempoPitch = false }
                    }
                } else {
                    Box(Modifier.weight(1f)) {
                        MetroEqScreen(
                            rememberProfilePicker = rememberProfilePicker,
                            isAudioProcessingAvailable = isAudioProcessingAvailable,
                            initialAutoEqSearch = initialAutoEqSearch,
                        )
                    }
                    Box(Modifier.padding(16.dp)) {
                        PlayerMenuActionGroup(
                            actions = listOf(tempoPitchMenuAction(varispeed) { showTempoPitch = true }),
                        )
                    }
                }
            }
        }
    }
}

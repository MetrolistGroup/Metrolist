package com.metrolist.android.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.copied
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.ui.theme.MdSpacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun ErrorBox(error: String) {
    val copiedText = stringResource(Res.string.copied)

    val context = LocalContext.current
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    Column {
        Text(stringResource(Res.string.error_occurred))

        Spacer(Modifier.height(MdSpacing.xs))

        SelectionContainer(
            Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    MaterialTheme.shapes.extraSmall,
                ).padding(MdSpacing.sm)
                .horizontalScroll(rememberScrollState())
                .clickable(
                    onClick = {
                        cm.setPrimaryClip(ClipData.newPlainText("text", error))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                ),
        ) {
            Text(
                text = error,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = MdSpacing.xl),
            )
        }
    }
}

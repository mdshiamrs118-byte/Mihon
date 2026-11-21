package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.icons.custommaterialsymbols.CustomMaterialSymbols
import mihon.icons.custommaterialsymbols.rounded.CropOff
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Crop
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.rounded.VolumeUp
import mihon.translation.TranslationStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class ReaderTranslateActions(
    val status: TranslationStatus,
    val showingTranslated: Boolean,
    val onTranslate: () -> Unit,
    val onStop: () -> Unit,
    val onToggleShown: () -> Unit,
    val onDelete: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
private fun TranslateButton(actions: ReaderTranslateActions) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        if (actions.status.running) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = MaterialSymbols.Rounded.Translate,
                contentDescription = stringResource(MR.strings.action_translate),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (actions.status.running) {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.translation_stop)) },
                    onClick = {
                        expanded = false
                        actions.onStop()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(MR.strings.translation_from_page)) },
                    onClick = {
                        expanded = false
                        actions.onTranslate()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (actions.showingTranslated) {
                                MR.strings.translation_show_original
                            } else {
                                MR.strings.translation_show_translated
                            },
                        ),
                    )
                },
                onClick = {
                    expanded = false
                    actions.onToggleShown()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.translation_delete)) },
                onClick = {
                    expanded = false
                    actions.onDelete()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.translation_settings)) },
                onClick = {
                    expanded = false
                    actions.onOpenSettings()
                },
            )
        }
    }
}

@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    translate: ReaderTranslateActions,
    readAloudActive: Boolean,
    onClickReadAloud: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClickReadingMode) {
            Icon(
                imageVector = readingMode.icon,
                contentDescription = stringResource(MR.strings.viewer),
            )
        }

        TranslateButton(translate)

        IconButton(onClick = onClickReadAloud) {
            Icon(
                imageVector = MaterialSymbols.Rounded.VolumeUp,
                contentDescription = stringResource(MR.strings.action_read_aloud),
                tint = if (readAloudActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }

        IconButton(onClick = onClickOrientation) {
            Icon(
                imageVector = orientation.icon,
                contentDescription = stringResource(MR.strings.rotation_type),
            )
        }

        IconButton(onClick = onClickCropBorder) {
            Icon(
                imageVector = if (cropEnabled) MaterialSymbols.Rounded.Crop else CustomMaterialSymbols.Rounded.CropOff,
                contentDescription = stringResource(MR.strings.pref_crop_borders),
            )
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}

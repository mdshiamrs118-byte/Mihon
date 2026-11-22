package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceItem
import eu.kanade.presentation.more.settings.screen.SettingsReadAloudScreen
import eu.kanade.presentation.more.settings.screen.SettingsTranslationScreen
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderTranslationDialog(
    onDismissRequest: () -> Unit,
    viewModel: ReaderViewModel,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.action_translate),
        stringResource(MR.strings.pref_category_read_aloud),
        stringResource(MR.strings.pref_translation_services),
    )
    val pagerState = rememberPagerState { tabTitles.size }

    BoxWithConstraints {
        TabbedDialog(
            modifier = Modifier.heightIn(max = maxHeight * 0.75f),
            onDismissRequest = onDismissRequest,
            tabTitles = tabTitles,
            pagerState = pagerState,
        ) { page ->
            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                val actions = actionsGroup(viewModel, onDismissRequest)
                val groups = when (page) {
                    0 -> listOf(actions) + SettingsTranslationScreen.getTranslatorGroups()
                    1 -> SettingsReadAloudScreen.getPreferences()
                    else -> SettingsTranslationScreen.getServiceGroups()
                }
                groups.forEachIndexed { index, group ->
                    if (group is Preference.PreferenceGroup) {
                        PreferenceGroupHeader(title = group.title)
                        group.preferenceItems.forEach { PreferenceItem(item = it, highlightKey = null) }
                        if (index < groups.lastIndex) Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun actionsGroup(viewModel: ReaderViewModel, onDismissRequest: () -> Unit): Preference.PreferenceGroup {
    val manager = viewModel.translationManager
    val status by manager.status.collectAsState()
    val showingTranslated by manager.showingTranslated.collectAsState()

    return Preference.PreferenceGroup(
        title = stringResource(MR.strings.translation_this_chapter),
        preferenceItems = listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(
                    if (status.running) MR.strings.translation_stop else MR.strings.translation_from_page,
                ),
                subtitle = status.message ?: stringResource(MR.strings.translation_from_page_summary),
                onClick = {
                    if (status.running) manager.stop() else viewModel.translateFromCurrentPage()
                    onDismissRequest()
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(
                    if (showingTranslated) {
                        MR.strings.translation_show_original
                    } else {
                        MR.strings.translation_show_translated
                    },
                ),
                onClick = viewModel::toggleTranslated,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.translation_delete),
                onClick = {
                    viewModel.deleteTranslation()
                    onDismissRequest()
                },
            ),
        ),
    )
}

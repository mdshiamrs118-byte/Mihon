package eu.kanade.presentation.more.settings.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mihon.app.di.appGraph
import mihon.translation.Catalog
import mihon.translation.EngineKind
import mihon.translation.Langs
import mihon.translation.TranslationManager
import mihon.translation.TranslationPreferences
import mihon.translation.WebAi
import mihon.translation.WebAiSignInActivity
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
data object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> = getTranslatorGroups() + getServiceGroups()

    @Composable
    fun getTranslatorGroups(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember { context.appGraph.translationPreferences }
        val manager = remember { context.appGraph.translationManager }
        return listOf(getGeneralGroup(prefs), getEngineGroup(manager, prefs))
    }

    @Composable
    fun getServiceGroups(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember { context.appGraph.translationPreferences }
        val manager = remember { context.appGraph.translationManager }
        return listOf(getOnlineGroup(prefs), getWebGroup(manager, prefs), getFallbackGroup(prefs))
    }

    @Composable
    private fun getGeneralGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        val languageCode by prefs.sourceLanguage.collectAsState()
        val language = Langs.byCode(languageCode)
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_translation_general),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.sourceLanguage,
                    entries = Langs.all.associate { it.code to it.name },
                    title = stringResource(MR.strings.pref_translation_language),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.engine,
                    entries = Catalog.engines(language).associate { it.key to it.title },
                    title = stringResource(MR.strings.pref_translation_engine),
                ),
            ),
        )
    }

    @Composable
    private fun getEngineGroup(manager: TranslationManager, prefs: TranslationPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val languageCode by prefs.sourceLanguage.collectAsState()
        val engineKey by prefs.engine.collectAsState()
        val language = Langs.byCode(languageCode)
        val info = Catalog.byKey(engineKey, language)
        val states by manager.engineStates.collectAsState()
        val state = states[info.id]
        val ready by produceState(false, info, language, states) { value = manager.isReady(info, language) }
        val busy = state?.progress != null

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) scope.launch { context.toast(manager.importFiles(info, uris)) }
        }

        val downloadable = info.kind == EngineKind.MLKIT || info.kind == EngineKind.ONNX
        return Preference.PreferenceGroup(
            title = info.title,
            preferenceItems = listOf(
                Preference.PreferenceItem.InfoPreference(info.description),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(
                        if (busy) MR.strings.pref_translation_cancel_download else MR.strings.pref_translation_download,
                    ),
                    subtitle = when {
                        state?.error != null -> state.error
                        busy -> state.progress
                        ready -> stringResource(MR.strings.pref_translation_ready)
                        else -> stringResource(MR.strings.pref_translation_not_downloaded, info.approxSize)
                    },
                    visible = downloadable && (busy || !ready),
                    onClick = {
                        if (busy) manager.cancelDownload(info) else manager.download(info, language)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_translation_import_files),
                    subtitle = stringResource(MR.strings.pref_translation_import_files_summary),
                    visible = info.kind == EngineKind.ONNX && !busy,
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_translation_delete_download),
                    subtitle = stringResource(MR.strings.pref_translation_ready),
                    visible = downloadable && ready && !busy,
                    onClick = { manager.delete(info, language) },
                ),
            ),
        )
    }

    @Composable
    private fun getOnlineGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_translation_online),
            preferenceItems = listOf(
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_translation_online_summary)),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.apiBaseUrl,
                    title = stringResource(MR.strings.pref_translation_api_base_url),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.apiKey,
                    title = stringResource(MR.strings.pref_translation_api_key),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.apiModel,
                    title = stringResource(MR.strings.pref_translation_api_model),
                ),
            ),
        )
    }

    @Composable
    private fun getWebGroup(manager: TranslationManager, prefs: TranslationPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val signInItems = WebAi.sites.filter { it.loginNeeded }.map { site ->
            val signedIn by prefs.signedIn(site.id).collectAsState()
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_translation_sign_in, site.title),
                subtitle = stringResource(
                    if (signedIn) MR.strings.pref_translation_signed_in else MR.strings.pref_translation_not_signed_in,
                ),
                onClick = { context.startActivity(WebAiSignInActivity.newIntent(context, site.id)) },
            )
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_translation_web),
            preferenceItems = buildList {
                add(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_translation_web_summary)))
                addAll(signInItems)
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_sign_out_all),
                        onClick = {
                            manager.clearLogins()
                            context.toast(MR.strings.pref_translation_signed_out)
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.SliderPreference(
                        preference = prefs.webTimeoutSeconds,
                        title = stringResource(MR.strings.pref_translation_web_timeout),
                        subtitle = stringResource(MR.strings.pref_translation_web_timeout_summary),
                        valueText = { stringResource(MR.strings.pref_translation_seconds, it) },
                        valueRange = 30..300 step 15,
                    ),
                )
            },
        )
    }

    @Composable
    private fun getFallbackGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_translation_fallback_group),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.fallbackToDuckDuckGo,
                    title = stringResource(MR.strings.pref_translation_fallback),
                    subtitle = stringResource(MR.strings.pref_translation_fallback_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.markFallback,
                    title = stringResource(MR.strings.pref_translation_mark_fallback),
                    subtitle = stringResource(MR.strings.pref_translation_mark_fallback_summary),
                ),
            ),
        )
    }
}

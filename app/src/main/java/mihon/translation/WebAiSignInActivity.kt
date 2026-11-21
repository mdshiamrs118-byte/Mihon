package mihon.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** Shows a chat website so the user can sign in or pass a human check; the login lives in the shared cookie jar. */
class WebAiSignInActivity : BaseActivity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val site = WebAi.byId(intent.getStringExtra(EXTRA_SITE).orEmpty())
        if (site == null) {
            finish()
            return
        }
        val preferences = appGraph.translationPreferences

        setComposeContent {
            var canGoBack by remember { mutableStateOf(false) }
            BackHandler(enabled = canGoBack) { web?.goBack() }

            Scaffold(
                topBar = { scrollBehavior ->
                    AppBar(
                        title = stringResource(MR.strings.translation_sign_in_title, site.title),
                        navigateUp = ::finish,
                        actions = {
                            TextButton(
                                onClick = {
                                    preferences.setSignedIn(site.id, true)
                                    WebAi.flushCookies()
                                    finish()
                                },
                            ) {
                                Text(stringResource(MR.strings.translation_signed_in_done))
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                },
            ) { contentPadding ->
                Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
                    Text(
                        text = stringResource(MR.strings.translation_sign_in_hint) + " " + site.note,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.small,
                        ),
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).also {
                                web = it
                                WebAi.configure(it)
                                it.webViewClient = object : android.webkit.WebViewClient() {
                                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                        canGoBack = view.canGoBack()
                                    }
                                }
                                it.loadUrl(site.url)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        WebAi.flushCookies()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SITE = "site"

        fun newIntent(context: Context, siteId: String) =
            Intent(context, WebAiSignInActivity::class.java).putExtra(EXTRA_SITE, siteId)
    }
}

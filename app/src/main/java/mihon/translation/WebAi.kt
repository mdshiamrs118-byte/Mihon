package mihon.translation

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.IOException
import kotlin.coroutines.resume

/** A chat website Mihon can drive: where to open it and how to find its message box and send button. */
class WebSite(
    val id: String,
    val title: String,
    val url: String,
    val loginNeeded: Boolean,
    val input: List<String>,
    val send: List<String>,
    val note: String,
)

object WebAi {

    val sites: List<WebSite> = listOf(
        WebSite(
            "chatgpt", "ChatGPT", "https://chatgpt.com/", true,
            listOf("#prompt-textarea", "div#prompt-textarea"),
            listOf("button[data-testid=\"send-button\"]", "button[aria-label*=\"Send\" i]"),
            "Uses your ChatGPT web account and its free message limit.",
        ),
        WebSite(
            "gemini", "Gemini", "https://gemini.google.com/app", true,
            listOf("rich-textarea div.ql-editor", "div.ql-editor[contenteditable=\"true\"]"),
            listOf("button.send-button", "button[aria-label*=\"Send message\" i]", "button[aria-label*=\"Send\" i]"),
            "Uses your Google account. Google may refuse sign-in inside an app; if so, use another site.",
        ),
        WebSite(
            "claude", "Claude", "https://claude.ai/new", true,
            listOf("div.ProseMirror[contenteditable=\"true\"]", "div[contenteditable=\"true\"]"),
            listOf("button[aria-label*=\"Send\" i]"),
            "Uses your Claude web account and its free message limit.",
        ),
        WebSite(
            "duckai", "DuckDuckGo AI Chat", "https://duck.ai/", false,
            listOf("textarea[name=\"user-prompt\"]", "textarea"),
            listOf("button[type=\"submit\"]", "button[aria-label*=\"Send\" i]"),
            "No account needed.",
        ),
        WebSite(
            "copilot", "Microsoft Copilot", "https://copilot.microsoft.com/", true,
            listOf("textarea#userInput", "textarea"),
            listOf("button[data-testid=\"submit-button\"]", "button[aria-label*=\"Submit\" i]"),
            "Works with or without a Microsoft account; sign in for fewer limits.",
        ),
        WebSite(
            "perplexity", "Perplexity", "https://www.perplexity.ai/", true,
            listOf("div#ask-input[contenteditable=\"true\"]", "textarea"),
            listOf("button[aria-label*=\"Submit\" i]", "button[aria-label*=\"Send\" i]"),
            "Uses your Perplexity web account.",
        ),
    )

    fun byKey(key: String): WebSite? = sites.firstOrNull { "web-${it.id}" == key }

    fun byId(id: String): WebSite? = sites.firstOrNull { it.id == id }

    /** The normal mobile Chrome user agent without the "wv" marker, which some sites (Google) use to refuse WebViews. */
    fun userAgent(ctx: Context): String =
        WebSettings.getDefaultUserAgent(ctx).replace("; wv", "").replace("Version/4.0 ", "")

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = userAgent(web.context)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
    }

    fun flushCookies() = CookieManager.getInstance().flush()

    fun clearLogins() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}

/** Where the reader lets a web translator attach its invisible browser while it works. */
object WebAiHost {
    var container: FrameLayout? = null
}

/** A WebView that never swallows touches, so it can sit invisibly behind the reader. */
private class QuietWebView(ctx: Context) : WebView(ctx) {
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

/**
 * Translates by typing the text into a chat website in a hidden browser and reading the answer back.
 * Needs the user to be signed in once (Settings > Translation > Sign in). Site pages change, so this can break.
 */
class WebAiTranslator(
    private val site: WebSite,
    private val languageName: String,
    private val timeoutMs: Long,
) : Translator {

    override suspend fun translate(texts: List<String>, onProgress: (Int, Int) -> Unit): List<String> =
        withContext(Dispatchers.Main) {
            if (texts.isEmpty()) return@withContext emptyList()
            val host = WebAiHost.container ?: throw IOException("Open the translation from the reader screen.")
            onProgress(0, texts.size)

            val web = QuietWebView(host.context)
            WebAi.configure(web)
            val loaded = CompletableDeferred<Unit>()
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.complete(Unit)
                }
            }
            web.alpha = 0.01f
            host.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            try {
                val answer = withTimeoutOrNull(timeoutMs) {
                web.loadUrl(site.url)
                withTimeoutOrNull(45_000) { loaded.await() }

                // wait for the message box (and click away welcome / terms dialogs)
                var ready = false
                for (i in 0 until 45) {
                    if (web.eval(findInputJs()) == "ok") {
                        ready = true
                        break
                    }
                    web.eval(DISMISS_JS)
                    delay(900)
                }
                if (!ready) {
                    throw IOException(
                        "Could not find the chat box on ${site.title}. Open Settings > Translation > ${site.title} > Sign in, " +
                            "sign in, finish any \"verify you are human\" check and make sure you can chat there."
                    )
                }

                val nonce = WebAiPrompt.newNonce()
                val prompt = WebAiPrompt.build(texts, languageName, nonce)
                if (web.eval(typeJs(prompt)) != "typed") throw IOException("Could not type into the ${site.title} chat box.")
                delay(900)
                web.eval(sendJs())

                // wait for the reply
                var segment: String? = null
                var last = ""
                while (true) {
                    delay(1500)
                    val page = web.readText()
                    last = page
                    segment = WebAiPrompt.segment(page, nonce)
                    if (segment != null) break
                }
                if (segment == null) {
                    throw IOException(
                        "${site.title} did not finish a reply in time (or ignored the format). " +
                            "Last text on the page: " + last.takeLast(160).replace('\n', ' ')
                    )
                }
                val arrayText = WebAiPrompt.arrayText(segment)
                    ?: throw IOException("${site.title} answered without a list: " + segment.take(160))
                val arr = try {
                    JSONArray(arrayText)
                } catch (e: Exception) {
                    throw IOException("Could not read ${site.title}'s answer: " + arrayText.take(160))
                }
                val result = List(arr.length()) { arr.get(it).toString() }
                if (result.size < texts.size) throw IOException("${site.title} returned ${result.size} items, expected ${texts.size}")
                onProgress(texts.size, texts.size)
                result.take(texts.size)
                }
                answer ?: throw IOException("${site.title} took longer than ${timeoutMs / 1000} s for this page.")
            } finally {
                host.removeView(web)
                web.stopLoading()
                web.destroy()
                WebAi.flushCookies()
            }
        }

    private suspend fun WebView.eval(js: String): String = suspendCancellableCoroutine { c ->
        evaluateJavascript(js) { c.resume((it ?: "null").removeSurrounding("\"")) }
    }

    private suspend fun WebView.readText(): String = suspendCancellableCoroutine { c ->
        evaluateJavascript("(document.body ? document.body.innerText : '')") {
            val raw = it ?: "null"
            c.resume(
                if (raw == "null") "" else try {
                    JSONArray("[$raw]").getString(0)
                } catch (e: Exception) {
                    ""
                }
            )
        }
    }

    private fun inputList(): String =
        WebAiPrompt.jsonArray(site.input + listOf("textarea", "div[contenteditable=\"true\"]", "[role=\"textbox\"]"))

    private fun findInputJs(): String =
        "(function(){" + HELPERS + "return pick(" + inputList() + ")?'ok':'none';})()"

    private fun typeJs(text: String): String =
        "(function(text){" + HELPERS +
            "var el=pick(" + inputList() + ");if(!el)return 'no-input';el.focus();" +
            "if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){" +
            "var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "Object.getOwnPropertyDescriptor(proto,'value').set.call(el,text);" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));}" +
            "else{document.execCommand('selectAll',false,null);document.execCommand('insertText',false,text);" +
            "if(!((el.innerText||'').trim())){el.textContent=text;" +
            "el.dispatchEvent(new InputEvent('input',{bubbles:true,data:text,inputType:'insertText'}));}}" +
            "return 'typed';})(" + WebAiPrompt.jsonString(text) + ")"

    private fun sendJs(): String {
        val list = WebAiPrompt.jsonArray(
            site.send + listOf("button[data-testid*=\"send\" i]", "button[aria-label*=\"Send\" i]", "button[type=\"submit\"]")
        )
        return "(function(){" + HELPERS +
            "var list=" + list + ";" +
            "for(var i=0;i<list.length;i++){var els=document.querySelectorAll(list[i]);" +
            "for(var k=0;k<els.length;k++){var b=els[k];if(vis(b)&&!b.disabled&&b.getAttribute('aria-disabled')!=='true'){b.click();return 'clicked';}}}" +
            "var el=document.activeElement||document.body;" +
            "['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});" +
            "return 'enter';})()"
    }

    private companion object {
        const val HELPERS =
            "function vis(e){return e&&(e.offsetWidth>0||e.offsetHeight>0||e.getClientRects().length>0);}" +
                "function pick(list){for(var i=0;i<list.length;i++){var els=document.querySelectorAll(list[i]);" +
                "for(var k=0;k<els.length;k++){if(vis(els[k]))return els[k];}}return null;}"

        /** Clicks "Agree / Got it / Continue" style buttons, but only inside dialogs. */
        const val DISMISS_JS =
            "(function(){var ds=document.querySelectorAll('[role=\"dialog\"],dialog,[aria-modal=\"true\"]');" +
                "for(var i=0;i<ds.length;i++){var bs=ds[i].querySelectorAll('button');" +
                "for(var k=0;k<bs.length;k++){var t=(bs[k].innerText||'').trim();" +
                "if(/^(agree|i agree|accept|accept all|agree and continue|got it|okay|ok|continue|next|stay logged out|no thanks|not now)\$/i.test(t)){bs[k].click();return 'clicked';}}}" +
                "return 'none';})()"
    }
}

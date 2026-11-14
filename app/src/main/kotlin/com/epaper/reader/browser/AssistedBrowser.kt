package com.epaper.reader.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.epaper.core.ExtractionConfig
import com.epaper.core.HostRule
import com.epaper.core.hostMatches
import com.epaper.core.hostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream

/**
 * Policy for the sandboxed browser, kept free of Android types so it is unit-testable.
 * - sub-resources from ad/tracker hosts are blocked;
 * - top-level navigation is limited to the host's own domains, the blog, and (with a user gesture)
 *   other non-blocked sites; anything else is checked and only followed if it is the document itself.
 */
class BrowserPolicy(private val config: ExtractionConfig, private val rule: HostRule?) {
    private val allowed: List<String> = buildList {
        rule?.hosts?.let(::addAll)
        rule?.browserHosts?.let(::addAll)
        hostOf(config.blogBaseUrl)?.let(::add)
        config.rules.forEach { addAll(it.hosts) }
    }

    fun blocksResource(url: String): Boolean = hostOf(url)?.let(config::isBlocked) ?: false

    enum class Nav { ALLOW, BLOCK, CHECK }

    fun navigation(url: String, hasGesture: Boolean): Nav {
        val scheme = url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") return Nav.BLOCK // intent:, market:, javascript: …
        val host = hostOf(url) ?: return Nav.BLOCK
        if (config.isBlocked(host)) return Nav.BLOCK
        if (looksLikePdf(url)) return Nav.CHECK
        if (allowed.any { hostMatches(host, it) }) return Nav.ALLOW
        return if (hasGesture) Nav.ALLOW else Nav.CHECK
    }

    fun looksLikePdf(url: String) = url.substringBefore('?').substringBefore('#').endsWith(".pdf", ignoreCase = true)

    /** JS run on each page: hides clutter and presses the configured download button once it is enabled. */
    fun pageScript(): String {
        val hide = rule?.hideSelectors.orEmpty().joinToString(",")
        val clicks = rule?.autoClickSelectors.orEmpty().joinToString(",") { "'" + it.replace("'", "\\'") + "'" }
        return """
            (function(){
              if (window.__ep) return; window.__ep = true;
              ${if (hide.isNotEmpty()) "var s=document.createElement('style');s.textContent='${hide.replace("'", "\\'")}{display:none!important}';document.documentElement.appendChild(s);" else ""}
              var sel=[$clicks];
              var t=setInterval(function(){
                sel.forEach(function(q){
                  document.querySelectorAll(q).forEach(function(b){
                    if(!b.disabled && !b.dataset.epClicked && b.offsetParent!==null){ b.dataset.epClicked='1'; b.click(); }
                  });
                });
              },1000);
              setTimeout(function(){clearInterval(t)},180000);
            })();
        """.trimIndent()
    }
}

/** A download the browser handed over: the URL plus what is needed to fetch it as the browser would. */
data class CapturedDownload(val url: String, val headers: Map<String, String>)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AssistedBrowserScreen(
    startUrl: String,
    title: String,
    subtitle: String?,
    config: ExtractionConfig,
    http: OkHttpClient,
    onCaptured: (CapturedDownload) -> Unit,
    onClose: () -> Unit,
    /** Batch mode ("Fetch all"): progress label such as "2 of 5" and a way to skip this paper. */
    queueLabel: String? = null,
    onSkip: (() -> Unit)? = null,
) {
    val rule = remember(startUrl) { config.ruleFor(startUrl) }
    val policy = remember(rule) { BrowserPolicy(config, rule) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var progress by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableIntStateOf(0) }
    var captured by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(startUrl) { kotlinx.coroutines.delay(30_000); slow = true }

    fun capture(url: String, view: WebView?) {
        if (captured) return
        captured = true
        val headers = buildMap {
            CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
            view?.settings?.userAgentString?.let { put("User-Agent", it) }
            view?.url?.let { put("Referer", it) }
        }
        onCaptured(CapturedDownload(url, headers))
    }

    /** For a navigation we would otherwise block: follow it only if it is the document itself. */
    fun check(url: String, view: WebView) {
        scope.launch {
            val isDoc = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(url).head()
                        .apply { CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) } }
                        .header("User-Agent", view.settings.userAgentString).build()
                    http.newBuilder().followRedirects(true).build().newCall(req).execute().use { r ->
                        val type = r.header("Content-Type").orEmpty()
                        val disp = r.header("Content-Disposition").orEmpty()
                        r.isSuccessful && (type.contains("pdf") || type.contains("octet-stream") || disp.contains("attachment"))
                    }
                }.getOrDefault(policy.looksLikePdf(url))
            }
            if (isDoc) capture(url, view) else {
                blocked++
                snackbar.showSnackbar("Blocked a redirect to ${hostOf(url)}")
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            val sub = listOfNotNull(queueLabel, subtitle).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") } },
                    actions = {
                        onSkip?.let { TextButton(onClick = it) { Text("Skip") } }
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(startUrl))) }) {
                            Icon(Icons.Filled.OpenInBrowser, "Open in external browser")
                        }
                    },
                )
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, null, Modifier.padding(end = 8.dp))
                        Text(
                            when {
                                captured -> "Got it — saving the paper…"
                                slow -> "Still waiting on the file host. If it shows a checkbox, tap it; the rest is automatic."
                                else -> "Keep this screen open: the file host runs a security check and a short countdown, " +
                                    "then the paper is saved automatically. Ads and pop-ups blocked" + if (blocked > 0) " ($blocked)." else "."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val webViewHolder = remember { arrayOfNulls<WebView>(1) }
        DisposableEffect(Unit) { onDispose { webViewHolder[0]?.destroy() } }
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewHolder[0] = this
                    settings.javaScriptEnabled = true // required by the host's captcha / proof-of-work check
                    settings.domStorageEnabled = true
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val url = request.url.toString()
                            return when (policy.navigation(url, request.hasGesture())) {
                                BrowserPolicy.Nav.ALLOW -> false
                                BrowserPolicy.Nav.BLOCK -> { blocked++; true }
                                BrowserPolicy.Nav.CHECK -> { check(url, view); true }
                            }
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (request.isForMainFrame || !policy.blocksResource(request.url.toString())) return null
                            main.post { blocked++ }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (policy.looksLikePdf(url)) { view.stopLoading(); capture(url, view) }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(policy.pageScript(), null)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }

                        // Page-raised alert/confirm/prompt/"leave page?" dialogs are swallowed, never shown.
                        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                            result.confirm(); main.post { blocked++ }; return true
                        }
                        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                            result.cancel(); main.post { blocked++ }; return true
                        }
                        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                            result.cancel(); main.post { blocked++ }; return true
                        }
                        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                            result.confirm(); return true
                        }

                        /**
                         * The host opens an ad tab alongside the download step. Some pages refuse to continue if
                         * window.open fails, so give it an invisible, never-attached WebView that may only load
                         * the host's own pages, and destroy it shortly after.
                         */
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                            val sink = WebView(view.context)
                            sink.settings.javaScriptEnabled = false
                            sink.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                    val u = r.url.toString()
                                    if (policy.looksLikePdf(u)) { main.post { capture(u, view) } }
                                    return true // nothing ever loads in a pop-up
                                }
                                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) =
                                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            (resultMsg.obj as WebView.WebViewTransport).webView = sink
                            resultMsg.sendToTarget()
                            main.post { blocked++ }
                            main.postDelayed({ sink.destroy() }, 5_000)
                            return true
                        }
                    }

                    setDownloadListener { url, _, _, _, _ -> capture(url, this) }
                    loadUrl(startUrl)
                }
            },
        )
    }
}

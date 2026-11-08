package com.notcode.mobile.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun BrowserScreen(controller: PreviewController, onSavePage: (String, String) -> Unit, onClose: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saveCallback by rememberUpdatedState(onSavePage)
    var address by remember(state.url) { mutableStateOf(state.url.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<DownloadRequest?>(null) }
    var downloadNote by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val onDownload by rememberUpdatedState<(DownloadRequest) -> Unit> { request ->
        if (downloadJob?.isActive == true) {
            controller.reportError("Дождитесь окончания текущей загрузки")
        } else {
            downloadNote = null
            pendingDownload = request
        }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = pendingDownload
        pendingDownload = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult
        downloadNote = "Скачивание ${request.fileName}…"
        downloadJob = scope.launch {
            runCatching {
                val cookies = CookieManager.getInstance().getCookie(request.url)
                val temporary = java.io.File.createTempFile("notcode-download-", ".part", context.cacheDir)
                try {
                    val bytes = temporary.outputStream().use { output -> BrowserDownloader.save(request, cookies, output) }
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "w")
                            ?: error("Не удалось открыть место сохранения")
                        output.use { sink -> temporary.inputStream().use { it.copyTo(sink) } }
                    }
                    bytes
                } finally {
                    temporary.delete()
                }
            }.onSuccess { bytes ->
                downloadNote = "Сохранено: ${request.fileName} (${(bytes + 1024 * 1024 - 1) / (1024 * 1024)} МБ)"
            }.onFailure {
                if (it is CancellationException) throw it
                downloadNote = null
                controller.reportError(it.message ?: "Не удалось скачать файл")
            }
        }
    }
    val webView = remember(controller, context) { createPreviewWebView(context, controller) { onDownload(it) } }
    var requestedUrl by remember(webView) { mutableStateOf<String?>(null) }
    DisposableEffect(webView) {
        onDispose {
            downloadJob?.cancel()
            webView.stopLoading()
            webView.destroy()
            // Controller/server and NPM terminal session are deliberately not closed on UI detachment.
        }
    }
    pendingDownload?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Скачать файл?") },
            text = {
                Text(request.fileName + "\n" + request.mimeType +
                    if (request.contentLength > 0) "\n${request.contentLength / (1024 * 1024)} МБ" else "")
            },
            confirmButton = { TextButton(onClick = { savePicker.launch(request.fileName) }) { Text("Скачать") } },
            dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text("Отмена") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { controller.close(); onClose() }) { Text("Назад") }
            OutlinedTextField(address, { address = it }, Modifier.weight(1f), singleLine = true,
                label = { Text("HTTP(S) URL") })
            TextButton(onClick = { controller.openUrl(address) }) { Text("Открыть") }
            Column {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Обновить страницу") }, onClick = { menu = false; webView.reload() })
                    if (downloadJob?.isActive == true) {
                        DropdownMenuItem(text = { Text("Отменить загрузку") }, onClick = {
                            menu = false
                            downloadJob?.cancel()
                            downloadNote = null
                        })
                    }
                    DropdownMenuItem(text = { Text("Сохранить страницу в проект") }, onClick = {
                        menu = false
                        val source = webView.url
                        if (source == null || !PreviewUrlPolicy.allows(source)) {
                            controller.reportError("No supported page to save")
                        } else {
                            webView.evaluateJavascript(
                                "(function(){var s=document.documentElement.outerHTML;return s.length>2097152?null:s;})()"
                            ) { encoded ->
                                runCatching {
                                    require(webView.url == source) { "Page changed while saving" }
                                    val html = Json.parseToJsonElement(encoded).jsonPrimitive.content
                                    require(encoded != "null" && html.toByteArray(Charsets.UTF_8).size <= MAX_HTML_BYTES) {
                                        "Page HTML exceeds 2 MiB or is unavailable"
                                    }
                                    // Only a user-triggered snapshot; caller must confirm/save via its VM.
                                    saveCallback(webView.title.orEmpty().take(512), html)
                                }.onFailure { controller.reportError(it.message ?: "Cannot capture page HTML") }
                            }
                        }
                    })
                    DropdownMenuItem(text = { Text("Открыть с помощью…") }, onClick = {
                        menu = false
                        runCatching {
                            val url = PreviewUrlPolicy.normalize(webView.url ?: state.url.orEmpty())
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                            })
                        }.onFailure {
                            controller.reportError(if (it is ActivityNotFoundException) "No browser is available" else it.message ?: "Cannot open browser")
                        }
                    })
                }
            }
        }
        state.error?.let { Text(it) }
        downloadNote?.let { Text(it) }
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth(), update = {
            val url = state.url
            if (url != null && url != requestedUrl) {
                requestedUrl = url
                if (it.url != url) it.loadUrl(url)
            } else if (url == null && requestedUrl != null) {
                requestedUrl = null
                it.stopLoading()
                it.loadUrl("about:blank")
            }
        })
    }
}

private const val MAX_HTML_BYTES = 2 * 1024 * 1024

@Suppress("DEPRECATION")
internal fun createPreviewWebView(
    context: android.content.Context,
    controller: PreviewController,
    onDownload: (DownloadRequest) -> Unit = {},
): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        // Never addJavascriptInterface: page scripts cannot invoke app/model actions.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (!PreviewUrlPolicy.allows(url)) {
                controller.reportError("Blocked unsupported or insecure download URL")
            } else {
                onDownload(DownloadRequest(
                    url,
                    BrowserDownloader.safeFileName(URLUtil.guessFileName(url, contentDisposition, mimetype)),
                    mimetype.orEmpty(),
                    contentLength,
                    userAgent,
                ))
            }
        }
        webViewClient = object : WebViewClient() {
            private fun blocked(url: String): Boolean {
                val deny = !PreviewUrlPolicy.allows(url)
                if (deny) controller.reportError("Blocked unsupported or insecure URL")
                return deny
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = blocked(request.url.toString())
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blocked(url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (!PreviewUrlPolicy.allows(request.url.toString())) {
                    controller.reportError("Blocked unsupported or insecure resource")
                    WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                } else null
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && url != "about:blank" && blocked(url)) view.stopLoading()
            }
            override fun onPageFinished(view: WebView, url: String?) { controller.page(url, view.title) }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                controller.reportError("TLS certificate error; connection blocked")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) controller.reportError("Page failed to load: ${error.description}")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) controller.reportError("HTTP ${response.statusCode}")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny(); controller.reportError("Page permissions are disabled") }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
                controller.reportError("Location access is disabled")
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                controller.reportError("File access is disabled")
                return true
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: android.os.Message): Boolean {
                controller.reportError("Popup windows are disabled")
                return false
            }
        }
    }

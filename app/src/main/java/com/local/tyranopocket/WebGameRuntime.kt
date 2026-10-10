package com.local.tyranopocket

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.local.tyranopocket.core.*
import java.io.*
import java.util.Collections

/** No JavascriptInterface. Read-only fs shim reuses same-origin intercepted HTTP. */
@SuppressLint("SetJavaScriptEnabled")
class WebGameRuntime(private val activity: Activity, val game: Game,
    private val vfs: VirtualFileSystem, private val onError: (String) -> Unit) {
    val container = FrameLayout(activity)
    val view = WebView(activity)
    private val log = Diagnostics(game.directory)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private var foreground = false
    private var closed = false
    private var reportedScriptError = false
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private val lifecycleScript = activity.assets.open("runtime-lifecycle.js").bufferedReader().use { it.readText() }
    private val compatScript = activity.assets.open("tyranopocket-compat.js").readBytes()
    private val startScriptSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private val csp = "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data: blob:; media-src 'self' data: blob:; font-src 'self' data:; frame-src 'self'; worker-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'"
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_GAIN && foreground) resumeMedia() else pauseMedia()
        }.build()

    private val loader = WebViewAssetLoader.Builder().setDomain(game.host).setHttpAllowed(false)
        .addPathHandler("/") { decoded ->
            // WebViewAssetLoader decodes its path exactly once. Do not URL-decode again.
            runCatching { serve(SafeFiles.relative(decoded), "GET", null, false) }
                .getOrElse { response(400, "Bad Request") }
        }.build()

    init {
        log.record("WEBVIEW_VERSION=${WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"}; SDK=${android.os.Build.VERSION.SDK_INT}")
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(view.settings) {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            builtInZoomControls = false; displayZoomControls = false
        }
        view.setDownloadListener { _, _, _, _, _ -> log.record("DOWNLOAD_BLOCKED") }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        if (startScriptSupported) {
            val js = lifecycleScript + if (game.shim) "\n" + String(compatScript, Charsets.UTF_8) else ""
            WebViewCompat.addDocumentStartJavaScript(view, js, setOf(game.origin))
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(web: WebView, request: WebResourceRequest): WebResourceResponse {
                if (!local(request.url) || request.method !in listOf("GET", "HEAD")) return response(403, "Forbidden")
                if (request.method == "HEAD" && request.url.encodedPath == "/") return response(204, "No Content")
                return try {
                    val range = request.requestHeaders.entries.firstOrNull { it.key.equals("range", true) }?.value
                    val readOnlyShim = request.requestHeaders.entries.any { it.key.equals("X-TyranoPocket-Read", true) && it.value == "1" }
                    if (range != null || request.method == "HEAD" || readOnlyShim) {
                        serve(VirtualFileSystem.urlPath(request.url.encodedPath.orEmpty()), request.method, range, readOnlyShim)
                    } else loader.shouldInterceptRequest(request.url) ?: response(404, "Not Found")
                } catch (_: Exception) { log.record("RESOURCE_REJECTED"); response(400, "Bad Request") }
            }
            override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean = !local(request.url)
            override fun onPageFinished(web: WebView, url: String) { if (local(Uri.parse(url))) log.record("PAGE_LOADED; runtime functionality not inferred") }
            override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                log.record("WEB_ERROR code=${error.errorCode} main=${request.isForMainFrame}")
                if (request.isForMainFrame) onError("게임 화면을 불러오지 못했습니다. 상세 정보에서 실행 기록을 확인할 수 있습니다.")
            }
            override fun onReceivedHttpError(web: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                log.record("HTTP_ERROR code=${error.statusCode}")
                if (request.isForMainFrame) onError("게임 화면을 불러오지 못했습니다. 메인 화면에서 게임 파일을 다시 검사해 주세요.")
            }
            override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                log.record("RENDERER_EXIT crashed=${detail.didCrash()}")
                onError("게임 화면이 중단되었습니다. 메인 화면에서 다시 실행해 주세요.")
                close(); return true
            }
            override fun onReceivedSslError(web: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
                    log.record("JS_${message.messageLevel()} line=${message.lineNumber()}")
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && !reportedScriptError) {
                    reportedScriptError = true
                    onError(if (message.message().contains("TyranoPocket unsupported API")) "이 게임은 현재 TyranoPocket에서 지원하지 않는 기능을 사용합니다."
                        else "게임 실행 중 오류가 발생했습니다. 게임 메뉴로 돌아가 상세 실행 기록을 확인해 주세요.")
                }
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
            override fun onShowFileChooser(web: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
            override fun onCreateWindow(web: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean = false
            override fun onShowCustomView(custom: View, callback: CustomViewCallback) {
                if (fullView != null) { callback.onCustomViewHidden(); return }
                fullView = custom; fullCallback = callback; container.addView(custom, FrameLayout.LayoutParams(-1, -1)); view.visibility = View.GONE
            }
            override fun onHideCustomView() = hideVideo()
        }
        view.loadUrl(game.origin + "/" + game.entry.split('/').joinToString("/") { Uri.encode(it) })
    }
    private fun local(uri: Uri) = uri.scheme == "https" && uri.host == game.host && uri.port == -1 && uri.userInfo == null
    private fun response(code: Int, reason: String, headers: Map<String, String> = emptyMap()) =
        WebResourceResponse("text/plain", "UTF-8", code, reason, headers + securityHeaders(), ByteArrayInputStream(byteArrayOf()))
    private fun securityHeaders() = mapOf("Content-Security-Policy" to csp, "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer", "Cache-Control" to "no-store")

    private fun serve(path: String, method: String, range: String?, shimRead: Boolean): WebResourceResponse {
        if (path == "__tyranopocket/lifecycle.js") return WebResourceResponse("text/javascript", "UTF-8", ByteArrayInputStream(lifecycleScript.toByteArray()))
        if (path == "__tyranopocket/compat.js" && game.shim) return WebResourceResponse("text/javascript", "UTF-8", ByteArrayInputStream(compatScript))
        val file = vfs.file(path)
        if (file == null || !file.isFile) {
            if (method == "HEAD" && vfs.exists(path)) return response(204, "No Content")
            log.record("MISSING_RESOURCE path=${path.take(240)}"); return response(404, "Not Found")
        }
        if (shimRead && file.length() > 8 * 1024 * 1024) return response(413, "Content Too Large")
        val bounds = try { ByteRange.parse(range, file.length()) }
            catch (_: IOException) { return response(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${file.length()}")) }
        val start = bounds.start; val end = bounds.end; val status = if (bounds.partial) 206 else 200
        val headers = securityHeaders().toMutableMap().apply { put("Accept-Ranges", "bytes") }
        if (bounds.partial) headers["Content-Range"] = "bytes $start-$end/${file.length()}"
        val length = bounds.length
        var stream: InputStream = if (method == "HEAD") ByteArrayInputStream(byteArrayOf()) else BoundedFileStream(file, start, length)
        val mime = VirtualFileSystem.mime(path)
        val inject = mime == "text/html" && !startScriptSupported && method == "GET" && status == 200
        if (inject) stream = injectEarly(stream) else headers["Content-Length"] = length.toString()
        return WebResourceResponse(mime, null, status, if (status == 206) "Partial Content" else "OK", headers, stream)
    }
    private fun injectEarly(input: InputStream): InputStream {
        val prefix = ByteArray(64 * 1024); var count = 0
        while (count < prefix.size) { val n = input.read(prefix, count, prefix.size - count); if (n < 0) break; count += n }
        if (count >= 2 && ((prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte()) || (prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte()))) {
            input.close(); throw IOException("UTF16_HTML_SHIM_UNSUPPORTED")
        }
        val original = prefix.copyOf(count)
        val text = String(original, Charsets.ISO_8859_1)
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(text)
        val position = head?.range?.last?.plus(1) ?: Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(text)?.range?.last?.plus(1)
            ?: if (count >= 3 && prefix[0] == 0xef.toByte() && prefix[1] == 0xbb.toByte() && prefix[2] == 0xbf.toByte()) 3 else 0
        val scripts = "<script src=\"/__tyranopocket/lifecycle.js\"></script>" + if (game.shim) "<script src=\"/__tyranopocket/compat.js\"></script>" else ""
        return SequenceInputStream(Collections.enumeration(listOf(ByteArrayInputStream(original, 0, position), ByteArrayInputStream(scripts.toByteArray(Charsets.US_ASCII)), ByteArrayInputStream(original, position, count-position), input)))
    }
    fun hideVideo() { fullView?.let { container.removeView(it) }; fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null; view.visibility = View.VISIBLE }
    fun reload() { if (!closed) { reportedScriptError = false; view.reload() } }
    fun resume() { if (closed) return; foreground = true; view.onResume(); view.resumeTimers(); if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) resumeMedia() else pauseMedia() }
    fun pause() { if (closed) return; foreground = false; pauseMedia(); audio.abandonAudioFocusRequest(focus) }
    private fun pauseMedia() {
        if (closed) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(view, true)
        view.evaluateJavascript("window.__tyranoPocketMedia&&window.__tyranoPocketMedia.pause()", null)
        view.onPause()
    }
    private fun resumeMedia() {
        if (closed || !foreground) return
        view.onResume()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(view, false)
        view.evaluateJavascript("window.__tyranoPocketMedia&&window.__tyranoPocketMedia.resume()", null)
    }
    fun close() {
        if (closed) return
        pause(); hideVideo(); closed = true
        view.stopLoading(); container.removeView(view); view.destroy(); log.close()
    }
}

private class BoundedFileStream(file: File, offset: Long, private var remaining: Long) : InputStream() {
    private val input = RandomAccessFile(file, "r").apply { seek(offset) }
    override fun read(): Int { if (remaining <= 0) return -1; val b = input.read(); if (b >= 0) remaining--; return b }
    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0) return -1
        val n = input.read(buffer, off, minOf(len.toLong(), remaining).toInt()); if (n > 0) remaining -= n; return n
    }
    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    override fun close() = input.close()
}

package com.youngsu.pinlink.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.youngsu.pinlink.BuildConfig
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.map.MapStyle

/** 자바스크립트로 넘길 숫자 — 지수 표기 없이, 무한대는 0 으로 */
internal fun jsNumber(value: Double): String =
    if (value.isFinite()) value.toString() else "0"

/**
 * 국내 지도 — 네이버 지도 JavaScript API v3 를 WebView 안에서 그린다.
 *
 * · assets/naver_map.html 을 `https://youngsujoa1-cyber.github.io/pinlink/` 를 baseURL 로 연다.
 *   (네이버 클라우드 플랫폼에 등록된 Web 서비스 URL 이 그 주소라서 지도 인증이 통과한다.)
 * · 키는 빌드할 때 local.properties 의 NAVER_MAP_CLIENT_ID 로 채워 넣는다 — 저장소에는 없다.
 * · 손가락 조작(이동·확대)은 네이버 지도가 직접 처리하고, 중심 이동·핀 탭·길게 누르기만
 *   [PinLinkNative] 브리지로 안드로이드에 알린다.
 * · 지도가 뜨지 않으면([onFailure]) 부르는 쪽이 CARTO 지도로 넘어간다.
 */
class NaverMapView(context: Context) : FrameLayout(context) {

    companion object {
        /** 네이버 클라우드 플랫폼에 등록한 Web 서비스 URL */
        const val BASE_URL = "https://youngsujoa1-cyber.github.io/pinlink/"

        private const val ASSET = "naver_map.html"
        private const val KEY_PLACEHOLDER = "__NCP_KEY_ID__"

        /** 이 시간 안에 지도가 그려지지 않으면 실패로 본다(그때 CARTO 로 넘어간다) */
        private const val READY_TIMEOUT_MS = 9_000L

        /** 지도 준비 전에 들어온 명령을 쌓아 두는 한도 */
        private const val MAX_QUEUE = 40

        private const val BACKDROP = "#0E1520"
    }

    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(context)
    private val queue = ArrayList<String>(8)

    private var ready = false
    private var dead = false
    private var failureReason: String? = null

    /** 만들자마자 실패했는가(에셋 누락 등) — 부르는 쪽이 콜백을 붙인 뒤에 확인한다 */
    val failed: Boolean get() = failureReason != null
    val failedReason: String? get() = failureReason

    var onReady: (() -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    var onCenter: ((lat: Double, lng: Double, zoom: Float) -> Unit)? = null
    var onFollowChanged: ((Boolean) -> Unit)? = null
    var onMemberTap: ((String) -> Unit)? = null
    var onLongPress: ((lat: Double, lng: Double) -> Unit)? = null

    private val watchdog = Runnable { fail("timeout") }

    init {
        setBackgroundColor(Color.parseColor(BACKDROP))
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setUpWeb()
        loadPage()
        main.postDelayed(watchdog, READY_TIMEOUT_MS)
    }

    // ------------------------------------------------------------------ 준비

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWeb() {
        web.setBackgroundColor(Color.parseColor(BACKDROP))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = false
            useWideViewPort = false
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = OVER_SCROLL_NEVER
        web.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        web.addJavascriptInterface(Bridge(), "PinLinkNative")
        web.webViewClient = object : WebViewClient() {
            // 지도 안에서 링크를 눌러 다른 페이지로 나가는 일은 없다 — 모든 이동을 막는다
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("net:${error.errorCode}")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("render-gone")
                return true
            }
        }
    }

    private fun loadPage() {
        val html = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
                .replace(KEY_PLACEHOLDER, BuildConfig.NAVER_MAP_CLIENT_ID)
        }.getOrNull()
        if (html == null) {
            fail("asset")
            return
        }
        // baseURL 이 곧 지도 인증에 쓰는 주소(location.href)다
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", BASE_URL)
    }

    private fun fail(reason: String) {
        if (dead) return
        dead = true
        failureReason = reason
        ready = false
        queue.clear()
        main.removeCallbacks(watchdog)
        onFailure?.invoke(reason)
    }

    // ------------------------------------------------------- 안드로이드 → 지도

    private fun run(code: String) {
        if (dead) return
        if (!ready) {
            if (queue.size < MAX_QUEUE) queue.add(code)
            return
        }
        runCatching { web.evaluateJavascript(code, null) }
    }

    private fun flush() {
        if (queue.isEmpty()) return
        val pending = ArrayList(queue)
        queue.clear()
        for (code in pending) runCatching { web.evaluateJavascript(code, null) }
    }

    fun pushMembers(json: String) = run("window.PinLink&&PinLink.setMembers($json)")

    fun pushMyLocation(lat: Double?, lng: Double?) =
        run(if (lat == null || lng == null) "PinLink.clearMy()" else "PinLink.setMy(${jsNumber(lat)},${jsNumber(lng)})")

    fun pushMeeting(point: MeetingPoint?) {
        if (point == null) run("PinLink.clearMeeting()")
        else run("PinLink.setMeeting(${jsNumber(point.lat)},${jsNumber(point.lng)},${quote(point.name)})")
    }

    fun setDark(dark: Boolean) = run("PinLink.setDark($dark)")

    fun setPickMode(on: Boolean) = run("PinLink.setPick($on)")

    fun setFollow(on: Boolean) = run("PinLink.setFollow($on)")

    fun moveTo(lat: Double, lng: Double, zoom: Float?) =
        run("PinLink.moveTo(${jsNumber(lat)},${jsNumber(lng)},${zoom?.let { jsNumber(it.toDouble()) } ?: "null"})")

    /** 화면에서 떠날 때 부른다 — WebView 가 붙잡은 메모리를 돌려준다 */
    fun destroy() {
        dead = true
        main.removeCallbacks(watchdog)
        queue.clear()
        removeAllViews()
        runCatching {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
    }

    // ------------------------------------------------------- 지도 → 안드로이드

    private inner class Bridge {

        @JavascriptInterface
        fun onReady() {
            main.post {
                if (dead || ready) return@post
                ready = true
                main.removeCallbacks(watchdog)
                flush()
                onReady?.invoke()
            }
        }

        @JavascriptInterface
        fun onCenter(lat: Double, lng: Double, zoom: Double) {
            if (!lat.isFinite() || !lng.isFinite()) return
            main.post { onCenter?.invoke(lat, lng, zoom.toFloat()) }
        }

        @JavascriptInterface
        fun onFollowChanged(follow: Boolean) {
            main.post { onFollowChanged?.invoke(follow) }
        }

        @JavascriptInterface
        fun onMemberTap(id: String?) {
            if (id.isNullOrEmpty()) return
            main.post { onMemberTap?.invoke(id) }
        }

        @JavascriptInterface
        fun onLongPress(lat: Double, lng: Double) {
            if (!lat.isFinite() || !lng.isFinite()) return
            main.post { onLongPress?.invoke(lat, lng) }
        }

        @JavascriptInterface
        fun onFailure(reason: String?) {
            main.post { fail(reason ?: "js") }
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(String.format(java.util.Locale.US, "\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

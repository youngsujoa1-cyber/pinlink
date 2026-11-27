package com.youngsu.pinlink.ui.widget

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.youngsu.pinlink.BuildConfig
import com.youngsu.pinlink.core.FirestoreCodec
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.core.PinColors
import com.youngsu.pinlink.map.MapRegion
import com.youngsu.pinlink.map.MapSource
import com.youngsu.pinlink.map.MapStyle

/**
 * 지도 하나로 보이는 두 지도 — 국내는 네이버, 해외는 CARTO.
 *
 *  · 지도 중심(또는 내 위치)이 대한민국 범위([MapRegion.isKorea])이고 네이버 키가 있으면
 *    [NaverMapView] 를, 그 밖이거나 네이버 지도가 뜨지 않으면 [MemberMapView] (CARTO 512px)를 보여 준다.
 *  · 바깥(화면)은 [MemberMapView] 와 똑같은 API 를 쓴다 — 부르는 쪽은 어느 지도가 떠 있는지 몰라도 된다.
 *  · 지도를 바꾸면 [onSourceChanged] 로 알린다(화면 우측 상단 `NAVER` / `CARTO` 배지).
 */
class HybridMapView(context: Context) : FrameLayout(context) {

    companion object {
        // 처음 맞추는 자리 — 서울시청 (MemberMapView 와 같은 값)
        const val DEFAULT_LAT = MemberMapView.DEFAULT_LAT
        const val DEFAULT_LNG = MemberMapView.DEFAULT_LNG
        const val DEFAULT_ZOOM = MemberMapView.DEFAULT_ZOOM

        private const val TAG = "PinLink"

        /**
         * 이번 실행에서 네이버 지도가 한 번 실패하면 다시 만들지 않는다.
         * (앱을 다시 켜면 새로 시도한다 — 실패한 지도를 다시 띄우느라 지도가 깜빡이지 않게)
         */
        private var naverBroken = false
    }

    private val carto = MemberMapView(context)
    private var naver: NaverMapView? = null

    /** 지금 화면에 떠 있는 지도 */
    var source: MapSource = MapSource.CARTO
        private set

    var mapStyle: MapStyle = MapStyle.DARK
        private set

    var onMemberTap: ((Member) -> Unit)? = null
    var onFollowChanged: ((Boolean) -> Unit)? = null
    var onSourceChanged: ((MapSource) -> Unit)? = null
    var onLongPress: ((lat: Double, lng: Double) -> Unit)? = null

    private var members: List<Member> = emptyList()
    private var myLat: Double? = null
    private var myLng: Double? = null
    private var meeting: MeetingPoint? = null
    private var selectedId: String? = null
    private var follow = true
    private var pickMode = false

    /** 같은 내용을 매초 다시 보내지 않도록 마지막으로 넘긴 값 */
    private var pushedMembers = ""
    private var pushedMy = ""
    private var pushedMeeting = ""

    private var naverLat: Double? = null
    private var naverLng: Double? = null
    private var naverZoom: Float = DEFAULT_ZOOM

    /** 지도 중심 — 지금 떠 있는 지도 기준 */
    val centerLat: Double get() = if (source == MapSource.NAVER) naverLat ?: carto.centerLat else carto.centerLat
    val centerLng: Double get() = if (source == MapSource.NAVER) naverLng ?: carto.centerLng else carto.centerLng

    init {
        addView(carto, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        carto.onMemberTap = { onMemberTap?.invoke(it) }
        carto.onFollowChanged = { value ->
            follow = value
            onFollowChanged?.invoke(value)
        }
        carto.onLongPress = { lat, lng -> onLongPress?.invoke(lat, lng) }
    }

    // --------------------------------------------------------------- 공개 API

    fun setMembers(list: List<Member>) {
        members = list
        carto.setMembers(list)
        val view = naver
        if (view != null) {
            val json = membersJson(list)
            if (json != pushedMembers) {
                pushedMembers = json
                view.pushMembers(json)
            }
        }
        evaluateSource()
    }

    fun setMyLocation(lat: Double?, lng: Double?) {
        myLat = lat
        myLng = lng
        carto.setMyLocation(lat, lng)
        val view = naver
        if (view != null) {
            val key = "$lat,$lng"
            if (key != pushedMy) {
                pushedMy = key
                view.pushMyLocation(lat, lng)
            }
        }
        evaluateSource()
    }

    fun setMeeting(point: MeetingPoint?) {
        meeting = point
        carto.setMeeting(point)
        val view = naver
        if (view != null) {
            val key = meetingKey(point)
            if (key != pushedMeeting) {
                pushedMeeting = key
                view.pushMeeting(point)
            }
        }
    }

    fun setMapStyle(style: MapStyle) {
        mapStyle = style
        carto.setMapStyle(style)
        naver?.setDark(style == MapStyle.DARK)
    }

    fun selectMember(id: String?) {
        selectedId = id
        carto.selectMember(id)
        val view = naver
        if (view != null) {
            val json = membersJson(members)
            pushedMembers = json
            view.pushMembers(json)
        }
    }

    fun setPickMode(enabled: Boolean) {
        pickMode = enabled
        carto.setPickMode(enabled)
        naver?.setPickMode(enabled)
    }

    fun setFollow(enabled: Boolean) {
        follow = enabled
        carto.setFollow(enabled)
        naver?.setFollow(enabled)
    }

    fun animateTo(lat: Double, lng: Double, newZoom: Float? = null) {
        if (source == MapSource.NAVER) {
            naverLat = lat
            naverLng = lng
            newZoom?.let { naverZoom = it }
            naver?.moveTo(lat, lng, newZoom)
        } else {
            carto.animateTo(lat, lng, newZoom)
        }
        evaluateSource(lat, lng)
    }

    fun destroy() {
        naver?.let {
            removeView(it)
            it.destroy()
        }
        naver = null
    }

    /** 화면이 완전히 끝날 때만 WebView 를 정리한다(탭 전환으로 떼었다 붙이는 것과 구분) */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val activity = context as? android.app.Activity
        if (activity == null || activity.isFinishing) destroy()
    }

    // ------------------------------------------------------------ 지도 고르기

    private fun naverUsable(): Boolean =
        BuildConfig.NAVER_MAP_CLIENT_ID.isNotBlank() && !naverBroken

    private fun evaluateSource(lat: Double? = centerLat, lng: Double? = centerLng) {
        applySource(MapRegion.decide(source, naverUsable(), lat, lng))
    }

    private fun applySource(target: MapSource) {
        if (target == source) return

        if (target == MapSource.NAVER) {
            val view = ensureNaver() ?: return
            val lat = carto.centerLat
            val lng = carto.centerLng
            val zoom = carto.zoom
            view.visibility = View.VISIBLE
            carto.visibility = View.GONE
            source = MapSource.NAVER
            naverLat = lat
            naverLng = lng
            naverZoom = zoom
            view.setDark(mapStyle == MapStyle.DARK)
            view.setFollow(follow)
            view.setPickMode(pickMode)
            pushedMembers = membersJson(members)
            pushedMy = "$myLat,$myLng"
            pushedMeeting = meetingKey(meeting)
            view.pushMembers(pushedMembers)
            view.pushMyLocation(myLat, myLng)
            view.pushMeeting(meeting)
            view.moveTo(lat, lng, zoom)
        } else {
            val lat = naverLat
            val lng = naverLng
            if (lat != null && lng != null) carto.setCenter(lat, lng, naverZoom)
            naver?.visibility = View.GONE
            carto.visibility = View.VISIBLE
            source = MapSource.CARTO
            carto.setFollow(follow)
        }
        onSourceChanged?.invoke(source)
    }

    private fun ensureNaver(): NaverMapView? {
        naver?.let { return it }
        if (!naverUsable()) return null
        val view = runCatching { NaverMapView(context) }.getOrNull()
        if (view == null) {
            naverBroken = true
            return null
        }
        view.onFailure = { reason -> handleNaverFailure(reason) }
        view.onReady = { pushStateToNaver() }
        view.onCenter = { lat, lng, zoom -> onNaverCenter(lat, lng, zoom) }
        view.onFollowChanged = { value ->
            follow = value
            onFollowChanged?.invoke(value)
        }
        view.onMemberTap = { id -> members.firstOrNull { it.id == id }?.let { onMemberTap?.invoke(it) } }
        view.onLongPress = { lat, lng -> onLongPress?.invoke(lat, lng) }
        view.visibility = View.GONE
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        naver = view
        if (view.failed) {
            // 에셋 누락처럼 만드는 순간 실패한 경우 — 콜백을 붙이기 전이라 직접 처리한다
            handleNaverFailure(view.failedReason ?: "failed")
            return null
        }
        return view
    }

    /** 네이버 지도가 준비되면 지금 상태를 한 번에 밀어 넣는다 */
    private fun pushStateToNaver() {
        val view = naver ?: return
        pushedMembers = membersJson(members)
        pushedMy = "$myLat,$myLng"
        pushedMeeting = meetingKey(meeting)
        view.setDark(mapStyle == MapStyle.DARK)
        view.setFollow(follow)
        view.setPickMode(pickMode)
        view.pushMembers(pushedMembers)
        view.pushMyLocation(myLat, myLng)
        view.pushMeeting(meeting)
        naverLat?.let { lat -> naverLng?.let { lng -> view.moveTo(lat, lng, naverZoom) } }
    }

    private fun onNaverCenter(lat: Double, lng: Double, zoom: Float) {
        naverLat = lat
        naverLng = lng
        naverZoom = zoom
        evaluateSource(lat, lng)
    }

    private fun handleNaverFailure(reason: String) {
        Log.w(TAG, "네이버 지도를 띄우지 못했습니다($reason) — CARTO 지도로 넘어갑니다")
        naverBroken = true
        val view = naver
        naver = null
        if (view != null) {
            removeView(view)
            view.destroy()
        }
        pushedMembers = ""
        pushedMy = ""
        pushedMeeting = ""
        val lat = naverLat
        val lng = naverLng
        if (lat != null && lng != null) carto.setCenter(lat, lng, naverZoom)
        carto.visibility = View.VISIBLE
        if (source == MapSource.NAVER) {
            source = MapSource.CARTO
            onSourceChanged?.invoke(MapSource.CARTO)
        }
    }

    // ------------------------------------------------------------------ JSON

    private fun membersJson(list: List<Member>): String {
        val now = System.currentTimeMillis()
        val builder = StringBuilder("[")
        var first = true
        for (member in list) {
            if (!member.valid) continue
            if (!first) builder.append(',')
            first = false
            // 오래된 핀은 투명도로 흐리지 않는다 — 밝은 지도에서 사라져 보였다.
            // 대신 stale 표시를 보내 지도 쪽에서 채도만 낮춘다 (v1.0.10)
            val stale = !member.sharing || PinLogic.isStale(member.updatedAt, now)
            builder.append('{')
            builder.append("\"id\":").append(FirestoreCodec.jsonString(member.id))
            builder.append(",\"name\":").append(FirestoreCodec.jsonString(member.name))
            // 아바타 색 그대로 쓰면 밝은 지도에서 연한 파스텔이 사라진다 — 채도를 올린 지도용 색
            builder.append(",\"color\":").append(FirestoreCodec.jsonString(PinColors.vivid(member.color)))
            builder.append(",\"lat\":").append(jsNumber(member.lat))
            builder.append(",\"lng\":").append(jsNumber(member.lng))
            builder.append(",\"stale\":").append(stale)
            builder.append(",\"selected\":").append(member.id == selectedId)
            builder.append('}')
        }
        builder.append(']')
        return builder.toString()
    }

    /** 화면 검사용 — 지금 지도에 밀어 넣을 멤버 JSON (좌표가 바뀌면 이 문자열도 바뀌어야 핀이 움직인다) */
    @androidx.annotation.VisibleForTesting
    internal fun membersJsonForTest(list: List<Member> = members): String = membersJson(list)

    /** 화면 검사용 — 지도에 마지막으로 넘긴 내 좌표 */
    @androidx.annotation.VisibleForTesting
    internal val myLatForTest: Double? get() = myLat

    @androidx.annotation.VisibleForTesting
    internal val myLngForTest: Double? get() = myLng

    private fun meetingKey(point: MeetingPoint?): String =
        if (point == null) "-" else "${jsNumber(point.lat)},${jsNumber(point.lng)},${point.name}"
}

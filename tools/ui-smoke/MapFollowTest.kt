package com.youngsu.pinlink

import android.location.Location
import android.view.View
import android.view.ViewGroup
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinColors
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.ui.MainActivity
import com.youngsu.pinlink.ui.widget.HybridMapView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 위치가 **저절로** 따라오는지 (v1.0.10).
 *
 * 2026-11-27 실기기 제보: "안 움직여" · "Gps 못잡는듯" · "나도 새로고침 눌러서 위치 바꼈고".
 * 좌표를 받아도 지도에 다시 밀어 넣는 길이 막혀 있으면 핀은 제자리에 멈춘다.
 * 여기서는 (1) 새 측정이 오면 내 파란 점의 좌표가 지도로 넘어가는지,
 * (2) 상대 멤버가 움직이면 지도에 넘기는 JSON 의 좌표가 바뀌는지,
 * (3) 늦게 도착한 옛 측정이 핀을 뒤로 되돌리지 않는지,
 * (4) 지도 핀 색이 밝은 지도용 선명한 색으로 바뀌는지 를 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapFollowTest {

    /**
     * 검사들이 PinSession(객체) 하나를 함께 쓴다 — 앞 검사가 남긴 측정보다 **항상 새 측정**이 되도록
     * 검사마다 시각을 크게 앞으로 밀어 준다 (기기에서는 언제나 '지금'이다).
     */
    private fun nextFixTime(): Long = CLOCK.addAndGet(600_000L)

    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun layout(activity: MainActivity) {
        val root = activity.window.decorView
        repeat(3) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 2340)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun fix(lat: Double, lng: Double, at: Long, accuracy: Float = 8f): Location =
        Location("gps").apply {
            latitude = lat
            longitude = lng
            time = at
            this.accuracy = accuracy
        }

    private fun member(id: String, name: String, lat: Double, lng: Double, at: Long) = Member(
        id = id,
        name = name,
        color = "#F2A9B4",
        lat = lat,
        lng = lng,
        battery = 72,
        moving = true,
        updatedAt = at,
        sharing = true
    )

    private fun openMap(app: android.app.Application, code: String): MainActivity {
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"
        prefs.roomCode = code
        prefs.roomOwner = true
        prefs.shareMode = ShareMode.PAUSED
        prefs.startedAt = 0L
        prefs.expiresAt = 0L
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)
        return activity
    }

    @Test
    fun newFixMovesMyPinOnMap() {
        val app = RuntimeEnvironment.getApplication()
        val activity = openMap(app, "MOVE01")
        val map = find(activity.window.decorView) { it is HybridMapView } as? HybridMapView
        assertNotNull("지도가 화면에 있어야 한다", map)

        val now = nextFixTime()
        PinSession.onLocation(fix(37.3948, 127.1112, now))
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        layout(activity)
        val first = PinSession.myFix
        assertNotNull("첫 측정이 들어와야 한다", first)
        assertEquals("첫 측정 위도", 37.3948, first!!.lat, 1e-6)
        assertEquals("지도에 첫 좌표가 넘어간다", 37.3948, map!!.myLatForTest ?: 0.0, 1e-6)

        // 차를 타고 이동한 뒤의 측정 (같은 앱이 스스로 받아 오는 값과 같은 모양)
        PinSession.onLocation(fix(37.4200, 127.1080, nextFixTime()))
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        layout(activity)

        assertEquals("내 좌표가 새 측정으로 바뀐다", 37.4200, PinSession.myFix!!.lat, 1e-6)
        assertEquals("지도에도 새 좌표가 넘어간다", 37.4200, map!!.myLatForTest ?: 0.0, 1e-6)
        assertEquals("경도도 함께 바뀐다", 127.1080, map.myLngForTest ?: 0.0, 1e-6)
    }

    @Test
    fun staleFixDoesNotPullPinBack() {
        val app = RuntimeEnvironment.getApplication()
        openMap(app, "MOVE02")
        val now = nextFixTime()
        PinSession.onLocation(fix(37.5000, 127.0000, now, accuracy = 6f))
        // 늦게 도착한 옛 측정 — 정확도가 좋아도 시각이 더 오래됐으면 쓰지 않는다
        PinSession.onLocation(fix(37.4000, 126.9000, now - 20_000, accuracy = 4f))
        assertEquals("옛 측정은 무시한다", 37.5000, PinSession.myFix!!.lat, 1e-6)

        // 같은 시각이면 정확도가 좋은 쪽을 쓴다
        PinSession.onLocation(fix(37.5001, 127.0001, now + 100, accuracy = 3f))
        assertEquals("같은 순간이면 정확한 값", 37.5001, PinSession.myFix!!.lat, 1e-6)
    }

    @Test
    fun movingMemberChangesMapJson() {
        val app = RuntimeEnvironment.getApplication()
        val map = HybridMapView(app)
        val now = System.currentTimeMillis()
        val before = map.membersJsonForTest(listOf(member("dev-b", "홍", 37.3948, 127.1112, now)))
        map.setMembers(listOf(member("dev-b", "홍", 37.4000, 127.1150, now + 6_000)))
        val after = map.membersJsonForTest()

        assertTrue("지도로 넘기는 JSON 에 첫 좌표가 있다: $before", before.contains("37.3948"))
        assertTrue("멤버가 움직이면 JSON 의 좌표가 바뀐다: $after", after.contains("37.4"))
        assertNotEquals("좌표가 바뀌면 같은 문자열이면 안 된다", before, after)
        // 밝은 지도 위에서 사라지지 않도록 아바타 파스텔(#F2A9B4)이 선명한 색으로 바뀐다
        assertTrue("핀 색이 선명한 색으로: $after", after.contains(PinColors.vivid("#F2A9B4")))
        assertTrue("투명도 대신 stale 표시를 보낸다", after.contains("\"stale\""))
    }

    @Test
    fun refreshNowKeepsLastFixAndAsksAgain() {
        val app = RuntimeEnvironment.getApplication()
        val activity = openActivityInRoom(app, "MOVE03")
        val map = find(activity.window.decorView) { it is HybridMapView } as? HybridMapView
        assertNotNull("지도가 화면에 있어야 한다", map)
        val now = nextFixTime()
        PinSession.onLocation(fix(37.3948, 127.1112, now))
        assertEquals("첫 측정", 37.3948, PinSession.myFix!!.lat, 1e-6)

        // '지금 갱신' — 새 측정을 요청하고 지금 좌표로 서버에 올린다.
        // (화면 검사 환경에는 진짜 GPS 가 없으므로 '상태와 지도가 서로 같은 좌표를 가리키는지'를 본다)
        PinSession.refreshNow()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        layout(activity)

        assertNotNull("갱신 뒤에도 마지막 측정을 잃지 않는다", PinSession.myFix)
        assertTrue("측정 횟수가 기록된다", PinSession.locationFixCount >= 1)
        assertTrue("마지막 측정 시각이 기록된다", PinSession.lastFixAt > 0L)
        assertEquals(
            "갱신 뒤에도 지도는 내 위치와 같은 좌표",
            PinSession.myFix!!.lat,
            map!!.myLatForTest ?: 0.0,
            1e-6
        )
    }

    private fun openActivityInRoom(app: android.app.Application, code: String): MainActivity {
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"
        prefs.roomCode = code
        prefs.roomOwner = true
        prefs.shareMode = ShareMode.PAUSED
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)
        return activity
    }

    companion object {
        /** 검사 사이에 계속 앞으로 가는 시각 — 측정이 앞 검사 값에 가려 버려지지 않게 */
        private val CLOCK = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    }
}

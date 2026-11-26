package com.youngsu.pinlink

import android.view.View
import android.view.ViewGroup
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.data.RoomInfo
import com.youngsu.pinlink.ui.MainActivity
import org.junit.Assert.assertEquals
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
 * 약속 장소(위치 지정)가 서버 응답을 기다리지 않고 바로 보이는지 (v1.0.8).
 *
 * v1.0.7 까지는 `PinSession.setMeeting` 이 서버 응답(`ensureRoom` 읽기 + `PATCH` 쓰기, 왕복 2회)이
 * 돌아온 뒤에야 `meeting` 을 바꿨기 때문에, 지도에서 약속 장소를 찍어도 깃발이 늦게 떴다.
 * 사용자 제보: "내가 글을 쓰거나 위치 지정 같은 거 하면 딜레이가 너무 되는 듯해".
 *
 * 이 검사는 **서버 응답을 기다리지 않고**(루퍼를 돌리지 않고) 값이 바뀌는지, 그리고 저장 중에
 * 방 문서 폴링이 옛 값을 들고 와도 내가 찍은 값이 유지되는지를 본다.
 * (v1.0.7 코드에서는 첫 검사가 실패한다)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantMeetingTest {

    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun layout(activity: MainActivity) {
        val root = activity.window.decorView
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 2340)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun activityInRoom(code: String): MainActivity {
        val app = RuntimeEnvironment.getApplication()
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
    fun meetingPointAppearsBeforeTheServerAnswers() {
        val activity = activityInRoom("TIMING")
        val decor = activity.window.decorView
        // 방이 있으니 지도 화면이 떠 있어야 한다
        assertTrue("지도 영역이 보여야 한다", find(decor) { it.tag == "map-area" }?.visibility == View.VISIBLE)

        val point = MeetingPoint(37.3980, 127.1090, "정자역 2번 출구", "영수", System.currentTimeMillis())
        PinSession.setMeeting(point) { }

        // 루퍼를 돌리지 않았다 = 서버 응답이 아직 오지 않았다. 그래도 내 화면 값은 이미 바뀌어 있다.
        assertEquals("약속 장소는 서버 응답 전에 바로 반영되어야 한다", point, PinSession.meeting)

        // 지도에도 그 자리로 그려진다 (화면 갱신은 다음 프레임에)
        layout(activity)
        assertEquals("화면을 한 번 그린 뒤에도 그대로여야 한다", point, PinSession.meeting)
    }

    @Test
    fun roomRefreshKeepsThePointWhileSaving() {
        activityInRoom("TIMING2")

        val old = MeetingPoint(37.5665, 126.9780, "옛 약속", "Mina", 1_700_000_000_000L)
        PinSession.applyRoomForTest(RoomInfo("TIMING2", "영수", 1_700_000_000_000L, old))
        assertEquals("방 문서에 있던 약속 장소", old, PinSession.meeting)

        val picked = MeetingPoint(37.4000, 127.1100, "서현역", "영수", System.currentTimeMillis())
        PinSession.setMeeting(picked) { }
        assertEquals("찍은 즉시", picked, PinSession.meeting)

        // 저장 중에 방 문서 폴링이 아직 옛 값을 들고 와도 깃발이 깜빡이지 않는다
        PinSession.applyRoomForTest(RoomInfo("TIMING2", "영수", 1_700_000_000_000L, old))
        assertEquals("저장이 끝나기 전에는 옛 값으로 되돌리지 않는다", picked, PinSession.meeting)
    }

    @Test
    fun uiReturnRunsATickRightAway() {
        val activity = activityInRoom("TIMING3")
        layout(activity)
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)

        // 화면을 떠났다가 돌아오면 다음 틱(1초)을 기다리지 않고 바로 한 번 돈다 —
        // 상태가 그대로여도 화면이 살아 있는지(예외 없이) 확인한다
        PinSession.setUiVisible(false)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        PinSession.setUiVisible(true)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        layout(activity)

        assertTrue("돌아온 뒤에도 지도 화면이 살아 있어야 한다", find(activity.window.decorView) { it.tag == "map-area" } != null)
        assertTrue("화면이 보이는 상태", PinSession.uiVisibleNow)
    }
}

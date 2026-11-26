package com.youngsu.pinlink

import android.view.View
import android.view.ViewGroup
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.data.RoomInfo
import com.youngsu.pinlink.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 방장의 멤버 내보내기 (v1.0.7).
 *
 *  - 방장에게만 다른 멤버 행에 '내보내기' 버튼이 보이고, 방장에게는 '방장' 배지가 붙는다
 *  - 내가 방장이 아니면 버튼이 없다
 *  - 내가 방장에 의해 내보내지면 즉시 방에서 나가고 안내 표시가 남는다(공유 중지)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomKickTest {

    private fun texts(root: View): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.visibility == View.VISIBLE) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
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

    private fun member(id: String, name: String) = Member(
        id = id,
        name = name,
        color = "#6FE3FF",
        lat = 37.35,
        lng = 127.12,
        battery = 72,
        moving = false,
        updatedAt = System.currentTimeMillis(),
        sharing = true,
        staySince = System.currentTimeMillis() - 600_000L
    )

    private fun openRoom(app: android.app.Application, code: String): MainActivity {
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
        activity.findViewById<View>(R.id.nav_room)?.performClick()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        layout(activity)
        return activity
    }

    @Test
    fun hostSeesBadgeAndKickButtons() {
        val app = RuntimeEnvironment.getApplication()
        val activity = openRoom(app, "KICK01")
        val me = AppPrefs.get(app).deviceId

        val others = listOf(member("dev-b", "Ddd"), member("dev-c", "구형폰"))
        // 이 검사 환경은 서버에 방을 만들 수 없어(자바 HttpURLConnection 이 PATCH 를 막는다)
        // 앱을 열 때 보낸 멤버 조회가 늦게 404(빈 목록)로 돌아와 채워 둔 목록을 덮어쓸 수 있다.
        // 한 번 흘려보낸 뒤에 채워 넣고 바로 확인한다.
        PinSession.previewRoomForTest(hostId = me, others = others)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        PinSession.previewRoomForTest(hostId = me, others = others)
        ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS)
        layout(activity)

        val shown = texts(activity.window.decorView)
        val badge = app.getString(R.string.host_badge)
        val kick = app.getString(R.string.kick_member)
        assertTrue("방장 배지가 보여야 한다: $shown", shown.any { it == badge })
        assertEquals("다른 멤버 두 명에게만 내보내기 버튼", 2, shown.count { it == kick })
        assertTrue("멤버 이름이 보여야 한다: $shown", shown.any { it == "Ddd" } && shown.any { it == "구형폰" })
    }

    @Test
    fun guestSeesNoKickButtons() {
        val app = RuntimeEnvironment.getApplication()
        val activity = openRoom(app, "KICK02")
        val me = AppPrefs.get(app).deviceId

        // 방장은 다른 기기 — 이 기기는 멤버일 뿐이다
        PinSession.previewRoomForTest(hostId = "dev-host", others = listOf(member("dev-b", "Ddd"), member("dev-c", "구형폰")))
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        layout(activity)

        val shown = texts(activity.window.decorView)
        val kick = app.getString(R.string.kick_member)
        assertTrue("방장이 아니면 내보내기 버튼이 없어야 한다: $shown", shown.none { it == kick })
        assertTrue("이 기기는 방장이 아니다", !PinSession.isHost)
        assertEquals("방장 ID 는 그대로", "dev-host", PinSession.hostIdResolved)
        assertFalse(me == PinSession.hostIdResolved)
    }

    @Test
    fun legacyRoomCreatorIsHost() {
        val app = RuntimeEnvironment.getApplication()
        val me = AppPrefs.get(app).deviceId
        // 옛 방: 방 문서에 hostDeviceId 가 없다 → 그 방을 만든 기기만 방장
        AppPrefs.get(app).roomCode = "KICK03"
        AppPrefs.get(app).roomOwner = true
        PinSession.init(app)
        PinSession.applyRoomForTest(RoomInfo("KICK03", "영수", System.currentTimeMillis(), null, hostId = ""))
        assertTrue("옛 방을 만든 기기는 방장으로 본다", PinSession.isHost)
        assertEquals(me, PinSession.hostIdResolved)
    }

    @Test
    fun kickedMemberLeavesAtOnce() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs.get(app)
        val activity = openRoom(app, "KICK04")
        val me = prefs.deviceId

        assertTrue("처음에는 방에 있다", PinSession.hasRoom())

        // 방장이 이 기기를 내보냈다 (서버 방 문서의 kickedIds 에 내 ID)
        PinSession.applyRoomForTest(
            RoomInfo("KICK04", "영수", System.currentTimeMillis(), null, hostId = "dev-host", kicked = listOf("dev-b", me))
        )
        ShadowLooper.idleMainLooper()
        assertFalse("내보내지면 즉시 방에서 나간다", PinSession.hasRoom())
        assertFalse("위치 공유도 멈춘다", PinSession.isSharingNow())
        assertTrue("안내 표시가 남는다", PinSession.consumeKickedNotice())
        assertFalse("안내는 한 번만 보여 준다", PinSession.consumeKickedNotice())
        assertEquals("방 코드도 지운다", "", prefs.roomCode)
    }

    @Test
    fun roomSurfaceShowsLobbyAfterKick() {
        val app = RuntimeEnvironment.getApplication()
        val activity = openRoom(app, "KICK05")
        val me = AppPrefs.get(app).deviceId

        PinSession.applyRoomForTest(
            RoomInfo("KICK05", "영수", System.currentTimeMillis(), null, hostId = "dev-host", kicked = listOf(me))
        )
        ShadowLooper.idleMainLooper()
        layout(activity)
        activity.findViewById<View>(R.id.nav_map)?.performClick()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        layout(activity)

        val shown = texts(activity.window.decorView)
        assertTrue(
            "방에서 나간 뒤에는 시작 화면(방 만들기)이 보여야 한다: $shown",
            shown.any { it == app.getString(R.string.btn_create_room) }
        )
    }
}

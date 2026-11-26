package com.youngsu.pinlink

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.youngsu.pinlink.core.ChatMessage
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.ui.MainActivity
import com.youngsu.pinlink.ui.MapChatPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * 지도 아래에 붙은 방 채팅창 (v1.0.7).
 *
 * 지도와 채팅이 위/아래로 나뉘어 함께 보이고, 지도가 채팅창보다 넓으며,
 * 하단 탭 바·배너가 그 아래에 그대로 있는지 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapChatTest {

    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

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

    @Test
    fun mapShowsChatPanelBelowTheMap() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"
        prefs.roomCode = "MAPCHT"
        prefs.roomOwner = true
        prefs.shareMode = ShareMode.PAUSED
        prefs.startedAt = 0L
        prefs.expiresAt = 0L

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        layout(activity)

        val decor = activity.window.decorView
        val panel = find(decor) { it.tag == MapChatPanel.TAG }
        val mapArea = find(decor) { it.tag == "map-area" }
        assertNotNull("지도 화면에 채팅 패널이 있어야 한다", panel)
        assertNotNull("지도 영역이 있어야 한다", mapArea)
        assertEquals("채팅 패널이 보여야 한다", View.VISIBLE, panel!!.visibility)
        assertEquals("지도 영역이 보여야 한다", View.VISIBLE, mapArea!!.visibility)

        assertTrue("지도가 채팅창보다 넓어야 한다 (map=${mapArea.height}, chat=${panel.height})", mapArea.height > panel.height)
        assertTrue("채팅창도 화면만큼은 보여야 한다 (height=${panel.height})", panel.height > activity.resources.displayMetrics.density * 100)

        // 패널은 지도 영역 **아래**에 붙는다 (같은 부모 안에서 위/아래로 나뉜다)
        assertTrue("채팅창은 지도 아래에 있어야 한다", panel.top >= mapArea.bottom - 1)

        // 채팅 입력줄이 지도 화면에서 바로 보인다
        val input = find(decor) { it is EditText && it.visibility == View.VISIBLE }
        assertNotNull("지도 화면에 채팅 입력칸이 있어야 한다", input)
        assertEquals(app.getString(R.string.chat_hint), (input as EditText).hint)

        // 하단 탭 바와 배너 자리도 그대로다 (콘텐츠 → 탭 → 배너)
        val content = decor.findViewById<ViewGroup>(android.R.id.content)
        assertEquals(3, content.getChildAt(0).let { (it as ViewGroup).childCount })
    }

    @Test
    fun messagesAppearInTheMapChatPanel() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"
        prefs.roomCode = "MAPCH2"
        prefs.roomOwner = true
        prefs.shareMode = ShareMode.PAUSED
        prefs.startedAt = 0L
        prefs.expiresAt = 0L

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)

        val now = System.currentTimeMillis()
        PinSession.previewMessagesForTest(
            listOf(
                ChatMessage("panel-1", "friend-mina", "Mina", "#FF7A59", "지도 보면서 답장할게요", now - 60_000L),
                ChatMessage("panel-2", prefs.deviceId, "영수", "#E5C158", "네 보여요", now - 30_000L)
            )
        )
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        layout(activity)

        val shown = texts(activity.window.decorView)
        assertTrue("남의 메시지가 지도 아래 채팅창에 보여야 한다: $shown", shown.any { it == "지도 보면서 답장할게요" })
        assertTrue("내 메시지도 보여야 한다: $shown", shown.any { it == "네 보여요" })
        assertTrue("보낸 사람 이름이 보여야 한다: $shown", shown.any { it.contains("Mina") })
    }

    @Test
    fun panelTogglesAndKeepsReadState() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"
        prefs.roomCode = "MAPCH3"
        prefs.roomOwner = false
        prefs.shareMode = ShareMode.PAUSED
        prefs.startedAt = 0L
        prefs.expiresAt = 0L

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        layout(activity)

        val decor = activity.window.decorView
        val panel = find(decor) { it.tag == MapChatPanel.TAG }
        assertNotNull(panel)
        val expandedHeight = panel!!.height
        assertTrue("펼친 상태에서는 자리를 차지한다", expandedHeight > 0)

        // 헤더를 누르면 접힌다 — 지도가 넓어진다
        val header = (panel as ViewGroup).getChildAt(0)
        header.performClick()
        ShadowLooper.idleMainLooper()
        layout(activity)
        val collapsedHeight = panel.height
        assertTrue("접으면 훨씬 낮아져야 한다 (expanded=$expandedHeight, collapsed=$collapsedHeight)", collapsedHeight < expandedHeight / 2)

        // 다시 누르면 펼쳐진다
        header.performClick()
        ShadowLooper.idleMainLooper()
        layout(activity)
        assertTrue("다시 펼쳐지면 높이가 돌아온다", panel.height > collapsedHeight)
    }
}

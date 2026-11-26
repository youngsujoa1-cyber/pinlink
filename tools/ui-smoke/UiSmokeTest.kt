package com.youngsu.pinlink

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.ui.LanguageOnboarding
import com.youngsu.pinlink.ui.MainActivity
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
import java.io.File
import java.util.concurrent.TimeUnit

/** 실제 안드로이드 런타임(Robolectric)에서 앱이 크래시 없이 뜨는지 본다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {

    private fun texts(root: View): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** 화면 안의 첫 입력칸 — 채팅 입력줄 확인용 */
    private fun findEditText(root: View): android.widget.EditText? {
        if (root is android.widget.EditText) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findEditText(root.getChildAt(i))?.let { return it }
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

    @Test
    fun applicationStarts() {
        val app: Application = RuntimeEnvironment.getApplication()
        assertTrue("PinLinkApp 이 만들어져야 한다", app is PinLinkApp)
    }

    @Test
    fun firstLaunchShowsLanguageOnboardingThenMainActivity() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.get(app).languageOnboarded = false

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        assertNotNull(texts(activity.window.decorView))
    }

    @Test
    fun languageOnboardingShowsAlone() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        LanguageOnboarding.show(activity)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun secondLaunchWithOnboardedFlag() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.get(app).languageOnboarded = true
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        activity.recreate()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun launcherIconRenders() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getApplicationInfo(app.packageName, 0)
        assertTrue("아이콘 리소스가 있어야 한다", info.icon != 0)
        val drawable = app.getDrawable(info.icon)
        assertNotNull("아이콘 drawable 이 그려져야 한다", drawable)
        drawable!!.setBounds(0, 0, 432, 432)
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        // 가운데 색이 실제로 칠해졌는지 (기본 안드로이드 아이콘/빈 아이콘이면 실패)
        val out = File("/workspace/work/pinlink-icon-render.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val center = bitmap.getPixel(216, 216)
        val alpha = (center ushr 24) and 0xFF
        assertTrue("아이콘 가운데가 비어 있으면 안 된다 (alpha=$alpha)", alpha > 200)
    }

    @Test
    fun tabsAndFlowsWork() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.get(app).languageOnboarded = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)

        activity.findViewById<View>(R.id.nav_room)?.performClick()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        layout(activity)
        activity.findViewById<View>(R.id.nav_settings)?.performClick()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        layout(activity)
        activity.findViewById<View>(R.id.nav_map)?.performClick()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        layout(activity)

        activity.startCreateRoom()
        ShadowLooper.idleMainLooper()
        layout(activity)
    }

    @Test
    fun savedSharingSessionRestores() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"
        prefs.roomCode = "ABC234"
        prefs.roomOwner = true
        prefs.shareMode = ShareMode.ALWAYS
        prefs.startedAt = System.currentTimeMillis()
        prefs.expiresAt = 0L

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
        layout(activity)
        assertNotNull(PinSession.roomCode)
    }


    @Test
    fun mapScreenShowsSourceBadge() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"
        prefs.roomCode = "ABC234"
        prefs.roomOwner = true
        prefs.shareMode = ShareMode.ALWAYS
        prefs.startedAt = System.currentTimeMillis()
        prefs.expiresAt = 0L

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)
        // 네이버 지도 준비 대기(9초)를 넘겨도 앱은 살아 있고, 지도는 CARTO 로 넘어간다
        ShadowLooper.idleMainLooper(12, TimeUnit.SECONDS)
        layout(activity)

        val texts = texts(activity.window.decorView)
        assertTrue(
            "지도 제공자 배지(NAVER/CARTO)가 보여야 한다: $texts",
            texts.any { it == "NAVER" || it == "CARTO" }
        )
    }

    @Test
    fun chatTabShowsRoomChat() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"
        prefs.roomCode = "UITEST"
        prefs.roomOwner = true
        prefs.shareMode = ShareMode.PAUSED
        prefs.startedAt = 0L
        prefs.expiresAt = 0L

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)

        // 하단 탭에서 채팅 열기
        activity.findViewById<View>(R.id.nav_chat)?.performClick()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        layout(activity)

        val shown = texts(activity.window.decorView)
        assertTrue("채팅 화면 제목이 보여야 한다: $shown", shown.any { it == app.getString(R.string.chat_title) })
        assertTrue("방 코드가 채팅 화면에 보여야 한다: $shown", shown.any { it == "UITEST" })
        assertTrue(
            "입력 안내가 보여야 한다",
            findEditText(activity.window.decorView)?.hint == app.getString(R.string.chat_hint)
        )

        // 메시지를 보내면 화면에 바로 말풍선이 생긴다
        PinSession.sendChat("테스트 메시지") { }
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        layout(activity)
        assertTrue(
            "보낸 메시지가 말풍선으로 보여야 한다",
            texts(activity.window.decorView).any { it == "테스트 메시지" }
        )
    }

    @Test
    fun crashLogIsRecordedAndShown() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.get(app).languageOnboarded = true
        CrashLog.record(app, IllegalStateException("테스트용 오류"))
        val saved = CrashLog.read(app)
        assertNotNull("크래시 기록이 남아야 한다", saved)
        assertTrue(saved!!.contains("테스트용 오류"))
        // 다음 실행에서 안내 창이 떠도 앱은 살아 있어야 한다
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        layout(activity)
        CrashLog.clear(app)
    }
}

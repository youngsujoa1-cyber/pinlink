package com.youngsu.pinlink

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.ui.MainActivity
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

/**
 * 하단 탭 바가 3버튼 내비게이션 바가 있는 폰에서도 보이는지 (v1.0.7 실기기 버그).
 *
 * v1.0.6 은 BottomNavigationView 높이를 64dp 로 고정해 두었는데, Material 이 시스템 하단 인셋
 * (3버튼 바 약 48dp)을 자기 paddingBottom 으로 한 번 더 먹어 탭 글자·아이콘이 잘리고
 * 화면에는 검은 빈칸만 남았다. v1.0.7 은 탭 바가 인셋을 먹지 않게 막았다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavInsetsTest {

    private fun findNav(view: View): BottomNavigationView? {
        if (view is BottomNavigationView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findNav(view.getChildAt(i))?.let { return it }
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
    fun bottomNavIgnoresSystemBarInset() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = AppPrefs.get(app)
        prefs.languageOnboarded = true
        prefs.nickname = "영수"

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)

        val nav = findNav(activity.window.decorView)
        assertNotNull("하단 탭 바가 있어야 한다", nav)
        assertEquals("탭 4개(지도·방·채팅·설정)가 그대로 있어야 한다", 4, nav!!.menu.size())

        // 3버튼 내비게이션 바(48dp ≈ 144px)가 있는 폰을 흉내 낸다
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 144))
            .build()
        ViewCompat.dispatchApplyWindowInsets(nav, insets)
        ShadowLooper.idleMainLooper()
        layout(activity)

        assertEquals("탭 바가 시스템 인셋을 패딩으로 먹으면 안 된다", 0, nav.paddingBottom)
        assertEquals("시스템 인셋과 별개로 탭 바 높이는 64dp 를 유지한다", activity.resources.displayMetrics.density.let { (64 * it + 0.5f).toInt() }, nav.height)
        assertTrue("탭 바가 화면에 그려질 높이를 가져야 한다 (height=${nav.height})", nav.height > 0)
    }

    @Test
    fun contentKeepsItsFullHeightWithInset() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.get(app).languageOnboarded = true

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)

        // 루트는 세로: 콘텐츠 → 탭 바 → 배너. 셋이 겹치지 않고 순서대로 쌓여야 한다.
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val column = root.getChildAt(0) as ViewGroup
        assertEquals("콘텐츠·탭·배너 세 칸이어야 한다", 3, column.childCount)
        val nav = findNav(column)
        assertNotNull(nav)
        assertTrue("탭 바는 배너보다 위에 있어야 한다", nav!!.top >= column.getChildAt(0).bottom)
    }
}

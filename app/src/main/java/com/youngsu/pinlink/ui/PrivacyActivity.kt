package com.youngsu.pinlink.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.youngsu.pinlink.R

/** 개인정보 처리방침 — 앱 안의 privacy.html(한국어/영어)을 그대로 보여 준다 */
class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        setContentView(root)

        val header = Ui.row(this).apply {
            setPadding(dp(10f), dp(8f), dp(16f), dp(8f))
            addView(
                FrameLayout(this@PrivacyActivity).apply {
                    addView(
                        Ui.icon(this@PrivacyActivity, R.drawable.ic_back, 21f, getColor(R.color.text_primary)),
                        FrameLayout.LayoutParams(dp(21f), dp(21f)).apply { gravity = Gravity.CENTER }
                    )
                    setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                    Ui.clickable(this) { finish() }
                },
                LinearLayout.LayoutParams(dp(44f), dp(44f))
            )
            addView(
                Ui.text(this@PrivacyActivity, getString(R.string.settings_privacy), 16f, getColor(R.color.text_primary), bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    leftMargin = dp(4f)
                }
            )
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val web = WebView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            loadUrl("file:///android_asset/privacy.html")
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            header.setPadding(dp(10f), dp(8f) + bars.top, dp(16f), dp(8f))
            root.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

package com.youngsu.pinlink.ads

import android.app.Activity
import android.content.Context
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds

/**
 * AdMob 하단 배너. 광고가 없거나 실패하면 자리를 차지하지 않고 조용히 숨긴다.
 */
object AdManager {

    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        runCatching {
            MobileAds.initialize(context.applicationContext) { }
        }
    }

    fun attachBanner(activity: Activity, container: ViewGroup) {
        runCatching { attachBannerInternal(activity, container) }
            .onFailure { container.removeAllViews(); container.visibility = ViewGroup.GONE }
    }

    private fun attachBannerInternal(activity: Activity, container: ViewGroup) {
        container.removeAllViews()
        container.visibility = ViewGroup.GONE
        if (activity.isFinishing || activity.isDestroyed) return

        val adWidthDp = bannerWidthDp(activity, container)
        val adSize = runCatching {
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidthDp)
        }.getOrNull() ?: AdSize.BANNER

        val adView = AdView(activity).apply {
            adUnitId = AdConfig.BANNER_UNIT_ID
            setAdSize(adSize)
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    container.visibility = ViewGroup.VISIBLE
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    container.visibility = ViewGroup.GONE
                }
            }
        }
        container.addView(
            adView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun bannerWidthDp(activity: Activity, container: ViewGroup): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getMetrics(metrics)
        val widthPx = if (container.width > 0) container.width else metrics.widthPixels
        val density = activity.resources.displayMetrics.density
        return (widthPx / density).toInt().coerceAtLeast(320)
    }
}

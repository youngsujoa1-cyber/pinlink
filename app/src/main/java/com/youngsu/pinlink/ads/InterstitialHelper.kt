package com.youngsu.pinlink.ads

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.lang.ref.WeakReference

/**
 * 방을 만들거나 들어간 직후에 띄우는 전면 광고.
 *
 * - 앱이 뜰 때 한 번 미리 받아 두고, 닫히거나 실패하면 다음 광고를 미리 받아 둔다.
 * - 같은 광고를 3분 안에 다시 띄우지 않는다 (첫 번째는 바로 띄운다).
 * - 광고가 아직 없으면(오프라인·로딩 중) 기다리게 하지 않고 지도 화면을 그대로 보여준다.
 *   (화면 전환은 호출하는 쪽에서 이미 끝낸 뒤 이 함수를 부른다)
 */
object InterstitialHelper {

    private const val TAG = "InterstitialHelper"

    /** 전면 광고 최소 노출 간격 — 3분 */
    const val COOLDOWN_MILLIS = 180_000L

    private var hostRef: WeakReference<Activity>? = null
    private var interstitial: InterstitialAd? = null
    private var loading = false

    /** 마지막으로 광고가 "실제로 떠서 닫힌" 시각. 0 이면 아직 한 번도 안 띄웠다. */
    private var lastShownAtMillis = 0L

    /** MainActivity 가 뜰 때 호출 — 광고를 미리 받아 둔다. */
    fun attach(activity: Activity) {
        hostRef = WeakReference(activity)
        preload()
    }

    /** MainActivity 가 사라질 때 호출 — 죽은 액티비티를 붙잡지 않는다. */
    fun detach(activity: Activity) {
        if (hostRef?.get() === activity) hostRef = null
    }

    /**
     * 방 생성·참여가 끝난 직후 호출.
     * 광고가 준비돼 있고 3분이 지났으면 바로 띄우고, 아니면 조용히 넘어간다(화면은 이미 지도).
     */
    fun showAfterRoomOpened(activity: Activity) {
        hostRef = WeakReference(activity)
        val ad = interstitial
        if (ad == null) {
            // 아직 못 받았으면 다음 진입을 위해 다시 시도해 둔다. 사용자는 기다리지 않는다.
            preload()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastShownAtMillis < COOLDOWN_MILLIS) return

        val shownBefore = lastShownAtMillis
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                // 광고가 끝나면 지도는 이미 아래에 떠 있으니 그대로 두고, 다음 광고만 받아 둔다.
                ad.fullScreenContentCallback = null
                preload()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                // 실제로 못 띄웠으면 3분 쿨다운을 쓰지 않는다. 다음 방 입장에서 다시 시도.
                lastShownAtMillis = shownBefore
                ad.fullScreenContentCallback = null
                preload()
            }

            override fun onAdShowedFullScreenContent() {
                lastShownAtMillis = now
            }
        }

        val shown = runCatching { ad.show(activity) }.isSuccess
        if (!shown) {
            interstitial = null
            lastShownAtMillis = shownBefore
            preload()
        }
    }

    /** 다음 전면 광고를 미리 받아 둔다. 이미 받았거나 받는 중이면 아무 것도 하지 않는다. */
    fun preload() {
        if (interstitial != null || loading) return
        val activity = hostRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        loading = true
        val unitId = AdConfig.interstitialUnitId()
        runCatching {
            InterstitialAd.load(
                activity,
                unitId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        loading = false
                        interstitial = ad
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loading = false
                        interstitial = null
                        Log.d(TAG, "전면 광고 로드 실패: ${error.message}")
                        // 실패해도 앱 흐름은 막지 않는다. 다음 방 입장 때 다시 시도한다.
                    }
                }
            )
        }.onFailure {
            loading = false
            interstitial = null
            Log.d(TAG, "전면 광고 로드 예외: ${it.message}")
        }
    }

    /** 지금 광고가 준비돼 있는지 (로그·디버그용) */
    fun isReady(): Boolean = interstitial != null
}

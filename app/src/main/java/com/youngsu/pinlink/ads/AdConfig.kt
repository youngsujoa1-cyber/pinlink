package com.youngsu.pinlink.ads

import com.youngsu.pinlink.BuildConfig

/**
 * 광고 설정을 한 곳에 모아 둔다.
 *
 * 광고 단위 ID 는 코드 상수가 아니라 BuildConfig 로 들어온다.
 * - debug   : Google 공식 테스트 ID
 * - release : PinLink 실제 AdMob ID (app/build.gradle.kts 의 buildConfigField)
 * 그래서 release 바이너리에는 테스트 ID 문자열이 남지 않는다.
 */
object AdConfig {

    /** 배너 단위 ID (debug = Google 테스트, release = 실제) */
    val BANNER_UNIT_ID: String
        get() = BuildConfig.ADMOB_BANNER_UNIT_ID

    /** 전면 광고 단위 ID (debug = Google 테스트, release = 실제) */
    fun interstitialUnitId(): String = BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID

    /** 지금 빌드가 테스트 광고 단위를 쓰는지 (설정 화면의 광고 안내 문구 분기) */
    fun isTestMode(): Boolean = BuildConfig.DEBUG
}

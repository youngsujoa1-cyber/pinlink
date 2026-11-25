package com.youngsu.pinlink.ads

/**
 * 광고 설정을 한 곳에 모아 둔다.
 * 지금은 Google 공식 테스트 ID를 쓰고, 실제 서비스로 바꿀 때
 * 이 파일과 AndroidManifest.xml 의 APPLICATION_ID 만 교체하면 된다.
 */
object AdConfig {

    /** Google 공식 테스트 앱 ID — AndroidManifest.xml 의 APPLICATION_ID 와 같아야 한다. */
    const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"

    /** Google 공식 테스트 배너 단위 ID (화면 최하단) */
    const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

    /** Google 공식 테스트 전면(Interstitial) 단위 ID — 방에 들어간 직후 1회 */
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    /** 출시 전 false 로 바꾸고 단위 ID 를 실제 값으로 교체한다. */
    const val USE_TEST_ADS: Boolean = true

    val BANNER_UNIT_ID: String
        get() = TEST_BANNER_UNIT_ID

    fun interstitialUnitId(): String = TEST_INTERSTITIAL_UNIT_ID

    fun isTestMode(): Boolean = USE_TEST_ADS
}

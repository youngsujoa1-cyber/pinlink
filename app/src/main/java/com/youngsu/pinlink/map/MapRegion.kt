package com.youngsu.pinlink.map

/**
 * 지도를 어느 제공자로 그릴지 정하는 순수 로직 — 안드로이드에 기대지 않으므로
 * `tools/logic-check` 에서 그대로 검증한다.
 *
 *  · 국내(대한민국 범위)는 네이버 지도 — 도로·건물·지명이 가장 자세하다.
 *  · 해외는 CARTO 512×512 @2x 지도 — 전 세계가 같은 화질로 나온다.
 */

/** 지금 화면에 그려지고 있는 지도 — 우측 상단 배지에 그대로 쓴다 */
enum class MapSource(val label: String) {
    NAVER("NAVER"),
    CARTO("CARTO")
}

object MapRegion {

    /** 대한민국 범위 — 육지와 제주·울릉·독도를 모두 담는 네모 */
    const val KOREA_MIN_LAT = 33.0
    const val KOREA_MAX_LAT = 38.9
    const val KOREA_MIN_LNG = 124.5
    const val KOREA_MAX_LNG = 132.0

    /**
     * 네이버 지도에서 CARTO 로 넘어갈 때 두는 여유(도).
     * 경계선 위에서 손가락이 조금 흔들려도 지도가 두 번 깜빡이지 않게 한다.
     */
    const val SWITCH_MARGIN = 0.6

    /** 이 좌표가 대한민국 범위 안인가 (경계 포함) */
    fun isKorea(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (!lat.isFinite() || !lng.isFinite()) return false
        return lat in KOREA_MIN_LAT..KOREA_MAX_LAT && lng in KOREA_MIN_LNG..KOREA_MAX_LNG
    }

    /** 국내에 조금 더 붙어 있는가 — 네이버 지도를 그대로 두어도 되는 범위 */
    fun nearKorea(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (!lat.isFinite() || !lng.isFinite()) return false
        return lat in (KOREA_MIN_LAT - SWITCH_MARGIN)..(KOREA_MAX_LAT + SWITCH_MARGIN) &&
            lng in (KOREA_MIN_LNG - SWITCH_MARGIN)..(KOREA_MAX_LNG + SWITCH_MARGIN)
    }

    /**
     * 지금 보여 줄 지도를 고른다.
     *
     * @param current 지금 그려지고 있는 지도
     * @param usable 네이버 지도를 쓸 수 있는가 (키가 있고, 이번 실행에서 실패한 적이 없는가)
     * @param lat 지도 중심(또는 내 위치) 위도
     * @param lng 지도 중심(또는 내 위치) 경도
     */
    fun decide(current: MapSource, usable: Boolean, lat: Double?, lng: Double?): MapSource {
        if (!usable) return MapSource.CARTO
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return MapSource.CARTO
        return when (current) {
            // 네이버 지도는 국내를 조금 벗어날 때까지 그대로 둔다
            MapSource.NAVER -> if (nearKorea(lat, lng)) MapSource.NAVER else MapSource.CARTO
            // CARTO 는 국내로 들어오면 곧바로 네이버 지도로 바꾼다
            MapSource.CARTO -> if (isKorea(lat, lng)) MapSource.NAVER else MapSource.CARTO
        }
    }
}

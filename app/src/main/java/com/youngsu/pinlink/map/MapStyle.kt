package com.youngsu.pinlink.map

/**
 * 지도 스타일 — 앱 다크 테마에 맞춘 어두운 지도와, 낮에 보기 좋은 밝고 깔끔한 지도.
 *
 * 안드로이드에 기대지 않는 순수 값이라 `tools/logic-check` 에서도 그대로 검증한다.
 *
 * @param id 설정(AppPrefs)에 저장하는 값
 * @param cartoPath CARTO 베이스맵 경로 — 둘 다 512×512 @2x 고해상도 타일을 준다
 * @param cacheDir 스타일별 디스크 캐시 폴더 이름 — 해상도·스타일이 바뀌면 폴더도 바뀐다
 */
enum class MapStyle(val id: String, val cartoPath: String, val cacheDir: String) {
    /** Dark Matter — 도로·건물만 남긴 어두운 지도 (기본값) */
    DARK("dark", "dark_all", "v2_dark"),

    /** Voyager — 밝은 바탕에 지명이 또렷한 지도 */
    LIGHT("light", "rastertiles/voyager", "v2_light");

    companion object {
        fun fromId(value: String?): MapStyle = entries.firstOrNull { it.id == value } ?: DARK
    }
}

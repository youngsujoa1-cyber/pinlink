package com.youngsu.pinlink.map

/**
 * 지도 타일 주소 만들기 — 순수 계산만 하므로 `tools/logic-check` 에서 실제 URL 을 검증한다.
 *
 *  · CARTO 베이스맵의 **512×512 @2x** 타일을 쓴다(키 파라미터 이름은 `key`).
 *  · CARTO 가 막히면 OpenStreetMap 표준 타일(256×256)로 내려간다.
 */
object TileUrl {

    /** 접속을 나누는 서브도메인 [a,b,c,d] — 한 호스트에 몰리면 타일이 늦게 온다 */
    private val SUBDOMAINS = arrayOf("a", "b", "c", "d")

    private val OSM_HOSTS = arrayOf(
        "https://tile.openstreetmap.org",
        "https://tile.openstreetmap.de",
        "https://a.tile.openstreetmap.fr/osmfr"
    )

    /**
     * CARTO 고해상도 타일 주소. 키가 비어 있으면 null(호출 쪽이 OSM 으로 넘어간다).
     *
     * 주의: 키 파라미터는 `key` 다. `api_key` 로 보내면 CARTO 는 200 응답과 함께
     * 'API KEY REQUIRED' 워터마크가 찍힌 256×256 그림을 준다.
     */
    fun carto(style: MapStyle, z: Int, x: Int, y: Int, key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        val host = SUBDOMAINS[Math.floorMod(x + y, SUBDOMAINS.size)]
        return "https://$host.basemaps.cartocdn.com/${style.cartoPath}/$z/$x/$y@2x.png?key=$trimmed"
    }

    /** CARTO 가 안 될 때 쓰는 OpenStreetMap 표준 타일 주소(앞에서부터 시도) */
    fun osm(z: Int, x: Int, y: Int): List<String> = OSM_HOSTS.map { "$it/$z/$x/$y.png" }
}

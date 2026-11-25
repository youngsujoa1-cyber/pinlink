import com.youngsu.pinlink.core.FirestoreCodec
import com.youngsu.pinlink.core.Fix
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.map.MapStyle
import com.youngsu.pinlink.map.TileUrl
import java.security.SecureRandom
import java.util.Locale

/**
 * PinLink 순수 로직 검증 (안드로이드 없이 JVM 에서 실행)
 *
 *  - 방 코드: 길이·알파벳·헷갈리는 글자 제외, 정규화, 잘못된 입력 거르기
 *  - 공유 시간: 모드별 종료 시각, 남은 시간, 만료 판정, 표시 형식
 *  - 거리·좌표: 하버사인 거리, 표시 형식, 이동 판정
 *  - 지도 타일 주소: CARTO @2x URL 모양, 서브도메인 분산, 키가 없을 때 OSM 폴백
 *  - Firestore 직렬화: 문서 필드 모양, JSON 문자열, 이스케이프, 되읽기
 */
var failures = 0

fun expect(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS  $name = $actual")
    else {
        failures++
        println("FAIL  $name = $actual (expected $expected)")
    }
}

fun expectNear(name: String, actual: Double, expected: Double, tolerance: Double = 0.5) {
    if (kotlin.math.abs(actual - expected) <= tolerance) println("PASS  $name = $actual")
    else {
        failures++
        println("FAIL  $name = $actual (expected $expected ± $tolerance)")
    }
}

fun expectTrue(name: String, value: Boolean) {
    if (value) println("PASS  $name")
    else {
        failures++
        println("FAIL  $name (expected true)")
    }
}

fun main() {
    Locale.setDefault(Locale.KOREA)

    codeChecks()
    durationChecks()
    distanceChecks()
    motionChecks()
    codecChecks()

    println()
    if (failures == 0) println("ALL CHECKS PASSED")
    else println("$failures CHECK(S) FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}

// ------------------------------------------------------------------ 방 코드

fun codeChecks() {
    expect("alphabet excludes 0/1/I/L/O", PinLogic.CODE_ALPHABET.none { it in "01OIL" }, true)
    expect("alphabet size", PinLogic.CODE_ALPHABET.length, 31)

    val random = SecureRandom()
    val codes = (1..3000).map { PinLogic.newRoomCode(random) }
    expect("code length", codes.all { it.length == 6 }, true)
    expect("code charset", codes.all { code -> code.all { it in PinLogic.CODE_ALPHABET } }, true)
    expect("codes are uppercase", codes.all { it == it.uppercase() }, true)
    expect("3000 codes are unique", codes.toSet().size, codes.size)
    expect("looksLikeCode(valid)", PinLogic.looksLikeCode(codes.first()), true)
    expect("looksLikeCode(lowercase)", PinLogic.looksLikeCode("abc234"), false)
    expect("looksLikeCode(with O)", PinLogic.looksLikeCode("ABCO23"), false)

    // 사용자가 붙여넣은 코드 다듬기
    expect("normalize lowercase", PinLogic.normalizeCode("ab c2-3d"), "ABC23D")
    expect("normalize trims", PinLogic.normalizeCode("  k7m9qp  "), "K7M9QP")
    expect("normalize keeps digits", PinLogic.normalizeCode("234567"), "234567")
    expect("normalize too short", PinLogic.normalizeCode("ABC23"), null)
    expect("normalize too long", PinLogic.normalizeCode("ABC2345"), null)
    expect("normalize symbol", PinLogic.normalizeCode("ABC$23"), null)
    expect("normalize empty", PinLogic.normalizeCode("   "), null)
    expect("normalize fullwidth space", PinLogic.normalizeCode("ＡＢＣ２３４"), "ABC234")
}

// ---------------------------------------------------------------- 공유 시간

fun durationChecks() {
    val start = 1_700_000_000_000L
    expect("ALWAYS expiry", PinLogic.expiryFrom(start, ShareMode.ALWAYS), 0L)
    expect("HOUR_1 expiry", PinLogic.expiryFrom(start, ShareMode.HOUR_1), start + 3_600_000L)
    expect("HOUR_3 expiry", PinLogic.expiryFrom(start, ShareMode.HOUR_3), start + 3 * 3_600_000L)
    expect("HOUR_8 expiry", PinLogic.expiryFrom(start, ShareMode.HOUR_8), start + 8 * 3_600_000L)
    expect("HOUR_24 expiry", PinLogic.expiryFrom(start, ShareMode.HOUR_24), start + 24 * 3_600_000L)
    expect("PAUSED expiry", PinLogic.expiryFrom(start, ShareMode.PAUSED), 0L)

    expect("ALWAYS has no expiry", ShareMode.ALWAYS.hasExpiry, false)
    expect("HOUR_8 has expiry", ShareMode.HOUR_8.hasExpiry, true)
    expect("PAUSED not sharing", ShareMode.PAUSED.isSharing, false)
    expect("ALWAYS sharing", ShareMode.ALWAYS.isSharing, true)
    expect("fromId unknown -> ALWAYS", ShareMode.fromId("nope"), ShareMode.ALWAYS)
    expect("fromId h24", ShareMode.fromId("h24"), ShareMode.HOUR_24)
    expect("fromId null -> ALWAYS", ShareMode.fromId(null), ShareMode.ALWAYS)

    val expiry8 = PinLogic.expiryFrom(start, ShareMode.HOUR_8)
    expect("not expired at start", PinLogic.isExpired(expiry8, start), false)
    expect("not expired 1ms before", PinLogic.isExpired(expiry8, expiry8 - 1), false)
    expect("expired exactly", PinLogic.isExpired(expiry8, expiry8), true)
    expect("expired after", PinLogic.isExpired(expiry8, expiry8 + 5_000), true)
    expect("expiry 0 never expires", PinLogic.isExpired(0L, Long.MAX_VALUE), false)

    expect("remaining unlimited", PinLogic.remainingMillis(0L, start), 0L)
    expect("remaining full", PinLogic.remainingMillis(expiry8, start), 8 * 3_600_000L)
    expect("remaining half", PinLogic.remainingMillis(expiry8, start + 4 * 3_600_000L), 4 * 3_600_000L)
    expect("remaining expired", PinLogic.remainingMillis(expiry8, expiry8 + 1), -1L)

    expect("format 0", PinLogic.formatDuration(0), "0:00")
    expect("format negative", PinLogic.formatDuration(-5), "0:00")
    expect("format 59s", PinLogic.formatDuration(59_000), "0:59")
    expect("format 1m", PinLogic.formatDuration(60_000), "1:00")
    expect("format 61m2s", PinLogic.formatDuration(61 * 60_000L + 2_000), "1:01:02")
    expect("format 24h", PinLogic.formatDuration(24 * 3_600_000L), "24:00:00")

    val now = 1_700_000_000_000L
    expect("minutesAgo same", PinLogic.minutesAgo(now, now), 0L)
    expect("minutesAgo 90s", PinLogic.minutesAgo(now - 90_000, now), 1L)
    expect("minutesAgo future", PinLogic.minutesAgo(now + 5_000, now), 0L)
    expect("stale after 3min", PinLogic.isStale(now - 3 * 60_000, now), true)
    expect("fresh after 30s", PinLogic.isStale(now - 30_000, now), false)
    expect("stale when never updated", PinLogic.isStale(0L, now), true)
    expect("stale custom limit", PinLogic.isStale(now - 9 * 60_000, now, 10 * 60_000L), false)
}

// -------------------------------------------------------------------- 거리

fun distanceChecks() {
    expectNear("same point", PinLogic.distanceMeters(37.5665, 126.9780, 37.5665, 126.9780), 0.0, 0.001)
    // 위도 1도 ≈ 111.19km
    expectNear("1 degree latitude", PinLogic.distanceMeters(0.0, 0.0, 1.0, 0.0), 111_194.9, 60.0)
    // 적도에서 경도 1도도 ≈ 111.19km
    expectNear("1 degree longitude at equator", PinLogic.distanceMeters(0.0, 0.0, 0.0, 1.0), 111_194.9, 60.0)
    // 서울시청 → 부산시청 약 325km
    expectNear("Seoul to Busan", PinLogic.distanceMeters(37.5665, 126.9780, 35.1796, 129.0756), 325_000.0, 6_000.0)
    // 대칭성
    val a = PinLogic.distanceMeters(37.5, 127.0, 37.51, 127.02)
    val b = PinLogic.distanceMeters(37.51, 127.02, 37.5, 127.0)
    expectNear("symmetric", a, b, 0.0001)

    expect("format 0 m", PinLogic.formatDistance(0.0), "0 m")
    expect("format 820 m", PinLogic.formatDistance(820.0), "820 m")
    expect("format 999 m", PinLogic.formatDistance(999.4), "999 m")
    expect("format 1.2 km", PinLogic.formatDistance(1_240.0), "1.2 km")
    expect("format 12 km", PinLogic.formatDistance(12_340.0), "12.3 km")
    expect("format negative", PinLogic.formatDistance(-1.0), "-")
}

// ------------------------------------------------------------------ 움직임

fun motionChecks() {
    expect("walking 1.4 m/s", PinLogic.isMoving(1.4), true)
    expect("slow 0.2 m/s", PinLogic.isMoving(0.2), false)
    expect("threshold 0.85", PinLogic.isMoving(0.85), true)
    expect("NaN speed", PinLogic.isMoving(Double.NaN), false)

    val t0 = 1_700_000_000_000L
    val previous = Fix(37.5, 127.0, at = t0)
    // 20초에 40m ≈ 2 m/s → 이동
    val moved = Fix(37.50036, 127.0, at = t0 + 20_000)
    expect("40m in 20s is moving", PinLogic.isMovingBetween(previous, moved), true)
    // 20초에 10m ≈ 0.5 m/s 이고 30m 미만 → 정지
    val jitter = Fix(37.50009, 127.0, at = t0 + 20_000)
    expect("10m in 20s is stopped", PinLogic.isMovingBetween(previous, jitter), false)
    // 100초에 100m = 1 m/s → 이동
    val slowWalk = Fix(37.5009, 127.0, at = t0 + 100_000)
    expect("100m in 100s is moving", PinLogic.isMovingBetween(previous, slowWalk), true)
    // 이전 측정 없음 → 정지
    expect("no previous fix", PinLogic.isMovingBetween(null, moved), false)
    // 시간이 같으면 판정 불가 → 정지
    expect("same timestamp", PinLogic.isMovingBetween(previous, moved.copy(at = t0)), false)

    expect("valid fix", Fix(37.5, 127.0).valid, true)
    expect("zero fix invalid", Fix(0.0, 0.0).valid, false)
    expect("NaN fix invalid", Fix(Double.NaN, 127.0).valid, false)
}

// ------------------------------------------------------------ Firestore 직렬화

fun codecChecks() {
    val member = Member(
        id = "dev1234",
        name = "영수 \"YS\"",
        color = "#2BD9A6",
        lat = 37.5665,
        lng = 126.9780,
        battery = 76,
        moving = true,
        updatedAt = 1_764_000_000_000L,
        sharing = true
    )

    val fields = FirestoreCodec.memberFields(member)
    expect("member field count", fields.size, 8)
    expect("name stringValue", fields["name"]?.get("stringValue"), "영수 \"YS\"")
    expect("lat doubleValue", fields["lat"]?.get("doubleValue"), 37.5665)
    expect("battery integerValue is string", fields["battery"]?.get("integerValue"), "76")
    expect("moving booleanValue", fields["moving"]?.get("booleanValue"), true)
    expect("updatedAt is ISO", fields["updatedAt"]?.get("timestampValue"), FirestoreCodec.isoUtc(1_764_000_000_000L))
    expectTrue("ISO ends with Z", FirestoreCodec.isoUtc(1_764_000_000_000L).endsWith("Z"))

    val back = FirestoreCodec.memberFrom(fields, "dev1234", mine = true)
    expect("roundtrip name", back?.name, member.name)
    expect("roundtrip color", back?.color, member.color)
    expect("roundtrip lat", back?.lat, member.lat)
    expect("roundtrip lng", back?.lng, member.lng)
    expect("roundtrip battery", back?.battery, 76)
    expect("roundtrip moving", back?.moving, true)
    expect("roundtrip mine", back?.mine, true)
    expectNear("roundtrip updatedAt (초 단위)", (back?.updatedAt ?: 0L).toDouble(), member.updatedAt.toDouble(), 1000.0)

    // Firestore 응답은 소수점 이하 초를 붙여 준다
    expect("parse fractional ISO", FirestoreCodec.parseIso("2026-11-25T04:01:10.260314Z"), 1_795_579_270_000L)
    expect("parse plain ISO", FirestoreCodec.parseIso("2026-11-25T04:01:10Z"), 1_795_579_270_000L)
    expect("parse empty", FirestoreCodec.parseIso(null), 0L)
    expect("parse garbage", FirestoreCodec.parseIso("not-a-date"), 0L)

    // 약속 장소
    val meeting = MeetingPoint(37.4979, 127.0276, "강남역 10번 출구", "민지", 1_764_000_000_000L)
    val roomFields = FirestoreCodec.roomFields("ABC234", "영수", 1_764_000_000_000L, meeting)
    expect("room code field", roomFields["code"]?.get("stringValue"), "ABC234")
    expect("room active", roomFields["active"]?.get("booleanValue"), true)
    expect("room meeting name", FirestoreCodec.meetingFrom(roomFields)?.name, "강남역 10번 출구")
    expect("room without meeting", FirestoreCodec.meetingFrom(FirestoreCodec.roomFields("ABC234", "영수", 0L, null)), null)

    // 부분 패치(약속 장소만) — updateMask 로 보낼 때는 meeting* 필드만 담는다
    val meetingOnly = FirestoreCodec.roomFields("ABC234", "", 0L, meeting).filterKeys { it.startsWith("meeting") }
    expect("meeting-only patch fields", meetingOnly.keys.sorted().joinToString(","), "meetingAt,meetingBy,meetingLat,meetingLng,meetingName")

    // JSON 문자열
    val json = FirestoreCodec.documentJson(
        mapOf(
            "name" to mapOf("stringValue" to "영수 \"YS\""),
            "lat" to mapOf("doubleValue" to 37.5665),
            "battery" to mapOf("integerValue" to "76"),
            "moving" to mapOf("booleanValue" to true)
        )
    )
    expect(
        "document JSON",
        json,
        "{\"fields\":{\"name\":{\"stringValue\":\"영수 \\\"YS\\\"\"},\"lat\":{\"doubleValue\":37.5665},\"battery\":{\"integerValue\":\"76\"},\"moving\":{\"booleanValue\":true}}}"
    )
    expect("escape control chars", FirestoreCodec.jsonString("a\nb\tc\\d"), "\"a\\nb\\tc\\\\d\"")
    expect("escape null char", FirestoreCodec.jsonString("\u0001"), "\"\\u0001\"")
    expect("number integer double", FirestoreCodec.numberString(35.0), "35")
    expect("number negative", FirestoreCodec.numberString(-0.5), "-0.5")
    expect("number NaN guarded", FirestoreCodec.numberString(Double.NaN), "0")
    expect("empty document", FirestoreCodec.documentJson(emptyMap()), "{\"fields\":{}}")

    // 약속 장소 PATCH — updateMask 가 없으면 Firestore 가 방 문서를 통째로 교체한다
    expect("meeting fields", FirestoreCodec.meetingFieldPaths().size, 5)
    expect(
        "meeting mask query",
        FirestoreCodec.meetingMaskQuery(),
        "updateMask.fieldPaths=meetingLat&updateMask.fieldPaths=meetingLng&updateMask.fieldPaths=meetingName&updateMask.fieldPaths=meetingBy&updateMask.fieldPaths=meetingAt"
    )
    expect("meeting mask starts without amp", FirestoreCodec.meetingMaskQuery().startsWith("&"), false)
    expect(
        "meeting mask covers every meeting field",
        FirestoreCodec.roomFields("ABC234", "me", 1L, MeetingPoint(37.5, 127.0, "강남역", "me", 2L))
            .filterKeys { it.startsWith("meeting") }.keys.sorted(),
        FirestoreCodec.meetingFieldPaths().sorted()
    )

    // 잘못된 문서는 읽지 않는다
    expect("missing lat -> null member", FirestoreCodec.memberFrom(mapOf("name" to mapOf("stringValue" to "x")), "id"), null)
    expect("integerValue as number", FirestoreCodec.memberFrom(mapOf("lat" to mapOf("doubleValue" to 1.0), "lng" to mapOf("doubleValue" to 2.0), "battery" to mapOf("integerValue" to 99)), "id")?.battery, 99)
    expect("boolean as string", FirestoreCodec.bool(mapOf("moving" to mapOf("stringValue" to "true")), "moving"), true)

    // 지도 타일 주소 — 512px@2x, 키 파라미터 이름은 key, 키가 없으면 OSM 으로 폴백
    val tileKey = "test_key_1234"
    expect(
        "carto dark @2x url",
        TileUrl.carto(MapStyle.DARK, 15, 27955, 12693, tileKey),
        "https://a.basemaps.cartocdn.com/dark_all/15/27955/12693@2x.png?key=test_key_1234"
    )
    expect(
        "carto light @2x url",
        TileUrl.carto(MapStyle.LIGHT, 16, 55883, 25378, tileKey),
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/16/55883/25378@2x.png?key=test_key_1234"
    )
    expect("carto url trims key", TileUrl.carto(MapStyle.DARK, 15, 27955, 12693, "  $tileKey  "), TileUrl.carto(MapStyle.DARK, 15, 27955, 12693, tileKey))
    expect("carto url without key", TileUrl.carto(MapStyle.DARK, 15, 1, 1, ""), null)
    expect("carto url blank key", TileUrl.carto(MapStyle.LIGHT, 15, 1, 1, "   "), null)
    expect(
        "subdomains spread",
        (0 until 8).map { x -> TileUrl.carto(MapStyle.DARK, 15, x, 0, tileKey)!!.substringAfter("https://").substringBefore(".") }.toSet().sorted(),
        listOf("a", "b", "c", "d")
    )
    expect("never api_key param", TileUrl.carto(MapStyle.DARK, 15, 7, 9, tileKey)!!.contains("api_key"), false)
    expect("osm fallback hosts", TileUrl.osm(15, 27955, 12693).size, 3)
    expect("osm tile url", TileUrl.osm(15, 27955, 12693).first(), "https://tile.openstreetmap.org/15/27955/12693.png")
    expect("style fromId dark", MapStyle.fromId("dark"), MapStyle.DARK)
    expect("style fromId light", MapStyle.fromId("light"), MapStyle.LIGHT)
    expect("style fromId unknown", MapStyle.fromId("sepia"), MapStyle.DARK)
    expect("style fromId null", MapStyle.fromId(null), MapStyle.DARK)
    expect("style cache dirs differ", MapStyle.DARK.cacheDir == MapStyle.LIGHT.cacheDir, false)
}

import com.youngsu.pinlink.core.FirestoreCodec
import com.youngsu.pinlink.core.Fix
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.map.MapRegion
import com.youngsu.pinlink.map.MapSource
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
 *  - 국내/해외 판별: 대한민국 범위, 경계값, 네이버·CARTO 전환 규칙
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
    speedChecks()
    stayChecks()
    codecChecks()
    regionChecks()

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

// ------------------------------------------------------------------ 속도(km/h)

fun speedChecks() {
    expect("speed 0 m/s", PinLogic.speedKmh(0.0), 0.0)
    expect("speed 0.3 m/s is stopped (1.08km/h)", PinLogic.speedKmh(0.3), 0.0)
    expect("speed 0.55 m/s is stopped (1.98km/h)", PinLogic.speedKmh(0.55), 0.0)
    expectNear("speed 1 m/s", PinLogic.speedKmh(1.0), 3.6, 0.001)
    expectNear("speed 11.6667 m/s is 42km/h", PinLogic.speedKmh(11.6667), 42.0, 0.05)
    expect("speed null", PinLogic.speedKmh(null), 0.0)
    expect("speed NaN", PinLogic.speedKmh(Double.NaN), 0.0)
    expect("speed negative", PinLogic.speedKmh(-2.0), 0.0)
    expect("sanitize huge", PinLogic.sanitizeSpeedKmh(9_999.0), PinLogic.MAX_SPEED_KMH)
    expect("sanitize 1.5km/h -> 0", PinLogic.sanitizeSpeedKmh(1.5), 0.0)
    expect("sanitize NaN -> 0", PinLogic.sanitizeSpeedKmh(Double.NaN), 0.0)
    expect("label 42.4 -> 42", PinLogic.speedLabelValue(42.4), 42)
    expect("isMovingAt 1.9", PinLogic.isMovingAt(1.9), false)
    expect("isMovingAt 2.0", PinLogic.isMovingAt(2.0), true)

    val t0 = 1_700_000_000_000L
    val previous = Fix(37.5, 127.0, at = t0)
    // 약 200m / 10초 = 20 m/s = 72 km/h
    val fast = Fix(37.5018, 127.0, at = t0 + 10_000)
    expectNear("speedKmhBetween ~72", PinLogic.speedKmhBetween(previous, fast), 72.0, 4.0)
    expect("speedKmhBetween no previous", PinLogic.speedKmhBetween(null, fast), 0.0)
    expect("speedKmhBetween jitter 10m", PinLogic.speedKmhBetween(previous, Fix(37.50009, 127.0, at = t0 + 10_000)), 0.0)
    expect("speedKmhBetween same time", PinLogic.speedKmhBetween(previous, fast.copy(at = t0)), 0.0)
}

// ---------------------------------------------------------------- 체류 시간

fun stayChecks() {
    val t0 = 1_700_000_000_000L
    expect("stay 30s just arrived", PinLogic.stay(t0, t0 + 30_000).justArrived, true)
    val twelve = PinLogic.stay(t0, t0 + 12 * 60_000)
    expect("stay 12min not just", twelve.justArrived, false)
    expect("stay 12min hours", twelve.hours, 0L)
    expect("stay 12min minutes", twelve.minutes, 12L)
    val long = PinLogic.stay(t0, t0 + 85 * 60_000)
    expect("stay 85min hours", long.hours, 1L)
    expect("stay 85min minutes", long.minutes, 25L)
    expect("stay unknown since", PinLogic.stay(0L, t0).justArrived, true)
    expect("stay future since", PinLogic.stay(t0 + 60_000, t0).justArrived, true)

    val anchor = Fix(37.5, 127.0, at = t0)
    expect("stay keeps when parked", PinLogic.nextStaySince(t0, anchor, Fix(37.50005, 127.0, at = t0 + 60_000), 0.0), t0)
    expect("stay resets when moving", PinLogic.nextStaySince(t0, anchor, Fix(37.5010, 127.0, at = t0 + 60_000), 30.0), t0 + 60_000)
    expect("stay resets when far (70m+)", PinLogic.nextStaySince(t0, anchor, Fix(37.5020, 127.0, at = t0 + 60_000), 0.0), t0 + 60_000)
    expect("stay first fix", PinLogic.nextStaySince(0L, null, Fix(37.5, 127.0, at = t0), 0.0), t0)
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
        sharing = true,
        speedKmh = 42.0,
        staySince = 1_764_000_000_000L
    )

    val fields = FirestoreCodec.memberFields(member)
    expect("member field count", fields.size, 8)
    expect("member field names", fields.keys.sorted().joinToString(","), FirestoreCodec.memberFieldPaths().sorted().joinToString(","))
    expect("member has no updatedAt (규칙 8개 제한)", fields.containsKey("updatedAt"), false)
    expect("member has no moving (속도로 계산)", fields.containsKey("moving"), false)
    expect("speedKmh doubleValue", fields["speedKmh"]?.get("doubleValue"), 42.0)
    expect("staySince is ISO", fields["staySince"]?.get("timestampValue"), FirestoreCodec.isoUtc(1_764_000_000_000L))
    expect("name stringValue", fields["name"]?.get("stringValue"), "영수 \"YS\"")
    expect("lat doubleValue", fields["lat"]?.get("doubleValue"), 37.5665)
    expect("battery integerValue is string", fields["battery"]?.get("integerValue"), "76")
    expect("sharing booleanValue", fields["sharing"]?.get("booleanValue"), true)
    expectTrue("ISO ends with Z", FirestoreCodec.isoUtc(1_764_000_000_000L).endsWith("Z"))

    val back = FirestoreCodec.memberFrom(fields, "dev1234", mine = true, updateTime = 1_764_000_060_000L)
    expect("roundtrip name", back?.name, member.name)
    expect("roundtrip color", back?.color, member.color)
    expect("roundtrip lat", back?.lat, member.lat)
    expect("roundtrip lng", back?.lng, member.lng)
    expect("roundtrip battery", back?.battery, 76)
    expect("roundtrip moving from speed", back?.moving, true)
    expectNear("roundtrip speed", back?.speedKmh ?: -1.0, 42.0, 0.01)
    expectNear("roundtrip staySince", (back?.staySince ?: 0L).toDouble(), 1_764_000_000_000.0, 1000.0)
    expect("roundtrip mine", back?.mine, true)
    expectNear("roundtrip updatedAt from updateTime", (back?.updatedAt ?: 0L).toDouble(), 1_764_000_060_000.0, 1000.0)

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

/**
 * 국내/해외 판별 — 지도를 네이버(국내)와 CARTO(해외) 중 어느 것으로 그릴지 정하는 규칙
 */
fun regionChecks() {
    // 대한민국 범위(위도 33.0~38.9, 경도 124.5~132.0)
    expect("seoul is korea", MapRegion.isKorea(37.5665, 126.9780), true)
    expect("busan is korea", MapRegion.isKorea(35.1796, 129.0756), true)
    expect("jeju is korea", MapRegion.isKorea(33.4996, 126.5312), true)
    expect("dokdo is korea", MapRegion.isKorea(37.2425, 131.8665), true)
    expect("paris is not korea", MapRegion.isKorea(48.8566, 2.3522), false)
    expect("tokyo is not korea", MapRegion.isKorea(35.6812, 139.7671), false)
    expect("new york is not korea", MapRegion.isKorea(40.7128, -74.0060), false)
    expect("sydney is not korea", MapRegion.isKorea(-33.8688, 151.2093), false)

    // 경계값 — 안쪽은 포함, 바깥은 제외
    expect("min lat inside", MapRegion.isKorea(33.0, 127.0), true)
    expect("min lat outside", MapRegion.isKorea(32.999, 127.0), false)
    expect("max lat inside", MapRegion.isKorea(38.9, 127.0), true)
    expect("max lat outside", MapRegion.isKorea(38.901, 127.0), false)
    expect("min lng inside", MapRegion.isKorea(37.0, 124.5), true)
    expect("min lng outside", MapRegion.isKorea(37.0, 124.499), false)
    expect("max lng inside", MapRegion.isKorea(37.0, 132.0), true)
    expect("max lng outside", MapRegion.isKorea(37.0, 132.001), false)

    // 값이 없거나 이상하면 국내가 아니다
    expect("null coords", MapRegion.isKorea(null, null), false)
    expect("nan coords", MapRegion.isKorea(Double.NaN, 127.0), false)
    expect("zero coords", MapRegion.isKorea(0.0, 0.0), false)

    // 네이버 지도에서 넘어갈 때는 여유를 둔다 (경계에서 깜빡이지 않게)
    expect("near korea north", MapRegion.nearKorea(39.3, 127.0), true)
    expect("near korea far north", MapRegion.nearKorea(39.6, 127.0), false)
    expect("near korea sea", MapRegion.nearKorea(37.0, 132.5), true)
    expect("near korea far sea", MapRegion.nearKorea(37.0, 132.7), false)
    expect("near korea japan", MapRegion.nearKorea(35.6812, 139.7671), false)

    // 전환 규칙
    expect("no key -> carto", MapRegion.decide(MapSource.NAVER, false, 37.5665, 126.9780), MapSource.CARTO)
    expect("korea on carto -> naver", MapRegion.decide(MapSource.CARTO, true, 37.5665, 126.9780), MapSource.NAVER)
    expect("korea on naver stays", MapRegion.decide(MapSource.NAVER, true, 37.5665, 126.9780), MapSource.NAVER)
    expect("overseas on carto stays", MapRegion.decide(MapSource.CARTO, true, 48.8566, 2.3522), MapSource.CARTO)
    expect("overseas on naver -> carto", MapRegion.decide(MapSource.NAVER, true, 48.8566, 2.3522), MapSource.CARTO)
    expect("japan on naver -> carto", MapRegion.decide(MapSource.NAVER, true, 35.6812, 139.7671), MapSource.CARTO)
    expect("border on naver stays", MapRegion.decide(MapSource.NAVER, true, 39.3, 127.0), MapSource.NAVER)
    expect("border on carto stays", MapRegion.decide(MapSource.CARTO, true, 39.3, 127.0), MapSource.CARTO)
    expect("no center -> carto", MapRegion.decide(MapSource.NAVER, true, null, null), MapSource.CARTO)
    expect("broken key -> carto", MapRegion.decide(MapSource.NAVER, false, 37.5665, 126.9780), MapSource.CARTO)

    // 배지 문구
    expect("naver badge", MapSource.NAVER.label, "NAVER")
    expect("carto badge", MapSource.CARTO.label, "CARTO")
}

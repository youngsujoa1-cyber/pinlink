import com.youngsu.pinlink.core.ChatMessage
import com.youngsu.pinlink.core.FirestoreCodec
import com.youngsu.pinlink.core.FirestoreFields
import com.youngsu.pinlink.core.Fix
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.core.PinColors
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.map.MapRegion
import com.youngsu.pinlink.map.MapSource
import com.youngsu.pinlink.map.MapStyle
import com.youngsu.pinlink.map.TileUrl
import java.security.SecureRandom
import java.util.Locale
import java.util.TimeZone

/**
 * PinLink 순수 로직 검증 (안드로이드 없이 JVM 에서 실행)
 *
 *  - 방 코드: 길이·알파벳·헷갈리는 글자 제외, 정규화, 잘못된 입력 거르기
 *  - 공유 시간: 모드별 종료 시각, 남은 시간, 만료 판정, 표시 형식
 *  - 거리·좌표: 하버사인 거리, 표시 형식, 이동 판정
 *  - 지도 타일 주소: CARTO @2x URL 모양, 서브도메인 분산, 키가 없을 때 OSM 폴백
 *  - 국내/해외 판별: 대한민국 범위, 경계값, 네이버·CARTO 전환 규칙
 *  - Firestore 직렬화: 문서 필드 모양, JSON 문자열, 이스케이프, 되읽기
 *  - 방 채팅: 글 다듬기, 문서 ID(뒤집은 시각) 순서, 합치기·읽지 않음, 시각 표시, 5필드
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
    chatChecks()
    hostChecks()
    timingChecks()
    uploadChecks()
    quotaChecks()
    fixChecks()
    coarseFilterChecks()
    colorChecks()
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

/**
 * 방 채팅 (v1.0.6)
 *
 *  - 글 다듬기: 앞뒤 공백 제거, 빈 글·500자 초과 거르기
 *  - 문서 ID: '뒤집은 시각'이라 ID 오름차순 = 최신 순서 (목록 API 에 정렬 옵션이 없어서)
 *  - 합치기·읽지 않음·시각 표시, Firestore 5필드(규칙의 size() <= 5 와 같은 모양)
 */
fun chatChecks() {
    // ---------------------------------------------------------------- 글 다듬기
    expect("chat trim", PinLogic.cleanChatText("  안녕하세요  "), "안녕하세요")
    expect("chat blank -> null", PinLogic.cleanChatText("   "), null)
    expect("chat empty -> null", PinLogic.cleanChatText(""), null)
    expect("chat newline inside kept", PinLogic.cleanChatText("a\nb"), "a\nb")
    expect("chat 500 ok", PinLogic.cleanChatText("가".repeat(500))?.length, 500)
    expect("chat 501 -> null", PinLogic.cleanChatText("가".repeat(501)), null)
    expect("chat limit matches rules", PinLogic.CHAT_MAX_LENGTH, 500)

    // ------------------------------------------------------------ 메시지 문서 ID
    val now = 1_795_689_757_911L
    val newer = now + 10_000L
    val idNow = PinLogic.chatDocId(now, "ABC234")
    val idNewer = PinLogic.chatDocId(newer, "ABC234")
    expect("chat id prefix", idNow.startsWith("b"), true)
    expect("chat id has 19-digit flipped time", idNow.substring(1, 20).length, 19)
    expect("chat id suffix", idNow.endsWith("-ABC234"), true)
    expect("newer id sorts first (smaller)", idNewer < idNow, true)
    expect("chat id time round-trip", PinLogic.chatMillisFromId(idNow), now)
    expect("chat id time round-trip newer", PinLogic.chatMillisFromId(idNewer), newer)
    expect("chat id broken -> 0", PinLogic.chatMillisFromId("nonsense"), 0L)
    expect("chat id ignores extra dashes", PinLogic.chatMillisFromId(idNow + "-x"), now)

    val suffix = PinLogic.chatSuffix()
    expect("chat suffix length", suffix.length, 6)
    expect("chat suffix charset", suffix.all { it in PinLogic.CODE_ALPHABET }, true)
    val ids = (1..500).map { PinLogic.chatDocId(now, PinLogic.chatSuffix()) }
    expect("chat ids unique in same ms", ids.toSet().size, ids.size)

    // ------------------------------------------------------------- 합치기·읽음
    fun msg(id: String, text: String, at: Long, sender: String = "friend", mine: Boolean = false) =
        ChatMessage(id, sender, "친구", "#E5C158", text, at, mine)

    val a = msg("a", "하나", now)
    val b = msg("b", "둘", now + 1_000L)
    val mine = ChatMessage("m", "me", "나", "#E5C158", "내 글", now + 2_000L, mine = true)
    val merged = PinLogic.mergeChat(listOf(b, a), listOf(a, mine))
    expect("merge sorted oldest first", merged.map { it.text }, listOf("하나", "둘", "내 글"))
    expect("merge dedupes by id", merged.size, 3)
    expect("merge keeps mine flag", merged.last().mine, true)
    val limited = PinLogic.mergeChat(listOf(a, b, mine), listOf(msg("c", "셋", now + 3_000L)), limit = 2)
    expect("merge keeps latest N", limited.map { it.text }, listOf("셋", "내 글").reversed())
    expect("merge drops invalid", PinLogic.mergeChat(emptyList(), listOf(msg("x", " ", now))), emptyList<ChatMessage>())

    expect("unread counts others", PinLogic.unreadCount(merged, now, "me"), 1)
    expect("unread excludes mine", PinLogic.unreadCount(merged, now + 1_500L, "me"), 0)
    expect("unread none after read", PinLogic.unreadCount(merged, now + 5_000L, "me"), 0)

    // --------------------------------------------------------------- 시각 표시
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    expect("chat clock", PinLogic.chatClock(now), "19:42")
    expect("chat clock zero", PinLogic.chatClock(0L), "")
    val dayNow = 1_795_680_000_000L                 // 2026-11-26 17:00 KST
    expect("chat day today", PinLogic.chatDayLabel(dayNow, dayNow + 3_600_000L), "TODAY")
    expect("chat day yesterday", PinLogic.chatDayLabel(dayNow - 86_400_000L, dayNow), "YESTERDAY")
    expect("chat day older", PinLogic.chatDayLabel(dayNow - 3 * 86_400_000L, dayNow), "2026.11.23")

    // ------------------------------------------------------ Firestore 5필드 문서
    val fields = FirestoreCodec.chatFields(mine)
    expect("chat doc field count", fields.size, 5)
    expect("chat doc fields match rules", FirestoreCodec.chatFieldPaths().sorted(), fields.keys.sorted())
    expect("chat doc text", FirestoreCodec.str(fields, "text"), "내 글")
    expect("chat doc sender", FirestoreCodec.str(fields, "senderId"), "me")
    expect("chat doc createdAtMs type", fields["createdAtMs"]?.keys?.first(), "integerValue")
    val json = FirestoreCodec.documentJson(fields)
    expectTrue("chat json has integerValue", json.contains("\"integerValue\":\"${now + 2_000L}\""))
    expectTrue("chat json escapes quote", FirestoreCodec.documentJson(FirestoreCodec.chatFields(
        ChatMessage("q", "me", "나", "#E5C158", "따옴표 \" 와 줄바꿈\n", now))).contains("\\\""))

    val parsed = FirestoreCodec.chatFrom(fields, "abc", myId = "me")
    expect("chat parse text", parsed?.text, "내 글")
    expect("chat parse mine", parsed?.mine, true)
    expect("chat parse not mine", FirestoreCodec.chatFrom(fields, "abc", myId = "other")?.mine, false)
    expect("chat parse keeps time", parsed?.createdAtMs, now + 2_000L)
    expect("chat parse name limit", FirestoreCodec.str(FirestoreCodec.chatFields(
        ChatMessage("n", "s", "가".repeat(60), "#FFFFFF", "x", now)), "senderName")?.length, 40)
    expect("chat parse text limit", FirestoreCodec.str(FirestoreCodec.chatFields(
        ChatMessage("t", "s", "이름", "#FFFFFF", "가".repeat(700), now)), "text")?.length, 500)
    expect("chat parse missing text -> null", FirestoreCodec.chatFrom(mapOf("createdAtMs" to mapOf("integerValue" to "1")), "id"), null)
    expect("chat parse missing time -> null", FirestoreCodec.chatFrom(mapOf("text" to mapOf("stringValue" to "x")), "id"), null)
    expect("chat parse legacy timestamp", FirestoreCodec.chatFrom(
        mapOf(
            "text" to mapOf("stringValue" to "옛 문서"),
            "createdAt" to mapOf("timestampValue" to FirestoreCodec.isoUtc(now))
        ), "id")?.createdAtMs, now - 911L)   // ISO 는 초 단위까지만 담는다
}

// ------------------------------------------------------- 방장·내보내기 (v1.0.7)

fun hostChecks() {
    // 방장 찾기 — 저장된 값이 있으면 그 기기, 없으면(옛 방) 방을 만든 기기
    expect("host saved id", PinLogic.resolveHost("host-device-1", "me", createdHere = false), "host-device-1")
    expect("host saved wins over creator", PinLogic.resolveHost("host-1", "me", createdHere = true), "host-1")
    expect("host legacy creator", PinLogic.resolveHost("", "me", createdHere = true), "me")
    expect("host legacy non-creator", PinLogic.resolveHost("", "guest", createdHere = false), "")
    expect("host legacy null", PinLogic.resolveHost(null, "guest", createdHere = false), "")
    expect("host trims", PinLogic.resolveHost("   ", "me", createdHere = true), "me")

    expect("isHost saved", PinLogic.isHost("me", "me", false), true)
    expect("isHost other", PinLogic.isHost("other", "me", false), false)
    expect("isHost legacy creator", PinLogic.isHost("", "me", true), true)
    expect("isHost legacy guest", PinLogic.isHost("", "guest", false), false)
    expect("isHost blank ids", PinLogic.isHost("", "", true), false)

    // 내보낸 목록 — 더하기·중복 없음·한도
    expect("kick add", PinLogic.withKicked(emptyList(), "b"), listOf("b"))
    expect("kick dedupe moves to end", PinLogic.withKicked(listOf("a", "b"), "a"), listOf("b", "a"))
    expect("kick keeps others", PinLogic.withKicked(listOf("a"), "b"), listOf("a", "b"))
    expect("kick ignores blank", PinLogic.withKicked(listOf("a"), "  "), listOf("a"))
    expect("kick trims target", PinLogic.withKicked(emptyList(), " b "), listOf("b"))
    val many = (1..25).map { "d$it" }
    val capped = PinLogic.withKicked(many, "new")
    expect("kick cap size", capped.size, PinLogic.KICK_LIMIT)
    expect("kick cap keeps newest", capped.last(), "new")
    expect("kick cap drops oldest", capped.firstOrNull(), "d7")
    expect("kick limit is rules limit", PinLogic.KICK_LIMIT, 20)

    expect("kickedOut yes", PinLogic.kickedOut(listOf("a", "me"), "me"), true)
    expect("kickedOut no", PinLogic.kickedOut(listOf("a"), "me"), false)
    expect("kickedOut empty", PinLogic.kickedOut(emptyList(), "me"), false)
    expect("kickedOut blank me", PinLogic.kickedOut(listOf(""), ""), false)

    // 방 문서 모양 — hostDeviceId 를 넣고/빼고, kickedIds 배열 JSON
    val room = FirestoreCodec.roomFields("ABC234", "영수", 1_700_000_000_000L, null, "host-9")
    expect("room fields with host", room.size, 5)
    expect("room host type", room["hostDeviceId"]?.keys?.first(), "stringValue")
    expect("room host value", FirestoreCodec.str(room, "hostDeviceId"), "host-9")
    expect("room without host", FirestoreCodec.roomFields("ABC234", "영수", 1_700_000_000_000L, null).size, 4)
    expect("room host length cap", FirestoreCodec.str(
        FirestoreCodec.roomFields("ABC234", "영수", 1_700_000_000_000L, null, "x".repeat(80)), "hostDeviceId")?.length, 64)
    val meetingRoom = FirestoreCodec.roomFields(
        "ABC234", "영수", 1_700_000_000_000L,
        MeetingPoint(37.5, 127.0, "정자역", "영수", 1_700_000_000_000L), "host-9"
    )
    expect("room with meeting + host", meetingRoom.size, 10)

    val kickJson = FirestoreCodec.documentJson(mapOf("kickedIds" to FirestoreCodec.stringArray(listOf("a", "b"))))
    expect("kickedIds json", kickJson, "{\"fields\":{\"kickedIds\":{\"arrayValue\":{\"values\":[{\"stringValue\":\"a\"},{\"stringValue\":\"b\"}]}}}}")
    expect("kickedIds empty json", FirestoreCodec.documentJson(mapOf("kickedIds" to FirestoreCodec.stringArray(emptyList()))),
        "{\"fields\":{\"kickedIds\":{\"arrayValue\":{\"values\":[]}}}}")
    expect("kickedIds mask", FirestoreCodec.kickMaskQuery(), "updateMask.fieldPaths=kickedIds")
    expect("host mask", FirestoreCodec.hostMaskQuery(), "updateMask.fieldPaths=hostDeviceId")

    // 읽기 — 서버 응답의 arrayValue → 목록
    val fields: FirestoreFields = mapOf("kickedIds" to mapOf("arrayValue" to listOf("a", "b")))
    expect("strList", FirestoreCodec.strList(fields, "kickedIds"), listOf("a", "b"))
    expect("strList empty", FirestoreCodec.strList(mapOf("kickedIds" to mapOf("arrayValue" to emptyList<String>())), "kickedIds"), emptyList<String>())
    expect("strList missing", FirestoreCodec.strList(emptyMap(), "kickedIds"), emptyList<String>())
    expect("strList wrong type", FirestoreCodec.strList(mapOf("kickedIds" to mapOf("stringValue" to "a")), "kickedIds"), emptyList<String>())
}

// ------------------------------------------------------- 폴링 주기 (v1.0.9)

fun timingChecks() {
    // 화면이 켜져 있을 때 — 1초 틱, 채팅 2.5초, 멤버·방 문서 6초 (v1.0.9)
    val active = PinLogic.timing(uiVisible = true, chatVisible = true)
    expect("active tick", active.tickMs, 1_000L)
    expect("active chat poll (v1.0.8 은 1.5초)", active.chatMs, 2_500L)
    expect("active member poll (v1.0.8 은 3초)", active.memberMs, 6_000L)
    expect("active room poll (v1.0.8 은 3초)", active.roomMs, 6_000L)
    expect("active upload gap (v1.0.8 은 3초)", active.uploadMs, 30_000L)
    expect("active upload move", active.uploadMeters, 5.0)
    expect("active room idle check", active.roomIdleMs, 6_000L)

    // 화면은 켜져 있지만 채팅은 접어 둔 상태 — 배지만 8초마다
    val foregroundNoChat = PinLogic.timing(uiVisible = true, chatVisible = false)
    expect("foreground no chat tick", foregroundNoChat.tickMs, 1_000L)
    expect("foreground no chat poll (v1.0.8 은 5초)", foregroundNoChat.chatMs, 8_000L)
    expect("foreground no chat keeps member poll", foregroundNoChat.memberMs, 6_000L)

    // 화면이 꺼져 있을 때 — 사용자가 지도를 보고 있지 않으므로 크게 줄인다
    val idle = PinLogic.timing(uiVisible = false, chatVisible = true)
    expect("idle tick (v1.0.8 은 5초)", idle.tickMs, 10_000L)
    expect("idle chat poll (v1.0.8 은 20초)", idle.chatMs, 60_000L)
    expect("idle member poll (v1.0.8 은 8초)", idle.memberMs, 60_000L)
    expect("idle room poll (v1.0.8 은 24초)", idle.roomMs, 30_000L)
    expect("idle upload gap (v1.0.8 은 8초)", idle.uploadMs, 45_000L)
    expect("idle upload move", idle.uploadMeters, 15.0)
    expect("idle room idle check", idle.roomIdleMs, 30_000L)

    // 채팅을 보고 있을 때가 접어 둔 때보다 느리지 않다
    expectTrue("chat visible is faster", active.chatMs <= foregroundNoChat.chatMs)
    expectTrue("idle is slower than active", idle.tickMs > active.tickMs && idle.memberMs > active.memberMs)
    // v1.0.9 의 핵심 — 화면이 꺼지면 읽기·쓰기 주기가 분 단위로 늘어난다
    expectTrue("idle chat is a minute away", idle.chatMs >= 45_000L)
    expectTrue("idle members are a minute away", idle.memberMs >= 45_000L)
    expectTrue("idle room check is 30s", idle.roomMs in 30_000L..60_000L)
    expectTrue("idle upload moves less often", idle.uploadMeters > active.uploadMeters && idle.uploadMs > active.uploadMs)

    // 지금 돌 때인가 — 마지막에 한 적이 없으면(0) 바로, 주기를 넘겼으면 바로
    expect("due never done", PinLogic.isDue(0L, 10_000L, 1_500L), true)
    expect("due just now", PinLogic.isDue(10_000L, 10_000L, 1_500L), false)
    expect("due before the interval", PinLogic.isDue(9_000L, 10_000L, 1_500L), false)
    expect("due exactly on the interval", PinLogic.isDue(8_500L, 10_000L, 1_500L), true)
    expect("due after the interval", PinLogic.isDue(5_000L, 10_000L, 1_500L), true)
    expect("due with clock moved back", PinLogic.isDue(20_000L, 10_000L, 1_500L), true)

    // 약속 장소 — 저장 중에는 서버의 옛 값으로 되돌리지 않는다
    val old = MeetingPoint(37.39, 127.10, "정자역", "영수", 1_700_000_000_000L)
    val picked = MeetingPoint(37.40, 127.11, "서현역", "영수", 1_700_000_100_000L)
    expect("meeting not saving keeps server", PinLogic.meetingAfterRefresh(false, null, old), old)
    expect("meeting not saving keeps null", PinLogic.meetingAfterRefresh(false, null, null), null)
    expect("meeting saving wins over old server value", PinLogic.meetingAfterRefresh(true, picked, old), picked)
    expect("meeting saving keeps when server has none", PinLogic.meetingAfterRefresh(true, picked, null), picked)
    expect("meeting saving wins when server already caught up", PinLogic.meetingAfterRefresh(true, picked, picked), picked)
    // 지우는 중에는 옛 깃발이 다시 살아나지 않아야 한다
    expect("meeting clearing ignores old server value", PinLogic.meetingAfterRefresh(true, null, old), null)
}

// ---------------------------------------------- 위치 업로드 조건 (v1.0.9)

fun uploadChecks() {
    val t0 = 1_700_000_000_000L
    val home = Fix(37.5665, 126.9780, at = t0)
    // 위도 0.00001도 ≈ 1.11m — 5m 는 약 0.000045도
    val near = Fix(37.56652, 126.9780, at = t0 + 3_000)      // 약 2.2m — 정지로 본다
    val moved5 = Fix(37.56660, 126.9780, at = t0 + 3_000)    // 약 11m — 5m 넘음
    val moved12 = Fix(37.56661, 126.9780, at = t0 + 3_000)   // 약 12m — 15m 미만
    val moved17 = Fix(37.56665, 126.9780, at = t0 + 3_000)   // 약 17m — 15m 넘음

    // 아직 한 번도 올리지 않았으면(방 입장 직후) 바로 올린다
    expect("never uploaded uploads now", PinLogic.shouldUpload(0L, 0.0, 0.0, false, home, t0, 5.0, 30_000L), true)
    expect("no fix does not upload", PinLogic.shouldUpload(0L, 0.0, 0.0, false, null, t0, 5.0, 30_000L), false)
    expect("invalid fix does not upload", PinLogic.shouldUpload(0L, 0.0, 0.0, false, Fix(0.0, 0.0), t0, 5.0, 30_000L), false)

    // 화면 켜짐 — 5m 미만 + 30초 미만이면 보내지 않는다 (v1.0.8 은 3초마다 같은 좌표를 덮어썼다)
    expect("same spot at 3s is skipped", PinLogic.shouldUpload(t0, home.lat, home.lng, true, home, t0 + 3_000, 5.0, 30_000L), false)
    expect("2m at 3s is skipped", PinLogic.shouldUpload(t0, home.lat, home.lng, true, near, t0 + 3_000, 5.0, 30_000L), false)
    expect("11m at 3s uploads", PinLogic.shouldUpload(t0, home.lat, home.lng, true, moved5, t0 + 3_000, 5.0, 30_000L), true)
    // 화면 켜짐 — 가만히 있어도 30초가 지나면 한 번은 올린다(심장 박동)
    expect("same spot at 29s is skipped", PinLogic.shouldUpload(t0, home.lat, home.lng, true, home, t0 + 29_000, 5.0, 30_000L), false)
    expect("same spot at 30s uploads", PinLogic.shouldUpload(t0, home.lat, home.lng, true, home, t0 + 30_000, 5.0, 30_000L), true)

    // 화면 꺼짐 — 12m 로는 올리지 않고 15m 부터, 시간은 45초
    expect("12m in idle is skipped", PinLogic.shouldUpload(t0, home.lat, home.lng, true, moved12, t0 + 40_000, 15.0, 45_000L), false)
    expect("17m in idle uploads", PinLogic.shouldUpload(t0, home.lat, home.lng, true, moved17, t0 + 3_000, 15.0, 45_000L), true)
    expect("11m in active uploads", PinLogic.shouldUpload(t0, home.lat, home.lng, true, moved5, t0 + 3_000, 5.0, 30_000L), true)
    expect("idle 44s is skipped", PinLogic.shouldUpload(t0, home.lat, home.lng, true, home, t0 + 44_000, 15.0, 45_000L), false)
    expect("idle 45s uploads", PinLogic.shouldUpload(t0, home.lat, home.lng, true, home, t0 + 45_000, 15.0, 45_000L), true)

    // 기기 시계가 뒤로 가도 한 번은 올려 본다
    expect("clock moved back uploads", PinLogic.shouldUpload(t0, home.lat, home.lng, true, home, t0 - 5_000, 5.0, 30_000L), true)

    // 하루 절약 계산: 화면 켜짐 1시간 동안 3초 주기(1,200회) → 30초+5m 조건(가만히 있으면 120회)
    val before = 3_600 / 3
    val after = 3_600 / 30
    expectTrue("upload writes drop 10x when still", after * 10 == before)
}

// ------------------------------------------------- 서버 한도 쿨다운 (v1.0.9)

fun quotaChecks() {
    val t0 = 1_700_000_000_000L
    // 처음 429 — 30초
    expect("first 429 cooldown", PinLogic.quotaCooldownMs(0L, t0), 30_000L)
    // 10분 안에 또 걸리면 60초
    expect("repeat 429 cooldown", PinLogic.quotaCooldownMs(t0, t0 + 60_000), 60_000L)
    // 10분이 지난 뒤의 429 는 다시 30초
    expect("429 after long gap", PinLogic.quotaCooldownMs(t0, t0 + 11 * 60_000L), 30_000L)

    expect("cooling right after 429", PinLogic.inQuotaCooldown(t0, 30_000L, t0 + 1), true)
    expect("cooling at 29s", PinLogic.inQuotaCooldown(t0, 30_000L, t0 + 29_000), true)
    expect("cooled at 30s", PinLogic.inQuotaCooldown(t0, 30_000L, t0 + 30_000), false)
    expect("never hit is not cooling", PinLogic.inQuotaCooldown(0L, 0L, t0), false)
    expect("cooldown 0 is not cooling", PinLogic.inQuotaCooldown(t0, 0L, t0 + 1), false)

    expect("remaining at 10s", PinLogic.quotaRemainingMs(t0, 30_000L, t0 + 10_000), 20_000L)
    expect("remaining at end", PinLogic.quotaRemainingMs(t0, 30_000L, t0 + 30_000), 0L)

    // 안내는 10분에 한 번만 — 폴링(2.5초)마다 토스트가 뜨지 않게
    expect("notice never shown", PinLogic.quotaNoticeDue(0L, t0), true)
    expect("notice again after 1 min", PinLogic.quotaNoticeDue(t0, t0 + 60_000), false)
    expect("notice after 10 min", PinLogic.quotaNoticeDue(t0, t0 + 10 * 60_000L), true)
    expect("notice after clock moved back", PinLogic.quotaNoticeDue(t0, t0 - 1_000), true)
}

// ------------------------------------------- 위치 수신 진단 (v1.0.10)

fun fixChecks() {
    val t0 = 1_700_000_000_000L

    // 살아 있는 측정 — 20초까지는 '살아 있다'
    expect("fix just now", PinLogic.fixFresh(t0, t0), true)
    expect("fix 20s ago", PinLogic.fixFresh(t0, t0 + 20_000), true)
    expect("fix 21s ago is dead", PinLogic.fixFresh(t0, t0 + 21_000), false)
    expect("no fix is not fresh", PinLogic.fixFresh(0L, t0), false)
    // 기기 시계가 뒤로 가도 살아 있는 것으로 본다 (핀이 영영 안 움직이지 않게)
    expect("clock moved back is fresh", PinLogic.fixFresh(t0, t0 - 5_000), true)

    // 다시 받아야 하는가 — 살아 있으면 요청하지 않는다
    expect("fresh fix needs no retry", PinLogic.fixRetryDue(t0, 0L, t0 + 5_000), false)
    expect("dead fix retries right away", PinLogic.fixRetryDue(t0, 0L, t0 + 30_000), true)
    // 이미 시도했으면 15초 간격을 지킨다 (요청 폭주 방지)
    expect("retry waits 15s", PinLogic.fixRetryDue(t0, t0 + 25_000, t0 + 35_000), false)
    expect("retry after 15s", PinLogic.fixRetryDue(t0, t0 + 25_000, t0 + 40_000), true)
    expect("never fixed retries at once", PinLogic.fixRetryDue(0L, 0L, t0), true)

    // 새 측정 채택 — 더 새 값은 채택, 늦게 온 옛 값은 버린다
    expect("newer fix wins", PinLogic.betterFix(t0, 10.0, t0 + 5_000, 30.0), true)
    expect("older fix is dropped", PinLogic.betterFix(t0, 10.0, t0 - 5_000, 5.0), false)
    // 같은 시각이면 정확도가 좋은 쪽
    expect("same time better accuracy wins", PinLogic.betterFix(t0, 80.0, t0 + 500, 12.0), true)
    expect("same time worse accuracy is dropped", PinLogic.betterFix(t0, 12.0, t0 + 500, 80.0), false)
    expect("first fix always accepted", PinLogic.betterFix(0L, 0.0, t0, 500.0), true)
    // 1초 안쪽 차이는 '같은 순간'으로 보고 정확도로 고른다 (융합·GPS·네트워크가 거의 동시에 올 때)
    expect("within 1s compares accuracy", PinLogic.betterFix(t0, 10.0, t0 + 900, 40.0), false)

    // 추적 주기: 화면이 켜져 있으면 1초, 꺼지면 10초 — 살아 있는지 확인은 5초마다
    expectTrue("watchdog is faster than stale limit", PinLogic.FIX_ALIVE_MS > 5_000L * 3)
    expectTrue("retry gap is shorter than stale limit", PinLogic.FIX_RETRY_MS < PinLogic.FIX_ALIVE_MS)
}

/**
 * v1.0.11 — 기지국 대략 위치가 정밀 위치를 덮어 840m 튀던 문제 (`rooms/AFZZE7`, 2026-11-28).
 * 정확도 필터(150m 초과 기지국 차단 · 첫 위치 예외)와 마지막 위치 선택을 본다.
 */
fun coarseFilterChecks() {
    val now = 1_800_000_000_000L
    fun verdict(
        hasFix: Boolean,
        previousAt: Long,
        previousAccuracy: Double,
        lastPreciseAt: Long,
        newAt: Long,
        newAccuracy: Double
    ) = PinLogic.fixVerdict(hasFix, previousAt, previousAccuracy, lastPreciseAt, newAt, newAccuracy, now)

    val accept = PinLogic.FixVerdict.ACCEPT
    val firstCoarse = PinLogic.FixVerdict.ACCEPT_COARSE_FIRST
    val dropCoarse = PinLogic.FixVerdict.DROP_COARSE
    val dropStale = PinLogic.FixVerdict.DROP_STALE

    // 정확도 경계 — 정밀(≤50m) · 대략(>150m 또는 모름)
    expect("8m is precise", PinLogic.isPreciseFix(8.0), true)
    expect("50m is still precise", PinLogic.isPreciseFix(50.0), true)
    expect("51m is not precise", PinLogic.isPreciseFix(51.0), false)
    expect("150m is not coarse", PinLogic.isCoarseFix(150.0), false)
    expect("151m is coarse", PinLogic.isCoarseFix(151.0), true)
    expect("unknown accuracy is coarse", PinLogic.isCoarseFix(0.0), true)
    expect("NaN accuracy is coarse", PinLogic.isCoarseFix(Double.NaN), true)

    // ① 위치가 아직 없을 때는 대략 위치라도 첫 핀으로 받는다 (임시 위치)
    expect(
        "no fix yet accepts 840m cell fix as first",
        verdict(false, 0L, 0.0, 0L, now, 840.0),
        firstCoarse
    )
    expect("first precise fix", verdict(false, 0L, 0.0, 0L, now, 12.0), accept)

    // ② 정밀 위치가 2분 안에 있었으면 기지국 값(840m)을 버린다 — 핀이 튀지 않게
    expect(
        "cell fix dropped while GPS was 5s ago",
        verdict(true, now - 5_000, 8.0, now - 5_000, now, 840.0),
        dropCoarse
    )
    expect(
        "cell fix dropped while precise fix is still the previous one",
        verdict(true, now - 30_000, 12.0, now - 30_000, now, 600.0),
        dropCoarse
    )
    expect(
        "cell fix dropped when precise was 119s ago",
        verdict(true, now - 119_000, 6.0, now - 119_000, now, 840.0),
        dropCoarse
    )
    // 정확도를 모르는 측정도 같은 규칙으로 버린다
    expect(
        "unknown accuracy dropped while precise was recent",
        verdict(true, now - 10_000, 6.0, now - 10_000, now, 0.0),
        dropCoarse
    )
    // ±60m Wi-Fi 융합 위치도 기지국 ±840m 보다 낫다 — 번갈아 튀지 않게 지킨다
    expect(
        "cell fix dropped while a 60m fix is recent",
        verdict(true, now - 8_000, 60.0, 0L, now, 840.0),
        dropCoarse
    )
    expect(
        "cell fix dropped while a 140m fix is recent",
        verdict(true, now - 8_000, 140.0, 0L, now, 840.0),
        dropCoarse
    )

    // ③ 경계 — 정확히 2분이면 아직 막고, 넘으면 받아들인다 (핀이 영영 멈추지 않게)
    expect(
        "cell fix dropped at exactly 120s",
        verdict(true, now - 120_000, 6.0, now - 120_000, now, 840.0),
        dropCoarse
    )
    expect(
        "cell fix accepted after 121s without precise",
        verdict(true, now - 121_000, 6.0, now - 121_000, now, 840.0),
        accept
    )
    expect(
        "cell fix accepted when precise was 3min ago",
        verdict(true, now - 180_000, 6.0, now - 180_000, now, 840.0),
        accept
    )

    // ④ 대략 위치만 있는 상태에서 좋아진 대략 위치는 받아들인다
    expect(
        "coarse to better coarse is accepted",
        verdict(true, now - 3_000, 840.0, 0L, now, 300.0),
        accept
    )

    // ⑤ 정밀 위치는 언제나 이긴다 (기지국 840m 위에 있는 상태에서 GPS 12m)
    expect(
        "precise fix replaces coarse fix",
        verdict(true, now - 3_000, 840.0, 0L, now, 12.0),
        accept
    )
    // ⑥ 늦게 도착한 옛 측정은 그대로 버린다 (v1.0.10 규칙 유지)
    expect(
        "late old measurement is dropped",
        verdict(true, now, 8.0, now, now - 20_000, 4.0),
        dropStale
    )

    // ⑦ 기기 시계가 뒤로 간 경우에도 '최근 정밀 위치'로 본다 (필터가 영영 켜지지 않게)
    expect(
        "precise fix with clock moved back counts as recent",
        PinLogic.preciseFixRecent(now + 30_000, now),
        true
    )
    expect("no precise fix yet", PinLogic.preciseFixRecent(0L, now), false)

    // ⑧ 마지막 위치 고르기 — 기지국 값이 더 새것이어도 정밀한 GPS 를 쓴다
    val gpsLast = Fix(37.752287, 127.069945, accuracyMeters = 12.0, at = now - 40_000)
    val cellLast = Fix(37.750393, 127.060652, accuracyMeters = 840.0, at = now - 1_000)
    expect("precise last known wins over newer cell", PinLogic.pickLastKnown(listOf(cellLast, gpsLast)), gpsLast)
    expect("newest wins when all are coarse", PinLogic.pickLastKnown(listOf(gpsLast, cellLast).map { it.copy(accuracyMeters = 900.0) })?.at, now - 1_000)
    expect("no candidates", PinLogic.pickLastKnown(emptyList()), null)

    // 진단 표기
    expect("accuracy label", PinLogic.accuracyLabel(840.4), "±840m")
    expect("accuracy label unknown", PinLogic.accuracyLabel(0.0), "±-")
}

// --------------------------------------------- 지도 핀 색 (v1.0.10)

fun colorChecks() {
    // 아바타 파스텔 8색 — 모두 '지도용 선명한 색'으로 바뀌어야 한다
    val palette = listOf("#E5C158", "#B9A7FF", "#6FE3FF", "#C9D3E0", "#7FDCC0", "#F2A9B4", "#F0B183", "#9FB4D8")
    for (hex in palette) {
        val vivid = PinColors.vivid(hex)
        expectTrue("palette $hex → $vivid is a color", Regex("^#[0-9A-F]{6}$").matches(vivid))
        expectTrue("palette $hex is more saturated", PinColors.channelSpread(vivid) >= PinColors.channelSpread(hex))
    }
    // 연분홍(홍) → 선명한 핫핑크
    expect("rose becomes hot pink", PinColors.vivid("#F2A9B4"), "#FF2E63")
    expect("orchid becomes violet", PinColors.vivid("#B9A7FF"), "#7C3AED")
    expect("jade becomes emerald", PinColors.vivid("#7FDCC0"), "#059669")
    // 소문자·공백도 같은 결과
    expect("lowercase rose", PinColors.vivid(" #f2a9b4 "), "#FF2E63")
    // 팔레트에 없는 색도 같은 색상으로 채도를 올린다
    val custom = PinColors.vivid("#88BB88")
    expectTrue("custom color is vivid ($custom)", PinColors.channelSpread(custom) >= 90)
    expectTrue("custom color keeps greenish hue", PinColors.parse(custom)!![1] >= PinColors.parse(custom)!![0])
    // 알파가 붙은 값·잘못된 값
    expect("alpha prefixed color", PinColors.vivid("#FFF2A9B4"), "#FF2E63")
    expect("empty color falls back", PinColors.vivid(""), PinColors.DEFAULT)
    expect("garbage color falls back", PinColors.vivid("nope"), PinColors.DEFAULT)
    expect("pin text is white", PinColors.onPin("#FF2E63"), "#FFFFFF")

    // 회색은 색상이 없으므로 밝기만 낮춰 대비를 준다
    val gray = PinColors.vivid("#B0B0B0")
    expectTrue("gray becomes dark enough ($gray)", PinColors.channelSpread(gray) < 40)
    val hsl = PinColors.toHsl(255, 46, 99)
    expectNear("rose hue", hsl[0], 344.0, 4.0)
}

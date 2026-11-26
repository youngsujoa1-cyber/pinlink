package com.youngsu.pinlink.core

import java.math.BigDecimal
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PinLink 의 순수 로직 — 안드로이드에 기대지 않으므로 JVM 에서 그대로 시험할 수 있다.
 * (방 코드 생성·정규화, 공유 시간 계산, 거리, 움직임 판정, Firestore 문서 직렬화)
 */

/** 위치 공유 모드 — 계속 / 1·3·8·24시간 / 일시정지 */
enum class ShareMode(val id: String, val hours: Int) {
    ALWAYS("always", 0),
    HOUR_1("h1", 1),
    HOUR_3("h3", 3),
    HOUR_8("h8", 8),
    HOUR_24("h24", 24),
    PAUSED("paused", -1);

    /** 지금 위치를 올리고 있는가 */
    val isSharing: Boolean get() = this != PAUSED

    /** 시간 제한이 있는가 (계속 공유·일시정지는 아님) */
    val hasExpiry: Boolean get() = hours > 0

    companion object {
        fun fromId(id: String?): ShareMode = entries.firstOrNull { it.id == id } ?: ALWAYS
    }
}

/** 한 번의 위치 측정값 */
data class Fix(
    val lat: Double,
    val lng: Double,
    val speedMps: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val at: Long = 0L
) {
    val valid: Boolean get() = lat.isFinite() && lng.isFinite() && (lat != 0.0 || lng != 0.0)
}

/** 방 멤버 (내 핀 포함) */
data class Member(
    val id: String,
    val name: String,
    val color: String,
    val lat: Double,
    val lng: Double,
    val battery: Int,
    val moving: Boolean,
    val updatedAt: Long,
    val sharing: Boolean = true,
    /** 지금 이동 속도(km/h) — 2km/h 미만은 0 으로 본다 */
    val speedKmh: Double = 0.0,
    /** 이 자리에 머물기 시작한 시각(ms) — 0 이면 모름 */
    val staySince: Long = 0L,
    val mine: Boolean = false
) {
    val valid: Boolean get() = lat.isFinite() && lng.isFinite() && !(lat == 0.0 && lng == 0.0)
}

/** 약속 장소(집결 핀) — 방 하나에 하나 */
data class MeetingPoint(
    val lat: Double,
    val lng: Double,
    val name: String,
    val by: String,
    val updatedAt: Long
)

object PinLogic {

    // ------------------------------------------------------------------ 방 코드

    const val CODE_LENGTH = 6

    /** 0/O, 1/I·L 처럼 헷갈리는 글자를 뺀 31글자 — 말로 불러도, 손으로 적어도 틀리지 않게 */
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun newRoomCode(random: SecureRandom = SecureRandom(), length: Int = CODE_LENGTH): String {
        val builder = StringBuilder(length)
        repeat(length) { builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        return builder.toString()
    }

    /**
     * 사용자가 붙여넣거나 불러 준 코드를 다듬는다.
     * 공백·하이픈·밑줄을 지우고 대문자로 바꾼 뒤 6자리 영문·숫자인지 확인한다.
     */
    fun normalizeCode(raw: String): String? {
        // 일본어·중국어 입력기에서 흔한 전각 영문·숫자(ＡＢＣ２３４)도 반각으로 받아 준다
        val builder = StringBuilder(raw.length)
        for (original in raw.trim()) {
            val ch = if (original.code in 0xFF01..0xFF5E) (original.code - 0xFEE0).toChar() else original
            if (ch.isWhitespace() || ch == '-' || ch == '_' || ch == '.') continue
            builder.append(ch)
        }
        val cleaned = builder.toString().uppercase(Locale.US)
        if (cleaned.length != CODE_LENGTH) return null
        if (!cleaned.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        return cleaned
    }

    /** 우리 알파벳으로만 만든 코드인지 (서버에 올릴 때 문서 ID 로 안전한지) */
    fun looksLikeCode(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it in CODE_ALPHABET }

    // --------------------------------------------------------------- 공유 시간

    /** 시작 시각과 모드로 자동 종료 시각을 계산한다. 계속 공유·일시정지는 0(제한 없음) */
    fun expiryFrom(startedAt: Long, mode: ShareMode): Long =
        if (mode.hasExpiry) startedAt + mode.hours * 3_600_000L else 0L

    fun isExpired(expiresAt: Long, now: Long): Boolean = expiresAt > 0L && now >= expiresAt

    /** 남은 시간 — 0 이면 제한 없음, -1 이면 이미 끝났다 */
    fun remainingMillis(expiresAt: Long, now: Long): Long = when {
        expiresAt <= 0L -> 0L
        now >= expiresAt -> -1L
        else -> expiresAt - now
    }

    /** 8:12:04 / 12:34 — 언어와 무관한 숫자 형식 */
    fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "0:00"
        val total = millis / 1000L
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** 마지막 갱신이 몇 분 전인지 (0 이면 1분 안) */
    fun minutesAgo(updatedAt: Long, now: Long): Long {
        val delta = now - updatedAt
        return if (delta <= 0L) 0L else delta / 60_000L
    }

    /** 2분 넘게 갱신이 없으면 '멈춘 핀'으로 본다 */
    fun isStale(updatedAt: Long, now: Long, limitMs: Long = 2 * 60_000L): Boolean =
        updatedAt <= 0L || now - updatedAt > limitMs

    // -------------------------------------------------------------------- 거리

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 두 좌표 사이 거리(m) — 하버사인 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(lng2 - lng1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 820 m / 1.2 km */
    fun formatDistance(meters: Double): String {
        if (!meters.isFinite() || meters < 0) return "-"
        return if (meters < 1000.0) {
            "${meters.roundToInt()} m"
        } else {
            String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
        }
    }

    // ------------------------------------------------------------------ 움직임

    /** 시속 약 3km/h 이상이면 '이동 중' */
    const val MOVING_SPEED_MPS = 0.85

    fun isMoving(speedMps: Double): Boolean = speedMps.isFinite() && speedMps >= MOVING_SPEED_MPS

    // ------------------------------------------------------------------ 속도

    /**
     * 이 아래는 '정지'로 본다 — 세워 둔 차에서도 GPS 속도가 0.5~1.5km/h 로 흔들린다.
     * (대리운전처럼 차를 세워 두는 일이 많은 앱에서는 이 흔들림을 그대로 보여 주면 지저분하다)
     */
    const val IDLE_SPEED_KMH = 2.0

    /** GPS 속도(m/s) → 화면·서버에 쓰는 km/h. 없거나 흔들림이면 0 */
    fun speedKmh(speedMps: Double?): Double {
        if (speedMps == null || !speedMps.isFinite() || speedMps < 0.0) return 0.0
        val kmh = speedMps * 3.6
        if (!kmh.isFinite() || kmh < IDLE_SPEED_KMH) return 0.0
        return round1(kmh)
    }

    /** GPS 가 속도를 주지 않을 때 — 두 측정값 사이 거리·시간으로 계산 */
    fun speedKmhBetween(previous: Fix?, current: Fix): Double {
        if (previous == null || previous.at <= 0L || current.at <= previous.at) return 0.0
        val seconds = (current.at - previous.at) / 1000.0
        val meters = distanceMeters(previous.lat, previous.lng, current.lat, current.lng)
        // 30m 미만 움직임은 GPS 오차로 본다 (isMovingBetween 과 같은 기준)
        if (meters < 30.0) return 0.0
        return speedKmh(meters / seconds)
    }

    /** 서버에서 받은 값이 이상해도 화면이 깨지지 않게 다듬는다 (음수·NaN·과속 튐 방지) */
    fun sanitizeSpeedKmh(value: Double?): Double {
        if (value == null || !value.isFinite() || value < 0.0) return 0.0
        if (value > MAX_SPEED_KMH) return MAX_SPEED_KMH
        return if (value < IDLE_SPEED_KMH) 0.0 else round1(value)
    }

    /** km/h 를 소수 첫째 자리까지 (42.34 → 42.3) */
    fun round1(value: Double): Double = if (!value.isFinite()) 0.0 else (value * 10.0).roundToInt() / 10.0

    /** 화면에 쓰는 정수 km/h — 0 이면 '정지'로 보여 준다 */
    fun speedLabelValue(kmh: Double): Int = sanitizeSpeedKmh(kmh).roundToInt()

    /** 지금 움직이는가 (속도 기준) */
    fun isMovingAt(kmh: Double): Boolean = sanitizeSpeedKmh(kmh) >= IDLE_SPEED_KMH

    /** 사람이 낼 수 없는 속도 — GPS 튐을 걸러 낸다 */
    const val MAX_SPEED_KMH = 300.0

    // ---------------------------------------------------------------- 체류 시간

    /** 이 반경 안에 머물면 '같은 자리'로 본다 (GPS 오차 감안) */
    const val STAY_RADIUS_M = 70.0

    /**
     * 머물기 시작한 시각을 갱신한다.
     *  - 움직이기 시작했거나(2km/h 이상), 처음 보는 위치거나, 70m 넘게 벗어났으면 '지금'
     *  - 같은 자리에 서 있으면 기존 시각 유지
     */
    fun nextStaySince(previousSince: Long, anchor: Fix?, current: Fix, speedKmh: Double): Long {
        if (current.at <= 0L) return previousSince
        if (previousSince <= 0L) return current.at
        if (isMovingAt(speedKmh)) return current.at
        if (anchor == null) return previousSince
        val moved = distanceMeters(anchor.lat, anchor.lng, current.lat, current.lng)
        return if (moved > STAY_RADIUS_M) current.at else previousSince
    }

    /** 화면 표시용 체류 시간 — 1분 미만은 '방금 도착' */
    data class Stay(val justArrived: Boolean, val hours: Long, val minutes: Long)

    fun stay(since: Long, now: Long): Stay {
        if (since <= 0L || now <= since) return Stay(true, 0, 0)
        val totalMinutes = (now - since) / 60_000L
        return Stay(
            justArrived = totalMinutes < 1L,
            hours = totalMinutes / 60L,
            minutes = totalMinutes % 60L
        )
    }

    /** 속도를 못 받았을 때 두 지점·시간으로 판정 (오차를 감안해 30m 이하는 무시) */
    fun isMovingBetween(previous: Fix?, current: Fix): Boolean {
        if (previous == null || previous.at <= 0L) return false
        val seconds = (current.at - previous.at) / 1000.0
        if (seconds <= 0.0) return false
        val meters = distanceMeters(previous.lat, previous.lng, current.lat, current.lng)
        if (meters < 30.0) return false
        return meters / seconds >= MOVING_SPEED_MPS
    }
}

/**
 * Firestore REST 문서 직렬화.
 *
 * 서버 문서는 `{ "fields": { 이름: { "stringValue": "..." }, ... } }` 모양이다.
 * 여기서는 (필드 이름 → { 타입: 값 }) 지도만 만들고, 그 지도를 JSON 문자열로 바꾸는 일도
 * 안드로이드 없이 끝낸다(PinCloud 는 문자열을 그대로 보내고, 응답만 JSONObject 로 읽는다).
 */
/** (필드 이름 → {타입: 값}) — Firestore 문서의 fields 부분 */
typealias FirestoreFields = Map<String, Map<String, Any>>

object FirestoreCodec {

    private const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    fun isoUtc(millis: Long): String =
        SimpleDateFormat(ISO_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))

    /** Firestore 는 2026-11-25T04:01:10.260314Z 처럼 소수점 이하 초를 붙여 준다 */
    fun parseIso(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val core = text.substringBefore('.').removeSuffix("Z")
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(core)?.time ?: 0L
        }.getOrDefault(0L)
    }

    // ---------------------------------------------------------------- 만들기

    /**
     * 멤버 문서 — **정확히 8개 필드**를 유지한다.
     *
     * 게시된 보안 규칙이 `request.resource.data.size() <= 8` 이라 9개째 필드를 보내면
     * 403 PERMISSION_DENIED 로 거부된다(v1.0.5 에서 실서버로 확인). 그래서 갱신 시각은
     * 문서 필드로 보내지 않고 Firestore 가 붙여 주는 `updateTime` 을 읽는다(서버 시계라 더 정확하다).
     */
    fun memberFields(member: Member): FirestoreFields = mapOf(
        "name" to mapOf("stringValue" to member.name),
        "color" to mapOf("stringValue" to member.color),
        "lat" to mapOf("doubleValue" to member.lat),
        "lng" to mapOf("doubleValue" to member.lng),
        "battery" to mapOf("integerValue" to member.battery.toString()),
        "sharing" to mapOf("booleanValue" to member.sharing),
        "speedKmh" to mapOf("doubleValue" to PinLogic.sanitizeSpeedKmh(member.speedKmh)),
        "staySince" to mapOf("timestampValue" to isoUtc(member.staySince.takeIf { it > 0L } ?: member.updatedAt))
    )

    /** 멤버 문서 필드 이름 — 보안 규칙의 8개 제한과 맞는지 검사에 쓴다 */
    fun memberFieldPaths(): List<String> = listOf(
        "name", "color", "lat", "lng", "battery", "sharing", "speedKmh", "staySince"
    )

    fun roomFields(code: String, owner: String, createdAt: Long, meeting: MeetingPoint?): FirestoreFields {
        val base = mutableMapOf(
            "code" to mapOf<String, Any>("stringValue" to code),
            "owner" to mapOf<String, Any>("stringValue" to owner),
            "createdAt" to mapOf<String, Any>("timestampValue" to isoUtc(createdAt)),
            "active" to mapOf<String, Any>("booleanValue" to true)
        )
        if (meeting != null) {
            base["meetingLat"] = mapOf("doubleValue" to meeting.lat)
            base["meetingLng"] = mapOf("doubleValue" to meeting.lng)
            base["meetingName"] = mapOf("stringValue" to meeting.name)
            base["meetingBy"] = mapOf("stringValue" to meeting.by)
            base["meetingAt"] = mapOf("timestampValue" to isoUtc(meeting.updatedAt))
        }
        return base
    }

    /**
     * 약속 장소를 PATCH 할 때 쓰는 필드 목록.
     * Firestore 의 PATCH 는 updateMask 없이 부르면 **문서 전체를 교체**한다 —
     * 마스크 없이 보내면 방 문서의 code·owner·createdAt 이 지워지고,
     * 약속을 지울 때는 방 문서가 빈 문서가 된다. 그래서 마스크를 항상 붙인다.
     */
    fun meetingFieldPaths(): List<String> =
        listOf("meetingLat", "meetingLng", "meetingName", "meetingBy", "meetingAt")

    /** `updateMask.fieldPaths=meetingLat&…` — 쿼리 파라미터(앞에 & 없이 시작) */
    fun meetingMaskQuery(): String =
        meetingFieldPaths().joinToString("&") { "updateMask.fieldPaths=$it" }

    // ---------------------------------------------------------------- 읽기

    fun str(fields: FirestoreFields, key: String): String? =
        (fields[key]?.get("stringValue") as? String)?.takeIf { it.isNotEmpty() }

    fun dbl(fields: FirestoreFields, key: String): Double? {
        val value = fields[key] ?: return null
        (value["doubleValue"] as? Double)?.let { return it }
        (value["integerValue"] as? String)?.let { return it.toDoubleOrNull() }
        (value["integerValue"] as? Number)?.let { return it.toDouble() }
        return null
    }

    fun integer(fields: FirestoreFields, key: String): Int? {
        val value = fields[key] ?: return null
        (value["integerValue"] as? String)?.toIntOrNull()?.let { return it }
        (value["integerValue"] as? Number)?.let { return it.toInt() }
        (value["doubleValue"] as? Double)?.let { return it.roundToInt() }
        return null
    }

    /** timestampValue 는 문자열(ISO)로 온다 — 놓치면 갱신 시각이 0 이 되어 '멈춘 핀'이 된다 */
    fun timestamp(fields: FirestoreFields, key: String): Long {
        val value = fields[key] ?: return 0L
        (value["timestampValue"] as? String)?.let { return parseIso(it) }
        (value["stringValue"] as? String)?.let { return parseIso(it) }
        return 0L
    }

    fun bool(fields: FirestoreFields, key: String): Boolean? =
        (fields[key]?.get("booleanValue") as? Boolean)
            ?: (fields[key]?.get("stringValue") as? String)?.toBooleanStrictOrNull()

    /**
     * 문서 → 멤버. 갱신 시각은 문서 필드가 아니라 문서의 `updateTime`(서버 시계)에서 받는다.
     * 옛 문서에 `updatedAt` 이 남아 있으면 그 값을 함께 본다(되도록 최신 쪽).
     */
    fun memberFrom(fields: FirestoreFields, id: String, mine: Boolean = false, updateTime: Long = 0L): Member? {
        val lat = dbl(fields, "lat") ?: return null
        val lng = dbl(fields, "lng") ?: return null
        val legacyUpdatedAt = timestamp(fields, "updatedAt")
        val speed = PinLogic.sanitizeSpeedKmh(dbl(fields, "speedKmh"))
        val staySince = timestamp(fields, "staySince").takeIf { it > 0L } ?: legacyUpdatedAt
        return Member(
            id = id,
            name = str(fields, "name") ?: "Guest",
            color = str(fields, "color") ?: "#E5C158",
            lat = lat,
            lng = lng,
            battery = integer(fields, "battery") ?: -1,
            // v1.0.5 부터 문서에 moving 이 없다 — 속도에서 계산하고, 예전 문서면 필드를 읽는다
            moving = bool(fields, "moving") ?: PinLogic.isMovingAt(speed),
            updatedAt = maxOf(legacyUpdatedAt, updateTime),
            sharing = bool(fields, "sharing") ?: true,
            speedKmh = speed,
            staySince = staySince,
            mine = mine
        )
    }

    fun meetingFrom(fields: FirestoreFields): MeetingPoint? {
        val lat = dbl(fields, "meetingLat") ?: return null
        val lng = dbl(fields, "meetingLng") ?: return null
        return MeetingPoint(
            lat = lat,
            lng = lng,
            name = str(fields, "meetingName") ?: "",
            by = str(fields, "meetingBy") ?: "",
            updatedAt = timestamp(fields, "meetingAt")
        )
    }

    // ------------------------------------------------------------ JSON 문자열

    /** `{ "fields": { ... } }` — PATCH 본문. 숫자는 지수 표기 없이 그대로 적는다. */
    fun documentJson(fields: FirestoreFields): String = buildString {
        append("{\"fields\":{")
        var firstField = true
        for ((name, value) in fields) {
            if (!firstField) append(',')
            firstField = false
            append(jsonString(name)).append(":{")
            var firstType = true
            for ((type, raw) in value) {
                if (!firstType) append(',')
                firstType = false
                append(jsonString(type)).append(':').append(jsonValue(type, raw))
            }
            append('}')
        }
        append("}}")
    }

    private fun jsonValue(type: String, raw: Any): String = when (type) {
        "stringValue", "timestampValue", "integerValue" -> jsonString(raw.toString())
        "doubleValue" -> numberString((raw as Number).toDouble())
        "booleanValue" -> if (raw as Boolean) "true" else "false"
        else -> jsonString(raw.toString())
    }

    fun numberString(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append(String.format(Locale.US, "\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

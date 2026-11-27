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

/** 방 채팅 메시지 한 건 (v1.0.6) */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val senderColor: String,
    val text: String,
    val createdAtMs: Long,
    /** 내가 보낸 메시지인가 (화면에서 오른쪽 골드 말풍선) */
    val mine: Boolean = false
) {
    val valid: Boolean get() = id.isNotBlank() && text.isNotBlank() && createdAtMs > 0L
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

    // ---------------------------------------------------------------- 방 채팅

    /** 메시지 한 건의 최대 길이 — 보안 규칙(`text.size() <= 500`)과 같은 값 */
    const val CHAT_MAX_LENGTH = 500

    /** 한 번에 받아 두는 최근 메시지 수 */
    const val CHAT_PAGE = 40

    /**
     * 입력한 글을 서버에 보낼 모양으로 다듬는다.
     * 앞뒤 공백을 지우고, 비었거나 500자를 넘으면 null(보내지 않음).
     */
    fun cleanChatText(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.length > CHAT_MAX_LENGTH) return null
        return text
    }

    /**
     * 메시지 문서 ID.
     *
     * Firestore 의 문서 목록은 **문서 ID 오름차순**으로 온다(정렬 옵션이 없다 — 하위 컬렉션
     * runQuery 는 이 프로젝트에서 403 이었다). 그래서 ID 앞에 '뒤집은 시각'(MAX-ms)을 넣어
     * ID 순서 = 최신 순서가 되게 한다. 그러면 `pageSize=1` 한 번으로 '새 메시지가 있는지'를
     * 읽기 1회로 확인할 수 있다.
     */
    fun chatDocId(createdAtMs: Long, suffix: String): String {
        val flipped = Long.MAX_VALUE - createdAtMs.coerceIn(0L, Long.MAX_VALUE)
        return "b" + flipped.toString().padStart(19, '0') + "-" + suffix
    }

    /** 메시지 문서 ID → 만든 시각(ms). 형식이 다르면 0 */
    fun chatMillisFromId(id: String): Long {
        val core = id.removePrefix("b").substringBefore('-')
        val flipped = core.toLongOrNull() ?: return 0L
        return Long.MAX_VALUE - flipped
    }

    /** 문서 ID 뒤에 붙는 짧은 무작위 꼬리 (같은 밀리초에 두 사람이 보내도 겹치지 않게) */
    fun chatSuffix(random: SecureRandom = SecureRandom(), length: Int = 6): String {
        val builder = StringBuilder(length)
        repeat(length) { builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        return builder.toString()
    }

    /**
     * 받아 온 메시지를 지금 목록에 합친다.
     * 같은 ID 는 하나만 남기고(서버 값 우선), 오래된 것부터 정렬해 최근 [limit] 건만 둔다.
     */
    fun mergeChat(existing: List<ChatMessage>, incoming: List<ChatMessage>, limit: Int = CHAT_PAGE): List<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        for (message in existing + incoming) {
            if (!message.valid) continue
            val previous = byId[message.id]
            // 서버에서 온 값이 있으면 서버 값을 쓰되 '내 것' 표시는 유지한다
            byId[message.id] = if (previous == null) message else message.copy(mine = message.mine || previous.mine)
        }
        return byId.values
            .sortedWith(compareBy({ it.createdAtMs }, { it.id }))
            .takeLast(limit)
    }

    /** 아직 읽지 않은 남의 메시지 수 — 마지막으로 읽은 시각 이후, 내가 보낸 것은 빼고 */
    fun unreadCount(messages: List<ChatMessage>, lastReadMs: Long, myId: String): Int =
        messages.count { it.createdAtMs > lastReadMs && it.senderId != myId }

    /** 말풍선 옆에 붙는 시각 (24시간제, 기기 언어와 무관하게 숫자만) */
    fun chatClock(createdAtMs: Long): String {
        if (createdAtMs <= 0L) return ""
        val format = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }
        return format.format(Date(createdAtMs))
    }

    /** 날짜 구분선 문구 — 오늘/어제/그 외 (yyyy.MM.dd) */
    fun chatDayLabel(dayMs: Long, nowMs: Long): String {
        val zone = TimeZone.getDefault()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(Date(dayMs))
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(Date(nowMs))
        val yesterday = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(Date(nowMs - 86_400_000L))
        return when (day) {
            today -> "TODAY"
            yesterday -> "YESTERDAY"
            else -> SimpleDateFormat("yyyy.MM.dd", Locale.US).apply { timeZone = zone }.format(Date(dayMs))
        }
    }

    // --------------------------------------------------------------- 방장·내보내기 (v1.0.7)

    /** 한 방에서 내보낸 기기를 최대 몇 개까지 기억하는가 (보안 규칙의 kickedIds 한도와 같은 값) */
    const val KICK_LIMIT = 20

    /**
     * 이 방의 방장 기기 ID.
     *  - 새 방: `rooms/{code}` 의 `hostDeviceId`
     *  - 옛 방(v1.0.6 까지 만든 방 — 필드가 없음): 그 방을 만든 기기만 방장으로 본다(`createdHere`).
     *    방장이 아닌 사람에게는 "" 가 되므로, 방장을 모를 때 UI 는 아무에게도 내보내기 버튼을 보여 주지 않는다.
     */
    fun resolveHost(hostDeviceId: String?, myDeviceId: String, createdHere: Boolean): String {
        val saved = hostDeviceId?.trim().orEmpty()
        if (saved.isNotEmpty()) return saved
        return if (createdHere) myDeviceId else ""
    }

    /** 지금 이 기기가 방장인가 */
    fun isHost(hostDeviceId: String?, myDeviceId: String, createdHere: Boolean): Boolean {
        val host = resolveHost(hostDeviceId, myDeviceId, createdHere)
        return host.isNotEmpty() && host == myDeviceId
    }

    /**
     * 내보낸 기기 목록에 대상을 더한다.
     * 이미 있으면 뒤로 옮기고, [KICK_LIMIT] 개를 넘으면 오래된 것부터 버린다.
     * (덮어쓰지 않고 **더하는** 함수 — 방장 폰 두 대가 동시에 내보내도 한 명이 풀리지 않게)
     */
    fun withKicked(existing: List<String>, target: String, limit: Int = KICK_LIMIT): List<String> {
        val id = target.trim()
        if (id.isEmpty()) return existing
        val out = existing.filter { it.isNotBlank() && it != id }.toMutableList()
        out.add(id)
        return if (out.size > limit) out.takeLast(limit) else out
    }

    /** 이 기기가 방장에 의해 내보내졌는가 */
    fun kickedOut(kicked: List<String>, deviceId: String): Boolean =
        deviceId.isNotBlank() && kicked.any { it == deviceId }

    // ------------------------------------------------------- 위치 수신 (v1.0.10)

    /**
     * 연속 위치 추적이 살아 있는지 보는 기준 (v1.0.10).
     *
     * v1.0.9 까지는 위치 요청을 한 번 걸어 두고 그 뒤로는 아무것도 확인하지 않았다.
     * GPS 칩이 측정을 멈추거나(터널·지하·실내), 등록이 조용히 실패하면 **다시 요청할 길이 없어**
     * 파란 점과 상대방 핀이 마지막 좌표에 그대로 멈췄다(2026-11-27 실기기: "안 움직여").
     * 이제는 마지막 측정이 [FIX_ALIVE_MS] 보다 오래되면 [FIX_RETRY_MS] 간격으로 한 번씩 다시 받는다.
     */
    const val FIX_ALIVE_MS = 20_000L

    /** 살아 있는 측정이 없을 때 다시 요청하는 간격 */
    const val FIX_RETRY_MS = 15_000L

    /** 이보다 부정확한 측정은 '대략 위치' — 더 좋은 값이 오면 그쪽을 쓴다.
     * (v1.0.10 은 500m 였지만, 기지국 값이 840m 튀는 것을 막기 위해 v1.0.11 에서 아래 `FIX_COARSE_ACCURACY_M`(150m)로 내렸다) */
    const val FIX_COARSE_LEGACY_ACCURACY_M = 500.0

    /** [at] 에 받은 측정이 아직 '살아 있는가' (기기 시계가 뒤로 간 경우도 살아 있는 것으로 본다) */
    fun fixFresh(at: Long, now: Long, limitMs: Long = FIX_ALIVE_MS): Boolean =
        at > 0L && (now < at || now - at <= limitMs)

    /**
     * 지금 한 번 강제로 위치를 다시 받아야 하는가.
     *  - 살아 있는 측정이 없고
     *  - 마지막 시도([lastAttemptAt])에서 [retryMs] 가 지났거나 아직 시도한 적이 없을 때
     */
    fun fixRetryDue(
        lastAcceptedAt: Long,
        lastAttemptAt: Long,
        now: Long,
        staleMs: Long = FIX_ALIVE_MS,
        retryMs: Long = FIX_RETRY_MS
    ): Boolean {
        if (fixFresh(lastAcceptedAt, now, staleMs)) return false
        if (lastAttemptAt <= 0L) return true
        if (now < lastAttemptAt) return true
        return now - lastAttemptAt >= retryMs
    }

    /**
     * 새 측정값을 채택할까 — 시각이 더 최신이면 채택, 같은 시각이면 정확도가 좋은 쪽.
     * (GPS·네트워크·융합 제공자가 함께 들어오므로 늦게 도착한 옛 값이 핀을 뒤로 되돌리지 않게 한다)
     */
    fun betterFix(oldAt: Long, oldAccuracy: Double, newAt: Long, newAccuracy: Double): Boolean {
        if (oldAt <= 0L) return true
        if (newAt > oldAt + 1_000L) return true
        if (newAt < oldAt - 1_000L) return false
        val oldAcc = if (oldAccuracy > 0.0) oldAccuracy else Double.MAX_VALUE
        val newAcc = if (newAccuracy > 0.0) newAccuracy else Double.MAX_VALUE
        return newAcc < oldAcc
    }

    // ------------------------------------------- 정밀 융합 위치 · 기지국 튐 방지 (v1.0.11)

    /**
     * 2026-11-28 실서버 `rooms/AFZZE7` 확인: 두 기기가 같은 자리에 있는데 `홍` 이 약 840m 옆에 떴다.
     * 카카오맵은 붙어 나오는데 PinLink 만 어긋난 이유는, 앱이 Android 기본 LocationManager 만 써서
     * GPS 가 약해진 순간 통신사 기지국 삼각측량(`NETWORK_PROVIDER`, 오차 수백m~1km)을 그대로 쓴 탓이다.
     * 카카오맵은 Google Play Services 융합 위치(Wi-Fi + GPS + 기지국)를 쓰므로 실내·차 안에서도 오차가 작다.
     *
     * 아래 값들은 그 원인을 두 겹으로 막는다.
     *  1) [FusedSource] — Play Services 융합 위치를 1순위로 받는다(정확도가 좋다)
     *  2) [fixVerdict] — 그래도 들어온 기지국 대략 위치가 정밀 위치를 덮어쓰지 못하게 거른다
     */

    /** 이 정도로 정확하면 '정밀 위치'로 본다 (m) — GPS·융합이 보통 5~30m 로 준다 */
    const val FIX_PRECISE_ACCURACY_M = 50.0

    /** 이보다 부정확한 측정은 '기지국 대략 위치'로 본다 (m) — 수백m~1km 어긋난다 */
    const val FIX_COARSE_ACCURACY_M = 150.0

    /** 정밀 위치를 '방금 잡힌 것'으로 보는 시간 (2분) — 이 안에서는 기지국 값으로 덮지 않는다 */
    const val FIX_PRECISE_WINDOW_MS = 2 * 60_000L

    /** 정확도를 알고 그 값이 [FIX_PRECISE_ACCURACY_M] 이하인가 (융합·GPS) */
    fun isPreciseFix(accuracyMeters: Double): Boolean =
        accuracyMeters.isFinite() && accuracyMeters > 0.0 && accuracyMeters <= FIX_PRECISE_ACCURACY_M

    /**
     * '기지국 대략 위치'인가 — 정확도를 모르거나(0) [FIX_COARSE_ACCURACY_M] 를 넘을 때.
     * 정확도를 모르는 측정은 믿지 않는다(지도에 840m 튀는 값의 정체가 대개 이것이다).
     */
    fun isCoarseFix(accuracyMeters: Double): Boolean =
        !accuracyMeters.isFinite() || accuracyMeters <= 0.0 || accuracyMeters > FIX_COARSE_ACCURACY_M

    /** [at] 에 정밀 위치를 잡았고 아직 [windowMs] 안인가 (기기 시계가 뒤로 가도 '최근'으로 본다) */
    fun preciseFixRecent(lastPreciseAt: Long, now: Long, windowMs: Long = FIX_PRECISE_WINDOW_MS): Boolean {
        if (lastPreciseAt <= 0L) return false
        if (now < lastPreciseAt) return true
        return now - lastPreciseAt <= windowMs
    }

    /** 측정값 처리 결과 (v1.0.11) */
    enum class FixVerdict {
        /** 그대로 쓴다 */
        ACCEPT,

        /** 위치가 아직 하나도 없어서 대략 위치라도 첫 핀으로 쓴다 (SyncLog 에 정확도를 남긴다) */
        ACCEPT_COARSE_FIRST,

        /** 최근 2분 안에 정밀 위치가 있었으므로 기지국 대략 위치를 무시한다 */
        DROP_COARSE,

        /** 늦게 도착한 옛 측정이라 버린다 */
        DROP_STALE
    }

    /**
     * 들어온 측정값을 쓸지 정한다 (v1.0.11).
     *
     * 순서
     *  1. 기지국 대략 위치(>150m 또는 정확도 모름)일 때
     *     - 위치가 아직 하나도 없으면 첫 핀으로 받아들인다([FixVerdict.ACCEPT_COARSE_FIRST])
     *     - 최근 [FIX_PRECISE_WINDOW_MS] 안에 정밀 위치(≤50m)가 있었으면 **버린다**([FixVerdict.DROP_COARSE])
     *     - 지금 값이 이미 기지국보다 나은 값(≤150m)이고 2분 안이면 그것도 지킨다
     *       (Wi-Fi 융합 위치가 ±60m 로 오는 기기에서 기지국 ±800m 와 번갈아 튀지 않게)
     *     - 그보다 오래됐으면(2분 넘게 좋은 위치가 없었다) 받아들인다 — 핀이 영영 멈추지 않게
     *  2. 그 밖에는 [betterFix] 규칙(더 새 값 채택, 같은 시각이면 정확도가 좋은 쪽)
     *     - 버려야 하는데 지금 값이 아직 살아 있으면 옛 측정으로 보고 버린다([FixVerdict.DROP_STALE])
     */
    fun fixVerdict(
        hasFix: Boolean,
        previousAt: Long,
        previousAccuracy: Double,
        lastPreciseAt: Long,
        newAt: Long,
        newAccuracy: Double,
        now: Long
    ): FixVerdict {
        if (isCoarseFix(newAccuracy)) {
            if (!hasFix) return FixVerdict.ACCEPT_COARSE_FIRST
            val recentGood = !isCoarseFix(previousAccuracy) && fixFresh(previousAt, now, FIX_PRECISE_WINDOW_MS)
            if (recentGood || preciseFixRecent(lastPreciseAt, now)) return FixVerdict.DROP_COARSE
        }
        if (!betterFix(previousAt, previousAccuracy, newAt, newAccuracy) && fixFresh(previousAt, now)) {
            return FixVerdict.DROP_STALE
        }
        return FixVerdict.ACCEPT
    }

    /** 진단에 쓰는 정확도 표기 — `±12m`, 모르면 `±-` */
    fun accuracyLabel(accuracyMeters: Double): String =
        if (accuracyMeters.isFinite() && accuracyMeters > 0.0) "±${accuracyMeters.roundToInt()}m" else "±-"

    /**
     * 마지막으로 알던 위치 후보 중 하나를 고른다 — 정밀한 것 우선, 그 안에서 가장 새것.
     * 정밀한 후보가 없으면 가장 새 후보를 쓴다(첫 핀이라도 보여 준다).
     * (v1.0.11: 제공자별 마지막 위치 중 '가장 새것'만 고르면 기지국 값이 GPS 값을 이겨 840m 튀었다)
     */
    fun pickLastKnown(candidates: List<Fix>): Fix? {
        val usable = candidates.filter { it.valid && it.at > 0L }
        if (usable.isEmpty()) return null
        val precise = usable.filter { isPreciseFix(it.accuracyMeters) }
        return (if (precise.isNotEmpty()) precise else usable).maxByOrNull { it.at }
    }

    // ------------------------------------------------------------ 주기 (v1.0.9)

    /**
     * 폴링 주기 묶음 (v1.0.9).
     *
     * v1.0.7 까지는 틱이 5초 고정이라 채팅(3초)·멤버(8초)·방 문서(24초)가 모두 5초의 배수로만 돌았고,
     * 약속 장소는 최대 30초에 한 번만 갱신됐다(사용자 제보: "글을 쓰거나 위치 지정 같은 거 하면 딜레이가 너무 돼").
     * v1.0.8 은 그래서 1초 틱·채팅 1.5초·멤버 3초로 당겼지만, 여러 대를 켜 두자 Firebase 무료 한도
     * (Spark: 하루 읽기 5만·쓰기 2만)가 하루 만에 찼다(`429 RESOURCE_EXHAUSTED`).
     * v1.0.9 는 화면을 보고 있을 때의 반응 속도는 그대로 두고, **가만히 있을 때와 화면이 꺼졌을 때**
     * 나가는 요청을 크게 줄인다 — 위치 올리기는 이동·경과 조건을 만족할 때만, 화면이 꺼지면 읽기는 분 단위로.
     */
    data class PollTiming(
        /** 타이머가 도는 간격 — 각 폴링은 자기 주기를 넘겼을 때만 나간다 */
        val tickMs: Long,
        /** 채팅 확인 주기 (대화를 보고 있을 때 / 안 보고 있을 때) */
        val chatMs: Long,
        /** 멤버 위치 받기 주기 */
        val memberMs: Long,
        /** 방 문서(약속 장소·방장 내보내기) 확인 주기 */
        val roomMs: Long,
        /** 위치를 숨겨 둔 동안 방 문서를 확인하는 주기 */
        val roomIdleMs: Long,
        /** 내 위치 올리기 — 좌표가 같아도 이만큼 지나면 한 번은 올린다(심장 박동) */
        val uploadMs: Long,
        /** 내 위치 올리기 — 이만큼 움직였으면 주기를 기다리지 않고 올린다 */
        val uploadMeters: Double
    )

    /** 화면이 켜져 있을 때의 틱 — 1초마다 깨어나 각 주기를 지킨다 */
    const val TICK_ACTIVE_MS = 1_000L

    /** 화면이 꺼져 있을 때의 틱 (v1.0.9: 5초 → 10초) — 깨어나는 횟수부터 줄인다 */
    const val TICK_IDLE_MS = 10_000L

    /** 채팅을 보고 있을 때 메시지 확인 주기 (2.5초, v1.0.8 은 1.5초) */
    const val CHAT_POLL_ACTIVE_MS = 2_500L

    /** 화면은 켜져 있지만 채팅은 접어 둔 상태 — 읽지 않음 배지만 갱신 (8초, v1.0.8 은 5초) */
    const val CHAT_POLL_FOREGROUND_MS = 8_000L

    /** 화면이 꺼져 있을 때 채팅 확인 주기 (60초 — 지도를 보고 있지 않다, v1.0.8 은 20초) */
    const val CHAT_POLL_IDLE_MS = 60_000L

    /** 화면이 켜져 있을 때 멤버 위치·방 문서 확인 주기 (6초, v1.0.8 은 3초) */
    const val POLL_ACTIVE_MS = 6_000L

    /** 화면이 꺼져 있을 때 멤버 위치 확인 주기 (60초, v1.0.8 은 8초) */
    const val POLL_IDLE_MS = 60_000L

    /** 화면이 꺼져 있을 때 방 문서(종료·내보내기) 확인 주기 (30초, v1.0.8 은 24초) */
    const val ROOM_POLL_IDLE_MS = 30_000L

    /** 화면이 꺼져 있고 위치를 숨겨 둔 동안에도 방장이 나를 내보냈는지 확인하는 주기 (30초) */
    const val ROOM_IDLE_CHECK_MS = 30_000L

    /** 화면이 켜져 있을 때 위치 올리기 — 5m 이상 움직였거나 30초가 지났을 때 */
    const val UPLOAD_MOVE_ACTIVE_M = 5.0
    const val UPLOAD_GAP_ACTIVE_MS = 30_000L

    /** 화면이 꺼져 있을 때 위치 올리기 — 15m 이상 움직였거나 45초가 지났을 때 */
    const val UPLOAD_MOVE_IDLE_M = 15.0
    const val UPLOAD_GAP_IDLE_MS = 45_000L

    private val TIMING_ACTIVE = PollTiming(
        tickMs = TICK_ACTIVE_MS,
        chatMs = CHAT_POLL_ACTIVE_MS,
        memberMs = POLL_ACTIVE_MS,
        roomMs = POLL_ACTIVE_MS,
        // 위치를 숨겨 둔 채로 지도를 보고 있어도 약속 장소는 바로 보여야 한다
        roomIdleMs = POLL_ACTIVE_MS,
        uploadMs = UPLOAD_GAP_ACTIVE_MS,
        uploadMeters = UPLOAD_MOVE_ACTIVE_M
    )

    /** 화면은 켜져 있지만 채팅은 접어 둔 상태 */
    private val TIMING_ACTIVE_NO_CHAT = TIMING_ACTIVE.copy(chatMs = CHAT_POLL_FOREGROUND_MS)

    private val TIMING_IDLE = PollTiming(
        tickMs = TICK_IDLE_MS,
        chatMs = CHAT_POLL_IDLE_MS,
        memberMs = POLL_IDLE_MS,
        roomMs = ROOM_POLL_IDLE_MS,
        roomIdleMs = ROOM_IDLE_CHECK_MS,
        uploadMs = UPLOAD_GAP_IDLE_MS,
        uploadMeters = UPLOAD_MOVE_IDLE_M
    )

    /** 지금 상태의 폴링 주기 — 화면이 켜져 있을 때만 빠르게, 꺼져 있으면 배터리를 아낀다 */
    fun timing(uiVisible: Boolean, chatVisible: Boolean): PollTiming = when {
        !uiVisible -> TIMING_IDLE
        chatVisible -> TIMING_ACTIVE
        else -> TIMING_ACTIVE_NO_CHAT
    }

    /**
     * 마지막으로 한 시각([lastAt])에서 [everyMs] 가 지났는가.
     * 0(아직 한 적 없음)이거나 기기 시계가 뒤로 갔으면 바로 돈다 — 화면으로 돌아왔을 때 한 번은 반드시 확인한다.
     */
    fun isDue(lastAt: Long, now: Long, everyMs: Long): Boolean =
        lastAt <= 0L || now < lastAt || now - lastAt >= everyMs

    /**
     * 이번 측정값을 서버에 올릴까 (v1.0.9).
     *
     * v1.0.8 은 화면이 켜져 있는 동안 3초마다(화면 꺼짐 8초) 좌표를 그대로 덮어썼다 —
     * 가만히 서 있어도 같은 값을 계속 쓰는 바람에 무료 쓰기 한도(하루 2만)가 금방 찼다.
     * 이제는 다음일 때만 올린다.
     *  - 방에 처음 들어왔거나 아직 한 번도 못 올렸을 때 (첫 핀이 바로 보여야 한다)
     *  - 마지막으로 올린 좌표에서 [uploadMeters] 이상 움직였을 때 (지도에서 핀이 따라온다)
     *  - 마지막 업로드에서 [maxGapMs] 이상 지났을 때 (심장 박동 — 멈춘 핀으로 보이지 않게)
     *  - 기기 시계가 뒤로 갔을 때 (한 번은 올려 본다)
     * 수동 즉시 전송(공유 시작·숨김·방 입장)은 부르는 쪽이 force 로 건너뛴다.
     */
    fun shouldUpload(
        lastUploadAt: Long,
        lastLat: Double,
        lastLng: Double,
        uploadedBefore: Boolean,
        fix: Fix?,
        now: Long,
        uploadMeters: Double,
        maxGapMs: Long
    ): Boolean {
        if (fix == null || !fix.valid) return false
        if (!uploadedBefore || lastUploadAt <= 0L) return true
        if (now < lastUploadAt) return true
        val moved = distanceMeters(lastLat, lastLng, fix.lat, fix.lng)
        if (moved >= uploadMeters) return true
        return now - lastUploadAt >= maxGapMs
    }

    // ------------------------------------------------------- 서버 한도 (v1.0.9)

    /** 429 를 처음 받았을 때 쉬는 시간 (30초) */
    const val QUOTA_COOLDOWN_MS = 30_000L

    /** 10분 안에 429 가 또 오면 더 길게 쉰다 (60초) — 한도가 바닥난 상태에서 요청을 반복하지 않게 */
    const val QUOTA_COOLDOWN_LONG_MS = 60_000L

    /** '또 걸렸다'로 보는 시간 창 (10분) */
    const val QUOTA_REPEAT_WINDOW_MS = 10 * 60_000L

    /** 같은 안내("서버 일일 무료 사용량이 찼습니다")를 다시 띄우기까지 (10분) */
    const val QUOTA_NOTICE_GAP_MS = 10 * 60_000L

    /**
     * 429 를 받았을 때 몇 초 쉴지.
     * 처음에는 30초, [QUOTA_REPEAT_WINDOW_MS] 안에 또 걸리면 60초 — 즉시 재시도 루프를 만들지 않는다.
     */
    fun quotaCooldownMs(previousQuotaAt: Long, now: Long): Long {
        if (previousQuotaAt <= 0L) return QUOTA_COOLDOWN_MS
        val delta = now - previousQuotaAt
        return if (delta in 0 until QUOTA_REPEAT_WINDOW_MS) QUOTA_COOLDOWN_LONG_MS else QUOTA_COOLDOWN_MS
    }

    /** 지금 429 쿨다운 중인가 (그동안에는 서버 요청을 보내지 않는다) */
    fun inQuotaCooldown(lastQuotaAt: Long, cooldownMs: Long, now: Long): Boolean =
        lastQuotaAt > 0L && cooldownMs > 0L && now >= lastQuotaAt && now - lastQuotaAt < cooldownMs

    /** 429 쿨다운이 끝나기까지 남은 시간(ms) — 0 이면 쉬는 중이 아니다 */
    fun quotaRemainingMs(lastQuotaAt: Long, cooldownMs: Long, now: Long): Long =
        if (!inQuotaCooldown(lastQuotaAt, cooldownMs, now)) 0L
        else (lastQuotaAt + cooldownMs - now).coerceAtLeast(0L)

    /** 사용자에게 한도 안내를 (다시) 보여 줄 때인가 — 폴링마다 토스트가 뜨지 않게 */
    fun quotaNoticeDue(lastNoticeAt: Long, now: Long, gapMs: Long = QUOTA_NOTICE_GAP_MS): Boolean =
        lastNoticeAt <= 0L || now < lastNoticeAt || now - lastNoticeAt >= gapMs

    /**
     * 방 문서를 새로 받았을 때 지도에 보여 줄 약속 장소 (v1.0.8).
     *
     * 내가 약속 장소를 찍어 저장하는 동안(`saving`)에는 서버 문서가 아직 옛 값을 줄 수 있다.
     * 그때 옛 값으로 되돌리면 깃발이 한 번 깜빡이므로, 저장이 끝날 때까지 내가 고른 값(`pending`,
     * 지우기면 null)을 그대로 보여 준다.
     */
    fun meetingAfterRefresh(saving: Boolean, pending: MeetingPoint?, incoming: MeetingPoint?): MeetingPoint? =
        if (saving) pending else incoming
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

    /**
     * 채팅 메시지 문서 — **정확히 5개 필드**.
     * 게시된 규칙이 `request.resource.data.size() <= 5` 이고, 글은 1~500자,
     * createdAtMs 는 0 보다 큰 정수여야 한다(v1.0.6 실서버 확인).
     */
    fun chatFields(message: ChatMessage): FirestoreFields = mapOf(
        "senderId" to mapOf("stringValue" to message.senderId.take(64)),
        "senderName" to mapOf("stringValue" to message.senderName.take(40)),
        "senderColor" to mapOf("stringValue" to message.senderColor.take(9)),
        "text" to mapOf("stringValue" to message.text.take(PinLogic.CHAT_MAX_LENGTH)),
        "createdAtMs" to mapOf("integerValue" to message.createdAtMs.toString())
    )

    fun chatFieldPaths(): List<String> = listOf("senderId", "senderName", "senderColor", "text", "createdAtMs")

    /** 문서 → 메시지. 형식이 어긋난 문서(옛 실험 문서 등)는 null 로 걸러 낸다 */
    fun chatFrom(fields: FirestoreFields, id: String, myId: String = ""): ChatMessage? {
        val text = str(fields, "text") ?: return null
        val createdAt = long(fields, "createdAtMs").takeIf { it > 0L }
            ?: timestamp(fields, "createdAt")   // 옛 문서 호환
        if (createdAt <= 0L) return null
        val senderId = str(fields, "senderId").orEmpty()
        return ChatMessage(
            id = id,
            senderId = senderId,
            senderName = str(fields, "senderName") ?: "Guest",
            senderColor = str(fields, "senderColor") ?: "#E5C158",
            text = text,
            createdAtMs = createdAt,
            mine = senderId.isNotEmpty() && senderId == myId
        )
    }

    /** integerValue 는 문자열로 온다 — Long 으로 읽는다 */
    fun long(fields: FirestoreFields, key: String): Long {
        val value = fields[key] ?: return 0L
        (value["integerValue"] as? String)?.toLongOrNull()?.let { return it }
        (value["integerValue"] as? Number)?.let { return it.toLong() }
        (value["doubleValue"] as? Double)?.let { return it.toLong() }
        return 0L
    }

    fun roomFields(code: String, owner: String, createdAt: Long, meeting: MeetingPoint?, hostDeviceId: String = ""): FirestoreFields {
        val base = mutableMapOf(
            "code" to mapOf<String, Any>("stringValue" to code),
            "owner" to mapOf<String, Any>("stringValue" to owner),
            "createdAt" to mapOf<String, Any>("timestampValue" to isoUtc(createdAt)),
            "active" to mapOf<String, Any>("booleanValue" to true)
        )
        // v1.0.7 방장 — 방을 만든 기기의 ID. 옛 방처럼 모를 때는 필드를 넣지 않는다(레거시 호환)
        if (hostDeviceId.isNotBlank()) {
            base["hostDeviceId"] = mapOf("stringValue" to hostDeviceId.take(64))
        }
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

    /** 방장 등록(옛 방의 hostDeviceId 채우기)에 쓰는 마스크 — 다른 필드는 건드리지 않는다 */
    fun hostMaskQuery(): String = "updateMask.fieldPaths=hostDeviceId"

    /** 내보낸 목록만 바꾸는 마스크 */
    fun kickMaskQuery(): String = "updateMask.fieldPaths=kickedIds"

    /** 문자열 배열 필드 — Firestore 모양 `{"arrayValue":{"values":[{"stringValue":…}]}}` */
    fun stringArray(values: List<String>): Map<String, Any> = mapOf("arrayValue" to values)

    /** `kickedIds` 같은 문자열 배열 필드 읽기 */
    fun strList(fields: FirestoreFields, key: String): List<String> {
        val raw = fields[key]?.get("arrayValue") ?: return emptyList()
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? String)?.takeIf { id -> id.isNotBlank() } }
    }
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
        "arrayValue" -> arrayJson(raw)
        else -> jsonString(raw.toString())
    }

    /** 문자열 배열 → `{"values":[{"stringValue":"a"},…]}` */
    private fun arrayJson(raw: Any): String {
        val values = raw as? List<*> ?: return "{\"values\":[]}"
        if (values.isEmpty()) return "{\"values\":[]}"
        return values.joinToString(",", prefix = "{\"values\":[", postfix = "]}") {
            "{\"stringValue\":" + jsonString(it?.toString().orEmpty()) + "}"
        }
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

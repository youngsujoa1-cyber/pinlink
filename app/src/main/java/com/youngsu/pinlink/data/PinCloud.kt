package com.youngsu.pinlink.data

import android.os.Handler
import android.os.Looper
import com.youngsu.pinlink.BuildConfig
import com.youngsu.pinlink.core.FirestoreCodec
import com.youngsu.pinlink.core.Fix
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinLogic
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 방 하나의 정보 — 방장·만든 시각·약속 장소 */
data class RoomInfo(
    val code: String,
    val owner: String,
    val createdAt: Long,
    val meeting: MeetingPoint?
)

/** 방을 만들거나 되살릴 때 필요한 최소 정보 */
data class RoomSeed(val code: String, val owner: String, val createdAt: Long)

/**
 * 실패한 이유. v1.0.4 까지는 모든 실패를 `false` 하나로 뭉개서 화면에
 * "서버에 연결하지 못했어요"만 떴다 — 5G 가 멀쩡해도 권한(403) 문제를 네트워크 문제로 보여 주는 바람에
 * 원인을 찾을 수 없었다. 이제는 이유를 나눠서 화면·설정의 진단에 그대로 남긴다.
 */
enum class SyncFailure {
    /** 기기·인터넷·DNS·시간 초과 — 서버에 닿지 못함 */
    OFFLINE,

    /** 서버가 거부함 (403) — 보안 규칙·잘못된 문서 모양 */
    DENIED,

    /** 문서가 없음 (404) */
    MISSING,

    /** 요청 모양이 틀림 (400·409) */
    BAD_REQUEST,

    /** 서버·할당량 문제 (429·5xx) */
    SERVER,

    UNKNOWN
}

data class SyncFault(
    val failure: SyncFailure,
    val httpCode: Int = 0,
    val reason: String = ""
) {
    /** 로그·진단에 쓰는 한 줄 (예: `DENIED 403 PERMISSION_DENIED: Missing or insufficient permissions.`) */
    val logText: String get() = "$failure $httpCode $reason".trim()

    /** 화면에 덧붙이는 짧은 사유 (예: `권한 거부(403)`) */
    fun shortText(): String = when (failure) {
        SyncFailure.OFFLINE -> if (reason.isNotBlank()) "연결 실패: $reason" else "연결 실패"
        SyncFailure.DENIED -> "권한 거부(403)"
        SyncFailure.MISSING -> "문서 없음(404)"
        SyncFailure.BAD_REQUEST -> "요청 오류($httpCode)"
        SyncFailure.SERVER -> "서버 오류($httpCode)"
        SyncFailure.UNKNOWN -> "알 수 없는 오류($httpCode)"
    }

    companion object {
        fun offline(reason: String) = SyncFault(SyncFailure.OFFLINE, 0, reason)
    }
}

/** 방 조회 결과 — '없음'과 '못 물어봄'을 구분한다 */
sealed class RoomLookup {
    data class Found(val room: RoomInfo) : RoomLookup()
    object Missing : RoomLookup()
    data class Failed(val fault: SyncFault) : RoomLookup()
}

/**
 * 방·멤버 위치를 주고받는 통로.
 * 실제 서버(Firestore REST)와 데모(친구 2명 시뮬레이션)가 같은 인터페이스를 쓴다.
 */
interface SyncEngine {

    val demo: Boolean

    fun createRoom(code: String, ownerName: String, createdAt: Long, onDone: (SyncFault?) -> Unit)

    /** 없으면 Missing, 못 물어보면 Failed */
    fun fetchRoom(code: String, onDone: (RoomLookup) -> Unit)

    /** 방이 서버에 없으면 만든다(있으면 그대로 둔다). 약속 장소·멤버를 쓰기 전에 늘 부른다 */
    fun ensureRoom(seed: RoomSeed, onDone: (SyncFault?) -> Unit)

    fun upload(code: String, member: Member, onDone: (SyncFault?) -> Unit)

    /** 실패하면 list 가 null 이고 fault 가 채워진다 */
    fun members(code: String, onDone: (List<Member>?, SyncFault?) -> Unit)

    fun setMeeting(seed: RoomSeed, meeting: MeetingPoint?, onDone: (SyncFault?) -> Unit)

    fun leave(code: String, deviceId: String)
}

/**
 * Firestore REST(외부 SDK 없음 — HttpURLConnection).
 *
 * 문서 모양: `rooms/{code}` + `rooms/{code}/members/{deviceId}`
 * 키는 local.properties 의 FIREBASE_API_KEY → BuildConfig 로만 들어온다.
 * 보안 규칙이 rooms 쓰기를 허용해야 실제 동기화가 된다(README 참고).
 */
class CloudEngine(private val projectId: String, private val apiKey: String) : SyncEngine {

    override val demo: Boolean = false

    private val base = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)

    private fun roomUrl(code: String) = "$base/rooms/$code"
    private fun membersUrl(code: String) = "$base/rooms/$code/members"

    override fun createRoom(code: String, ownerName: String, createdAt: Long, onDone: (SyncFault?) -> Unit) {
        val body = FirestoreCodec.documentJson(FirestoreCodec.roomFields(code, ownerName, createdAt, null))
        send("PATCH rooms/$code", "${roomUrl(code)}?key=$apiKey", "PATCH", body) { onDone(it) }
    }

    override fun fetchRoom(code: String, onDone: (RoomLookup) -> Unit) {
        read("GET rooms/$code", "${roomUrl(code)}?key=$apiKey") { reply ->
            if (reply == null) {
                onDone(RoomLookup.Failed(SyncFault.offline("응답 없음")))
                return@read
            }
            when {
                reply.ok -> onDone(RoomLookup.Found(roomOf(code, reply.body)))
                reply.code == 404 -> onDone(RoomLookup.Missing)
                else -> onDone(RoomLookup.Failed(faultOf(reply) ?: SyncFault(SyncFailure.UNKNOWN, reply.code)))
            }
        }
    }

    /**
     * 방 문서가 사라졌을 때 스스로 되살린다.
     *
     * 실기기에서 나온 버그: 방 문서가 서버에 없는데 앱은 prefs 의 코드를 그대로 띄웠고,
     * 그 상태에서 약속 장소를 찍으면 `PATCH rooms/{code}?updateMask=...` 가 **문서 만들기**로 처리되면서
     * 보안 규칙(`request.resource.data.code == code`)에 걸려 403 → 화면에는 "서버에 연결하지 못했어요"가 떴다.
     * 그래서 방을 쓰기 전에 늘 `code`·`owner`·`createdAt` 이 들어간 온전한 문서를 먼저 만든다.
     */
    override fun ensureRoom(seed: RoomSeed, onDone: (SyncFault?) -> Unit) {
        fetchRoom(seed.code) { lookup ->
            when (lookup) {
                is RoomLookup.Found -> onDone(null)
                is RoomLookup.Failed -> onDone(lookup.fault)
                is RoomLookup.Missing -> {
                    SyncLog.record("방 없음 → 다시 만듦", "rooms/${seed.code}")
                    createRoom(seed.code, seed.owner, seed.createdAt) { fault ->
                        if (fault == null) SyncLog.record("방 되살림 완료", "rooms/${seed.code}")
                        onDone(fault)
                    }
                }
            }
        }
    }

    override fun upload(code: String, member: Member, onDone: (SyncFault?) -> Unit) {
        val body = FirestoreCodec.documentJson(FirestoreCodec.memberFields(member))
        // updateMask 없이 PATCH = 문서 전체 교체 → 필드가 정확히 8개로 유지된다(보안 규칙의 size() <= 8)
        send("PATCH member", "${membersUrl(code)}/${member.id}?key=$apiKey", "PATCH", body) { onDone(it) }
    }

    override fun members(code: String, onDone: (List<Member>?, SyncFault?) -> Unit) {
        read("GET members", "${membersUrl(code)}?key=$apiKey&pageSize=50") { reply ->
            if (reply == null) {
                onDone(null, SyncFault.offline("응답 없음"))
                return@read
            }
            if (!reply.ok) {
                // 아직 아무도 안 올린 방은 하위 컬렉션이 404 다 — 오류가 아니라 '빈 목록'
                if (reply.code == 404) onDone(emptyList(), null) else onDone(null, faultOf(reply))
                return@read
            }
            val json = runCatching { JSONObject(reply.body) }.getOrNull()
            val documents = json?.optJSONArray("documents")
            val out = ArrayList<Member>()
            if (documents != null) {
                for (i in 0 until documents.length()) {
                    val doc = documents.optJSONObject(i) ?: continue
                    val id = doc.optString("name").substringAfterLast('/')
                    val updateTime = FirestoreCodec.parseIso(doc.optString("updateTime"))
                    val member = FirestoreCodec.memberFrom(docFields(doc), id, updateTime = updateTime) ?: continue
                    out.add(member)
                }
            }
            onDone(out, null)
        }
    }

    override fun setMeeting(seed: RoomSeed, meeting: MeetingPoint?, onDone: (SyncFault?) -> Unit) {
        ensureRoom(seed) { fault ->
            if (fault != null) {
                onDone(fault)
                return@ensureRoom
            }
            // 항상 updateMask 를 붙인다 — 없으면 Firestore 가 방 문서를 통째로 교체해 owner·createdAt 이 날아간다
            if (meeting != null) {
                val fields = FirestoreCodec.roomFields(seed.code, "", 0L, meeting)
                    .filterKeys { it.startsWith("meeting") }
                val body = FirestoreCodec.documentJson(fields)
                send("PATCH 약속", "${roomUrl(seed.code)}?key=$apiKey&${FirestoreCodec.meetingMaskQuery()}", "PATCH", body) { onDone(it) }
            } else {
                // updateMask 에만 있고 본문에 없는 필드는 삭제된다 — 약속 장소 지우기(나머지 필드는 그대로)
                send("PATCH 약속 지움", "${roomUrl(seed.code)}?key=$apiKey&${FirestoreCodec.meetingMaskQuery()}", "PATCH", "{\"fields\":{}}") { onDone(it) }
            }
        }
    }

    override fun leave(code: String, deviceId: String) {
        pool.execute {
            runCatching { call("DELETE member", "${membersUrl(code)}/$deviceId?key=$apiKey", "DELETE", null) }
        }
    }

    // ------------------------------------------------------------- HTTP / JSON

    private fun send(tag: String, url: String, method: String, body: String, onDone: (SyncFault?) -> Unit) {
        pool.execute {
            val reply = call(tag, url, method, body)
            val fault = if (reply == null) SyncFault.offline("연결 실패") else faultOf(reply)
            main.post { onDone(fault) }
        }
    }

    private fun read(tag: String, url: String, onDone: (Reply?) -> Unit) {
        pool.execute {
            val reply = call(tag, url, "GET", null)
            main.post { onDone(reply) }
        }
    }

    private data class Reply(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    /** 실제 요청 — 예외는 삼키지 않고 '왜 닿지 못했는지'로 바꿔서 남긴다 */
    private fun call(tag: String, url: String, method: String, body: String?): Reply? {
        val started = System.currentTimeMillis()
        val path = url.substringAfter("/documents/").substringBefore("?")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            val ms = System.currentTimeMillis() - started
            SyncLog.record(tag, "$path → $code (${ms}ms)${if (code in 200..299) "" else " " + errorLine(text)}")
            Reply(code, text)
        } catch (error: Throwable) {
            val reason = when (error) {
                is SocketTimeoutException -> "시간 초과"
                is UnknownHostException -> "주소를 찾을 수 없음"
                else -> error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")
            }
            SyncLog.record(tag, "$path → 실패 · $reason")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Firestore 오류 본문 → `PERMISSION_DENIED: Missing or insufficient permissions.` */
    private fun errorLine(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(120)
        val error = json.optJSONObject("error") ?: return body.take(120)
        val status = error.optString("status")
        val message = error.optString("message")
        return listOf(status, message).filter { it.isNotBlank() }.joinToString(": ").take(160)
    }

    private fun faultOf(reply: Reply): SyncFault? {
        if (reply.ok) return null
        val reason = errorLine(reply.body)
        val failure = when (reply.code) {
            403 -> SyncFailure.DENIED
            404 -> SyncFailure.MISSING
            400, 409, 412 -> SyncFailure.BAD_REQUEST
            429 -> SyncFailure.SERVER
            in 500..599 -> SyncFailure.SERVER
            else -> SyncFailure.UNKNOWN
        }
        return SyncFault(failure, reply.code, reason)
    }

    private fun roomOf(code: String, body: String): RoomInfo {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val fields = json?.let { docFields(it) } ?: emptyMap()
        return RoomInfo(
            code = code,
            owner = FirestoreCodec.str(fields, "owner").orEmpty(),
            createdAt = FirestoreCodec.parseIso(FirestoreCodec.str(fields, "createdAt")),
            meeting = FirestoreCodec.meetingFrom(fields)
        )
    }

    /** Firestore 응답 문서 → (필드 → {타입: 값}) */
    private fun docFields(doc: JSONObject): com.youngsu.pinlink.core.FirestoreFields {
        val fields = doc.optJSONObject("fields") ?: return emptyMap()
        val out = HashMap<String, Map<String, Any>>()
        val names = fields.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = fields.optJSONObject(name) ?: continue
            val type = value.keys().asSequence().firstOrNull() ?: continue
            val raw: Any = when (type) {
                "integerValue" -> value.optString(type)
                "booleanValue" -> value.optBoolean(type)
                "doubleValue" -> value.optDouble(type)
                else -> value.optString(type)
            }
            out[name] = mapOf(type to raw)
        }
        return out
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
    }
}

/**
 * 데모 모드 — 서버 키가 없는 빌드에서도 지도가 살아 있게 친구 2명을 움직인다.
 *
 * 내 위치(마지막 측정값)를 기준으로 반경 200~600m 안에서 천천히 도는 두 친구를 만든다.
 * 방·약속 장소는 앱이 켜져 있는 동안 메모리에만 남는다(앱을 끄면 처음부터).
 */
class DemoEngine : SyncEngine {

    override val demo: Boolean = true

    @Volatile var anchor: Fix? = null

    private val rooms = HashMap<String, RoomInfo>()

    override fun createRoom(code: String, ownerName: String, createdAt: Long, onDone: (SyncFault?) -> Unit) {
        rooms[code] = RoomInfo(code, ownerName, createdAt, rooms[code]?.meeting)
        onDone(null)
    }

    override fun fetchRoom(code: String, onDone: (RoomLookup) -> Unit) {
        val room = rooms[code]
        onDone(if (room == null) RoomLookup.Missing else RoomLookup.Found(room))
    }

    override fun ensureRoom(seed: RoomSeed, onDone: (SyncFault?) -> Unit) {
        if (rooms[seed.code] == null) {
            rooms[seed.code] = RoomInfo(seed.code, seed.owner, seed.createdAt, null)
        }
        onDone(null)
    }

    override fun upload(code: String, member: Member, onDone: (SyncFault?) -> Unit) {
        if (member.valid) anchor = Fix(member.lat, member.lng, at = member.updatedAt)
        onDone(null)
    }

    override fun members(code: String, onDone: (List<Member>?, SyncFault?) -> Unit) {
        val base = anchor ?: DEFAULT_ANCHOR
        val now = System.currentTimeMillis()
        val t = now / 1000.0
        val out = DEMO_FRIENDS.mapIndexed { index, friend ->
            val radius = 230.0 + index * 170.0
            val angle = t / (60.0 + index * 25.0) + index * PI
            val lat = base.lat + (radius * sin(angle)) / 111_320.0
            val lng = base.lng + (radius * cos(angle)) / (111_320.0 * cos(Math.toRadians(base.lat)))
            Member(
                id = friend.id,
                name = friend.name,
                color = friend.color,
                lat = lat,
                lng = lng,
                battery = friend.battery,
                moving = index == 0,
                updatedAt = now - (index * 20_000L),
                sharing = true,
                speedKmh = if (index == 0) 24.0 else 0.0
            )
        }
        onDone(out, null)
    }

    override fun setMeeting(seed: RoomSeed, meeting: MeetingPoint?, onDone: (SyncFault?) -> Unit) {
        rooms[seed.code] = rooms[seed.code]?.copy(meeting = meeting)
            ?: RoomInfo(seed.code, seed.owner, System.currentTimeMillis(), meeting)
        onDone(null)
    }

    override fun leave(code: String, deviceId: String) {
        rooms.remove(code)
    }

    private data class DemoFriend(val id: String, val name: String, val color: String, val battery: Int)

    companion object {
        /** 내 위치를 아직 못 받았을 때 — 서울시청 */
        val DEFAULT_ANCHOR = Fix(37.5665, 126.9780)

        private val DEMO_FRIENDS = listOf(
            DemoFriend("demo-mina", "Mina", "#FF7A59", 76),
            DemoFriend("demo-alex", "Alex", "#B776FF", 43)
        )
    }
}

/** 앱이 쓰는 통로 하나 — 키가 있으면 실서버, 없으면 데모 */
object Sync {
    val engine: SyncEngine by lazy {
        val key = BuildConfig.FIREBASE_API_KEY
        if (key.isBlank()) DemoEngine()
        else CloudEngine(BuildConfig.FIREBASE_PROJECT_ID, key)
    }

    val isDemo: Boolean get() = engine.demo || BuildConfig.FIREBASE_API_KEY.isBlank()

    val demoEngine: DemoEngine? get() = engine as? DemoEngine

    /** 서버에 붙어 있는가 (데모면 false) */
    val isLive: Boolean get() = !isDemo
}

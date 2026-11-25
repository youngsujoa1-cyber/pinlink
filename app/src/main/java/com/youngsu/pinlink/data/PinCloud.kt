package com.youngsu.pinlink.data

import android.os.Handler
import android.os.Looper
import com.youngsu.pinlink.BuildConfig
import com.youngsu.pinlink.core.FirestoreCodec
import com.youngsu.pinlink.core.FirestoreFields
import com.youngsu.pinlink.core.Fix
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
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

/**
 * 방·멤버 위치를 주고받는 통로.
 * 실제 서버(Firestore REST)와 데모(친구 2명 시뮬레이션)가 같은 인터페이스를 쓴다.
 */
interface SyncEngine {

    val demo: Boolean

    fun createRoom(code: String, ownerName: String, createdAt: Long, onDone: (Boolean) -> Unit)

    /** 없으면 null */
    fun fetchRoom(code: String, onDone: (RoomInfo?) -> Unit)

    fun upload(code: String, member: Member, onDone: (Boolean) -> Unit)

    /** 실패하면 null */
    fun members(code: String, onDone: (List<Member>?) -> Unit)

    fun setMeeting(code: String, meeting: MeetingPoint?, onDone: (Boolean) -> Unit)

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
    private val pool = Executors.newFixedThreadPool(2)

    private fun roomUrl(code: String) = "$base/rooms/$code"
    private fun membersUrl(code: String) = "$base/rooms/$code/members"

    override fun createRoom(code: String, ownerName: String, createdAt: Long, onDone: (Boolean) -> Unit) {
        val body = FirestoreCodec.documentJson(FirestoreCodec.roomFields(code, ownerName, createdAt, null))
        send("${roomUrl(code)}?key=$apiKey", "PATCH", body) { ok -> onDone(ok) }
    }

    override fun fetchRoom(code: String, onDone: (RoomInfo?) -> Unit) {
        read("${roomUrl(code)}?key=$apiKey") { json ->
            if (json == null) {
                onDone(null)
            } else {
                val fields = docFields(json)
                onDone(
                    RoomInfo(
                        code = code,
                        owner = FirestoreCodec.str(fields, "owner").orEmpty(),
                        createdAt = FirestoreCodec.parseIso(FirestoreCodec.str(fields, "createdAt")),
                        meeting = FirestoreCodec.meetingFrom(fields)
                    )
                )
            }
        }
    }

    override fun upload(code: String, member: Member, onDone: (Boolean) -> Unit) {
        val body = FirestoreCodec.documentJson(FirestoreCodec.memberFields(member))
        send("${membersUrl(code)}/${member.id}?key=$apiKey", "PATCH", body) { ok -> onDone(ok) }
    }

    override fun members(code: String, onDone: (List<Member>?) -> Unit) {
        read("${membersUrl(code)}?key=$apiKey&pageSize=50") { json ->
            if (json == null) {
                onDone(null)
                return@read
            }
            val documents = json.optJSONArray("documents")
            val out = ArrayList<Member>()
            if (documents != null) {
                for (i in 0 until documents.length()) {
                    val doc = documents.optJSONObject(i) ?: continue
                    val id = doc.optString("name").substringAfterLast('/')
                    val member = FirestoreCodec.memberFrom(docFields(doc), id) ?: continue
                    out.add(member)
                }
            }
            onDone(out)
        }
    }

    override fun setMeeting(code: String, meeting: MeetingPoint?, onDone: (Boolean) -> Unit) {
        // 항상 updateMask 를 붙인다 — 없으면 Firestore 가 방 문서를 통째로 교체해 owner·createdAt 이 날아간다
        if (meeting != null) {
            val fields = FirestoreCodec.roomFields(code, "", 0L, meeting)
                .filterKeys { it.startsWith("meeting") }
            val body = FirestoreCodec.documentJson(fields)
            send("${roomUrl(code)}?key=$apiKey&${FirestoreCodec.meetingMaskQuery()}", "PATCH", body) { onDone(it) }
        } else {
            // updateMask 에만 있고 본문에 없는 필드는 삭제된다 — 약속 장소 지우기(나머지 필드는 그대로)
            send("${roomUrl(code)}?key=$apiKey&${FirestoreCodec.meetingMaskQuery()}", "PATCH", "{\"fields\":{}}") { onDone(it) }
        }
    }

    override fun leave(code: String, deviceId: String) {
        pool.execute {
            runCatching { request("${membersUrl(code)}/$deviceId?key=$apiKey", "DELETE", null) }
        }
    }

    // ------------------------------------------------------------- HTTP / JSON

    private fun send(url: String, method: String, body: String, onDone: (Boolean) -> Unit) {
        pool.execute {
            val ok = runCatching { request(url, method, body).let { it in 200..299 } }.getOrDefault(false)
            main.post { onDone(ok) }
        }
    }

    private fun read(url: String, onDone: (JSONObject?) -> Unit) {
        pool.execute {
            val text = runCatching { requestText(url, "GET", null) }.getOrNull()
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            main.post { onDone(json) }
        }
    }

    private fun request(url: String, method: String, body: String?): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            runCatching {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.use { it.readBytes() }
            }
            return code
        } finally {
            conn.disconnect()
        }
    }

    private fun requestText(url: String, method: String, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Firestore 응답 문서 → (필드 → {타입: 값}) */
    private fun docFields(doc: JSONObject): FirestoreFields {
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
        private const val TIMEOUT_MS = 8_000
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

    override fun createRoom(code: String, ownerName: String, createdAt: Long, onDone: (Boolean) -> Unit) {
        rooms[code] = RoomInfo(code, ownerName, createdAt, rooms[code]?.meeting)
        onDone(true)
    }

    override fun fetchRoom(code: String, onDone: (RoomInfo?) -> Unit) {
        onDone(rooms[code])
    }

    override fun upload(code: String, member: Member, onDone: (Boolean) -> Unit) {
        if (member.valid) anchor = Fix(member.lat, member.lng, at = member.updatedAt)
        onDone(true)
    }

    override fun members(code: String, onDone: (List<Member>?) -> Unit) {
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
                sharing = true
            )
        }
        onDone(out)
    }

    override fun setMeeting(code: String, meeting: MeetingPoint?, onDone: (Boolean) -> Unit) {
        rooms[code] = rooms[code]?.copy(meeting = meeting) ?: RoomInfo(code, "", System.currentTimeMillis(), meeting)
        onDone(true)
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
}

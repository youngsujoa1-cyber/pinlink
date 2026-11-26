package com.youngsu.pinlink.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.youngsu.pinlink.core.ChatMessage
import com.youngsu.pinlink.core.Fix
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.location.ShareService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 앱의 상태 한 곳 — 내 프로필·방·공유 모드·내 위치·방 멤버·약속 장소.
 *
 * 화면(MainActivity)과 포그라운드 서비스(ShareService)가 같은 객체를 본다.
 *  - 서비스가 살아 있는 동안 또는 화면이 보이는 동안만 위치를 요청한다.
 *  - 공유 중이면 8초마다 내 위치를 올리고, 8초마다 방 멤버를 받아 온다.
 *  - 고른 시간이 끝나면 스스로 멈춘다(알림·핀도 함께 사라진다).
 */
object PinSession {

    private const val TICK_MS = 5_000L
    private const val UPLOAD_MS = 8_000L
    private const val POLL_MS = 8_000L
    private const val ROOM_POLL_EVERY = 3
    /** 채팅 창을 보고 있을 때 메시지 확인 주기 (v1.0.6) */
    private const val CHAT_POLL_MS = 3_000L
    /** 채팅 창을 닫아 두었을 때 — 읽지 않은 메시지 배지만 갱신 (내용은 새 메시지가 있을 때만 받아 온다) */
    private const val CHAT_IDLE_POLL_MS = 20_000L
    /** 위치를 숨겨 둔 동안에도 방장이 나를 내보냈는지 확인하는 주기 (v1.0.7) */
    private const val ROOM_IDLE_CHECK_MS = 20_000L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val prefs: AppPrefs get() = AppPrefs.get(app)
    private val engine: SyncEngine get() = Sync.engine

    @Volatile var roomCode: String = ""
        private set

    @Volatile var roomOwner: Boolean = false
        private set

    /** 방장 기기 ID — 서버 방 문서의 hostDeviceId (옛 방은 "", 방장을 모르면 "") */
    @Volatile var roomHostId: String = ""
        private set

    /** 방장이 내보낸 기기 ID 목록 — 내가 들어 있으면 즉시 공유를 멈추고 나간다 */
    @Volatile var kickedIds: List<String> = emptyList()
        private set

    /** 방장에 의해 내보내졌다는 안내를 한 번만 보여 주려고 남겨 두는 표시 */
    @Volatile private var kickedNotice = false

    /** 옛 방의 방장 등록을 이번 실행에서 이미 시도했는가 */
    private var hostClaimed = false

    @Volatile var members: List<Member> = emptyList()
        private set

    @Volatile var myFix: Fix? = null
        private set

    @Volatile var battery: Int = -1

    @Volatile var meeting: MeetingPoint? = null
        private set

    /** 방 채팅 — 오래된 것부터 정렬된 목록 */
    @Volatile var messages: List<ChatMessage> = emptyList()
        private set

    /** 읽지 않은 남의 메시지 수 (하단 탭·지도 버튼 배지) */
    @Volatile var chatUnread: Int = 0
        private set

    /** 채팅 창이 화면에 떠 있는가 — 떠 있을 때는 3초마다 확인하고 바로 읽음으로 친다 */
    @Volatile var chatOpen: Boolean = false
        private set

    /** 지도 아래 인라인 채팅창이 펼쳐져 있는가 (v1.0.7) — 이것도 '대화를 보고 있다'로 본다 */
    @Volatile var inlineChatOpen: Boolean = false
        private set

    @Volatile private var topChatId = ""
    @Volatile private var lastReadMs = 0L
    @Volatile private var lastChatPollAt = 0L

    /** 마지막 실패 — 화면·설정의 진단에 그대로 보여 준다 (성공하면 지워진다) */
    @Volatile var lastFault: SyncFault? = null
        private set

    /** 마지막 실패를 남긴 동작 (예: "약속", "멤버 올리기") */
    @Volatile var lastFaultAt: String = ""
        private set

    /** 서버에 없던 방을 이번 실행에서 되살렸는가 (한 번만 알려 주려고) */
    @Volatile var roomRepaired: Boolean = false
        private set

    /** 마지막 확인 결과 — 방 문서가 서버에 있는가 (null = 아직 모름) */
    @Volatile var roomOnServer: Boolean? = null
        private set

    /** 내 이동 속도(km/h) — 2km/h 미만은 0 */
    @Volatile var mySpeedKmh: Double = 0.0
        private set

    /** 내가 이 자리에 머물기 시작한 시각(ms) — 정지해 있을 때 지도에 '12분째'로 뜬다 */
    @Volatile var myStaySince: Long = 0L
        private set

    @Volatile var busy: Boolean = false
        private set

    @Volatile private var lastUploadAt = 0L
    @Volatile private var lastPollAt = 0L
    @Volatile private var lastRoomAt = 0L
    @Volatile private var pollCounter = 0
    @Volatile private var reportedHidden = false
    @Volatile private var serviceRunning = false
    @Volatile private var uiVisible = false
    @Volatile private var started = false

    private var locationManager: LocationManager? = null
    private var previousFix: Fix? = null
    private var stayAnchor: Fix? = null

    val demo: Boolean get() = Sync.isDemo

    // ------------------------------------------------------------------ 초기화

    fun init(context: Context) {
        // 화면(Activity)과 서비스가 같은 Application 을 넘긴다 — 진짜 앱에서는 한 번만 돈다.
        // (화면 검사 도구는 시험마다 새 Application 을 만들기 때문에 여기서 다시 읽어야 한다)
        val fresh = context.applicationContext
        if (started && app === fresh) return
        app = fresh
        started = true
        roomCode = prefs.roomCode
        roomOwner = prefs.roomOwner
        battery = readBattery()
        SyncLog.init(app)
        // 앱을 꺼다 켜도 '여기서 얼마나 있었는지'가 이어지도록 저장해 둔 기준을 읽는다
        myStaySince = prefs.staySince
        if (prefs.stayLat != 0.0 || prefs.stayLng != 0.0) {
            stayAnchor = Fix(prefs.stayLat, prefs.stayLng)
        }
        Sync.demoEngine?.anchor = DemoEngine.DEFAULT_ANCHOR
        main.post(ticker)
        // 앱을 다시 켰을 때: 공유 중이던 방이 아직 살아 있으면 서비스도 함께 되살린다
        main.post {
            if (roomCode.isEmpty()) return@post
            if (mode.isSharing) {
                if (PinLogic.isExpired(prefs.expiresAt, System.currentTimeMillis())) expireSharing()
                else startService()
            }
            // 방 문서가 서버에 그대로 있는지 먼저 확인한다.
            // (v1.0.4 는 이 확인이 없어서, 서버에서 사라진 방 코드를 그대로 띄운 채
            //  약속 장소를 찍을 때마다 403 → "서버에 연결하지 못했어요"가 떴다)
            verifyRoom(recreate = true)
            refreshMembers()
            refreshRoom()
        }
    }

    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        for (listener in listeners) runCatching { listener() }
    }

    // ---------------------------------------------------------------- 내 프로필

    val myName: String get() = prefs.nickname.ifBlank { "Guest" }
    val myColor: String get() = prefs.color

    fun saveProfile(name: String, color: String) {
        prefs.nickname = name
        prefs.color = color
        notifyChanged()
    }

    // ------------------------------------------------------------------ 공유 상태

    val mode: ShareMode get() = prefs.shareMode

    val startedAt: Long get() = prefs.startedAt

    val expiresAt: Long get() = prefs.expiresAt

    /** 지금 실제로 위치를 올리고 있는가 (모드가 공유이고 시간이 남았을 때) */
    fun isSharingNow(): Boolean =
        roomCode.isNotEmpty() && mode.isSharing && !PinLogic.isExpired(prefs.expiresAt, System.currentTimeMillis())

    fun remainingMillis(): Long = PinLogic.remainingMillis(prefs.expiresAt, System.currentTimeMillis())

    fun hasRoom(): Boolean = roomCode.isNotEmpty()

    // -------------------------------------------------------------------- 방

    /**
     * 방 만들기 — 결과는 (성공 여부, 실패 사유 코드) 로 돌려준다.
     * 사유 코드: "create"(만들지 못함) · "offline"(서버에 닿지 못함)
     */
    fun createRoom(name: String, color: String, startSharing: Boolean, onDone: (Boolean, String?) -> Unit) {
        if (busy) return
        busy = true
        saveProfile(name, color)
        tryNewRoom(attempt = 0, startSharing = startSharing, onDone = onDone)
    }

    private fun tryNewRoom(attempt: Int, startSharing: Boolean, onDone: (Boolean, String?) -> Unit) {
        val code = PinLogic.newRoomCode()
        engine.fetchRoom(code) { lookup ->
            when (lookup) {
                is RoomLookup.Found -> {
                    // 이미 누가 쓰는 코드 — 다른 코드로 다시
                    if (attempt < 3) tryNewRoom(attempt + 1, startSharing, onDone)
                    else finishCreate(false, startSharing, onDone)
                }
                is RoomLookup.Failed -> {
                    // 서버에 닿지 못했으면 코드를 바꿔 가며 재시도하지 않는다 (같은 결과)
                    noteFault("create-room", lookup.fault)
                    finishCreate(false, startSharing, onDone)
                }
                is RoomLookup.Missing -> {
                    val createdAt = System.currentTimeMillis()
                    // 방을 만든 기기가 곧 방장 — hostDeviceId 로 남겨 다른 멤버를 내보낼 수 있게 한다
                    engine.createRoom(code, myName, prefs.deviceId, createdAt) { fault ->
                        if (fault == null) {
                            enterRoom(code, owner = true, startSharing = startSharing, createdAt = createdAt)
                        } else {
                            noteFault("create-room", fault)
                        }
                        finishCreate(fault == null, startSharing, onDone)
                    }
                }
            }
        }
    }

    private fun finishCreate(ok: Boolean, startSharing: Boolean, onDone: (Boolean, String?) -> Unit) {
        busy = false
        notifyChanged()
        onDone(ok, if (ok) null else "create")
    }

    fun joinRoom(code: String, name: String, color: String, startSharing: Boolean, onDone: (Boolean, String?) -> Unit) {
        if (busy) return
        busy = true
        saveProfile(name, color)
        engine.fetchRoom(code) { lookup ->
            when (lookup) {
                is RoomLookup.Failed -> {
                    noteFault("join-room", lookup.fault)
                    busy = false
                    notifyChanged()
                    onDone(false, "network")
                }
                is RoomLookup.Missing -> {
                    if (!demo) {
                        busy = false
                        notifyChanged()
                        onDone(false, "notfound")
                    } else {
                        // 데모: 서버가 없으니 들어가려는 코드를 그 자리에서 데모 방으로 연다.
                        // 방장 내보내기를 직접 해 볼 수 있게 이 기기를 방장으로 둔다
                        engine.createRoom(code, "demo", prefs.deviceId, System.currentTimeMillis()) { }
                        enterRoom(code, owner = false, startSharing = startSharing)
                        busy = false
                        notifyChanged()
                        onDone(true, null)
                    }
                }
                is RoomLookup.Found -> {
                    enterRoom(code, owner = false, startSharing = startSharing, createdAt = lookup.room.createdAt)
                    applyRoomInfo(lookup.room)
                    busy = false
                    notifyChanged()
                    onDone(true, null)
                }
            }
        }
    }

    /** 방을 만들거나 들어간 뒤 — 서버에 방 문서가 실제로 있는지 한 번 더 확인한다 */
    fun verifyRoom(recreate: Boolean = true, onDone: ((SyncFault?, Boolean) -> Unit)? = null) {
        val code = roomCode
        if (code.isEmpty()) {
            onDone?.invoke(null, false)
            return
        }
        engine.fetchRoom(code) { lookup ->
            when (lookup) {
                is RoomLookup.Found -> {
                    roomOnServer = true
                    clearFault()
                    applyRoomInfo(lookup.room)
                    onDone?.invoke(null, false)
                }
                is RoomLookup.Failed -> {
                    // 확인 자체를 못했으면 '없다'고 단정하지 않는다
                    noteFault("room-check", lookup.fault)
                    onDone?.invoke(lookup.fault, false)
                }
                is RoomLookup.Missing -> {
                    roomOnServer = false
                    if (!recreate) {
                        onDone?.invoke(null, false)
                        return@fetchRoom
                    }
                    SyncLog.record("room-check", "rooms/$code missing → recreate")
                    engine.ensureRoom(roomSeed(code)) { fault ->
                        if (fault == null) {
                            roomOnServer = true
                            roomRepaired = true
                            clearFault()
                        } else {
                            noteFault("room-repair", fault)
                        }
                        notifyChanged()
                        onDone?.invoke(fault, fault == null)
                    }
                }
            }
        }
    }

    /** 되살리기 알림을 한 번만 보여 주려고 플래그를 내린다 */
    fun consumeRoomRepaired(): Boolean {
        val repaired = roomRepaired
        roomRepaired = false
        return repaired
    }

    private fun roomSeed(code: String = roomCode): RoomSeed {
        val createdAt = prefs.roomCreatedAt.takeIf { it > 0L }
            ?: prefs.startedAt.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        // 방을 되살릴 때 방장 정보도 함께 살린다 — 모르면 옛 방을 만든 기기만 방장으로 둔다
        val host = roomHostId.ifBlank { if (prefs.roomOwner) prefs.deviceId else "" }
        return RoomSeed(code, prefs.nickname.ifBlank { myName }, createdAt, host)
    }

    private fun noteFault(where: String, fault: SyncFault) {
        lastFault = fault
        lastFaultAt = where
        SyncLog.record("fail · $where", fault.logText)
    }

    private fun clearFault() {
        lastFault = null
        lastFaultAt = ""
    }

    private fun enterRoom(code: String, owner: Boolean, startSharing: Boolean, createdAt: Long = System.currentTimeMillis()) {
        roomCode = code
        roomOwner = owner
        roomRepaired = false
        roomOnServer = true
        prefs.roomCode = code
        prefs.roomOwner = owner
        prefs.roomCreatedAt = createdAt
        reportedHidden = false
        lastUploadAt = 0L
        lastPollAt = 0L
        messages = emptyList()
        topChatId = ""
        lastReadMs = 0L
        chatUnread = 0
        lastChatPollAt = 0L
        if (startSharing) {
            applyMode(ShareMode.ALWAYS)
        } else {
            applyMode(ShareMode.PAUSED)
        }
        refreshMembers()
        refreshRoom()
        refreshChat(full = true)
    }

    fun leaveRoom(onDone: (() -> Unit)? = null) {
        val code = roomCode
        if (code.isNotEmpty()) {
            engine.leave(code, prefs.deviceId)
            stopService()
        }
        resetRoom()
        onDone?.invoke()
    }

    /** 방을 떠난 뒤의 기억을 모두 지운다 (나가기·내보내짐이 같은 경로를 쓴다) */
    private fun resetRoom() {
        roomCode = ""
        roomOwner = false
        roomHostId = ""
        kickedIds = emptyList()
        hostClaimed = false
        members = emptyList()
        meeting = null
        messages = emptyList()
        chatUnread = 0
        chatOpen = false
        inlineChatOpen = false
        topChatId = ""
        lastReadMs = 0L
        mySpeedKmh = 0.0
        myStaySince = 0L
        stayAnchor = null
        previousFix = null
        roomOnServer = null
        prefs.clearRoom()
        applyLocation()
        notifyChanged()
    }

    // ------------------------------------------------------- 방장·내보내기 (v1.0.7)

    /** 이 방의 방장 기기 ID (모르면 "") */
    val hostIdResolved: String
        get() = PinLogic.resolveHost(roomHostId, prefs.deviceId, prefs.roomOwner)

    /** 지금 이 기기가 방장인가 — 내보내기 버튼은 이때만 보인다 */
    val isHost: Boolean get() = PinLogic.isHost(roomHostId, prefs.deviceId, prefs.roomOwner)

    /** 방 문서에서 받은 방장·내보낸 목록을 반영하고, 내가 내보내졌으면 즉시 나간다 */
    private fun applyRoomInfo(room: RoomInfo) {
        if (room.code != roomCode) return
        meeting = room.meeting
        roomHostId = room.hostId
        kickedIds = room.kicked
        // 1) 내가 내보내졌다 — 위치 공유를 멈추고 방에서 나간다 (다음 폴링에서 또 나가지 않게 먼저 확인)
        if (PinLogic.kickedOut(room.kicked, prefs.deviceId)) {
            onKickedOut()
            return
        }
        // 2) 옛 방(hostDeviceId 없음)을 만든 기기라면 이 기기를 방장으로 한 번 등록한다
        if (room.hostId.isBlank() && prefs.roomOwner && !hostClaimed && roomCode.isNotEmpty()) {
            hostClaimed = true
            val code = roomCode
            engine.claimHost(code, prefs.deviceId) { fault ->
                if (fault == null) {
                    roomHostId = prefs.deviceId
                    SyncLog.record("host-claim", "rooms/$code")
                    notifyChanged()
                } else {
                    noteFault("host-claim", fault)
                }
            }
        }
    }

    /**
     * 방장이 멤버를 내보낸다.
     * 서버에는 내보낸 목록(kickedIds)을 먼저 올리고 멤버 문서를 지우므로,
     * 상대 폰이 내보내진 뒤에 자기 위치를 다시 올리려 해도 보안 규칙이 막는다.
     */
    fun kickMember(target: Member, onDone: (SyncFault?) -> Unit) {
        val code = roomCode
        if (code.isEmpty() || target.id == prefs.deviceId) {
            onDone(null)
            return
        }
        if (!isHost) {
            // 화면에는 방장에게만 버튼이 보이지만, 권한을 한 번 더 확인한다
            onDone(SyncFault(SyncFailure.DENIED, 403, "not host"))
            return
        }
        // 방 만들기·참여와 달리 busy 로 막지 않는다 — 내보내기는 그 흐름과 무관하고,
        // 막으면 화면이 결과를 기다린 채 끝나지 않는다.
        engine.kick(code, target.id) { fault ->
            if (fault == null) {
                members = members.filterNot { it.id == target.id }
                kickedIds = PinLogic.withKicked(kickedIds, target.id)
                clearFault()
                SyncLog.record("kick", "rooms/$code · ${target.name}")
            } else {
                noteFault("kick", fault)
            }
            notifyChanged()
            onDone(fault)
        }
    }

    /**
     * 내가 방장에 의해 내보내졌을 때 — 위치 공유를 멈추고 방에서 나간다.
     * 안내는 화면이 떠 있을 때 한 번 보여 준다([consumeKickedNotice]).
     */
    private fun onKickedOut() {
        val code = roomCode
        SyncLog.record("kicked", "rooms/$code → leave")
        if (code.isNotEmpty()) {
            engine.leave(code, prefs.deviceId)
            stopService()
        }
        kickedNotice = true
        resetRoom()
    }

    /** 내보내졌다는 안내를 한 번만 가져간다 (화면이 띄운다) */
    fun consumeKickedNotice(): Boolean {
        val noticed = kickedNotice
        kickedNotice = false
        return noticed
    }

    /** 화면이 지금 보이는가 — 보이지 않으면 읽음 처리·알림을 하지 않는다 */
    val uiVisibleNow: Boolean get() = uiVisible

    // ------------------------------------------------------------- 공유 모드 변경

    /**
     * 공유 모드를 바꾼다. 계속/시간 제한은 서비스를 켜고, 일시정지는 끈다.
     * 권한은 부르는 쪽(화면)이 먼저 받아 두어야 한다.
     */
    fun applyMode(newMode: ShareMode) {
        val now = System.currentTimeMillis()
        prefs.shareMode = newMode
        if (newMode.isSharing) {
            prefs.startedAt = now
            prefs.expiresAt = PinLogic.expiryFrom(now, newMode)
            reportedHidden = false
            startService()
            uploadMine(force = true)
        } else {
            prefs.startedAt = 0L
            prefs.expiresAt = 0L
            stopService()
            if (!reportedHidden) {
                uploadMine(force = true, hidden = true)
                reportedHidden = true
            }
        }
        lastUploadAt = now
        notifyChanged()
    }

    /** 시간이 다 되어 스스로 멈출 때 */
    private fun expireSharing() {
        prefs.shareMode = ShareMode.PAUSED
        prefs.startedAt = 0L
        prefs.expiresAt = 0L
        stopService()
        uploadMine(force = true, hidden = true)
        reportedHidden = true
        notifyChanged()
    }

    // ------------------------------------------------------------------ 약속 장소

    /**
     * 약속 장소 저장 — 실패하면 이유([SyncFault])를 그대로 돌려준다.
     * 서버에 방 문서가 없으면 먼저 되살린 뒤 저장한다(CloudEngine.setMeeting → ensureRoom).
     */
    fun setMeeting(point: MeetingPoint?, onDone: (SyncFault?) -> Unit) {
        if (roomCode.isEmpty()) {
            meeting = point
            onDone(null)
            return
        }
        engine.setMeeting(roomSeed(), point) { fault ->
            if (fault == null) {
                meeting = point
                clearFault()
                notifyChanged()
            } else {
                noteFault("meeting", fault)
            }
            onDone(fault)
        }
    }

    // -------------------------------------------------------------------- 위치

    fun onLocation(location: Location) {
        val previous = myFix
        val rawSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else null
        val fix = Fix(
            lat = location.latitude,
            lng = location.longitude,
            speedMps = rawSpeedMps ?: 0.0,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
            at = System.currentTimeMillis()
        )
        if (!fix.valid) return
        // 속도: GPS 가 주면 그대로, 아니면 두 측정값 사이 거리·시간으로 계산한다.
        // 2km/h 미만(GPS 흔들림)은 0 = 정지로 본다.
        val speedKmh = if (rawSpeedMps != null && rawSpeedMps >= 0.0) {
            PinLogic.speedKmh(rawSpeedMps)
        } else {
            PinLogic.speedKmhBetween(previousFix, fix)
        }
        previousFix = fix
        myFix = fix
        mySpeedKmh = speedKmh
        // 체류 시간: 70m 넘게 벗어나거나 움직이기 시작하면 '지금'으로 다시 잡는다
        val nextStay = PinLogic.nextStaySince(myStaySince, stayAnchor, fix, speedKmh)
        if (nextStay != myStaySince) {
            myStaySince = nextStay
            prefs.staySince = nextStay
        }
        if (myStaySince == fix.at) {
            stayAnchor = fix
            prefs.stayLat = fix.lat
            prefs.stayLng = fix.lng
        }
        Sync.demoEngine?.anchor = fix
        if (previous == null || previous.at <= 0L) notifyChanged()
        if (isSharingNow() && System.currentTimeMillis() - lastUploadAt >= UPLOAD_MS) {
            uploadMine(force = true)
        }
    }

    private fun movingFrom(previous: Fix?, current: Fix): Boolean {
        if (current.speedMps > 0.0) return PinLogic.isMoving(current.speedMps)
        return PinLogic.isMovingBetween(previous, current)
    }

    private fun myMember(hidden: Boolean = false): Member? {
        val fix = myFix ?: return null
        val now = System.currentTimeMillis()
        return Member(
            id = prefs.deviceId,
            name = myName,
            color = myColor,
            lat = fix.lat,
            lng = fix.lng,
            battery = readBattery(),
            moving = movingFrom(null, fix) || PinLogic.isMovingAt(mySpeedKmh),
            updatedAt = now,
            sharing = !hidden,
            speedKmh = if (hidden) 0.0 else mySpeedKmh,
            staySince = if (myStaySince > 0L) myStaySince else now,
            mine = true
        )
    }

    private fun uploadMine(force: Boolean = false, hidden: Boolean = false) {
        if (roomCode.isEmpty()) return
        val member = myMember(hidden) ?: return
        lastUploadAt = System.currentTimeMillis()
        engine.upload(roomCode, member) { fault ->
            if (fault != null && !demo) {
                noteFault("member-upload", fault)
            } else if (fault == null && lastFaultAt == "member-upload") {
                clearFault()
            }
            notifyChanged()
        }
    }

    /** 방 멤버 목록을 새로 받는다 */
    fun refreshMembers() {
        if (roomCode.isEmpty()) return
        lastPollAt = System.currentTimeMillis()
        engine.members(roomCode) { list, fault ->
            if (list != null) {
                members = list.filter { it.id != prefs.deviceId }.sortedBy { it.name.lowercase() }
            } else if (fault != null && !demo) {
                noteFault("member-poll", fault)
            }
            notifyChanged()
        }
    }

    // --------------------------------------------------------------- 방 채팅 (v1.0.6)

    /**
     * 채팅을 새로 받아 온다.
     * 새 메시지가 있을 때만 최근 [PinLogic.CHAT_PAGE] 건을 다시 받으므로,
     * 보고 있지 않을 때의 주기 확인은 **읽기 1회**(pageSize=1)로 끝난다.
     */
    fun refreshChat(full: Boolean = false) {
        val code = roomCode
        if (code.isEmpty()) return
        lastChatPollAt = System.currentTimeMillis()
        if (full || messages.isEmpty() || topChatId.isEmpty()) {
            engine.messages(code, PinLogic.CHAT_PAGE) { list, fault -> handleChat(list, fault) }
            return
        }
        engine.messages(code, 1) { list, fault ->
            if (fault != null) {
                if (!demo) noteFault("chat-poll", fault)
                return@messages
            }
            val newest = list?.firstOrNull()?.id
            if (newest != null && newest != topChatId) {
                engine.messages(code, PinLogic.CHAT_PAGE) { fullList, fullFault -> handleChat(fullList, fullFault) }
            }
        }
    }

    private fun handleChat(list: List<ChatMessage>?, fault: SyncFault?) {
        if (list != null) applyChat(list)
        else if (fault != null && !demo) noteFault("chat-poll", fault)
    }

    private fun applyChat(incoming: List<ChatMessage>) {
        val mine = incoming.map { it.copy(mine = it.senderId == prefs.deviceId) }
        val merged = PinLogic.mergeChat(messages, mine)
        messages = merged
        topChatId = merged.lastOrNull()?.id ?: topChatId
        if (chatOpen || inlineChatOpen) {
            lastReadMs = maxOf(lastReadMs, merged.maxOfOrNull { it.createdAtMs } ?: 0L)
            chatUnread = 0
        } else {
            chatUnread = PinLogic.unreadCount(merged, lastReadMs, prefs.deviceId)
        }
        notifyChanged()
    }

    /**
     * 지도 아래 인라인 채팅창을 펼쳤다/접었다 (v1.0.7).
     * 펼쳐져 있는 동안에는 새 메시지를 바로 읽음으로 치고, 채팅 탭 배지도 같이 줄어든다.
     */
    fun setInlineChatOpen(open: Boolean) {
        if (inlineChatOpen == open) return
        inlineChatOpen = open
        if (open) markChatSeen()
        notifyChanged()
    }

    /** 지금 보이는 대화까지를 읽음으로 친다 (배지에서 뺀다) */
    fun markChatSeen() {
        val latest = messages.maxOfOrNull { it.createdAtMs } ?: return
        if (latest > lastReadMs) lastReadMs = latest
        if (chatUnread != 0) chatUnread = 0
    }

    /** 채팅 창을 열었다 — 지금까지 받은 메시지는 읽은 것으로 친다 */
    fun openChat() {
        chatOpen = true
        lastReadMs = maxOf(lastReadMs, messages.maxOfOrNull { it.createdAtMs } ?: 0L)
        chatUnread = 0
        refreshChat(full = true)
        notifyChanged()
    }

    fun closeChat() {
        chatOpen = false
        notifyChanged()
    }

    /** 메시지 보내기 — 화면에는 먼저 띄우고, 실패하면 거둔다 */
    fun sendChat(raw: String, onDone: (SyncFault?) -> Unit) {
        val code = roomCode
        val text = PinLogic.cleanChatText(raw)
        if (code.isEmpty() || text == null) {
            onDone(null)
            return
        }
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = PinLogic.chatDocId(now, PinLogic.chatSuffix()),
            senderId = prefs.deviceId,
            senderName = myName,
            senderColor = myColor,
            text = text,
            createdAtMs = now,
            mine = true
        )
        messages = PinLogic.mergeChat(messages, listOf(message))
        topChatId = messages.lastOrNull()?.id ?: message.id
        lastReadMs = maxOf(lastReadMs, now)
        chatUnread = 0
        notifyChanged()
        engine.sendMessage(code, message) { fault ->
            if (fault != null) {
                messages = messages.filterNot { it.id == message.id }
                if (!demo) noteFault("chat-send", fault)
                notifyChanged()
            }
            onDone(fault)
        }
    }

    /**
     * 화면 검사·미리보기 도구가 대화를 채워 넣을 때만 쓰는 통로.
     * 앱 코드는 언제나 서버·DemoEngine 에서 받은 값을 쓴다.
     */
    @androidx.annotation.VisibleForTesting
    fun previewMessagesForTest(items: List<ChatMessage>) {
        messages = PinLogic.mergeChat(emptyList(), items.map { it.copy(mine = it.senderId == prefs.deviceId) })
        topChatId = messages.lastOrNull()?.id.orEmpty()
        chatUnread = 0
        notifyChanged()
    }

    /**
     * 화면 검사 도구가 방장·멤버 목록을 채워 넣을 때만 쓰는 통로.
     * 앱 코드는 언제나 서버·DemoEngine 에서 받은 값을 쓴다.
     */
    /**
     * 방 문서에서 받은 것과 같은 정보를 화면 검사에서 직접 넣어 본다
     * (내보내짐 감지 → 즉시 공유 중지·퇴장까지 확인하려고).
     */
    @androidx.annotation.VisibleForTesting
    fun applyRoomForTest(room: RoomInfo) = applyRoomInfo(room)

    @androidx.annotation.VisibleForTesting
    fun previewRoomForTest(hostId: String, others: List<Member>, kicked: List<String> = emptyList()) {
        roomHostId = hostId
        kickedIds = kicked
        members = others.filter { it.id != prefs.deviceId }.sortedBy { it.name.lowercase() }
        notifyChanged()
    }

    /** 방 문서에서 약속 장소를 받는다 (다른 멤버가 찍었을 수 있다) */
    fun refreshRoom() {
        if (roomCode.isEmpty()) return
        lastRoomAt = System.currentTimeMillis()
        engine.fetchRoom(roomCode) { lookup ->
            when (lookup) {
                is RoomLookup.Found -> applyRoomInfo(lookup.room)
                is RoomLookup.Missing -> if (!demo) verifyRoom(recreate = true)
                is RoomLookup.Failed -> if (!demo) noteFault("room-check", lookup.fault)
            }
            notifyChanged()
        }
    }

    // ------------------------------------------------------- 위치 업데이트 제어

    fun onServiceStarted() {
        serviceRunning = true
        applyLocation()
    }

    fun onServiceStopped() {
        serviceRunning = false
        applyLocation()
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        applyLocation()
    }

    private fun applyLocation() {
        if (serviceRunning || uiVisible) startLocationUpdates() else stopLocationUpdates()
    }

    /**
     * 권한은 위에서 [hasPermission] 으로 확인하고, 꺼진 사이 회수될 수 있으므로
     * 요청 자체도 runCatching 으로 감싼다 (lint 는 이 흐름을 읽지 못한다).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startLocationUpdates() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) return
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = manager
        if (listener != null) return
        val made = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = made
        runCatching {
            val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val provider = if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            manager.requestLocationUpdates(provider, 3_000L, 2f, made, Looper.getMainLooper())
            if (provider == LocationManager.GPS_PROVIDER) {
                runCatching {
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 5f, made, Looper.getMainLooper())
                }
            }
            manager.getLastKnownLocation(provider)?.let { onLocation(it) }
        }
    }

    @Synchronized
    private fun stopLocationUpdates() {
        val manager = locationManager ?: return
        listener?.let { runCatching { manager.removeUpdates(it) } }
        listener = null
    }

    private var listener: LocationListener? = null

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    fun hasForegroundPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    // ------------------------------------------------------------------ 배터리

    fun readBattery(): Int {
        val manager = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
    }

    // -------------------------------------------------------------------- 서비스

    private fun startService() {
        runCatching {
            val intent = Intent(app, ShareService::class.java)
            ContextCompat.startForegroundService(app, intent)
        }
    }

    private fun stopService() {
        runCatching { app.stopService(Intent(app, ShareService::class.java)) }
    }

    // --------------------------------------------------------------------- 주기

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun onTick() {
        if (roomCode.isEmpty()) return
        val now = System.currentTimeMillis()
        if (mode.isSharing && PinLogic.isExpired(prefs.expiresAt, now)) {
            expireSharing()
            return
        }
        if (isSharingNow()) {
            if (now - lastUploadAt >= UPLOAD_MS) uploadMine()
            if (now - lastPollAt >= POLL_MS) {
                refreshMembers()
                pollCounter++
                if (pollCounter % ROOM_POLL_EVERY == 0) refreshRoom()
            }
        } else if (now - lastRoomAt >= ROOM_IDLE_CHECK_MS) {
            // 위치를 숨겨 둔 동안에도 방 문서를 가끔 확인한다 —
            // 방장이 내보냈는데 공유가 멈춰 있어서 영영 모르는 일이 없게
            refreshRoom()
        }
        // 채팅은 위치 공유와 별개다 — 위치를 숨겨도 대화는 계속된다
        val chatEvery = if (chatOpen) CHAT_POLL_MS else CHAT_IDLE_POLL_MS
        if (now - lastChatPollAt >= chatEvery) refreshChat()
        notifyChanged()
    }
}

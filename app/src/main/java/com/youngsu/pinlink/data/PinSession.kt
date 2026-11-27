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
import android.os.Build
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
 *  - 화면이 켜져 있을 때는 1초 틱으로 채팅(2.5초)·멤버 위치(6초)·방 문서(6초)를 확인하고,
 *    내 위치는 **5m 이상 움직였거나 30초가 지났을 때만** 올린다.
 *    꺼져 있으면 10초 틱으로 채팅·멤버(60초)·방 문서(30초)만 확인하고 위치는 15m/45초에 한 번 올린다.
 *    (v1.0.9 — 무료 한도(하루 읽기 5만·쓰기 2만)가 여러 대를 켜 두자 하루 만에 차서 요청을 줄였다)
 *  - 429(일일 무료 사용량 초과)를 받으면 30~60초 쉬었다가 다시 보낸다(안내는 10분에 한 번).
 *  - 고른 시간이 끝나면 스스로 멈춘다(알림·핀도 함께 사라진다).
 */
object PinSession {

    /**
     * 안드로이드 12+ 의 '융합' 위치 제공자 이름.
     * (LocationManager.FUSED_PROVIDER 상수는 API 31 부터라, 옛 기기에서도 컴파일·실행되도록 이름으로 쓴다 —
     *  이 제공자가 없는 기기에서는 [supports] 가 걸러 낸다)
     */
    private const val FUSED_PROVIDER = "fused"

    /** 지금 상태(화면 켜짐·채팅 보고 있음)에 맞는 폴링 주기 — v1.0.8 */
    private val timing: PinLogic.PollTiming
        get() = PinLogic.timing(uiVisible, chatOpen || inlineChatOpen)

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

    /**
     * 저장 중인 약속 장소 (v1.0.8).
     * 찍자마자 화면에 보여 주고, 서버가 따라올 때까지 방 문서 폴링이 옛 값으로 되돌리지 않게 한다.
     */
    @Volatile private var meetingSaving = false

    /** 저장 중인 값 — 지우기(null)도 구분해야 하므로 '저장 중'은 따로 표시한다 */
    @Volatile private var meetingPending: MeetingPoint? = null

    /** 약속 장소를 찍은 순서 — 늦게 도착한 옛 응답이 최신 값을 덮지 않게 */
    private var meetingSeq = 0

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

    /** 마지막으로 서버에 올린 좌표 — 여기서 [PinLogic.UPLOAD_MOVE_ACTIVE_M]m 넘게 움직여야 다시 올린다 */
    @Volatile private var lastUploadLat = 0.0
    @Volatile private var lastUploadLng = 0.0

    /** 이번 세션(방)에서 한 번이라도 올렸는가 — 방에 들어온 첫 핀은 바로 올린다 */
    @Volatile private var uploadedBefore = false

    @Volatile private var lastPollAt = 0L
    @Volatile private var lastRoomAt = 0L

    /** 마지막 429(일일 무료 사용량 초과) 시각과 그때 정한 쿨다운 — 그동안 서버 요청을 쉰다 (v1.0.9) */
    @Volatile private var quotaAt = 0L
    @Volatile private var quotaCooldownMs = 0L

    /** 한도 안내를 마지막으로 띄운 시각 — 같은 안내가 폴링마다 뜨지 않게 (v1.0.9) */
    @Volatile private var quotaNoticeAt = 0L
    @Volatile private var quotaNotice = false
    @Volatile private var reportedHidden = false
    @Volatile private var serviceRunning = false
    @Volatile private var uiVisible = false
    @Volatile private var started = false

    private var locationManager: LocationManager? = null
    private var previousFix: Fix? = null
    private var stayAnchor: Fix? = null

    // ------------------------------------------ 위치 수신 상태 (v1.0.10 · 설정 진단에 그대로 보인다)

    /** 마지막으로 측정값을 받은 시각(ms) — 0 이면 아직 한 번도 못 받았다 */
    @Volatile var lastFixAt: Long = 0L
        private set

    /** 마지막 측정을 준 제공자 (fused · gps · network · passive) */
    @Volatile var lastFixProvider: String = ""
        private set

    /** 마지막 측정의 정확도(m) — 0 이면 모름 */
    @Volatile var lastFixAccuracy: Double = 0.0
        private set

    /** 이번 실행에서 받은 측정 횟수 */
    @Volatile var locationFixCount: Int = 0
        private set

    /** 지금 등록되어 있는 위치 제공자들 */
    @Volatile var locationProviders: List<String> = emptyList()
        private set

    /** 마지막으로 '한 번만 받기'를 요청한 시각 — 요청 폭주를 막는다 */
    @Volatile private var lastFixAttemptAt = 0L

    /** 한 번만 받기 요청을 진행 중인가 (같은 요청이 겹치지 않게) */
    @Volatile private var fixRequestBusy = false

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
        val now = System.currentTimeMillis()
        if (fault.httpCode == 429) {
            // 이미 쉬는 중이면 시각을 다시 밀지 않는다 — 폴링이 몰려도 쿨다운이 계속 늘어나지 않게
            val cooling = PinLogic.inQuotaCooldown(quotaAt, quotaCooldownMs, now)
            if (!cooling) {
                val cooldown = PinLogic.quotaCooldownMs(quotaAt, now)
                quotaAt = now
                quotaCooldownMs = cooldown
                SyncLog.record("fail · $where", fault.logText)
                SyncLog.record("quota", "${cooldown / 1000}초 동안 요청을 쉼")
                if (PinLogic.quotaNoticeDue(quotaNoticeAt, now)) {
                    quotaNoticeAt = now
                    quotaNotice = true
                }
            }
            return
        }
        SyncLog.record("fail · $where", fault.logText)
    }

    /** 지금 서버가 429 로 막혀 쉬는 중인가 — 그동안에는 폴링을 보내지 않는다 (v1.0.9) */
    val inQuotaCooldown: Boolean
        get() = PinLogic.inQuotaCooldown(quotaAt, quotaCooldownMs, System.currentTimeMillis())

    /** 한도 안내를 한 번만 가져간다 (화면이 띄운다) — 10분에 한 번만 참이 된다 */
    fun consumeQuotaNotice(): Boolean {
        val noticed = quotaNotice
        quotaNotice = false
        return noticed
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
        lastUploadLat = 0.0
        lastUploadLng = 0.0
        uploadedBefore = false
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
        meetingSaving = false
        meetingPending = null
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
        lastUploadAt = 0L
        lastUploadLat = 0.0
        lastUploadLng = 0.0
        uploadedBefore = false
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
        // 저장 중인 약속 장소가 있으면 서버가 따라올 때까지 내가 고른 값을 그대로 보여 준다
        meeting = PinLogic.meetingAfterRefresh(meetingSaving, meetingPending, room.meeting)
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
            applyLocation()
            // 공유를 켜면 바로 한 번 새로 받아 온다 — 마지막 좌표가 아니라 '지금 여기'부터 시작하게 (v1.0.10)
            requestSingleFix("share-start")
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
     *
     * v1.0.8: [sendChat] 처럼 **내 화면에는 찍는 즉시(0ms)** 깃발을 띄우고, 서버 요청이 실패했을 때만
     * 이전 값으로 되돌리고 토스트로 알린다. v1.0.7 까지는 서버 응답이 올 때까지(왕복 2회) 깃발이 안 떴다.
     */
    fun setMeeting(point: MeetingPoint?, onDone: (SyncFault?) -> Unit) {
        val previous = meeting
        val token = ++meetingSeq
        meetingSaving = true
        meetingPending = point
        meeting = point
        notifyChanged()
        if (roomCode.isEmpty()) {
            meetingSaving = false
            meetingPending = null
            onDone(null)
            return
        }
        engine.setMeeting(roomSeed(), point) { fault ->
            // 더 새로운 요청이 뒤에 있으면 그쪽 결과를 따른다(옛 응답으로 되돌리지 않는다)
            if (token == meetingSeq) {
                meetingSaving = false
                meetingPending = null
                if (fault == null) {
                    clearFault()
                } else {
                    meeting = previous
                    noteFault("meeting", fault)
                }
                notifyChanged()
            }
            onDone(fault)
        }
    }

    // -------------------------------------------------------------------- 위치

    fun onLocation(location: Location) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        val at = if (location.time > 0L) location.time else System.currentTimeMillis()
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
        val previous = myFix
        // 늦게 도착한 옛 측정·아주 부정확한 측정은 버린다 — 핀이 뒤로 튀지 않게 (v1.0.10)
        if (!PinLogic.betterFix(previous?.at ?: 0L, previous?.accuracyMeters ?: 0.0, at, accuracy)) return
        val rawSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else null
        val fix = Fix(
            lat = location.latitude,
            lng = location.longitude,
            speedMps = rawSpeedMps ?: 0.0,
            accuracyMeters = accuracy,
            at = at
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
        // 진단(설정 → 동기화 진단)에 보여 줄 마지막 측정 정보 (v1.0.10)
        lastFixAt = System.currentTimeMillis()
        lastFixProvider = location.provider.orEmpty()
        lastFixAccuracy = accuracy
        locationFixCount++
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
        // 화면은 측정이 올 때마다 바로 갱신한다 — 서버 업로드가 늦거나 429 로 막혀도
        // 내 파란 점·남은 사람 핀이 한 박자 늦게라도 따라오게 (v1.0.10)
        notifyChanged()
        // 위치 올리기 (v1.0.9) — 측정값이 올 때마다 덮어쓰지 않고, 5m 이상 움직였거나 30초(꺼짐 45초)가
        // 지났을 때만 올린다. 쿨다운(429) 중에는 아예 보내지 않는다.
        if (isSharingNow() && !inQuotaCooldown) uploadMine()
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

    /** 마지막 업로드가 실패했는가 — 실패하면 다음 시도를 5초 뒤로 미룬다 */
    @Volatile private var uploadFailed = false

    /**
     * 내 위치를 서버에 올린다.
     * [force] 는 공유 시작·숨김·방 입장 같은 **수동 즉시 전송**에만 쓴다 —
     * 일반 폴링·GPS 측정은 [PinLogic.shouldUpload] 가 "5m 이상 움직였거나 30초 지났는가"를 보고 정한다(v1.0.9).
     */
    private fun uploadMine(force: Boolean = false, hidden: Boolean = false) {
        if (roomCode.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force) {
            // 실패 직후에는 5초 쉬어 간다 — 끊긴 망에서 1초마다 재시도하지 않게 (v1.0.10)
            if (uploadFailed && lastUploadAt > 0L && now >= lastUploadAt && now - lastUploadAt < 5_000L) return
            val every = timing
            if (!PinLogic.shouldUpload(
                    lastUploadAt = lastUploadAt,
                    lastLat = lastUploadLat,
                    lastLng = lastUploadLng,
                    uploadedBefore = uploadedBefore,
                    fix = myFix,
                    now = now,
                    uploadMeters = every.uploadMeters,
                    maxGapMs = every.uploadMs
                )
            ) return
        }
        val member = myMember(hidden) ?: return
        lastUploadAt = now
        uploadedBefore = true
        engine.upload(roomCode, member) { fault ->
            if (fault == null) {
                // 서버가 실제로 받은 좌표만 '올린 자리'로 기억한다 — 실패한 좌표를 기억하면
                // 그 뒤로는 5m 를 움직여도 다시 올리지 않는다 (v1.0.10)
                lastUploadLat = member.lat
                lastUploadLng = member.lng
                uploadFailed = false
                if (lastFaultAt == "member-upload") clearFault()
            } else {
                uploadFailed = true
                if (!demo) noteFault("member-upload", fault)
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
        if (open) {
            markChatSeen()
            // 펼친 순간부터 1.5초 주기로 확인하고, 다음 틱에서 바로 한 번 받아 온다 (v1.0.8)
            lastChatPollAt = 0L
        }
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
                // 거두었으니 '마지막으로 본 메시지'도 다시 잡는다 —
                // 서버에는 실제로 들어갔을 수도 있으므로 다음 폴링에서 다시 받아 온다
                topChatId = messages.lastOrNull()?.id.orEmpty()
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
        val wasVisible = uiVisible
        uiVisible = visible
        applyLocation()
        // 화면으로 돌아왔을 때 (또는 꺼질 때) — 틱 주기를 바꾸고 다음 틱을 5초 기다리지 않는다 (v1.0.8)
        if (uiVisible != wasVisible) kickClock()
        // 화면으로 돌아왔는데 마지막 측정이 오래됐으면 바로 한 번 새로 받는다 (v1.0.10)
        if (visible && PinLogic.fixRetryDue(lastFixAt, 0L, System.currentTimeMillis())) {
            requestSingleFix("ui-resume")
        }
    }

    private fun applyLocation() {
        if (serviceRunning || uiVisible) startLocationUpdates() else stopLocationUpdates()
    }

    /**
     * 위치 요청을 건다 (v1.0.10).
     *
     * v1.0.9 까지는 **한 제공자만** 골라 걸었다 — GPS 가 켜져 있으면 GPS 에만 걸고,
     * 등록이 한 번 실패하거나 GPS 칩이 측정을 멈추면 다시 요청할 길이 없어
     * 파란이 점과 상대방 핀이 마지막 자리에 그대로 멈췄다(실기기: "안 움직여" · "Gps 못잡는듯").
     * 이제는 융합(fused, 안드로이드 12+) · GPS · 네트워크를 **각각 따로** 걸고(하나가 실패해도 나머지는 산다),
     * 살아 있는 측정이 없으면 워치독이 15초마다 한 번씩 강제로 다시 받는다.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startLocationUpdates() {
        if (!hasForegroundPermission()) return
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = manager
        val made = listener ?: object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) { requestSingleFix("provider-on:$provider") }
            override fun onProviderDisabled(provider: String) {
                SyncLog.record("gps", "$provider 꺼짐 — 다른 제공자로 계속")
            }
        }.also { listener = it }

        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val wanted = ArrayList<String>(3)
        if (fine && supports(manager, FUSED_PROVIDER)) wanted.add(FUSED_PROVIDER)
        if (fine) wanted.add(LocationManager.GPS_PROVIDER)
        wanted.add(LocationManager.NETWORK_PROVIDER)

        for (provider in wanted) {
            if (registered.contains(provider)) continue
            // 최소 간격만 두고 거리 조건은 두지 않는다 — 서버 업로드는 [PinLogic.shouldUpload] 가
            // 5m/30초로 줄이므로, 기기 쪽은 '자주 받아 두고 필요한 것만 올린다'가 맞다
            val every = if (provider == LocationManager.NETWORK_PROVIDER) 3_000L else 2_000L
            val ok = runCatching {
                manager.requestLocationUpdates(provider, every, 0f, made, Looper.getMainLooper())
            }.isSuccess
            if (ok) madeProviders.add(provider) else SyncLog.record("gps", "$provider 요청 실패")
        }
        registered.clear()
        registered.addAll(madeProviders)
        locationProviders = registered.toList()

        // 아직 측정이 없으면 마지막으로 알던 위치라도 바로 보여 준다 (앱을 열자마자 내 핀이 보이게)
        if (myFix == null) bestLastKnown(manager)?.let { onLocation(it) }
        if (registered.isEmpty()) {
            SyncLog.record("gps", "걸린 제공자 없음 — 15초마다 다시 시도")
            requestSingleFix("register-failed")
        }
        startFixWatchdog()
    }

    @Synchronized
    private fun stopLocationUpdates() {
        stopFixWatchdog()
        val manager = locationManager
        val made = listener
        if (manager != null && made != null) runCatching { manager.removeUpdates(made) }
        madeProviders.clear()
        registered.clear()
        locationProviders = emptyList()
        listener = null
    }

    private var listener: LocationListener? = null
    private val madeProviders = HashSet<String>()
    private val registered = HashSet<String>()

    /** 이 기기가 그 제공자를 갖고 있는가 (permission 이 필요한 호출이라 감싸 둔다) */
    private fun supports(manager: LocationManager, provider: String): Boolean =
        runCatching { manager.allProviders?.contains(provider) == true }.getOrDefault(false)

    /** 마지막으로 알던 위치 중 가장 새것 · 가장 정확한 것 */
    @SuppressLint("MissingPermission")
    private fun bestLastKnown(manager: LocationManager): Location? {
        val candidates = ArrayList<Location>(3)
        for (provider in listOf(FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!supports(manager, provider)) continue
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { candidates.add(it) }
        }
        return candidates.filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .maxByOrNull { it.time }
    }

    /**
     * 지금 한 번만 위치를 받는다 (v1.0.10).
     * 연속 요청이 멈췄을 때(워치독) · 화면 복귀 · 공유 시작 · 사용자의 '지금 갱신' 에서 부른다.
     * 안드로이드 11+ 는 [LocationManager.getCurrentLocation], 그 이하는 단발 요청을 쓴다.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestSingleFix(reason: String) {
        if (!hasForegroundPermission()) return
        val manager = locationManager ?: return
        val made = listener ?: return
        val now = System.currentTimeMillis()
        if (fixRequestBusy) return
        if (lastFixAttemptAt > 0L && now >= lastFixAttemptAt && now - lastFixAttemptAt < 2_000L) return
        lastFixAttemptAt = now

        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = when {
            fine && supports(manager, FUSED_PROVIDER) -> FUSED_PROVIDER
            fine && supports(manager, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                fixRequestBusy = true
                manager.getCurrentLocation(
                    provider,
                    null,
                    ContextCompat.getMainExecutor(app)
                ) { location ->
                    fixRequestBusy = false
                    if (location != null) onLocation(location) else SyncLog.record("gps", "$reason · $provider 측정 없음")
                }
            }.isSuccess
        } else {
            runCatching { manager.requestSingleUpdate(provider, made, Looper.getMainLooper()) }.isSuccess
        }
        if (!ok) {
            fixRequestBusy = false
            SyncLog.record("gps", "$reason · $provider 단발 요청 실패")
            // 단발 요청이 막히면 등록을 모두 풀고 다시 건다 — 그사이에도 마지막 위치는 보여 준다
            bestLastKnown(manager)?.let { onLocation(it) }
            reregister()
        }
    }

    @Synchronized
    private fun reregister() {
        // 다시 걸기가 실패하면 또 다시 걸기를 부를 수 있다 — 한 번만 돌게 막는다
        if (reregistering) return
        reregistering = true
        try {
            stopLocationUpdates()
            startLocationUpdates()
        } finally {
            reregistering = false
        }
    }

    @Volatile private var reregistering = false

    /**
     * 5초마다 '살아 있는 측정'을 확인한다 — 20초 넘게 측정이 없으면 15초 간격으로 한 번씩 다시 받는다.
     * (GPS 칩이 조용해져도 파란 점이 영영 멈추지 않게 하는 마지막 안전장치)
     */
    private val fixWatchdog = object : Runnable {
        override fun run() {
            if (!(serviceRunning || uiVisible)) return
            val now = System.currentTimeMillis()
            // 단발 요청이 응답 없이 오래 묶여 있으면 풀어 준다 (그대로 두면 다시 요청할 수 없다)
            if (fixRequestBusy && lastFixAttemptAt > 0L && now - lastFixAttemptAt > 30_000L) {
                fixRequestBusy = false
            }
            if (PinLogic.fixRetryDue(lastFixAt, lastFixAttemptAt, now)) {
                requestSingleFix("watchdog")
            }
            main.postDelayed(this, 5_000L)
        }
    }

    private var watchdogOn = false

    private fun startFixWatchdog() {
        if (watchdogOn) return
        watchdogOn = true
        main.removeCallbacks(fixWatchdog)
        main.postDelayed(fixWatchdog, 5_000L)
    }

    private fun stopFixWatchdog() {
        if (!watchdogOn) return
        watchdogOn = false
        main.removeCallbacks(fixWatchdog)
    }

    /**
     * 사용자가 '지금 갱신'을 눌렀을 때 (v1.0.10) — 지도 우측 아래 현위치 버튼·설정의 갱신 버튼이 부른다.
     * 새 측정을 한 번 받고, 지금 좌표를 서버에 바로 올리고, 남은 사람들 위치도 새로 받아 온다.
     */
    fun refreshNow() {
        requestSingleFix("manual")
        lastUploadAt = 0L
        if (myFix != null && roomCode.isNotEmpty()) uploadMine(force = true)
        refreshMembers()
        refreshRoom()
        notifyChanged()
    }

    /** 마지막 측정이 몇 초 전인가 — 설정 진단 표시용 (아직 없으면 -1) */
    val fixAgeSeconds: Long
        get() {
            val at = lastFixAt
            if (at <= 0L) return -1L
            val delta = System.currentTimeMillis() - at
            return if (delta < 0L) 0L else delta / 1000L
        }

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

    /**
     * 타이머 — 화면이 켜져 있으면 1초, 꺼져 있으면 10초마다 깨어나 각 폴링이 자기 주기를 넘겼는지 본다.
     * (v1.0.9) 429 쿨다운 중에는 폴링을 보내지 않는다 — 한도가 바닥난 서버에 요청을 반복하지 않는다.
     */
    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            main.postDelayed(this, timing.tickMs)
        }
    }

    /**
     * 화면이 켜지거나 꺼질 때 타이머를 다시 맞춘다 (v1.0.8).
     * 주기가 바뀐 것에 더해, 5초를 기다리지 않고 **바로 한 번** 돈다 —
     * 앱으로 돌아오자마자 약속 장소·새 메시지가 보이게 하기 위해서다.
     * (main 스레드 하나에서만 불리므로 removeCallbacks 와 post 사이에 타이머가 끼어들지 않는다)
     */
    private fun kickClock() {
        lastUploadAt = 0L
        lastPollAt = 0L
        lastRoomAt = 0L
        lastChatPollAt = 0L
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun onTick() {
        if (roomCode.isEmpty()) return
        val now = System.currentTimeMillis()
        val every = timing
        if (mode.isSharing && PinLogic.isExpired(prefs.expiresAt, now)) {
            expireSharing()
            return
        }
        // 429 쿨다운 — 서버 요청을 쉬고 화면만 그린다 (v1.0.9)
        if (PinLogic.inQuotaCooldown(quotaAt, quotaCooldownMs, now)) {
            notifyChanged()
            return
        }
        if (isSharingNow()) {
            if (PinLogic.isDue(lastUploadAt, now, every.uploadMs)) uploadMine()
            if (PinLogic.isDue(lastPollAt, now, every.memberMs)) refreshMembers()
            // 약속 장소·방장 내보내기 — 화면이 켜져 있을 때 6초, 꺼져 있으면 30초 (v1.0.9)
            if (PinLogic.isDue(lastRoomAt, now, every.roomMs)) refreshRoom()
        } else if (PinLogic.isDue(lastRoomAt, now, every.roomIdleMs)) {
            // 위치를 숨겨 둔 동안에도 방 문서를 가끔 확인한다 —
            // 방장이 내보냈는데 공유가 멈춰 있어서 영영 모르는 일이 없게
            refreshRoom()
        }
        // 채팅은 위치 공유와 별개다 — 위치를 숨겨도 대화는 계속된다
        if (PinLogic.isDue(lastChatPollAt, now, every.chatMs)) refreshChat()
        notifyChanged()
    }
}

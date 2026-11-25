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

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val prefs: AppPrefs get() = AppPrefs.get(app)
    private val engine: SyncEngine get() = Sync.engine

    @Volatile var roomCode: String = ""
        private set

    @Volatile var roomOwner: Boolean = false
        private set

    @Volatile var members: List<Member> = emptyList()
        private set

    @Volatile var myFix: Fix? = null
        private set

    @Volatile var battery: Int = -1

    @Volatile var meeting: MeetingPoint? = null
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var busy: Boolean = false
        private set

    @Volatile private var lastUploadAt = 0L
    @Volatile private var lastPollAt = 0L
    @Volatile private var pollCounter = 0
    @Volatile private var reportedHidden = false
    @Volatile private var serviceRunning = false
    @Volatile private var uiVisible = false
    @Volatile private var started = false

    private var locationManager: LocationManager? = null

    val demo: Boolean get() = Sync.isDemo

    // ------------------------------------------------------------------ 초기화

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        roomCode = prefs.roomCode
        roomOwner = prefs.roomOwner
        battery = readBattery()
        Sync.demoEngine?.anchor = DemoEngine.DEFAULT_ANCHOR
        main.post(ticker)
        // 앱을 다시 켰을 때: 공유 중이던 방이 아직 살아 있으면 서비스도 함께 되살린다
        main.post {
            if (roomCode.isEmpty()) return@post
            if (mode.isSharing) {
                if (PinLogic.isExpired(prefs.expiresAt, System.currentTimeMillis())) expireSharing()
                else startService()
            }
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

    fun createRoom(name: String, color: String, startSharing: Boolean, onDone: (Boolean, String?) -> Unit) {
        if (busy) return
        busy = true
        saveProfile(name, color)
        tryNewRoom(attempt = 0, startSharing = startSharing, onDone = onDone)
    }

    private fun tryNewRoom(attempt: Int, startSharing: Boolean, onDone: (Boolean, String?) -> Unit) {
        val code = PinLogic.newRoomCode()
        engine.fetchRoom(code) { existing ->
            if (existing != null) {
                if (attempt < 3) tryNewRoom(attempt + 1, startSharing, onDone)
                else finishCreate(false, startSharing, onDone)
                return@fetchRoom
            }
            engine.createRoom(code, myName, System.currentTimeMillis()) { ok ->
                if (ok) {
                    enterRoom(code, owner = true, startSharing = startSharing)
                }
                finishCreate(ok, startSharing, onDone)
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
        engine.fetchRoom(code) { room ->
            if (room == null && !demo) {
                busy = false
                notifyChanged()
                onDone(false, "notfound")
                return@fetchRoom
            }
            if (room == null) {
                // 데모: 서버가 없으니 들어가려는 코드를 그 자리에서 데모 방으로 연다
                engine.createRoom(code, "demo", System.currentTimeMillis()) { }
            }
            enterRoom(code, owner = false, startSharing = startSharing)
            meeting = room?.meeting
            busy = false
            notifyChanged()
            onDone(true, null)
        }
    }

    private fun enterRoom(code: String, owner: Boolean, startSharing: Boolean) {
        roomCode = code
        roomOwner = owner
        prefs.roomCode = code
        prefs.roomOwner = owner
        reportedHidden = false
        lastUploadAt = 0L
        lastPollAt = 0L
        if (startSharing) {
            applyMode(ShareMode.ALWAYS)
        } else {
            applyMode(ShareMode.PAUSED)
        }
        refreshMembers()
        refreshRoom()
    }

    fun leaveRoom(onDone: (() -> Unit)? = null) {
        val code = roomCode
        if (code.isNotEmpty()) {
            engine.leave(code, prefs.deviceId)
            stopService()
        }
        roomCode = ""
        roomOwner = false
        members = emptyList()
        meeting = null
        prefs.clearRoom()
        applyLocation()
        notifyChanged()
        onDone?.invoke()
    }

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

    fun setMeeting(point: MeetingPoint?, onDone: (Boolean) -> Unit) {
        if (roomCode.isEmpty()) {
            meeting = point
            onDone(true)
            return
        }
        engine.setMeeting(roomCode, point) { ok ->
            if (ok) {
                meeting = point
                notifyChanged()
            }
            onDone(ok)
        }
    }

    // -------------------------------------------------------------------- 위치

    fun onLocation(location: Location) {
        val previous = myFix
        val fix = Fix(
            lat = location.latitude,
            lng = location.longitude,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
            at = System.currentTimeMillis()
        )
        if (!fix.valid) return
        myFix = fix
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
            moving = movingFrom(null, fix),
            updatedAt = now,
            sharing = !hidden,
            mine = true
        )
    }

    private fun uploadMine(force: Boolean = false, hidden: Boolean = false) {
        if (roomCode.isEmpty()) return
        val member = myMember(hidden) ?: return
        lastUploadAt = System.currentTimeMillis()
        engine.upload(roomCode, member) { ok ->
            if (!ok && !demo) lastError = "upload"
            notifyChanged()
        }
    }

    /** 방 멤버 목록을 새로 받는다 */
    fun refreshMembers() {
        if (roomCode.isEmpty()) return
        lastPollAt = System.currentTimeMillis()
        engine.members(roomCode) { list ->
            if (list != null) {
                members = list.filter { it.id != prefs.deviceId }.sortedBy { it.name.lowercase() }
                lastError = null
            } else if (!demo) {
                lastError = "members"
            }
            notifyChanged()
        }
    }

    /** 방 문서에서 약속 장소를 받는다 (다른 멤버가 찍었을 수 있다) */
    fun refreshRoom() {
        if (roomCode.isEmpty()) return
        engine.fetchRoom(roomCode) { room ->
            if (room != null) meeting = room.meeting
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
        }
        notifyChanged()
    }
}

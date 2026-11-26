package com.youngsu.pinlink.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.youngsu.pinlink.CrashLog
import com.youngsu.pinlink.R
import com.youngsu.pinlink.ads.AdManager
import com.youngsu.pinlink.ads.InterstitialHelper
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.data.Sync
import com.youngsu.pinlink.data.SyncFailure
import com.youngsu.pinlink.data.SyncFault

/**
 * PinLink 의 유일한 화면 — 지도 / 방 / 설정 탭과 하단 배너 광고.
 *
 * 화면 순서는 반드시 **콘텐츠 → 하단 탭 바 → 배너 광고**(화면 최하단)다.
 * 백그라운드 위치를 요청하기 전에는 Play 정책에 맞춰 명시적 안내(Prominent Disclosure)를 먼저 보여 준다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var adContainer: FrameLayout
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var mapScreen: MapScreen
    private lateinit var roomScreen: RoomScreen
    private lateinit var chatScreen: ChatScreen
    private lateinit var settingsScreen: SettingsScreen

    private var currentTab = R.id.nav_map
    private var topInset = 0
    private var bottomInset = 0
    private var lastStructureKey = ""
    private var pendingPermission: ((Boolean) -> Unit)? = null
    private var backgroundAsked = false

    /** 화면 골격을 끝까지 만들었는가 — 만들다 실패하면 안내 화면만 보여 주고 앱은 살려 둔다 */
    private var ready = false

    private val main = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            refreshCurrent()
            main.postDelayed(this, 1_000L)
        }
    }

    private val sessionListener: () -> Unit = { main.post { refreshSoon() } }
    private var refreshScheduled = false

    private val fineLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = PinSession.hasForegroundPermission()
        if (!granted) {
            toast(getString(R.string.toast_location_denied))
            finishPermission(false)
        } else {
            ensureBackground { finishPermission(true) }
        }
    }

    private val backgroundLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(getString(R.string.toast_background_denied))
        finishPermission(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Application 이 만들어지지 못한 경우에도 오류를 적어 둘 수 있게 한 번 더 건다(중복은 무시)
        runCatching { CrashLog.install(applicationContext) }

        // 어떤 부품이 실패해도 시스템 기본 "앱에 버그가 있어 종료했습니다" 창 대신
        // 앱 안에서 안내 화면을 보여 준다 — 사용자에게는 앱이 그대로 열린다.
        val built = runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            buildUi(savedInstanceState)
        }
        built.exceptionOrNull()?.let { error ->
            ready = false
            runCatching { CrashLog.record(this, error) }
            runCatching { showStartupFallback(error) }
            return
        }

        ready = true
        runCatching { notifyLastCrash() }
    }

    /** 화면 골격 — 실패하면 [onCreate] 가 받아서 안내 화면으로 대신한다 */
    private fun buildUi(savedInstanceState: Bundle?) {
        PinSession.init(this)
        AdManager.initialize(this)
        InterstitialHelper.preload()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        setContentView(root)

        // 1) 콘텐츠
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })

        // 2) 하단 탭 바
        bottomNav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.nav_menu)
            background = GradientDrawable().apply {
                setColor(getColor(R.color.nav_bg))
                setStroke(dp(1f), getColor(R.color.divider))
            }
            elevation = 0f
            itemIconTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(getColor(R.color.accent), getColor(R.color.text_tertiary))
            )
            itemTextColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(getColor(R.color.accent), getColor(R.color.text_tertiary))
            )
            itemActiveIndicatorColor = ColorStateList.valueOf(getColor(R.color.accent_dim))
            itemIconSize = dp(21f)
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener { item ->
                selectTab(item.itemId)
                true
            }
        }
        root.addView(bottomNav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f)))

        // 3) 배너 광고 — 화면 맨 끝(최하단)
        adContainer = FrameLayout(this)
        root.addView(
            adContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        hideSystemNavBar()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topInset = bars.top
            bottomInset = bars.bottom
            applyInsets()
            insets
        }

        mapScreen = MapScreen(this)
        roomScreen = RoomScreen(this)
        chatScreen = ChatScreen(this)
        settingsScreen = SettingsScreen(this)

        val startTab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.nav_map
        bottomNav.selectedItemId = startTab
        selectTab(startTab)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != R.id.nav_map) {
                    bottomNav.selectedItemId = R.id.nav_map
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        InterstitialHelper.attach(this)
        // 광고는 첫 화면이 뜬 뒤에 붙인다 — 광고 쪽 문제로 시작이 막히지 않게
        content.post {
            if (!isFinishing && !isDestroyed) {
                runCatching { AdManager.attachBanner(this, adContainer) }
                runCatching { adContainer.viewTreeObserver.addOnGlobalLayoutListener { applyInsets() } }
            }
        }

        // 처음 실행 — 무엇보다 먼저 쓸 언어를 고르게 한다
        if (savedInstanceState == null && !AppPrefs.get(this).languageOnboarded) {
            content.post { if (!isFinishing && !isDestroyed) LanguageOnboarding.show(this) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        runCatching {
            hideSystemNavBar()
            PinSession.addListener(sessionListener)
            PinSession.setUiVisible(true)
            refreshSoon()
            // 서버에 없던 방을 되살렸으면 한 번 알려 준다
            if (PinSession.consumeRoomRepaired()) toast(getString(R.string.toast_room_restored))
            main.removeCallbacks(updater)
            main.post(updater)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!ready) return
        runCatching {
            PinSession.removeListener(sessionListener)
            PinSession.setUiVisible(false)
            main.removeCallbacks(updater)
        }
    }

    // ------------------------------------------------------------------- 탭

    private fun selectTab(itemId: Int) {
        val leavingChat = currentTab == R.id.nav_chat && itemId != R.id.nav_chat
        currentTab = itemId
        val screen: View = when (itemId) {
            R.id.nav_room -> roomScreen.view
            R.id.nav_chat -> chatScreen.view
            R.id.nav_settings -> settingsScreen.view
            else -> mapScreen.view
        }
        if (leavingChat) PinSession.closeChat()
        if (itemId == R.id.nav_chat) PinSession.openChat()
        content.removeAllViews()
        content.addView(screen, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        updateChatBadge()
        refreshSoon()
    }

    /** 채팅 탭으로 이동 (지도·방 화면의 채팅 버튼) */
    fun openChatTab() {
        if (!PinSession.hasRoom()) {
            toast(getString(R.string.chat_no_room))
            return
        }
        if (currentTab == R.id.nav_chat) return
        bottomNav.selectedItemId = R.id.nav_chat
    }

    /** 하단 채팅 탭의 읽지 않은 메시지 배지 */
    private fun updateChatBadge() {
        runCatching {
            val unread = if (PinSession.hasRoom() && currentTab != R.id.nav_chat) PinSession.chatUnread else 0
            val badge = bottomNav.getOrCreateBadge(R.id.nav_chat)
            badge.isVisible = unread > 0
            badge.maxCharacterCount = 3
            badge.backgroundColor = getColor(R.color.accent)
            badge.badgeTextColor = getColor(R.color.on_accent)
            if (unread > 0) badge.number = unread
        }
    }

    private fun refreshCurrent() {
        if (!ready) return
        runCatching {
            when (currentTab) {
                R.id.nav_room -> roomScreen.refresh()
                R.id.nav_chat -> chatScreen.refresh()
                R.id.nav_settings -> settingsScreen.refresh()
                else -> mapScreen.refresh()
            }
            updateChatBadge()
        }
    }

    private fun refreshSoon() {
        if (!ready) return
        val key = buildString {
            append(currentTab).append('|')
            append(PinSession.roomCode).append('|')
            append(PinSession.members.size).append('|')
            append(PinSession.mode.id)
        }
        if (key != lastStructureKey) lastStructureKey = key
        if (refreshScheduled) return
        refreshScheduled = true
        main.postDelayed({
            refreshScheduled = false
            refreshCurrent()
        }, 60L)
    }

    // ------------------------------------------------------------- 방 만들기·참여

    fun startCreateRoom() {
        Dialogs.showCreate(this) { name, color ->
            withSharingPermission { granted ->
                PinSession.createRoom(name, color, startSharing = granted) { ok, _ ->
                    if (ok) {
                        onRoomEntered(getString(R.string.toast_joined))
                    } else {
                        toastFault(getString(R.string.err_create_failed), PinSession.lastFault)
                    }
                }
            }
        }
    }

    fun startJoinRoom() {
        Dialogs.showJoin(this) { code ->
            Dialogs.showCreate(
                this,
                title = getString(R.string.create_title),
                sub = getString(R.string.create_sub)
            ) { name, color ->
                withSharingPermission { granted ->
                    PinSession.joinRoom(code, name, color, startSharing = granted) { ok, error ->
                        if (ok) {
                            onRoomEntered(getString(R.string.toast_joined))
                        } else if (error == "notfound") {
                            toast(getString(R.string.err_room_not_found))
                        } else if (error == "network") {
                            toastFault(getString(R.string.err_network), PinSession.lastFault)
                        } else {
                            toastFault(getString(R.string.err_join_failed), PinSession.lastFault)
                        }
                    }
                }
            }
        }
    }

    private fun onRoomEntered(message: String) {
        toast(message)
        bottomNav.selectedItemId = R.id.nav_map
        refreshSoon()
        // 방을 만들거나 들어간 직후 한 번만 전면 광고 (3분 쿨다운)
        InterstitialHelper.showAfterRoomOpened(this)
    }

    // ----------------------------------------------------------- 공유 모드 변경

    fun requestShareMode(mode: ShareMode) {
        if (!PinSession.hasRoom()) {
            toast(getString(R.string.room_empty))
            return
        }
        if (!mode.isSharing) {
            PinSession.applyMode(ShareMode.PAUSED)
            toast(getString(R.string.sharing_paused))
            refreshSoon()
            return
        }
        withSharingPermission { granted ->
            if (granted) {
                PinSession.applyMode(mode)
                toast(getString(R.string.toast_sharing_started))
            } else {
                PinSession.applyMode(ShareMode.PAUSED)
            }
            refreshSoon()
        }
    }

    fun openMeetingPicker() {
        bottomNav.selectedItemId = R.id.nav_map
        mapScreen.startPick()
    }

    fun confirmLeaveRoom() {
        Dialogs.confirm(
            this,
            getString(R.string.leave_room_title),
            getString(R.string.leave_room_body),
            getString(R.string.leave),
            getString(R.string.cancel),
            destructive = true
        ) {
            PinSession.leaveRoom {
                toast(getString(R.string.leave))
                bottomNav.selectedItemId = R.id.nav_map
                refreshSoon()
            }
        }
    }

    // --------------------------------------------------------------- 코드 공유

    fun copyRoomCode() {
        val code = PinSession.roomCode
        if (code.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.room_code_title), code))
        toast(getString(R.string.code_copied))
    }

    fun shareRoomCode() {
        val code = PinSession.roomCode
        if (code.isEmpty()) return
        val text = getString(R.string.invite_message, code)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title))) }
    }

    // ------------------------------------------------------------ 권한 흐름

    /**
     * 공유를 시작하기 전에 필요한 권한을 받아 온다.
     *  1) 명시적 안내(Prominent Disclosure) — 처음 한 번, 동의 전에는 절대 권한 창을 띄우지 않는다
     *  2) 위치(정확/대략) + 알림
     *  3) 백그라운드 위치는 따로 — 거절해도 앱을 열어 둔 동안의 공유는 계속된다
     */
    private fun withSharingPermission(action: (Boolean) -> Unit) {
        if (isFinishing || isDestroyed) return
        if (PinSession.hasForegroundPermission()) {
            ensureBackground { action(true) }
            return
        }
        val proceed: () -> Unit = {
            pendingPermission = action
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            fineLauncher.launch(permissions.toTypedArray())
        }
        if (AppPrefs.get(this).disclosureAccepted) {
            proceed()
        } else {
            Dialogs.showDisclosure(
                this,
                onAccept = {
                    AppPrefs.get(this).disclosureAccepted = true
                    proceed()
                },
                onDecline = { action(false) }
            )
        }
    }

    /**
     * 백그라운드 위치.
     * 안드로이드 10 은 시스템 창에서 바로 '항상 허용'을 고를 수 있고,
     * 안드로이드 11+ 는 정책상 시스템 창에 그 선택지가 없어 앱 설정 화면으로 안내한다.
     * 한 번 물은 뒤에는 같은 실행에서 다시 묻지 않는다(설정 화면에서 언제든 바꿀 수 있으므로).
     */
    private fun ensureBackground(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || PinSession.hasBackgroundPermission()) {
            then()
            return
        }
        if (backgroundAsked) {
            then()
            return
        }
        backgroundAsked = true
        Dialogs.showBackgroundDisclosure(
            this,
            onOpenSettings = {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    runCatching { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                } else {
                    toast(getString(R.string.toast_background_denied))
                    openSystemSettings()
                }
                then()
            },
            onLater = {
                toast(getString(R.string.toast_background_denied))
                then()
            }
        )
    }

    private fun finishPermission(granted: Boolean) {
        val action = pendingPermission
        pendingPermission = null
        action?.invoke(granted)
    }

    private fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        runCatching { startActivity(intent) }
    }

    // ------------------------------------------------------------------ 잔손질

    /**
     * 화면을 만들다 실패했을 때 보여 주는 최소 화면.
     * 앱을 종료시키는 대신 무엇이 잘못됐는지 알려 주고 다시 열 수 있게 한다.
     */
    private fun showStartupFallback(error: Throwable?) {
        val built = runCatching {
            val column = Ui.column(this).apply {
                setPadding(dp(24f), dp(48f), dp(24f), dp(24f))
                setBackgroundColor(getColor(R.color.bg))
            }
            column.addView(
                Ui.text(this, getString(R.string.app_name), 22f, getColor(R.color.text_primary), bold = true)
            )
            column.addView(
                Ui.text(
                    this,
                    getString(R.string.startup_error_title),
                    13f,
                    getColor(R.color.text_secondary)
                ).apply { setPadding(0, dp(10f), 0, 0) }
            )
            column.addView(
                Ui.text(
                    this,
                    "${error?.javaClass?.simpleName.orEmpty()}: ${error?.message.orEmpty()}".trim(),
                    11.5f,
                    getColor(R.color.text_tertiary)
                ).apply { setPadding(0, dp(12f), 0, 0) }
            )
            // 안내 화면은 테마 위젯을 쓰지 않는다 — 테마가 깨져서 실패한 경우에도 뜨도록
            val retry = Ui.text(this, getString(R.string.startup_error_retry), 15f, getColor(R.color.on_accent), bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14f), dp(16f), dp(14f), dp(16f))
                setBackgroundColor(getColor(R.color.accent))
                Ui.clickable(this) { recreate() }
            }
            column.addView(
                retry,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(20f) }
            )
            setContentView(column)
        }
        if (built.isFailure) {
            // 그것도 안 되면 글자 한 줄만이라도 띄운다 (앱 종료 창 대신)
            runCatching {
                setContentView(
                    android.widget.TextView(this).apply {
                        text = getString(R.string.startup_error_title)
                        setPadding(dp(24f), dp(48f), dp(24f), dp(24f))
                    }
                )
            }
        }
        runCatching { PinSession.setUiVisible(false) }
    }

    /** 지난번에 앱이 비정상 종료된 기록이 있으면 한 번 보여 준다 (복사해서 보내면 원인 확인 가능) */
    private fun notifyLastCrash() {
        val record = runCatching { CrashLog.read(this) }.getOrNull() ?: return
        main.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val shown = runCatching {
                val message = Ui.text(this, record, 10.5f, getColor(R.color.text_secondary)).apply {
                    setTextIsSelectable(true)
                    setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                }
                val box = ScrollView(this).apply { addView(message) }
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.last_crash_title))
                    .setMessage(getString(R.string.last_crash_sub))
                    .setView(box)
                    .setPositiveButton(getString(R.string.last_crash_copy)) { _, _ ->
                        runCatching {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("PinLink crash", record))
                            toast(getString(R.string.crash_copied))
                        }
                        runCatching { CrashLog.clear(this) }
                    }
                    .setNegativeButton(getString(R.string.close)) { _, _ ->
                        runCatching { CrashLog.clear(this) }
                    }
                    .setCancelable(false)
                    .show()
            }
            if (shown.isFailure) runCatching { CrashLog.clear(this) }
        }, 900L)
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 실패 안내 — 기본 문구 아래에 사유 한 줄을 붙인다.
     * v1.0.4 까지는 403(권한)도 404(방 없음)도 네트워크 문제도 모두 "서버에 연결하지 못했어요" 하나로 보여 줘서
     * 원인을 알 수 없었다.
     */
    fun toastFault(base: String, fault: SyncFault?) {
        val detail = fault?.let { getString(faultLabel(it), it.httpCode) }
        toast(if (detail.isNullOrBlank()) base else "$base\n$detail")
    }

    private fun faultLabel(fault: SyncFault): Int = when (fault.failure) {
        SyncFailure.OFFLINE -> R.string.fault_offline
        SyncFailure.DENIED -> R.string.fault_denied
        SyncFailure.MISSING -> R.string.fault_missing
        SyncFailure.BAD_REQUEST -> R.string.fault_bad
        SyncFailure.SERVER -> R.string.fault_server
        SyncFailure.UNKNOWN -> R.string.fault_unknown
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun applyInsets() {
        // 상태바는 콘텐츠 위 여백으로, 시스템 내비게이션 바는 맨 아래 배너 밑 여백으로 비켜 준다
        content.setPadding(0, topInset, 0, 0)
        adContainer.setPadding(0, 0, 0, bottomInset)
    }

    private fun hideSystemNavBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    companion object {
        private const val STATE_TAB = "tab"
    }
}

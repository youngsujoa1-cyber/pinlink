package com.youngsu.pinlink.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.youngsu.pinlink.R
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.PinSession
import com.youngsu.pinlink.map.MapSource
import com.youngsu.pinlink.map.MapStyle
import com.youngsu.pinlink.map.MapTileProvider
import com.youngsu.pinlink.ui.widget.HybridMapView

/**
 * 지도 화면.
 *
 *  - 방이 없으면 시작 카드(방 만들기 / 코드로 참여)를 보여 준다.
 *  - 방이 있으면 실제 지도 위에 멤버 핀·내 위치·약속 장소를 그리고,
 *    위쪽에 방 코드·공유 상태·멤버 칩, 아래쪽에 선택한 멤버 카드와 버튼을 얹는다.
 *  - [startPick] 이면 화면 정중앙 핀으로 약속 장소를 고르는 모드가 된다.
 */
class MapScreen(private val host: MainActivity) {

    private val act = host
    private val prefs = AppPrefs.get(host)

    val view: FrameLayout = FrameLayout(act)

    private val lobby: View = buildLobby()
    private val live: FrameLayout = FrameLayout(act)

    private val map: HybridMapView = HybridMapView(act)
    private val topBar: LinearLayout = Ui.column(act)
    private val demoBanner: TextView
    private val statusPill: TextView
    private val speedPill: TextView
    private val sourceBadge: TextView
    private val attribution: TextView
    private val codeChip: TextView
    private val chipsRow: LinearLayout = Ui.row(act)
    private val styleButton: FrameLayout
    private val chatButton: FrameLayout
    private val chatBadge: TextView
    private val backgroundHint: TextView
    private val memberCard: LinearLayout
    private val memberCardDot: View
    private val memberCardName: TextView
    private val memberCardMeta: TextView
    private val actionsRow: LinearLayout
    private val bottomBar: LinearLayout
    private val meetingButton: LinearLayout
    private val pickHint: TextView
    private val pickBar: LinearLayout
    private val emptyHint: TextView

    private var selectedId: String? = null
    private var signature = ""
    private var pickMode = false

    init {
        // ---------------------------------------------------------- 방이 없을 때
        view.addView(lobby, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ------------------------------------------------------------- 지도
        live.visibility = View.GONE
        view.addView(live, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        live.addView(map, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        map.onMemberTap = { member -> select(member) }
        map.onFollowChanged = { follow ->
            if (follow) select(null)
        }
        // 지도를 길게 누르면 그 자리에 약속 장소를 찍는다 (핀을 끌어 놓는 대신)
        map.onLongPress = { lat, lng -> pickMeetingAt(lat, lng) }
        map.onSourceChanged = { source -> showSource(source) }
        map.setMapStyle(MapStyle.fromId(prefs.mapStyle))

        // 위쪽: 데모 안내 · 상태 · 방 코드 · 멤버 칩
        demoBanner = Ui.pill(
            act,
            act.getString(R.string.map_demo_banner),
            act.getColor(R.color.glass_strong),
            act.getColor(R.color.warn),
            10.5f
        ).apply {
            gravity = Gravity.START
            setPadding(act.dp(10f), act.dp(6f), act.dp(10f), act.dp(6f))
            visibility = if (PinSession.demo) View.VISIBLE else View.GONE
        }
        topBar.setPadding(act.dp(12f), act.dp(14f), act.dp(12f), 0)
        live.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })
        topBar.addView(demoBanner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = act.dp(8f) })

        statusPill = Ui.glassPill(act, "", act.getColor(R.color.accent), 10.5f)        // 우상단: 밝은/어두운 지도 1탭 전환
        styleButton = FrameLayout(act).apply {
            background = Ui.glass(act)
            addView(
                Ui.icon(act, R.drawable.ic_layers, 17f, act.getColor(R.color.accent)),
                FrameLayout.LayoutParams(act.dp(17f), act.dp(17f)).apply { gravity = Gravity.CENTER }
            )
            contentDescription = act.getString(R.string.map_style_toggle)
            Ui.clickable(this) { toggleMapStyle() }
        }

        codeChip = Ui.glassPill(act, "", act.getColor(R.color.platinum), 11f).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.14f
            setPadding(act.dp(11f), act.dp(5f), act.dp(11f), act.dp(5f))
            Ui.clickable(this) { host.copyRoomCode() }
        }

        // 방 채팅 바로가기 (v1.0.6) — 읽지 않은 메시지 수를 배지로 얹는다
        chatBadge = Ui.text(act, "", 8.5f, act.getColor(R.color.on_accent), bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = act.dpf(999f)
                setColor(act.getColor(R.color.accent))
            }
            minWidth = act.dp(15f)
            setPadding(act.dp(3f), 0, act.dp(3f), act.dp(1f))
            visibility = View.GONE
        }
        chatButton = FrameLayout(act).apply {
            background = Ui.glass(act)
            addView(
                Ui.icon(act, R.drawable.ic_chat, 17f, act.getColor(R.color.accent)),
                FrameLayout.LayoutParams(act.dp(17f), act.dp(17f)).apply { gravity = Gravity.CENTER }
            )
            addView(chatBadge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(15f)
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = act.dp(1f)
                rightMargin = act.dp(1f)
            })
            contentDescription = act.getString(R.string.chat_title)
            Ui.clickable(this) { host.openChatTab() }
        }
        // 내 이동 속도 — 서 있는 동안은 '정지'로 조용하게, 달리는 동안은 골드 숫자로
        speedPill = Ui.glassPill(act, act.getString(R.string.speed_idle), act.getColor(R.color.text_tertiary), 10.5f).apply {
            setPadding(act.dp(10f), act.dp(5f), act.dp(10f), act.dp(5f))
            visibility = View.GONE
        }
        // 우측 상단: 지금 떠 있는 지도 (NAVER / CARTO)
        sourceBadge = Ui.glassPill(act, MapSource.CARTO.label, act.getColor(R.color.text_secondary), 9.5f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(act.dp(9f), act.dp(4f), act.dp(9f), act.dp(4f))
            contentDescription = act.getString(R.string.map_source_badge)
        }
        val chipLine = Ui.row(act).apply {
            addView(statusPill)
            addView(codeChip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = act.dp(7f) })
            addView(speedPill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = act.dp(7f) })
            addView(View(act), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
            addView(chatButton, LinearLayout.LayoutParams(act.dp(34f), act.dp(34f)).apply { leftMargin = act.dp(6f) })
            addView(sourceBadge, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = act.dp(8f) })
            addView(styleButton, LinearLayout.LayoutParams(act.dp(34f), act.dp(34f)).apply { leftMargin = act.dp(6f) })
        }
        topBar.addView(chipLine, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val scroll = HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(chipsRow)
        }
        topBar.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(8f) })

        // 지도 저작권 표시 — CARTO 무료 티어 조건(© OpenStreetMap, © CARTO)과 네이버 지도 표시는 늘 보여야 한다
        attribution = Ui.text(act, cartoAttribution(), 9f, act.getColor(R.color.text_tertiary))
        topBar.addView(
            attribution,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = act.dp(4f) }
        )

        backgroundHint = Ui.pill(
            act,
            act.getString(R.string.map_local_only),
            act.getColor(R.color.glass_strong),
            act.getColor(R.color.warn),
            10.5f
        ).apply {
            gravity = Gravity.START
            setPadding(act.dp(10f), act.dp(6f), act.dp(10f), act.dp(6f))
            visibility = View.GONE
        }
        topBar.addView(backgroundHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(8f) })

        // 아래쪽: 선택한 멤버 카드 + 버튼 줄
        bottomBar = Ui.column(act).apply {
            setPadding(act.dp(12f), 0, act.dp(12f), act.dp(14f))
        }
        live.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        memberCard = Ui.row(act).apply {
            background = Ui.glass(act, 20f, strong = true)
            setPadding(act.dp(14f), act.dp(12f), act.dp(10f), act.dp(12f))
            visibility = View.GONE
            Ui.clickable(this) { selectedId?.let { id -> PinSession.members.firstOrNull { it.id == id }?.let { focusOn(it) } } }
        }
        memberCardDot = View(act).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E5C158"))
            }
        }
        memberCard.addView(memberCardDot, LinearLayout.LayoutParams(act.dp(10f), act.dp(10f)).apply {
            rightMargin = act.dp(10f)
        })
        val memberTexts = Ui.column(act)
        memberCardName = Ui.text(act, "", 14f, act.getColor(R.color.text_primary), bold = true)
        memberCardMeta = Ui.text(act, "", 11.5f, act.getColor(R.color.text_secondary))
        memberTexts.addView(memberCardName)
        memberTexts.addView(memberCardMeta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(2f) })
        memberCard.addView(memberTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        val close = Ui.icon(act, R.drawable.ic_close, 16f, act.getColor(R.color.text_tertiary)).apply {
            contentDescription = act.getString(R.string.cancel)
        }
        memberCard.addView(close, LinearLayout.LayoutParams(act.dp(28f), act.dp(28f)))
        Ui.clickable(close) { select(null) }
        bottomBar.addView(memberCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = act.dp(12f) })

        actionsRow = Ui.row(act)
        bottomBar.addView(actionsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        meetingButton = actionPill(R.drawable.ic_place, act.getString(R.string.btn_meeting_pin)) {
            startPick()
        }
        actionsRow.addView(meetingButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(46f)
        ))
        val spacer = View(act)
        actionsRow.addView(spacer, LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        val recenter = circleButton(R.drawable.ic_my_location, act.getString(R.string.map_recenter)) {
            map.setFollow(true)
            map.animateTo(
                PinSession.myFix?.lat ?: HybridMapView.DEFAULT_LAT,
                PinSession.myFix?.lng ?: HybridMapView.DEFAULT_LNG,
                HybridMapView.DEFAULT_ZOOM
            )
        }
        actionsRow.addView(recenter, LinearLayout.LayoutParams(act.dp(46f), act.dp(46f)))

        emptyHint = Ui.pill(act, act.getString(R.string.map_no_fix), act.getColor(R.color.glass_strong), act.getColor(R.color.platinum), 11f)
        live.addView(emptyHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = act.dp(60f)
        })

        // 약속 장소 고르기 모드
        pickHint = Ui.pill(act, act.getString(R.string.pick_mode_title), act.getColor(R.color.glass_strong), act.getColor(R.color.text_primary), 11.5f).apply {
            gravity = Gravity.CENTER
            setPadding(act.dp(12f), act.dp(8f), act.dp(12f), act.dp(8f))
            visibility = View.GONE
        }
        live.addView(pickHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = act.dp(64f)
        })

        pickBar = Ui.row(act).apply {
            setPadding(act.dp(12f), 0, act.dp(12f), act.dp(16f))
            visibility = View.GONE
        }
        live.addView(pickBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        val cancelPick = Ui.button(act, act.getString(R.string.cancel), primary = false) { cancelPick() }
        val setPick = Ui.button(act, act.getString(R.string.pick_mode_action), primary = true, iconRes = R.drawable.ic_place) {
            confirmPick()
        }
        pickBar.addView(cancelPick, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
            rightMargin = act.dp(8f)
        })
        pickBar.addView(setPick, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1.2f })

        showSource(map.source)
    }

    // ------------------------------------------------------------------ 화면 갱신

    fun refresh() {
        if (pickMode && !PinSession.hasRoom()) cancelPick()
        val hasRoom = PinSession.hasRoom()
        lobby.visibility = if (hasRoom) View.GONE else View.VISIBLE
        live.visibility = if (hasRoom) View.VISIBLE else View.GONE
        if (!hasRoom) return

        val now = System.currentTimeMillis()
        val members = PinSession.members
        val myFix = PinSession.myFix

        map.setMembers(members)
        map.setMyLocation(myFix?.lat, myFix?.lng)
        map.setMeeting(PinSession.meeting)
        emptyHint.visibility = if (myFix == null) View.VISIBLE else View.GONE

        val sharing = PinSession.isSharingNow()
        backgroundHint.visibility =
            if (sharing && !PinSession.hasBackgroundPermission()) View.VISIBLE else View.GONE
        statusPill.text = act.getString(if (sharing) R.string.status_sharing else R.string.status_hidden)
        statusPill.setTextColor(act.getColor(if (sharing) R.color.accent else R.color.text_secondary))
        statusPill.background = GradientDrawable().apply {
            cornerRadius = act.dpf(999f)
            setColor(act.getColor(if (sharing) R.color.glass_strong else R.color.glass))
            setStroke(act.dp(1f), act.getColor(if (sharing) R.color.glass_stroke else R.color.outline))
        }
        codeChip.text = PinSession.roomCode

        val unread = PinSession.chatUnread
        chatBadge.visibility = if (unread > 0) View.VISIBLE else View.GONE
        if (unread > 0) chatBadge.text = if (unread > 99) "99+" else unread.toString()

        // 내 속도 — 서 있는 동안은 '몇 분째 머무는지'로 바뀐다
        val mySpeed = PinLogic.speedLabelValue(PinSession.mySpeedKmh)
        if (myFix == null) {
            speedPill.visibility = View.GONE
        } else {
            speedPill.visibility = View.VISIBLE
            if (mySpeed > 0) {
                speedPill.text = act.getString(R.string.speed_kmh, mySpeed)
                speedPill.setTextColor(act.getColor(R.color.accent))
            } else {
                speedPill.text = stayText(act, PinSession.myStaySince, System.currentTimeMillis(), short = true)
                speedPill.setTextColor(act.getColor(R.color.text_secondary))
            }
        }

        val newSignature = buildString {
            append(PinSession.roomCode).append('|')
            append(PinSession.mode.id).append('|')
            append(members.joinToString { it.id + it.name + it.color })
            append('|')
            append(PinSession.meeting?.let { "${it.lat},${it.lng},${it.name}" } ?: "-")
            append('|')
            append(PinSession.myName)
        }
        if (newSignature != signature) {
            signature = newSignature
            rebuildChips(members)
        }

        // 선택한 멤버 카드 (약속 장소를 고르는 동안에는 숨긴다)
        val selected = selectedId?.let { id -> members.firstOrNull { it.id == id } }
        if (pickMode) {
            memberCard.visibility = View.GONE
        } else if (selected == null) {
            memberCard.visibility = View.GONE
            if (selectedId != null) {
                selectedId = null
                map.selectMember(null)
            }
        } else {
            memberCard.visibility = View.VISIBLE
            memberCardDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(runCatching { Color.parseColor(selected.color) }.getOrDefault(Color.GRAY))
            }
            memberCardName.text = selected.name
            memberCardMeta.text = metaLine(selected, myFix?.lat, myFix?.lng, now)
        }

        // 멤버 칩 강조
        for (i in 0 until chipsRow.childCount) {
            val chip = chipsRow.getChildAt(i) as? TextView ?: continue
            val id = chip.tag as? String
            chip.alpha = if (selectedId == null || selectedId == id) 1f else 0.55f
        }
    }

    private fun metaLine(member: Member, myLat: Double?, myLng: Double?, now: Long): String {
        val parts = ArrayList<String>()
        parts.add(lastUpdateText(member.updatedAt, now))
        if (myLat != null && myLng != null) {
            parts.add(PinLogic.formatDistance(PinLogic.distanceMeters(myLat, myLng, member.lat, member.lng)))
        }
        // 이동 중이면 속도, 서 있으면 '몇 분째 머무는지'
        val speed = PinLogic.speedLabelValue(member.speedKmh)
        if (speed > 0) parts.add(act.getString(R.string.speed_kmh, speed))
        else if (member.staySince > 0L) parts.add(stayText(act, member.staySince, now))
        if (member.battery in 0..100) parts.add(act.getString(R.string.member_battery, member.battery))
        parts.add(act.getString(if (member.moving) R.string.state_moving else R.string.state_stopped))
        return parts.joinToString(" · ")
    }

    private fun lastUpdateText(updatedAt: Long, now: Long): String {
        if (PinLogic.isStale(updatedAt, now, limitMs = 10 * 60_000L)) return act.getString(R.string.last_update_stale)
        val minutes = PinLogic.minutesAgo(updatedAt, now)
        return when {
            minutes < 1 -> act.getString(R.string.last_update_now)
            minutes < 60 -> act.getString(R.string.last_update_min, minutes.toInt())
            else -> act.getString(R.string.last_update_hours, (minutes / 60).toInt())
        }
    }

    private fun rebuildChips(members: List<Member>) {
        chipsRow.removeAllViews()
        for (member in members) {
            val chip = Ui.pill(
                act,
                member.name,
                act.getColor(R.color.surface_high),
                act.getColor(R.color.text_primary)
            ).apply {
                tag = member.id
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(runCatching { Color.parseColor(member.color) }.getOrDefault(Color.GRAY))
                    setSize(act.dp(8f), act.dp(8f))
                }
                setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                compoundDrawablePadding = act.dp(6f)
                setPadding(act.dp(10f), act.dp(5f), act.dp(11f), act.dp(5f))
                Ui.clickable(this) { focusOn(member) }
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = act.dp(6f) })
        }
    }

    // --------------------------------------------------------------- 선택·이동

    private fun select(member: Member?) {
        selectedId = member?.id
        map.selectMember(member?.id)
        if (member != null) focusOn(member)
        refresh()
    }

    private fun focusOn(member: Member) {
        map.setFollow(false)
        map.selectMember(member.id)
        map.animateTo(member.lat, member.lng, 16f)
        selectedId = member.id
        refresh()
    }

    // --------------------------------------------------------------- 지도 스타일

    /** 밝은/어두운 지도 1탭 전환 — 고른 스타일은 다음 실행에도 이어진다 */
    private fun toggleMapStyle() {
        val next = if (map.mapStyle == MapStyle.DARK) MapStyle.LIGHT else MapStyle.DARK
        map.setMapStyle(next)
        prefs.mapStyle = next.id
    }

    // ------------------------------------------------------------- 지도 제공자

    /** 지금 떠 있는 지도에 맞춰 우측 상단 배지와 저작권 표시를 바꾼다 */
    private fun showSource(source: MapSource) {
        val naverOn = source == MapSource.NAVER
        sourceBadge.text = source.label
        sourceBadge.setTextColor(act.getColor(if (naverOn) R.color.sky else R.color.text_secondary))
        sourceBadge.background = GradientDrawable().apply {
            cornerRadius = act.dpf(999f)
            setColor(act.getColor(R.color.glass))
            setStroke(act.dp(1f), act.getColor(if (naverOn) R.color.sky else R.color.outline))
        }
        attribution.text = if (naverOn) act.getString(R.string.map_attribution_naver) else cartoAttribution()
        // 네이버 지도는 SDK 가 자기 로고·저작권을 직접 그린다 — 앱이 한 번 더 쓰면
        // 상단 칩(공유 중·코드)과 글자가 겹쳐 보이므로 네이버일 때는 앱 쪽 문구를 감춘다
        attribution.visibility = if (naverOn) View.GONE else View.VISIBLE
        // 네이버 지도는 아래 왼쪽에 로고를 그린다 — 버튼에 가리지 않게 아래 여백을 조금 더 준다
        bottomBar.setPadding(act.dp(12f), 0, act.dp(12f), act.dp(if (naverOn) 34f else 14f))
    }

    private fun cartoAttribution(): String =
        if (MapTileProvider.USES_CARTO) "© OpenStreetMap contributors · © CARTO"
        else "© OpenStreetMap contributors"

    /** 길게 누른 자리에 약속 장소를 찍는다 */
    private fun pickMeetingAt(lat: Double, lng: Double) {
        if (!PinSession.hasRoom() || pickMode) return
        Dialogs.showMeetingName(act) { name ->
            val point = MeetingPoint(lat, lng, name, PinSession.myName, System.currentTimeMillis())
            PinSession.setMeeting(point) { fault ->
                if (fault == null) host.toast(act.getString(R.string.toast_meeting_set))
                else host.toastFault(act.getString(R.string.err_meeting_save), fault)
                refresh()
            }
        }
    }

    // ---------------------------------------------------------- 약속 장소 고르기

    fun startPick() {
        if (!PinSession.hasRoom()) {
            host.toast(act.getString(R.string.room_empty))
            return
        }
        pickMode = true
        map.setPickMode(true)
        pickHint.visibility = View.VISIBLE
        pickBar.visibility = View.VISIBLE
        actionsRow.visibility = View.GONE
        memberCard.visibility = View.GONE
        topBar.visibility = View.GONE
        if (PinSession.meeting != null) {
            map.animateTo(PinSession.meeting!!.lat, PinSession.meeting!!.lng, 16f)
        }
    }

    fun cancelPick() {
        pickMode = false
        map.setPickMode(false)
        pickHint.visibility = View.GONE
        pickBar.visibility = View.GONE
        actionsRow.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        refresh()
    }

    private fun confirmPick() {
        val lat = map.centerLat
        val lng = map.centerLng
        cancelPick()
        Dialogs.showMeetingName(act) { name ->
            val point = MeetingPoint(lat, lng, name, PinSession.myName, System.currentTimeMillis())
            PinSession.setMeeting(point) { fault ->
                if (fault == null) host.toast(act.getString(R.string.toast_meeting_set))
                else host.toastFault(act.getString(R.string.err_meeting_save), fault)
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ 시작 카드

    private fun buildLobby(): View {
        val scroll = NestedScrollView(act).apply { isFillViewport = true; clipToPadding = false }
        val column = Ui.column(act).apply {
            setPadding(act.dp(18f), act.dp(20f), act.dp(18f), act.dp(24f))
        }
        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 히어로 카드
        val hero = Ui.column(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                act.getColor(R.color.hero_start),
                act.getColor(R.color.hero_end)
            )).apply {
                cornerRadius = act.dpf(26f)
                setStroke(act.dp(1f), act.getColor(R.color.accent_soft))
            }
            setPadding(act.dp(20f), act.dp(20f), act.dp(20f), act.dp(20f))
        }
        column.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        hero.addView(
            Ui.pill(act, act.getString(R.string.lobby_badge), act.getColor(R.color.accent_dim), act.getColor(R.color.accent), 9.5f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val art = LinearLayout(act).apply {
            gravity = Gravity.CENTER
            addView(Ui.icon(act, R.drawable.ic_place, 46f, act.getColor(R.color.accent)), LinearLayout.LayoutParams(act.dp(46f), act.dp(46f)))
        }
        hero.addView(art, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })

        hero.addView(
            Ui.text(act, act.getString(R.string.lobby_hero_title), 23f, act.getColor(R.color.text_primary), bold = true, letterSpacing = -0.01f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = act.dp(10f)
            }
        )
        hero.addView(
            Ui.text(act, act.getString(R.string.lobby_hero_sub), 12.5f, act.getColor(R.color.text_secondary)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = act.dp(8f)
            }
        )

        val create = Ui.button(act, act.getString(R.string.btn_create_room), primary = true, iconRes = R.drawable.ic_add) {
            host.startCreateRoom()
        }
        hero.addView(create, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(18f) })

        val join = Ui.button(act, act.getString(R.string.btn_join_room), primary = false, iconRes = R.drawable.ic_group) {
            host.startJoinRoom()
        }
        hero.addView(join, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(8f) })

        // 사용 방법
        val steps = Ui.column(act).apply {
            background = Ui.rounded(act, act.getColor(R.color.surface_alt), 22f, act.getColor(R.color.outline), ripple = false)
            setPadding(act.dp(18f), act.dp(16f), act.dp(18f), act.dp(16f))
        }
        column.addView(steps, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(14f) })

        steps.addView(
            Ui.sectionTitle(act, act.getString(R.string.lobby_tip_title)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val tips = listOf(R.string.lobby_tip_1, R.string.lobby_tip_2, R.string.lobby_tip_3)
        tips.forEachIndexed { index, tip ->
            val row = Ui.row(act).apply {
                addView(
                    Ui.text(act, "${index + 1}", 11f, act.getColor(R.color.on_accent), bold = true).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(act.getColor(R.color.accent))
                        }
                    },
                    LinearLayout.LayoutParams(act.dp(20f), act.dp(20f)).apply { rightMargin = act.dp(10f) }
                )
                addView(
                    Ui.text(act, act.getString(tip), 12.5f, act.getColor(R.color.text_secondary)),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
                )
            }
            steps.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = act.dp(12f) })
        }

        val note = Ui.row(act).apply {
            addView(Ui.icon(act, R.drawable.ic_shield, 14f, act.getColor(R.color.text_tertiary)), LinearLayout.LayoutParams(act.dp(14f), act.dp(14f)).apply {
                rightMargin = act.dp(7f)
            })
            addView(
                Ui.text(act, act.getString(R.string.lobby_always_note), 11f, act.getColor(R.color.text_tertiary)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            )
        }
        column.addView(note, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(14f) })

        return scroll
    }

    // ------------------------------------------------------------------ 작은 부품

    private fun actionPill(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout =
        Ui.row(act).apply {
            background = Ui.glass(act)
            setPadding(act.dp(14f), 0, act.dp(16f), 0)
            addView(Ui.icon(act, iconRes, 18f, act.getColor(R.color.accent)), LinearLayout.LayoutParams(act.dp(18f), act.dp(18f)).apply {
                rightMargin = act.dp(7f)
            })
            addView(Ui.text(act, label, 12.5f, act.getColor(R.color.text_primary), bold = true))
            Ui.clickable(this) { onClick() }
        }

    private fun circleButton(iconRes: Int, description: String, onClick: () -> Unit): FrameLayout =
        FrameLayout(act).apply {
            background = Ui.glass(act)
            addView(
                Ui.icon(act, iconRes, 21f, act.getColor(R.color.accent)).apply { contentDescription = description },
                FrameLayout.LayoutParams(act.dp(21f), act.dp(21f)).apply { gravity = Gravity.CENTER }
            )
            Ui.clickable(this) { onClick() }
        }
}

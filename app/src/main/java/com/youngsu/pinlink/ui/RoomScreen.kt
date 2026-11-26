package com.youngsu.pinlink.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.youngsu.pinlink.R
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.core.ShareMode
import com.youngsu.pinlink.data.PinSession

/**
 * 방 화면 — 6자리 코드 공유, 공유 시간 선택, 멤버 목록, 약속 장소, 방 나가기.
 */
class RoomScreen(private val host: MainActivity) {

    private val act = host

    val view: View

    private val column: LinearLayout
    private val emptyCard: LinearLayout
    private val codeCard: LinearLayout
    private val codeText: TextView
    private val sharingCard: LinearLayout
    private val sharingStatus: TextView
    private val modeRows = LinkedHashMap<ShareMode, LinearLayout>()
    private val modeTrailing = LinkedHashMap<ShareMode, TextView>()
    private val modeDots = LinkedHashMap<ShareMode, View>()
    private val membersCard: LinearLayout
    private val membersTitle: TextView
    private val membersBox: LinearLayout
    private val meetingCard: LinearLayout
    private val meetingName: TextView
    private val meetingBy: TextView
    private val clearMeeting: TextView
    private val leaveCard: LinearLayout

    private var signature = ""

    init {
        val scroll = NestedScrollView(act).apply { isFillViewport = true; clipToPadding = false }
        view = scroll
        column = Ui.column(act).apply {
            setPadding(act.dp(16f), act.dp(18f), act.dp(16f), act.dp(24f))
        }
        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        column.addView(
            Ui.text(act, act.getString(R.string.tab_room), 22f, act.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 방이 없을 때
        emptyCard = card()
        emptyCard.addView(
            Ui.text(act, act.getString(R.string.room_empty), 13f, act.getColor(R.color.text_secondary)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val emptyButtons = Ui.row(act)
        emptyButtons.addView(
            Ui.button(act, act.getString(R.string.btn_create_room), primary = true) { host.startCreateRoom() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                rightMargin = act.dp(8f)
            }
        )
        emptyButtons.addView(
            Ui.button(act, act.getString(R.string.btn_join_room), primary = false) { host.startJoinRoom() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )
        emptyCard.addView(emptyButtons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(14f) })
        column.addView(emptyCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(14f) })

        // 방 코드 카드
        codeCard = card()
        codeCard.addView(
            Ui.sectionTitle(act, act.getString(R.string.room_code_title)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        codeText = Ui.text(act, "------", 34f, act.getColor(R.color.accent), bold = true).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setPadding(0, act.dp(10f), 0, act.dp(2f))
        }
        codeCard.addView(codeText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        codeCard.addView(
            Ui.text(act, act.getString(R.string.room_code_sub), 11.5f, act.getColor(R.color.text_tertiary)).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val codeButtons = Ui.row(act)
        codeButtons.addView(
            Ui.button(act, act.getString(R.string.btn_copy), primary = false, iconRes = R.drawable.ic_copy) {
                host.copyRoomCode()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                rightMargin = act.dp(8f)
            }
        )
        codeButtons.addView(
            Ui.button(act, act.getString(R.string.btn_share), primary = true, iconRes = R.drawable.ic_share) {
                host.shareRoomCode()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )
        codeCard.addView(codeButtons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(14f) })
        column.addView(codeCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })

        // 공유 시간 카드
        sharingCard = card()
        sharingCard.addView(
            Ui.sectionTitle(act, act.getString(R.string.section_share_mode)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        sharingStatus = Ui.text(act, "", 12.5f, act.getColor(R.color.accent), bold = true)
        sharingCard.addView(sharingStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(8f) })

        addModeRow(sharingCard, ShareMode.ALWAYS, R.string.share_always, R.string.share_always_desc)
        addModeRow(sharingCard, ShareMode.HOUR_1, R.string.share_1h, 0)
        addModeRow(sharingCard, ShareMode.HOUR_3, R.string.share_3h, 0)
        addModeRow(sharingCard, ShareMode.HOUR_8, R.string.share_8h, 0)
        addModeRow(sharingCard, ShareMode.HOUR_24, R.string.share_24h, 0)
        addModeRow(sharingCard, ShareMode.PAUSED, R.string.share_paused, R.string.share_paused_desc)
        column.addView(sharingCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })

        // 멤버 카드
        membersCard = card()
        membersTitle = Ui.sectionTitle(act, act.getString(R.string.members_title))
        membersCard.addView(membersTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        membersBox = Ui.column(act)
        membersCard.addView(membersBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(6f) })
        column.addView(membersCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })

        // 약속 장소 카드
        meetingCard = card()
        meetingCard.addView(
            Ui.sectionTitle(act, act.getString(R.string.section_meeting)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        meetingName = Ui.text(act, "", 15f, act.getColor(R.color.text_primary), bold = true)
        meetingCard.addView(meetingName, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(8f) })
        meetingBy = Ui.text(act, "", 11.5f, act.getColor(R.color.text_tertiary))
        meetingCard.addView(meetingBy, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(3f) })

        val meetingButtons = Ui.row(act)
        meetingButtons.addView(
            Ui.button(act, act.getString(R.string.btn_pick_meeting), primary = false, iconRes = R.drawable.ic_place) {
                host.openMeetingPicker()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                rightMargin = act.dp(8f)
            }
        )
        clearMeeting = Ui.button(act, act.getString(R.string.btn_clear_meeting), primary = false, iconRes = R.drawable.ic_close) {
            PinSession.setMeeting(null) { fault ->
                if (fault == null) host.toast(act.getString(R.string.toast_meeting_cleared))
                else host.toastFault(act.getString(R.string.err_meeting_save), fault)
            }
        }
        meetingButtons.addView(clearMeeting, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        meetingCard.addView(meetingButtons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(14f) })
        column.addView(meetingCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })

        // 나가기
        leaveCard = card()
        val leave = Ui.row(act).apply {
            background = Ui.rounded(act, act.getColor(R.color.danger_dim), 16f, ripple = true)
            setPadding(act.dp(14f), act.dp(14f), act.dp(14f), act.dp(14f))
            addView(Ui.icon(act, R.drawable.ic_exit, 18f, act.getColor(R.color.danger)), LinearLayout.LayoutParams(act.dp(18f), act.dp(18f)).apply {
                rightMargin = act.dp(9f)
            })
            addView(Ui.text(act, act.getString(R.string.btn_leave_room), 13.5f, act.getColor(R.color.danger), bold = true))
            Ui.clickable(this) { host.confirmLeaveRoom() }
        }
        leaveCard.addView(leave, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(leaveCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(12f) })
    }

    // ------------------------------------------------------------------ 화면 갱신

    fun refresh() {
        val hasRoom = PinSession.hasRoom()
        emptyCard.visibility = if (hasRoom) View.GONE else View.VISIBLE
        codeCard.visibility = if (hasRoom) View.VISIBLE else View.GONE
        sharingCard.visibility = if (hasRoom) View.VISIBLE else View.GONE
        membersCard.visibility = if (hasRoom) View.VISIBLE else View.GONE
        meetingCard.visibility = if (hasRoom) View.VISIBLE else View.GONE
        leaveCard.visibility = if (hasRoom) View.VISIBLE else View.GONE
        if (!hasRoom) return

        codeText.text = PinSession.roomCode

        val now = System.currentTimeMillis()
        val mode = PinSession.mode
        val sharing = PinSession.isSharingNow()
        val remaining = PinLogic.remainingMillis(PinSession.expiresAt, now)
        sharingStatus.text = when {
            !mode.isSharing -> act.getString(R.string.sharing_paused)
            remaining < 0 -> act.getString(R.string.share_expired)
            remaining == 0L -> act.getString(R.string.share_no_expiry)
            else -> act.getString(R.string.share_expires, PinLogic.formatDuration(remaining))
        }
        sharingStatus.setTextColor(
            act.getColor(
                when {
                    !mode.isSharing -> R.color.text_secondary
                    remaining < 0 -> R.color.danger
                    else -> R.color.accent
                }
            )
        )

        for ((rowMode, row) in modeRows) {
            val active = rowMode == mode
            val dot = modeDots[rowMode]
            if (dot != null) {
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (active) act.getColor(R.color.accent) else Color.TRANSPARENT)
                    setStroke(act.dp(1.6f), act.getColor(if (active) R.color.accent else R.color.outline))
                }
                val dotDot = (dot as? FrameLayout)?.getChildAt(0)
                dotDot?.visibility = if (active) View.VISIBLE else View.GONE
            }
            row.background = Ui.rounded(
                act,
                if (active) act.getColor(R.color.accent_dim) else Color.TRANSPARENT,
                14f,
                ripple = true
            )
            modeTrailing[rowMode]?.text = if (active && rowMode.isSharing && remaining > 0) {
                PinLogic.formatDuration(remaining)
            } else {
                ""
            }
        }

        // 멤버 목록은 구성이 바뀔 때만 다시 그린다
        val newSignature = buildString {
            append(PinSession.roomCode).append('|')
            append(PinSession.myName).append(PinSession.myColor).append('|')
            append(PinSession.members.joinToString { it.id + it.name + it.color })
            append('|')
            append(PinSession.meeting?.let { "${it.name}${it.by}" } ?: "-")
            append('|')
            append(if (sharing) "1" else "0")
        }
        if (newSignature != signature) {
            signature = newSignature
            rebuildMembers(now)
            rebuildMeeting()
        } else {
            // 시간·배터리처럼 자주 바뀌는 글자만 갱신
            for (i in 0 until membersBox.childCount) {
                val row = membersBox.getChildAt(i)
                val id = row.tag as? String ?: continue
                val meta = row.findViewWithTag<TextView>("meta") ?: continue
                if (id == "__me__") {
                    meta.text = myMetaLine(now)
                } else {
                    val member = PinSession.members.firstOrNull { it.id == id } ?: continue
                    meta.text = buildString {
                        append(lastUpdateText(member.updatedAt, now))
                        val speed = PinLogic.speedLabelValue(member.speedKmh)
                        if (speed > 0) append(" · ").append(act.getString(R.string.speed_kmh, speed))
                        else if (member.staySince > 0L) append(" · ").append(stayText(act, member.staySince, now))
                        if (member.battery in 0..100) append(" · ").append(act.getString(R.string.member_battery, member.battery))
                    }
                }
            }
        }
        membersTitle.text = act.getString(
            R.string.members_count,
            PinSession.members.size + 1
        )
    }

    private fun myMetaLine(now: Long): String {
        val parts = ArrayList<String>()
        parts.add(act.getString(R.string.member_you))
        val speed = PinLogic.speedLabelValue(PinSession.mySpeedKmh)
        if (speed > 0) {
            parts.add(act.getString(R.string.speed_kmh, speed))
        } else if (PinSession.myStaySince > 0L) {
            parts.add(stayText(act, PinSession.myStaySince, now))
        }
        val battery = PinSession.battery
        if (battery in 0..100) parts.add(act.getString(R.string.member_battery, battery))
        parts.add(act.getString(if (PinSession.isSharingNow()) R.string.sharing_live else R.string.sharing_paused))
        return parts.joinToString(" · ")
    }

    private fun rebuildMembers(now: Long) {
        membersBox.removeAllViews()

        // 나
        val me = memberRow(
            name = PinSession.myName,
            color = PinSession.myColor,
            meta = myMetaLine(now),
            trailing = act.getString(if (PinSession.isSharingNow()) R.string.sharing_live else R.string.sharing_paused)
        )
        me.tag = "__me__"
        membersBox.addView(me, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(8f) })

        for (member in PinSession.members) {
            val meta = buildString {
                append(lastUpdateText(member.updatedAt, now))
                val speed = PinLogic.speedLabelValue(member.speedKmh)
                if (speed > 0) append(" · ").append(act.getString(R.string.speed_kmh, speed))
                else if (member.staySince > 0L) append(" · ").append(stayText(act, member.staySince, now))
                if (member.battery in 0..100) append(" · ").append(act.getString(R.string.member_battery, member.battery))
            }
            val speed = PinLogic.speedLabelValue(member.speedKmh)
            val row = memberRow(
                name = member.name,
                color = member.color,
                meta = meta,
                trailing = when {
                    speed > 0 -> act.getString(R.string.speed_kmh, speed)
                    member.staySince > 0L -> stayText(act, member.staySince, now, short = true)
                    else -> act.getString(R.string.state_stopped)
                }
            )
            row.tag = member.id
            membersBox.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = act.dp(8f) })
        }

        if (PinSession.members.isEmpty()) {
            membersBox.addView(
                Ui.text(act, act.getString(R.string.room_empty), 11.5f, act.getColor(R.color.text_tertiary)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = act.dp(10f)
                }
            )
        }
    }

    private fun memberRow(name: String, color: String, meta: String, trailing: String): LinearLayout {
        val row = Ui.row(act)
        val avatar = Ui.text(act, name.trim().firstOrNull()?.toString()?.uppercase() ?: "?", 13f, Color.parseColor("#0B0F19"), bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(runCatching { Color.parseColor(color) }.getOrDefault(Color.GRAY))
            }
        }
        row.addView(avatar, LinearLayout.LayoutParams(act.dp(34f), act.dp(34f)).apply { rightMargin = act.dp(11f) })

        val texts = Ui.column(act)
        texts.addView(Ui.text(act, name, 13.5f, act.getColor(R.color.text_primary), bold = true))
        texts.addView(
            Ui.text(act, meta, 11f, act.getColor(R.color.text_secondary)).apply { tag = "meta" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = act.dp(2f)
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        row.addView(Ui.text(act, trailing, 10.5f, act.getColor(R.color.text_tertiary), bold = true))
        return row
    }

    private fun rebuildMeeting() {
        val meeting = PinSession.meeting
        if (meeting == null) {
            meetingName.text = act.getString(R.string.meeting_none)
            meetingBy.text = ""
            clearMeeting.visibility = View.GONE
        } else {
            meetingName.text = meeting.name.ifBlank { act.getString(R.string.meeting_name_default) }
            meetingBy.text = act.getString(R.string.meeting_set_by, meeting.by.ifBlank { "-" })
            clearMeeting.visibility = View.VISIBLE
        }
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

    // ------------------------------------------------------------------ 부품

    private fun addModeRow(parent: LinearLayout, mode: ShareMode, labelRes: Int, descRes: Int) {
        val row = Ui.row(act).apply {
            setPadding(act.dp(10f), act.dp(10f), act.dp(10f), act.dp(10f))
            background = Ui.rounded(act, Color.TRANSPARENT, 14f, ripple = true)
        }
        val dot = FrameLayout(act)
        val inner = View(act).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(act.getColor(R.color.on_accent))
            }
        }
        dot.addView(inner, FrameLayout.LayoutParams(act.dp(6f), act.dp(6f)).apply { gravity = Gravity.CENTER })
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(act.dp(1.6f), act.getColor(R.color.outline))
        }
        row.addView(dot, LinearLayout.LayoutParams(act.dp(19f), act.dp(19f)).apply { rightMargin = act.dp(11f) })

        val texts = Ui.column(act)
        texts.addView(Ui.text(act, act.getString(labelRes), 13.5f, act.getColor(R.color.text_primary), bold = true))
        if (descRes != 0) {
            texts.addView(
                Ui.text(act, act.getString(descRes), 11f, act.getColor(R.color.text_tertiary)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = act.dp(2f)
                }
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        val trailing = Ui.text(act, "", 11.5f, act.getColor(R.color.accent), bold = true)
        row.addView(trailing)

        Ui.clickable(row) { host.requestShareMode(mode) }

        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(4f) })

        modeRows[mode] = row
        modeDots[mode] = dot
        modeTrailing[mode] = trailing
    }

    private fun card(): LinearLayout = Ui.column(act).apply {
        background = Ui.rounded(
            act,
            act.getColor(R.color.surface_alt),
            22f,
            act.getColor(R.color.outline),
            ripple = false
        )
        setPadding(act.dp(16f), act.dp(16f), act.dp(16f), act.dp(16f))
    }
}

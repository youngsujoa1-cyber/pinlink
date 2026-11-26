package com.youngsu.pinlink.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.youngsu.pinlink.R
import com.youngsu.pinlink.core.ChatMessage
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.data.PinSession

/**
 * 방 채팅 화면 (v1.0.6).
 *
 *  - 방에 들어와 있으면 그 방(`rooms/{code}/messages`)의 대화를 3초마다 확인한다(PinSession).
 *  - 내 말풍선은 오른쪽 골드, 남의 말풍선은 왼쪽 유리판 + 아바타 색 점.
 *  - 방이 없으면 방 만들기·참여 버튼을 보여 준다.
 */
class ChatScreen(private val host: MainActivity) {

    private val act = host

    val view: FrameLayout = FrameLayout(act)

    private val body: LinearLayout = Ui.column(act)
    private val noRoomCard: LinearLayout
    private val codeChip: TextView
    private val membersText: TextView
    private val scroll: ScrollView
    private val listBox: LinearLayout
    private val input: EditText
    private val send: FrameLayout
    private val counter: TextView

    private var signature = ""
    private var stickBottom = true

    init {
        // ------------------------------------------------------------ 방이 있을 때
        view.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val header = Ui.row(act).apply {
            setPadding(act.dp(16f), act.dp(16f), act.dp(16f), act.dp(8f))
        }
        header.addView(
            Ui.text(act, act.getString(R.string.chat_title), 20f, act.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )
        membersText = Ui.text(act, "", 11f, act.getColor(R.color.text_tertiary))
        header.addView(membersText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = act.dp(8f) })
        codeChip = Ui.glassPill(act, "", act.getColor(R.color.platinum), 10.5f).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(act.dp(9f), act.dp(4f), act.dp(9f), act.dp(4f))
            Ui.clickable(this) { host.copyRoomCode() }
        }
        header.addView(codeChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        body.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scroll = ScrollView(act).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        listBox = Ui.column(act).apply {
            setPadding(act.dp(14f), act.dp(4f), act.dp(14f), act.dp(10f))
        }
        scroll.addView(listBox, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            val distanceToBottom = child.height - scroll.height - scroll.scrollY
            stickBottom = distanceToBottom < act.dp(48f)
        }
        body.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0
        ).apply { weight = 1f })

        // 입력 줄 — 글자 수는 400자를 넘으면 보여 준다
        val inputRow = Ui.row(act).apply {
            setPadding(act.dp(12f), act.dp(6f), act.dp(12f), act.dp(12f))
        }
        counter = Ui.text(act, "", 10f, act.getColor(R.color.text_tertiary)).apply {
            visibility = View.GONE
        }
        input = EditText(act).apply {
            hint = act.getString(R.string.chat_hint)
            setHintTextColor(act.getColor(R.color.text_tertiary))
            setTextColor(act.getColor(R.color.text_primary))
            textSize = 14.5f
            maxLines = 4
            background = Ui.rounded(act, act.getColor(R.color.surface_high), 22f, act.getColor(R.color.outline))
            setPadding(act.dp(14f), act.dp(10f), act.dp(14f), act.dp(10f))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(PinLogic.CHAT_MAX_LENGTH))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val length = s?.length ?: 0
                    counter.text = act.getString(R.string.chat_counter, length, PinLogic.CHAT_MAX_LENGTH)
                    counter.visibility = if (length >= 400) View.VISIBLE else View.GONE
                    updateSendEnabled()
                }
            })
        }
        val inputColumn = Ui.column(act)
        inputColumn.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        inputColumn.addView(counter, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = act.dp(3f)
            leftMargin = act.dp(6f)
            gravity = Gravity.END
        })
        inputRow.addView(inputColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        send = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(act.getColor(R.color.accent))
            }
            addView(
                Ui.icon(act, R.drawable.ic_send, 20f, act.getColor(R.color.on_accent)),
                FrameLayout.LayoutParams(act.dp(20f), act.dp(20f)).apply { gravity = Gravity.CENTER }
            )
            contentDescription = act.getString(R.string.chat_send)
            alpha = 0.35f
            Ui.clickable(this) { submit() }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(act.dp(46f), act.dp(46f)).apply { leftMargin = act.dp(8f) })
        body.addView(inputRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ------------------------------------------------------------ 방이 없을 때
        noRoomCard = Ui.column(act).apply {
            background = Ui.rounded(act, act.getColor(R.color.surface_alt), 24f, act.getColor(R.color.outline), ripple = false)
            setPadding(act.dp(20f), act.dp(20f), act.dp(20f), act.dp(20f))
            addView(
                Ui.text(act, act.getString(R.string.chat_title), 17f, act.getColor(R.color.text_primary), bold = true)
            )
            addView(
                Ui.text(act, act.getString(R.string.chat_no_room), 12.5f, act.getColor(R.color.text_secondary)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = act.dp(6f) }
            )
            addView(
                Ui.button(act, act.getString(R.string.btn_create_room), primary = true, iconRes = R.drawable.ic_add) {
                    host.startCreateRoom()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = act.dp(16f) }
            )
            addView(
                Ui.button(act, act.getString(R.string.btn_join_room), primary = false, iconRes = R.drawable.ic_group) {
                    host.startJoinRoom()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = act.dp(8f) }
            )
        }
        view.addView(noRoomCard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = act.dp(20f)
            rightMargin = act.dp(20f)
        })

        noRoomCard.visibility = View.GONE
    }

    // ------------------------------------------------------------------ 화면 갱신

    fun refresh() {
        val hasRoom = PinSession.hasRoom()
        body.visibility = if (hasRoom) View.VISIBLE else View.GONE
        noRoomCard.visibility = if (hasRoom) View.GONE else View.VISIBLE
        if (!hasRoom) return

        codeChip.text = PinSession.roomCode
        membersText.text = act.getString(R.string.chat_members, PinSession.members.size + 1)
        updateSendEnabled()

        // 대화가 적을 때도 입력줄 바로 위에 붙게 (스크롤 영역 높이만큼 최소 높이를 잡고 아래로 정렬)
        if (scroll.height > 0 && listBox.minimumHeight != scroll.height) {
            listBox.minimumHeight = scroll.height
            listBox.gravity = Gravity.BOTTOM
        }

        val messages = PinSession.messages
        val now = System.currentTimeMillis()
        val newSignature = buildString {
            append(PinSession.roomCode).append('|')
            append(PinSession.myName).append('|')
            for (message in messages) {
                append(message.id).append(':').append(message.text.length).append(',')
            }
            append('|')
            // '3분 전' 같은 시각 표시를 다시 그리지 않고도 날짜 구분선이 바뀌게 하루 단위만 넣는다
            append(now / 86_400_000L)
        }
        if (newSignature != signature) {
            signature = newSignature
            rebuild(messages, now)
        }
    }

    private fun rebuild(messages: List<ChatMessage>, now: Long) {
        listBox.removeAllViews()
        if (messages.isEmpty()) {
            listBox.addView(
                Ui.text(act, act.getString(R.string.chat_empty), 12.5f, act.getColor(R.color.text_tertiary)).apply {
                    gravity = Gravity.CENTER
                    setPadding(act.dp(8f), act.dp(28f), act.dp(8f), act.dp(28f))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }
        var lastDay = ""
        for (message in messages) {
            val day = PinLogic.chatDayLabel(message.createdAtMs, now)
            if (day != lastDay) {
                lastDay = day
                listBox.addView(dayDivider(day), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = act.dp(10f); bottomMargin = act.dp(2f) })
            }
            listBox.addView(
                bubbleRow(message),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = act.dp(8f) }
            )
        }
        if (stickBottom) scroll.post { runCatching { scroll.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun dayDivider(day: String): TextView {
        val label = when (day) {
            "TODAY" -> act.getString(R.string.chat_today)
            "YESTERDAY" -> act.getString(R.string.chat_yesterday)
            else -> day
        }
        return Ui.text(act, label, 10.5f, act.getColor(R.color.text_tertiary)).apply {
            gravity = Gravity.CENTER
        }
    }

    /** 말풍선 한 줄 — 내 것은 오른쪽, 남의 것은 아바타와 함께 왼쪽 */
    private fun bubbleRow(message: ChatMessage): LinearLayout {
        val row = Ui.row(act).apply {
            gravity = if (message.mine) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            // 말풍선이 화면을 다 차지하지 않게 왼쪽(또는 오른쪽)에 여백을 준다
            setPadding(if (message.mine) act.dp(56f) else 0, 0, if (message.mine) 0 else act.dp(40f), 0)
        }
        val texts = Ui.column(act).apply {
            gravity = if (message.mine) Gravity.END else Gravity.START
        }

        if (!message.mine) {
            row.addView(avatar(message), LinearLayout.LayoutParams(act.dp(30f), act.dp(30f)).apply {
                rightMargin = act.dp(9f)
                gravity = Gravity.BOTTOM
            })
            texts.addView(
                Ui.text(act, message.senderName.ifBlank { "Guest" }, 10.5f, act.getColor(R.color.text_secondary), bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = act.dp(2f)
                }
            )
        }

        val bubble = Ui.text(act, message.text, 13.5f, act.getColor(if (message.mine) R.color.on_accent else R.color.text_primary)).apply {
            setPadding(act.dp(12f), act.dp(9f), act.dp(12f), act.dp(9f))
            background = GradientDrawable().apply {
                cornerRadius = act.dpf(16f)
                setColor(act.getColor(if (message.mine) R.color.accent else R.color.surface_high))
                if (!message.mine) setStroke(act.dp(1f), act.getColor(R.color.glass_stroke))
            }
            maxWidth = (act.resources.displayMetrics.widthPixels * 0.72f).toInt()
            setTextIsSelectable(true)
        }
        texts.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        texts.addView(
            Ui.text(act, PinLogic.chatClock(message.createdAtMs), 9.5f, act.getColor(R.color.text_tertiary)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = act.dp(3f)
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return row
    }

    private fun avatar(message: ChatMessage): TextView {
        val color = runCatching { Color.parseColor(message.senderColor) }.getOrDefault(act.getColor(R.color.accent))
        return Ui.text(
            act,
            message.senderName.trim().firstOrNull()?.toString()?.uppercase() ?: "?",
            12f,
            act.getColor(R.color.on_accent),
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    // -------------------------------------------------------------------- 보내기

    private fun updateSendEnabled() {
        val enabled = PinSession.hasRoom() && input.text.isNotBlank()
        send.alpha = if (enabled) 1f else 0.35f
    }

    private fun submit() {
        val text = input.text.toString()
        if (text.isBlank()) return
        if (!PinSession.hasRoom()) {
            host.toast(act.getString(R.string.room_empty))
            return
        }
        input.text.clear()
        stickBottom = true
        PinSession.sendChat(text) { fault ->
            if (fault != null) host.toastFault(act.getString(R.string.chat_failed), fault)
            refresh()
        }
        refresh()
    }
}

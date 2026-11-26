package com.youngsu.pinlink.ui

import android.graphics.Color
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
 * 지도 화면(지도 탭) 아래에 붙는 방 채팅창 (v1.0.7).
 *
 *  - 지도를 보면서 바로 대화를 읽고 보낼 수 있게 지도 영역 아래에 고정한다.
 *  - 방에 들어와 있을 때만 보이고, 헤더를 누르면 접었다(헤더만) 펼 수 있다.
 *  - 펼쳐져 있는 동안에는 새 메시지를 읽음으로 친다 — 채팅 탭 배지와 같은 상태를 쓴다.
 *  - 말풍선·디자인은 채팅 탭과 같은 톤(내 것은 골드, 남의 것은 유리판)이되 높이를 아끼려고 더 조밀하다.
 */
class MapChatPanel(private val host: MainActivity) {

    companion object {
        /** 화면 검사가 이 패널을 찾을 때 쓰는 태그 */
        const val TAG = "map-chat-panel"
    }

    private val act = host

    /** 지도 아래에 붙는 패널 — 세로: 헤더 / 대화 / 입력줄 */
    val view: LinearLayout = Ui.column(act)

    private val header: LinearLayout
    private val title: TextView
    private val people: TextView
    private val unreadPill: TextView
    private val toggle: TextView
    private val scroll: ScrollView
    private val listBox: LinearLayout
    private val input: EditText
    private val send: FrameLayout

    /** 펼친 상태인가 — 접으면 헤더만 남는다 */
    var expanded: Boolean = true
        private set

    /** 지금 이 패널이 보여 줄 자리인가 — 약속 장소를 고르는 동안에는 숨긴다(지도를 넓게) */
    var allowed: Boolean = true

    private var signature = ""
    private var stickBottom = true

    init {
        view.background = GradientDrawable().apply {
            // 위쪽 모서리만 둥글게 — 지도 위에 얹힌 카드처럼 보이게
            cornerRadii = floatArrayOf(
                act.dpf(22f), act.dpf(22f),
                act.dpf(22f), act.dpf(22f),
                0f, 0f,
                0f, 0f
            )
            setColor(act.getColor(R.color.surface_alt))
            setStroke(act.dp(1f), act.getColor(R.color.outline))
        }

        unreadPill = Ui.pill(act, "", act.getColor(R.color.accent), act.getColor(R.color.on_accent), 9f).apply {
            visibility = View.GONE
        }
        toggle = Ui.text(act, act.getString(R.string.chat_collapse), 10.5f, act.getColor(R.color.accent), bold = true).apply {
            setPadding(act.dp(8f), act.dp(4f), act.dp(4f), act.dp(4f))
            contentDescription = act.getString(R.string.chat_collapse)
        }
        title = Ui.text(act, act.getString(R.string.chat_title), 12.5f, act.getColor(R.color.text_primary), bold = true)
        people = Ui.text(act, "", 10f, act.getColor(R.color.text_tertiary))
        // '크게 보기' — 채팅 탭(전체 화면)으로 넘어간다
        val openFull = Ui.text(act, act.getString(R.string.chat_open_full), 9.5f, act.getColor(R.color.text_tertiary), bold = true).apply {
            setPadding(act.dp(6f), act.dp(4f), act.dp(6f), act.dp(4f))
            contentDescription = act.getString(R.string.chat_open_full)
            Ui.clickable(this) { host.openChatTab() }
        }

        header = Ui.row(act).apply {
            setPadding(act.dp(14f), act.dp(9f), act.dp(10f), act.dp(9f))
            addView(
                Ui.icon(act, R.drawable.ic_chat, 15f, act.getColor(R.color.accent)),
                LinearLayout.LayoutParams(act.dp(15f), act.dp(15f)).apply { rightMargin = act.dp(7f) }
            )
            addView(title)
            addView(people, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = act.dp(7f) })
            addView(unreadPill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = act.dp(7f) })
            addView(View(act), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
            addView(openFull, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = act.dp(2f) })
            addView(toggle)
            Ui.clickable(this) { setExpanded(!expanded) }
        }
        view.tag = TAG
        view.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scroll = ScrollView(act).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        listBox = Ui.column(act).apply {
            setPadding(act.dp(12f), act.dp(2f), act.dp(12f), act.dp(6f))
        }
        scroll.addView(listBox, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            stickBottom = child.height - scroll.height - scroll.scrollY < act.dp(48f)
        }
        view.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0
        ).apply { weight = 1f })

        // 입력줄 — 채팅 탭과 같은 모양이되 한 줄 높이로 조밀하게
        val inputRow = Ui.row(act).apply {
            setPadding(act.dp(10f), act.dp(2f), act.dp(10f), act.dp(10f))
        }
        input = EditText(act).apply {
            hint = act.getString(R.string.chat_hint)
            setHintTextColor(act.getColor(R.color.text_tertiary))
            setTextColor(act.getColor(R.color.text_primary))
            textSize = 13.5f
            maxLines = 2
            minHeight = act.dp(40f)
            background = Ui.rounded(act, act.getColor(R.color.surface_high), 20f, act.getColor(R.color.outline))
            setPadding(act.dp(13f), act.dp(9f), act.dp(13f), act.dp(9f))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(PinLogic.CHAT_MAX_LENGTH))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateSendEnabled()
            })
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        send = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(act.getColor(R.color.accent))
            }
            addView(
                Ui.icon(act, R.drawable.ic_send, 17f, act.getColor(R.color.on_accent)),
                FrameLayout.LayoutParams(act.dp(17f), act.dp(17f)).apply { gravity = Gravity.CENTER }
            )
            contentDescription = act.getString(R.string.chat_send)
            alpha = 0.35f
            Ui.clickable(this) { submit() }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(act.dp(40f), act.dp(40f)).apply { leftMargin = act.dp(8f) })
        view.addView(inputRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        applyHeight()
    }

    // ------------------------------------------------------------------ 접기/펼치기

    fun setExpanded(open: Boolean) {
        if (expanded == open) return
        expanded = open
        toggle.text = act.getString(if (open) R.string.chat_collapse else R.string.chat_expand)
        toggle.contentDescription = toggle.text
        scroll.visibility = if (open) View.VISIBLE else View.GONE
        input.visibility = if (open) View.VISIBLE else View.GONE
        (input.parent as? View)?.visibility = if (open) View.VISIBLE else View.GONE
        applyHeight()
        refresh()
    }

    /**
     * 펼쳤을 때의 높이 — 화면의 약 1/3. 작은 화면에서도 지도·하단 탭·배너가 함께 보이도록
     * 170~300dp 사이로 제한한다.
     */
    private fun applyHeight() {
        val screen = act.resources.displayMetrics.heightPixels
        val wanted = (screen * 0.33f).toInt()
        val height = if (expanded) wanted.coerceIn(act.dp(178f), act.dp(300f)) else ViewGroup.LayoutParams.WRAP_CONTENT
        view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        view.requestLayout()
    }

    // ------------------------------------------------------------------ 화면 갱신

    fun refresh() {
        val hasRoom = PinSession.hasRoom()
        val show = hasRoom && allowed
        view.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            // 방이 없거나 약속 장소를 고르는 중 — 화면 아래 채팅창이 없으니 '읽음' 상태도 내려 둔다
            if (PinSession.inlineChatOpen) PinSession.setInlineChatOpen(false)
            return
        }

        val open = expanded && PinSession.uiVisibleNow
        PinSession.setInlineChatOpen(open)
        if (open) PinSession.markChatSeen()

        people.text = act.getString(R.string.chat_members, PinSession.members.size + 1)
        val unread = if (open) 0 else PinSession.chatUnread
        unreadPill.visibility = if (unread > 0) View.VISIBLE else View.GONE
        if (unread > 0) unreadPill.text = act.getString(R.string.chat_unread, unread)

        updateSendEnabled()
        if (!expanded) return

        if (scroll.height > 0 && listBox.minimumHeight != scroll.height) {
            listBox.minimumHeight = scroll.height
            listBox.gravity = Gravity.BOTTOM
        }

        val messages = PinSession.messages
        val now = System.currentTimeMillis()
        val newSignature = buildString {
            append(PinSession.roomCode).append('|')
            append(PinSession.myName).append('|')
            for (message in messages) append(message.id).append(':').append(message.text.length).append(',')
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
                Ui.text(act, act.getString(R.string.chat_empty), 11.5f, act.getColor(R.color.text_tertiary)).apply {
                    gravity = Gravity.CENTER
                    setPadding(act.dp(6f), act.dp(18f), act.dp(6f), act.dp(18f))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }
        // 자리가 좁으니 최근 30건만
        for (message in messages.takeLast(30)) {
            listBox.addView(
                bubbleRow(message),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = act.dp(5f) }
            )
        }
        if (stickBottom) scroll.post { runCatching { scroll.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun bubbleRow(message: ChatMessage): LinearLayout {
        val row = Ui.row(act).apply {
            gravity = if (message.mine) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(if (message.mine) act.dp(44f) else 0, 0, if (message.mine) 0 else act.dp(30f), 0)
        }
        val texts = Ui.column(act).apply {
            gravity = if (message.mine) Gravity.END else Gravity.START
        }
        if (!message.mine) {
            row.addView(avatar(message), LinearLayout.LayoutParams(act.dp(22f), act.dp(22f)).apply {
                rightMargin = act.dp(7f)
                gravity = Gravity.BOTTOM
            })
            texts.addView(
                Ui.text(
                    act,
                    "${message.senderName.ifBlank { "Guest" }} · ${PinLogic.chatClock(message.createdAtMs)}",
                    8.5f,
                    act.getColor(R.color.text_tertiary),
                    bold = true
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = act.dp(2f) }
            )
        }
        val bubble = Ui.text(act, message.text, 12.5f, act.getColor(if (message.mine) R.color.on_accent else R.color.text_primary)).apply {
            setPadding(act.dp(10f), act.dp(7f), act.dp(10f), act.dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = act.dpf(14f)
                setColor(act.getColor(if (message.mine) R.color.accent else R.color.surface_high))
                if (!message.mine) setStroke(act.dp(1f), act.getColor(R.color.glass_stroke))
            }
            maxWidth = (act.resources.displayMetrics.widthPixels * 0.68f).toInt()
            setTextIsSelectable(true)
        }
        texts.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
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
            10f,
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

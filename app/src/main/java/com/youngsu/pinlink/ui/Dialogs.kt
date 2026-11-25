package com.youngsu.pinlink.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.youngsu.pinlink.R
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.data.AppPrefs
import com.youngsu.pinlink.data.AvatarPalette

/** 대화상자 모음 — 방 만들기·참여·약속 장소 이름·나가기·백그라운드 위치 안내 */
object Dialogs {

    // ---------------------------------------------------------------- 공통 틀

    private fun cardDialog(activity: Activity): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val card = Ui.column(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = activity.dpf(26f)
                setColor(activity.getColor(R.color.surface_alt))
                setStroke(activity.dp(1f), activity.getColor(R.color.outline))
            }
            setPadding(activity.dp(20f), activity.dp(20f), activity.dp(20f), activity.dp(16f))
        }
        return dialog to card
    }

    private fun frame(activity: Activity, card: LinearLayout): FrameLayout =
        FrameLayout(activity).apply {
            setPadding(activity.dp(18f), activity.dp(20f), activity.dp(18f), activity.dp(20f))
            addView(
                ScrollView(activity).apply {
                    isFillViewport = false
                    clipToPadding = false
                    addView(
                        card,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

    private fun show(dialog: Dialog, view: FrameLayout) {
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0.72f)
                setGravity(Gravity.CENTER)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.72f)
            setGravity(Gravity.CENTER)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun textField(
        activity: Activity,
        hint: String,
        value: String = "",
        maxLength: Int = 20,
        numberLike: Boolean = false
    ): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(activity).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = activity.getColor(R.color.surface)
            boxStrokeColor = activity.getColor(R.color.accent)
            setBoxCornerRadii(activity.dpf(16f), activity.dpf(16f), activity.dpf(16f), activity.dpf(16f))
            setBoxStrokeWidth(activity.dp(1f))
            setBoxStrokeWidthFocused(activity.dp(1.6f))
            this.hint = hint
            setHintTextColor(activity.getColorStateList(R.color.text_secondary))
            setPadding(0, 0, 0, 0)
        }
        val edit = TextInputEditText(activity).apply {
            setText(value)
            textSize = 17f
            setTextColor(activity.getColor(R.color.text_primary))
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            inputType = if (numberLike) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            setPadding(activity.dp(14f), activity.dp(14f), activity.dp(14f), activity.dp(14f))
        }
        layout.addView(edit)
        return layout to edit
    }

    private fun actualText(edit: TextInputEditText): String = edit.text?.toString().orEmpty()

    // ------------------------------------------------------------ 방 만들기

    /** 닉네임과 아바타 색을 고른다 — 방을 만들 때, 그리고 설정에서 프로필을 고칠 때 */
    fun showCreate(
        activity: Activity,
        title: String = activity.getString(R.string.create_title),
        sub: String = activity.getString(R.string.create_sub),
        onConfirm: (name: String, color: String) -> Unit
    ) {
        val prefs = AppPrefs.get(activity)
        var color = if (prefs.color in AvatarPalette.COLORS) prefs.color else AvatarPalette.DEFAULT

        val (dialog, card) = cardDialog(activity)

        card.addView(
            Ui.text(activity, title, 20f, activity.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        card.addView(
            Ui.text(activity, sub, 12.5f, activity.getColor(R.color.text_secondary)).apply {
                setPadding(0, activity.dp(6f), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val (inputLayout, edit) = textField(
            activity,
            activity.getString(R.string.field_nickname),
            prefs.nickname,
            maxLength = 18
        )
        inputLayout.placeholderText = activity.getString(R.string.field_nickname_hint)
        card.addView(inputLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(16f) })

        card.addView(
            Ui.text(activity, activity.getString(R.string.label_avatar_color), 11.5f, activity.getColor(R.color.text_secondary), bold = true).apply {
                letterSpacing = 0.06f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = activity.dp(18f)
            }
        )

        val row = Ui.row(activity)
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(10f) })

        val swatches = HashMap<String, FrameLayout>()
        for (hex in AvatarPalette.COLORS) {
            val swatch = FrameLayout(activity).apply {
                background = swatchBackground(activity, hex, hex == color)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    color = hex
                    swatches.forEach { (key, view) ->
                        view.background = swatchBackground(activity, key, key == color)
                    }
                }
            }
            swatches[hex] = swatch
            row.addView(swatch, LinearLayout.LayoutParams(0, activity.dp(34f)).apply {
                weight = 1f
                rightMargin = activity.dp(5f)
            })
        }

        val confirm = Ui.button(activity, activity.getString(R.string.btn_create_now)) {
            val name = actualText(edit).trim()
            if (name.isEmpty()) {
                inputLayout.error = activity.getString(R.string.err_nickname_required)
                return@button
            }
            inputLayout.error = null
            dialog.dismiss()
            onConfirm(name, color)
        }
        card.addView(confirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(20f) })

        val cancel = Ui.text(activity, activity.getString(R.string.cancel), 13f, activity.getColor(R.color.text_secondary), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(12f), activity.dp(14f), activity.dp(12f), activity.dp(4f))
            Ui.clickable(this) { dialog.dismiss() }
        }
        card.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        show(dialog, frame(activity, card))
        edit.requestFocus()
    }

    // ------------------------------------------------------------ 코드로 참여

    fun showJoin(activity: Activity, onConfirm: (code: String) -> Unit) {
        val (dialog, card) = cardDialog(activity)

        card.addView(
            Ui.text(activity, activity.getString(R.string.join_title), 20f, activity.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        card.addView(
            Ui.text(activity, activity.getString(R.string.join_sub), 12.5f, activity.getColor(R.color.text_secondary)).apply {
                setPadding(0, activity.dp(6f), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val (inputLayout, edit) = textField(
            activity,
            activity.getString(R.string.field_code),
            "",
            maxLength = PinLogic.CODE_LENGTH,
            numberLike = true
        )
        inputLayout.placeholderText = activity.getString(R.string.field_code_hint)
        edit.apply {
            textSize = 24f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.28f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val upper = s?.toString()?.uppercase().orEmpty()
                    if (upper != s?.toString()) {
                        s?.replace(0, s.length, upper)
                    }
                    inputLayout.error = null
                }
            })
        }
        card.addView(inputLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(16f) })

        card.addView(
            Ui.text(activity, activity.getString(R.string.join_demo_note), 11f, activity.getColor(R.color.text_tertiary)).apply {
                setPadding(0, activity.dp(12f), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val confirm = Ui.button(activity, activity.getString(R.string.btn_join_now)) {
            val code = PinLogic.normalizeCode(actualText(edit))
            if (code == null) {
                inputLayout.error = activity.getString(R.string.err_code_needed)
                return@button
            }
            inputLayout.error = null
            dialog.dismiss()
            onConfirm(code)
        }
        card.addView(confirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(18f) })

        val cancel = Ui.text(activity, activity.getString(R.string.cancel), 13f, activity.getColor(R.color.text_secondary), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(12f), activity.dp(14f), activity.dp(12f), activity.dp(4f))
            Ui.clickable(this) { dialog.dismiss() }
        }
        card.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        show(dialog, frame(activity, card))
        edit.requestFocus()
    }

    // --------------------------------------------------------- 약속 장소 이름

    fun showMeetingName(activity: Activity, onConfirm: (String) -> Unit) {
        val (dialog, card) = cardDialog(activity)
        card.addView(
            Ui.text(activity, activity.getString(R.string.meeting_name_title), 19f, activity.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val (inputLayout, edit) = textField(
            activity,
            activity.getString(R.string.meeting_name_hint),
            activity.getString(R.string.meeting_name_default),
            maxLength = 32
        )
        card.addView(inputLayout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(14f) })

        val confirm = Ui.button(activity, activity.getString(R.string.save)) {
            val name = actualText(edit).trim().ifBlank { activity.getString(R.string.meeting_name_default) }
            dialog.dismiss()
            onConfirm(name)
        }
        card.addView(confirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(16f) })

        val cancel = Ui.text(activity, activity.getString(R.string.cancel), 13f, activity.getColor(R.color.text_secondary), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(12f), activity.dp(14f), activity.dp(12f), activity.dp(4f))
            Ui.clickable(this) { dialog.dismiss() }
        }
        card.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        show(dialog, frame(activity, card))
        edit.requestFocus()
        edit.setSelection(actualText(edit).length)
    }

    // ------------------------------------------------------------------ 안내

    fun confirm(
        activity: Activity,
        title: String,
        body: String,
        positive: String,
        negative: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton(negative, null)
            .setCancelable(true)
            .create()
            .apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(
                        ContextCompat.getColor(
                            activity,
                            if (destructive) R.color.danger else R.color.accent
                        )
                    )
                }
            }
            .show()
    }

    /** Play 정책의 명시적 안내(Prominent Disclosure) — 백그라운드 위치를 요청하기 전에 반드시 보여 준다 */
    fun showDisclosure(activity: Activity, onAccept: () -> Unit, onDecline: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.disclosure_title))
            .setMessage(activity.getString(R.string.disclosure_body))
            .setPositiveButton(activity.getString(R.string.disclosure_accept)) { _, _ -> onAccept() }
            .setNegativeButton(activity.getString(R.string.disclosure_decline)) { _, _ -> onDecline() }
            .setCancelable(false)
            .create()
            .apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(
                        ContextCompat.getColor(activity, R.color.accent)
                    )
                }
            }
            .show()
    }

    fun showBackgroundDisclosure(activity: Activity, onOpenSettings: () -> Unit, onLater: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.bg_disclosure_title))
            .setMessage(activity.getString(R.string.bg_disclosure_body))
            .setPositiveButton("${activity.getString(R.string.bg_disclosure_open)}") { _, _ -> onOpenSettings() }
            .setNegativeButton(activity.getString(R.string.bg_disclosure_later)) { _, _ -> onLater() }
            .create()
            .apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(
                        ContextCompat.getColor(activity, R.color.accent)
                    )
                }
            }
            .show()
    }

    private fun swatchBackground(context: Context, hex: String, selected: Boolean): GradientDrawable {
        val color = runCatching { Color.parseColor(hex) }.getOrDefault(Color.GRAY)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) {
                setStroke(context.dp(2.4f), Color.WHITE)
            } else {
                setStroke(context.dp(1f), Color.parseColor("#33000000"))
            }
        }
    }
}

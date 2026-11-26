package com.youngsu.pinlink.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.youngsu.pinlink.R

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

fun Context.str(id: Int, vararg args: Any): String = getString(id, *args)

/** 화면을 코드로 짤 때 쓰는 작은 도우미들 — 간격·색을 한 곳에서 유지한다 */
object Ui {

    fun column(context: Context, paddingH: Int = 0, paddingV: Int = 0): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingH, paddingV, paddingH, paddingV)
        }

    fun row(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float = 14f,
        @ColorInt color: Int = context.getColor(R.color.text_primary),
        bold: Boolean = false,
        letterSpacing: Float = 0f,
        maxLines: Int = Int.MAX_VALUE
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        else typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        if (letterSpacing != 0f) this.letterSpacing = letterSpacing
        this.maxLines = maxLines
        ellipsize = if (maxLines != Int.MAX_VALUE) android.text.TextUtils.TruncateAt.END else null
    }

    fun label(context: Context, value: CharSequence, @ColorInt color: Int = context.getColor(R.color.text_secondary)): TextView =
        text(context, value, 12f, color)

    fun sectionTitle(context: Context, value: CharSequence): TextView =
        text(context, value, 11.5f, context.getColor(R.color.text_secondary), bold = true, letterSpacing = 0.1f)

    fun icon(context: Context, @DrawableRes res: Int, sizeDp: Float, @ColorInt tint: Int): ImageView =
        ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
        }

    /** 둥근 사각 배경 (선택적으로 눌림 효과) */
    fun rounded(
        context: Context,
        @ColorInt fill: Int,
        radiusDp: Float = 20f,
        @ColorInt stroke: Int? = null,
        strokeWidthDp: Float = 1f,
        ripple: Boolean = true
    ): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dpf(radiusDp)
            setColor(fill)
            if (stroke != null) setStroke(context.dp(strokeWidthDp), stroke)
        }
        if (!ripple) return shape
        return RippleDrawable(
            ColorStateList.valueOf(context.getColor(R.color.accent_dim)),
            shape,
            null
        )
    }

    fun circle(context: Context, sizeDp: Float, @ColorInt fill: Int, stroke: Int? = null): FrameLayout {
        val size = context.dp(sizeDp)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                if (stroke != null) setStroke(context.dp(1f), stroke)
            }
        }
    }

    fun clickable(view: View, onClick: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { onClick() }
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(context.getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1f)).apply {
            topMargin = context.dp(6f)
            bottomMargin = context.dp(6f)
        }
    }

    fun button(
        context: Context,
        textValue: CharSequence,
        primary: Boolean = true,
        @DrawableRes iconRes: Int? = null,
        onClick: () -> Unit
    ): MaterialButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
        text = textValue
        textSize = 15f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        minHeight = context.dp(52f)
        cornerRadius = context.dp(16f)
        insetTop = 0
        insetBottom = 0
        backgroundTintList = ColorStateList.valueOf(
            if (primary) context.getColor(R.color.accent) else context.getColor(R.color.surface_high)
        )
        setTextColor(if (primary) context.getColor(R.color.on_accent) else context.getColor(R.color.text_primary))
        if (iconRes != null) {
            setIconResource(iconRes)
            iconTint = ColorStateList.valueOf(
                if (primary) context.getColor(R.color.on_accent) else context.getColor(R.color.text_primary)
            )
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = context.dp(8f)
            iconSize = context.dp(18f)
        }
        setOnClickListener { onClick() }
    }

    /** 아이콘 + 글자 세로 배치 툴 버튼 (편집 화면) */
    fun toolButton(context: Context, @DrawableRes iconRes: Int, label: String, onClick: () -> Unit): LinearLayout =
        column(context).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(context, 0x00000000, 14f, ripple = true)
            setPadding(context.dp(6f), context.dp(8f), context.dp(6f), context.dp(8f))
            addView(icon(context, iconRes, 22f, context.getColor(R.color.text_primary)), LinearLayout.LayoutParams(context.dp(22f), context.dp(22f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(text(context, label, 11.5f, context.getColor(R.color.text_secondary), maxLines = 1).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(6f)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            setOnClickListener { onClick() }
        }

    fun pill(context: Context, value: CharSequence, @ColorInt bg: Int, @ColorInt fg: Int, sizeSp: Float = 11f): TextView =
        text(context, value, sizeSp, fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(9f), context.dp(4f), context.dp(9f), context.dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(999f)
                setColor(bg)
            }
            letterSpacing = 0.04f
        }

    /**
     * 지도 위에 떠 있는 유리판 — 반투명 차콜 + 1dp 샴페인 테두리.
     * 밝은 지도 위에서도 칩이 또렷하게 읽히도록 v1.0.5 에서 도입했다.
     */
    fun glass(context: Context, radiusDp: Float = 999f, strong: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = context.dpf(radiusDp)
            setColor(context.getColor(if (strong) R.color.glass_strong else R.color.glass))
            setStroke(context.dp(1f), context.getColor(R.color.glass_stroke))
        }

    /** 유리 칩 — 투명한 바탕에 1dp 테두리 */
    fun glassPill(context: Context, value: CharSequence, @ColorInt fg: Int, sizeSp: Float = 11f): TextView =
        text(context, value, sizeSp, fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(10f), context.dp(5f), context.dp(10f), context.dp(5f))
            background = glass(context)
            letterSpacing = 0.03f
        }

    fun spacing(context: Context, heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(heightDp))
    }

    fun weight(view: View): View {
        view.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        return view
    }

    fun marginTop(view: View, marginDp: Float): View {
        (view.layoutParams as? LinearLayout.LayoutParams)?.topMargin = view.context.dp(marginDp)
        return view
    }

    fun marginTop(context: Context, view: View, marginDp: Float): View {
        (view.layoutParams as? LinearLayout.LayoutParams)?.topMargin = context.dp(marginDp)
        return view
    }

    fun color(context: Context, id: Int): Int = ContextCompat.getColor(context, id)
}

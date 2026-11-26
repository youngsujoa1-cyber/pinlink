package com.youngsu.pinlink.ui.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.youngsu.pinlink.core.MeetingPoint
import com.youngsu.pinlink.core.Member
import com.youngsu.pinlink.core.PinLogic
import com.youngsu.pinlink.map.MapStyle
import com.youngsu.pinlink.map.MapTileProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 방 멤버들이 한눈에 보이는 지도.
 *
 *  · 지도 타일(기본 CARTO Dark Matter @2x, 밝은 지도는 Voyager @2x)을 Canvas 에 직접 그린다.
 *  · [setMapStyle] 로 밝은/어두운 지도를 바꾼다 — 타일·캐시·바탕색이 스타일을 따라간다.
 *  · 손가락으로 밀어 이동(팬), 두 손가락 확대·축소(핀치), 두 번 탭 한 단계 확대.
 *  · 멤버 핀: 아바타 색 원 + 이름 첫 글자, 핀 아래 이름표, 오래된 핀은 흐리게.
 *  · 내 위치: 파란 점 + 옅은 물결.
 *  · 약속 장소: 민트 깃발 핀 + 이름.
 *  · 핀을 누르면 [onMemberTap] 으로 알린다.
 *  · [pickMode] 이면 화면 정중앙 고정 핀으로 약속 장소를 고른다.
 */
class MemberMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MIN_ZOOM = 3f
        const val MAX_ZOOM = 19f
        const val DEFAULT_ZOOM = 15f

        /** 위치를 못 받았을 때 처음 맞추는 곳 — 서울시청 */
        const val DEFAULT_LAT = 37.5665
        const val DEFAULT_LNG = 126.9780

        private const val TILE_PX = 256.0
        private const val MY_COLOR = "#4C8DFF"

        /** 지도 스타일별 바탕·격자 색 — 타일이 아직 없을 때 이 색이 보인다 */
        private const val DARK_BACKDROP = "#0E1520"
        private const val LIGHT_BACKDROP = "#E9EEF3"
        private const val DARK_GRID = "#1B2738"
        private const val LIGHT_GRID = "#D6DFE9"
    }

    private val tiles = MapTileProvider.get(context)
    private val density = resources.displayMetrics.density

    private var centerX = 0.5
    private var centerY = 0.5

    var zoom: Float = DEFAULT_ZOOM
        private set

    /** 내 위치를 따라가는 중인가 (손으로 옮기면 꺼진다) */
    var followMe: Boolean = true
        private set

    var pickMode: Boolean = false
        private set

    var onMemberTap: ((Member) -> Unit)? = null
    var onFollowChanged: ((Boolean) -> Unit)? = null
    var onCenterChanged: ((lat: Double, lng: Double) -> Unit)? = null

    /** 지도를 길게 눌렀을 때 — 그 자리에 약속 장소를 찍는다 */
    var onLongPress: ((lat: Double, lng: Double) -> Unit)? = null

    private var members: List<Member> = emptyList()
    private var meeting: MeetingPoint? = null
    private var myLat: Double? = null
    private var myLng: Double? = null
    private var selectedId: String? = null
    private var hasCenteredOnce = false

    val centerLat: Double get() = worldYToLat(centerY)
    val centerLng: Double get() = worldXToLon(centerX)

    /** 그리는 타일 페인트 — 확대해도 계단지지 않고(필터), 경계도 부드럽게(안티앨리어스) */
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
        isFilterBitmap = true
        isAntiAlias = true
    }

    private val backdropPaint = Paint().apply { color = Color.parseColor(DARK_BACKDROP) }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor(DARK_GRID)
    }

    private val pinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.WHITE
    }

    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33000000") }

    private val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E60B1420") }

    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#3322314A")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAF0FA")
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val meetingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E5C158") }
    private val meetingPath = Path()

    private val pickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E5C158") }
    private val pickHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#66000000")
    }

    private val tileRect = RectF()
    private val tileSrc = Rect()

    private var animator: ValueAnimator? = null

    /** 지금 그리는 지도 스타일 (기본: 앱 다크 테마에 맞춘 Dark Matter) */
    var mapStyle: MapStyle = MapStyle.DARK
        private set

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            panBy(distanceX, distanceY)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            applyZoom(2f, e.x, e.y)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            handleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (pickMode) return
            val perWorld = pxPerWorld()
            val lat = worldYToLat(centerY + (e.y - height / 2.0) / perWorld)
            var lng = worldXToLon(centerX + (e.x - width / 2.0) / perWorld)
            lng = ((lng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            if (lat.isFinite() && lng.isFinite()) onLongPress?.invoke(lat, lng)
        }
    })

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0f) applyZoom(factor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    init {
        isClickable = true
        isFocusable = true
        applyStyleColors()
    }

    // ------------------------------------------------------------------ 공개 API

    fun setMembers(list: List<Member>) {
        members = list
        invalidate()
    }

    /** 밝은/어두운 지도 전환 — 타일 캐시와 바탕색이 함께 바뀐다 */
    fun setMapStyle(style: MapStyle) {
        if (mapStyle == style) return
        mapStyle = style
        applyStyleColors()
        invalidate()
    }

    private fun applyStyleColors() {
        val dark = mapStyle == MapStyle.DARK
        backdropPaint.color = Color.parseColor(if (dark) DARK_BACKDROP else LIGHT_BACKDROP)
        gridPaint.color = Color.parseColor(if (dark) DARK_GRID else LIGHT_GRID)
    }

    fun setMeeting(point: MeetingPoint?) {
        meeting = point
        invalidate()
    }

    fun setMyLocation(lat: Double?, lng: Double?) {
        myLat = lat
        myLng = lng
        if (followMe && lat != null && lng != null) {
            if (!hasCenteredOnce) {
                hasCenteredOnce = true
                setCenter(lat, lng, DEFAULT_ZOOM, notify = false)
            } else {
                setCenter(lat, lng, null, notify = false)
            }
        }
        invalidate()
    }

    fun selectMember(id: String?) {
        selectedId = id
        invalidate()
    }

    fun setPickMode(enabled: Boolean) {
        pickMode = enabled
        invalidate()
        if (enabled) onCenterChanged?.invoke(centerLat, centerLng)
    }

    fun setFollow(enabled: Boolean) {
        followMe = enabled
        onFollowChanged?.invoke(enabled)
        if (enabled) {
            val lat = myLat
            val lng = myLng
            if (lat != null && lng != null) animateTo(lat, lng, null) else invalidate()
        }
    }

    /** 지도 중심을 위경도로 옮긴다 */
    fun setCenter(lat: Double, lng: Double, newZoom: Float? = null, notify: Boolean = true) {
        cancelAnimation()
        centerX = lonToWorldX(lng)
        centerY = latToWorldY(lat)
        if (newZoom != null) zoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        clampCenter()
        if (notify) onCenterChanged?.invoke(centerLat, centerLng)
        invalidate()
    }

    fun animateTo(lat: Double, lng: Double, newZoom: Float? = null) {
        val targetX = lonToWorldX(lng)
        val targetY = latToWorldY(lat)
        val startX = centerX
        val startY = centerY
        // 위도선을 가로지르는 경우 가장 가까운 방향으로
        val dx = shortestDelta(startX, targetX)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                centerX = startX + dx * t
                centerY = startY + (targetY - startY) * t
                if (newZoom != null) zoom = zoom + (newZoom - zoom) * 0.35f
                clampCenter()
                invalidate()
            }
        }
        animator.start()
        this.animator = animator
    }

    /** 핀을 화면 좌표로 */
    private fun screenOf(lat: Double, lng: Double): Pair<Float, Float> {
        val perWorld = pxPerWorld()
        val dx = lonToWorldX(lng) - centerX
        val wrapped = ((dx + 0.5) % 1.0 + 1.0) % 1.0 - 0.5
        val x = wrapped * perWorld + width / 2.0
        val y = (latToWorldY(lat) - centerY) * perWorld + height / 2.0
        return x.toFloat() to y.toFloat()
    }

    // -------------------------------------------------------------------- 제스처

    // 탭은 handleTap 에서 직접 처리하고 접근성 클릭도 performClick 으로 넘긴다
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount <= 1) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelAnimation()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun handleTap(x: Float, y: Float) {
        if (pickMode) {
            onCenterChanged?.invoke(centerLat, centerLng)
            return
        }
        val perWorld = pxPerWorld()
        var best: Member? = null
        var bestDistance = Float.MAX_VALUE
        for (member in members) {
            if (!member.valid) continue
            val (px, py) = screenOf(member.lat, member.lng)
            val bubbleY = py - 34f * density
            val dx = abs(px - x)
            val dy = abs(bubbleY - y)
            val distance = if (dy < 30f * density && dx < 26f * density) dy else Float.MAX_VALUE
            if (distance < bestDistance) {
                bestDistance = distance
                best = member
            }
        }
        if (best != null) {
            selectedId = best.id
            onMemberTap?.invoke(best)
            invalidate()
        } else {
            selectedId = null
            invalidate()
        }
    }

    private fun panBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        if (followMe) {
            followMe = false
            onFollowChanged?.invoke(false)
        }
        val perWorld = pxPerWorld()
        centerX += dx / perWorld
        centerY += dy / perWorld
        clampCenter()
        invalidate()
    }

    private fun applyZoom(factor: Float, focusX: Float, focusY: Float) {
        val beforeX = screenToWorldX(focusX)
        val beforeY = screenToWorldY(focusY)
        val target = (zoom + log2(factor)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(target - zoom) < 0.001f) return
        zoom = target
        val afterX = screenToWorldX(focusX)
        val afterY = screenToWorldY(focusY)
        centerX += beforeX - afterX
        centerY += beforeY - afterY
        clampCenter()
        invalidate()
    }

    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun pxPerWorld(): Double = TILE_PX * density * Math.pow(2.0, zoom.toDouble())

    private fun screenToWorldX(px: Float): Double = centerX + (px - width / 2.0) / pxPerWorld()

    private fun screenToWorldY(py: Float): Double = centerY + (py - height / 2.0) / pxPerWorld()

    private fun clampCenter() {
        centerX = ((centerX % 1.0) + 1.0) % 1.0
        centerY = centerY.coerceIn(0.001, 0.999)
    }

    private fun shortestDelta(from: Double, to: Double): Double {
        var delta = to - from
        if (delta > 0.5) delta -= 1.0
        if (delta < -0.5) delta += 1.0
        return delta
    }

    // ---------------------------------------------------------------------- 그리기

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backdropPaint)

        val z = zoom.roundToInt().coerceIn(3, 18)
        val n = 1 shl z
        val tileSide = TILE_PX * density * Math.pow(2.0, (zoom - z).toDouble())
        val scale = tileSide / TILE_PX
        val worldPx = TILE_PX * n
        val cx = centerX * worldPx
        val cy = centerY * worldPx

        val halfW = w / 2.0 / scale
        val halfH = h / 2.0 / scale
        val x0 = floor((cx - halfW) / TILE_PX).toInt()
        val x1 = floor((cx + halfW) / TILE_PX).toInt()
        val y0 = floor((cy - halfH) / TILE_PX).toInt()
        val y1 = floor((cy + halfH) / TILE_PX).toInt()

        var drawn = 0
        var cells = 0
        for (tx in x0..x1) {
            for (ty in y0..y1) {
                if (ty < 0 || ty >= n) continue
                cells++
                val left = ((tx * TILE_PX - cx) * scale + w / 2.0).toFloat()
                val top = ((ty * TILE_PX - cy) * scale + h / 2.0).toFloat()
                tileRect.set(left, top, left + tileSide.toFloat() + 1f, top + tileSide.toFloat() + 1f)

                val wrapped = ((tx % n) + n) % n
                val bmp: Bitmap? = tiles.tile(mapStyle, z, wrapped, ty) { invalidate() }
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, tileRect, tilePaint)
                    drawn++
                } else if (drawParentTile(canvas, z, wrapped, ty)) {
                    // 줌이 바뀌는 순간 기다리는 대신 이전 단계 타일을 늘려 그린다
                    drawn++
                } else {
                    canvas.drawRect(tileRect, gridPaint)
                }
            }
        }
        if (drawn == 0) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backdropPaint)

        // 화면이 다 그려진 뒤에만 둘레 한 줄을 미리 받아 둔다(첫 화면을 늦추지 않게)
        if (drawn > 0 && drawn == cells) prefetchAround(z, n, x0, x1, y0, y1)

        // 약속 장소 → 멤버 핀 → 내 위치 → 고정 핀 순서로 겹친다
        meeting?.let { drawMeeting(canvas, it) }
        val now = System.currentTimeMillis()
        for (member in members) {
            if (!member.valid) continue
            drawMember(canvas, member, now)
        }
        drawMe(canvas)
        if (pickMode) drawPickPin(canvas, w / 2f, h / 2f)
    }

    /**
     * 새 줌 레벨 타일이 아직 안 왔을 때 부모 타일(z-1)의 해당 구역만 잘라 늘려 그린다.
     * 핀치·줌 중에 빈 격자가 깜빡이는 대신 지도가 그대로 이어진다.
     */
    private fun drawParentTile(canvas: Canvas, z: Int, x: Int, y: Int): Boolean {
        if (z <= MIN_ZOOM.roundToInt()) return false
        val parent = tiles.tile(mapStyle, z - 1, x / 2, y / 2) { invalidate() } ?: return false
        if (parent.width < 4 || parent.height < 4) return false
        val halfW = parent.width / 2
        val halfH = parent.height / 2
        val sx = (x % 2) * halfW
        val sy = (y % 2) * halfH
        tileSrc.set(sx, sy, sx + halfW, sy + halfH)
        canvas.drawBitmap(parent, tileSrc, tileRect, tilePaint)
        return true
    }

    /** 화면 둘레 한 줄을 미리 받아 둔다 — 팬·줌이 끊기지 않게 */
    private fun prefetchAround(z: Int, n: Int, x0: Int, x1: Int, y0: Int, y1: Int) {
        for (tx in (x0 - 1)..(x1 + 1)) {
            for (ty in (y0 - 1)..(y1 + 1)) {
                if (tx in x0..x1 && ty in y0..y1) continue
                if (ty < 0 || ty >= n) continue
                tiles.prefetch(mapStyle, z, ((tx % n) + n) % n, ty)
            }
        }
    }

    private fun drawMember(canvas: Canvas, member: Member, now: Long) {
        val (px, py) = screenOf(member.lat, member.lng)
        if (px < -80f || px > width + 80f || py < -80f || py > height + 80f) return

        val bubbleR = 17f * density
        val bubbleY = py - 34f * density

        val stale = PinLogic.isStale(member.updatedAt, now)
        val alpha = when {
            !member.sharing -> 90
            stale -> 150
            else -> 255
        }

        // 바닥 그림자
        canvas.drawOval(
            px - 8f * density, py - 4f * density,
            px + 8f * density, py + 3f * density,
            shadowPaint
        )

        // 핀 몸통 (원 + 아래 꼭지)
        val color = runCatching { Color.parseColor(member.color) }.getOrDefault(Color.parseColor(MY_COLOR))
        pinFill.color = color
        pinFill.alpha = alpha
        canvas.drawCircle(px, bubbleY, bubbleR, pinFill)
        val tail = Path().apply {
            moveTo(px - bubbleR * 0.45f, bubbleY + bubbleR * 0.72f)
            lineTo(px + bubbleR * 0.45f, bubbleY + bubbleR * 0.72f)
            lineTo(px, py - 1f * density)
            close()
        }
        canvas.drawPath(tail, pinFill)

        pinStroke.alpha = alpha
        canvas.drawCircle(px, bubbleY, bubbleR, pinStroke)

        if (selectedId == member.id) {
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f * density
                this.color = Color.WHITE
            }
            canvas.drawCircle(px, bubbleY, bubbleR + 6f * density, ring)
            canvas.drawCircle(px, bubbleY, bubbleR + 9f * density, pickHalo)
        }

        // 이름 첫 글자
        initialPaint.alpha = alpha
        initialPaint.textSize = 15f * density
        val initial = member.name.trim().firstOrNull()?.toString()?.uppercase() ?: "?"
        val textY = bubbleY - (initialPaint.descent() + initialPaint.ascent()) / 2f
        canvas.drawText(initial, px, textY, initialPaint)

        // 이름표
        drawLabel(canvas, member.name, px, py + 5f * density, alpha)
    }

    private fun drawMeeting(canvas: Canvas, point: MeetingPoint) {
        val (px, py) = screenOf(point.lat, point.lng)
        if (px < -80f || px > width + 80f || py < -80f || py > height + 80f) return

        val top = py - 44f * density
        canvas.drawOval(
            px - 7f * density, py - 3.5f * density,
            px + 7f * density, py + 3.5f * density,
            shadowPaint
        )

        // 깃발: 기둥 + 깃발 천 + 받침 점
        val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * density
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#0B1420")
        }
        canvas.drawLine(px, py - 2f * density, px, top, pole)
        meetingPaint.color = Color.parseColor("#E5C158")
        meetingPath.reset()
        meetingPath.moveTo(px + 1f * density, top)
        meetingPath.lineTo(px + 20f * density, top + 6f * density)
        meetingPath.lineTo(px + 1f * density, top + 13f * density)
        meetingPath.close()
        canvas.drawPath(meetingPath, meetingPaint)
        canvas.drawCircle(px, py - 1f * density, 4.2f * density, meetingPaint)
        canvas.drawCircle(px, py - 1f * density, 2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

        if (point.name.isNotBlank()) {
            drawLabel(canvas, point.name, px, py + 5f * density, 255, highlight = true)
        }
    }

    private fun drawMe(canvas: Canvas) {
        val lat = myLat ?: return
        val lng = myLng ?: return
        val (px, py) = screenOf(lat, lng)
        if (px < -60f || px > width + 60f || py < -60f || py > height + 60f) return

        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#334CD5FF") }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(MY_COLOR) }

        canvas.drawCircle(px, py, 18f * density, halo)
        canvas.drawCircle(px, py, 9f * density, ring)
        canvas.drawCircle(px, py, 6.5f * density, dot)
    }

    private fun drawPickPin(canvas: Canvas, tipX: Float, tipY: Float) {
        val headR = 13f * density
        val headCy = tipY - 40f * density

        canvas.drawOval(
            tipX - 7f * density, tipY - 3.5f * density,
            tipX + 7f * density, tipY + 3.5f * density,
            shadowPaint
        )
        canvas.drawLine(tipX, tipY - 2f * density, tipX, headCy + headR * 0.7f, pickHalo)
        canvas.drawLine(tipX, tipY - 2f * density, tipX, headCy + headR * 0.7f, pickPaint)

        canvas.drawCircle(tipX, headCy, headR + 2.5f * density, pinStroke)
        canvas.drawCircle(tipX, headCy, headR, pickPaint)
        canvas.drawCircle(tipX, headCy, headR * 0.32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0F19") })
    }

    private fun drawLabel(
        canvas: Canvas,
        text: String,
        cx: Float,
        top: Float,
        alpha: Int,
        highlight: Boolean = false
    ) {
        labelPaint.textSize = 10.5f * density
        labelPaint.alpha = alpha
        labelBgPaint.alpha = if (highlight) 240 else 220
        val label = if (text.length > 14) text.take(13) + "…" else text
        val textWidth = labelPaint.measureText(label)
        val padH = 6f * density
        val padV = 3.5f * density
        val height = labelPaint.textSize + padV * 2
        val rect = RectF(cx - textWidth / 2 - padH, top, cx + textWidth / 2 + padH, top + height)
        canvas.drawRoundRect(rect, height / 2, height / 2, labelBgPaint)
        if (highlight) {
            labelStrokePaint.alpha = 120
            canvas.drawRoundRect(rect, height / 2, height / 2, labelStrokePaint)
        }
        canvas.drawText(label, cx, top + padV - labelPaint.ascent(), labelPaint)
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------- 좌표 변환

    private fun lonToWorldX(lng: Double): Double = (lng + 180.0) / 360.0

    private fun latToWorldY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = clamped * PI / 180.0
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
    }

    private fun worldXToLon(x: Double): Double = x * 360.0 - 180.0

    private fun worldYToLat(y: Double): Double =
        atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI
}

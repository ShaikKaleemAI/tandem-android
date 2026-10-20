package app.tandem.multi

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/* ------------------------------------------------------------------ */
/* Small drawn widgets                                                 */
/* ------------------------------------------------------------------ */

/** Idle dot / spinning ring while an AI is answering / green check when finished. */
class StatusDot(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val path = Path()
    private var angle = 0f
    private var animator: ValueAnimator? = null

    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }

    /** 0 = idle, 1 = busy, 2 = done */
    var mode: Int = 0
        set(v) { field = v; updateAnim(); invalidate() }

    private fun updateAnim() {
        if (mode == 1 && isAttachedToWindow) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            animator?.cancel()
            animator = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnim()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        when (mode) {
            1 -> {
                val inset = dpf(1f)
                p.style = Paint.Style.STROKE
                p.strokeWidth = dpf(2f)
                p.color = withAlpha(color, 0x55)
                c.drawCircle(cx, cy, r - inset, p)
                p.color = color
                arc.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
                c.drawArc(arc, angle, 100f, false, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r * 0.38f, p)
            }
            2 -> {
                p.style = Paint.Style.FILL
                p.color = T.GREEN
                c.drawCircle(cx, cy, r * 0.95f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = dpf(1.8f)
                p.strokeCap = Paint.Cap.ROUND
                p.strokeJoin = Paint.Join.ROUND
                p.color = T.INK
                path.reset()
                path.moveTo(cx - r * 0.4f, cy)
                path.lineTo(cx - r * 0.1f, cy + r * 0.3f)
                path.lineTo(cx + r * 0.42f, cy - r * 0.3f)
                c.drawPath(path, p)
            }
            else -> {
                p.style = Paint.Style.FILL
                p.color = color
                c.drawCircle(cx, cy, r * 0.55f, p)
            }
        }
    }
}

/** Vector-style icon button drawn with Canvas (no image assets needed). */
class IconButton(ctx: Context, var kind: Int, private val tint: Int = T.TEXT, fill: Int? = null) : View(ctx) {
    companion object {
        const val RELOAD = 0
        const val MIN = 1
        const val MAX = 2
        const val RESTORE = 3
        const val CLOSE = 4
        const val PLUS = 5
        const val LAYOUT = 6
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = true
        val content: Drawable? = fill?.let {
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(it) }
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val k = minOf(width, height) * 0.2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = dpf(1.8f)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = tint
        when (kind) {
            MIN -> c.drawLine(cx - k, cy + k * 0.6f, cx + k, cy + k * 0.6f, p)
            MAX -> c.drawRoundRect(cx - k, cy - k, cx + k, cy + k, dpf(2f), dpf(2f), p)
            RESTORE -> {
                c.drawRoundRect(cx - k, cy - k * 0.2f, cx + k * 0.5f, cy + k, dpf(2f), dpf(2f), p)
                path.reset()
                path.moveTo(cx - k * 0.2f, cy - k * 0.2f)
                path.lineTo(cx - k * 0.2f, cy - k)
                path.lineTo(cx + k, cy - k)
                path.lineTo(cx + k, cy + k * 0.5f)
                path.lineTo(cx + k * 0.5f, cy + k * 0.5f)
                c.drawPath(path, p)
            }
            CLOSE -> {
                c.drawLine(cx - k, cy - k, cx + k, cy + k, p)
                c.drawLine(cx + k, cy - k, cx - k, cy + k, p)
            }
            PLUS -> {
                c.drawLine(cx - k * 1.1f, cy, cx + k * 1.1f, cy, p)
                c.drawLine(cx, cy - k * 1.1f, cx, cy + k * 1.1f, p)
            }
            RELOAD -> {
                val rr = k * 1.15f
                c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -50f, 270f, false, p)
                val a = Math.toRadians(220.0)
                val ex = cx + rr * Math.cos(a).toFloat()
                val ey = cy + rr * Math.sin(a).toFloat()
                val tx = -Math.sin(a).toFloat()
                val ty = Math.cos(a).toFloat()
                val nx = Math.cos(a).toFloat()
                val ny = Math.sin(a).toFloat()
                path.reset()
                path.moveTo(ex + tx * rr * 0.6f, ey + ty * rr * 0.6f)
                path.lineTo(ex + nx * rr * 0.55f, ey + ny * rr * 0.55f)
                path.lineTo(ex - nx * rr * 0.55f, ey - ny * rr * 0.55f)
                path.close()
                p.style = Paint.Style.FILL
                c.drawPath(path, p)
            }
            LAYOUT -> {
                c.drawRoundRect(cx - k * 1.2f, cy - k, cx + k * 1.2f, cy + k, dpf(2f), dpf(2f), p)
                c.drawLine(cx, cy - k, cx, cy + k, p)
                c.drawLine(cx, cy, cx + k * 1.2f, cy, p)
            }
        }
    }
}

class ProgressLine(ctx: Context) : View(ctx) {
    private val p = Paint()
    var color: Int = T.ACCENT
    var fraction: Float = 1f
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        if (fraction > 0.001f && fraction < 0.999f) {
            p.color = color
            c.drawRect(0f, 0f, width * fraction, height.toFloat(), p)
        }
    }
}

class LogoView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = height * 0.32f
        val cx = width / 2f
        val cy = height / 2f
        p.color = withAlpha(T.ACCENT, 0xE6)
        c.drawCircle(cx - r * 0.55f, cy, r, p)
        p.color = withAlpha(0xFFFF7A59.toInt(), 0xE6)
        c.drawCircle(cx + r * 0.55f, cy, r, p)
    }
}

class LayoutGlyph(ctx: Context, private val n: Int, private val selected: Boolean, private val enabled: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val a = Arrange.compute(n, width, height, dp(6), dp(3), 0.5f)
        p.style = Paint.Style.FILL
        p.color = if (selected) T.ACCENT else if (enabled) T.MUTED else withAlpha(T.MUTED, 0x55)
        for (r in a.rects) {
            c.drawRoundRect(RectF(r), dpf(4f), dpf(4f), p)
        }
    }
}

class MaxHeightScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

/* ------------------------------------------------------------------ */
/* Split-view engine                                                   */
/* ------------------------------------------------------------------ */

class DividerHandle(ctx: Context) : View(ctx) {
    var vertical = true
    var active = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        p.color = if (active) T.ACCENT else withAlpha(T.TEXT, 0x80)
        val cx = width / 2f
        val cy = height / 2f
        val len = dpf(40f)
        val th = dpf(4f)
        val r = if (vertical) RectF(cx - th / 2, cy - len / 2, cx + th / 2, cy + len / 2)
        else RectF(cx - len / 2, cy - th / 2, cx + len / 2, cy + th / 2)
        c.drawRoundRect(r, th / 2, th / 2, p)
    }
}

/**
 * Lays out up to 4 PaneViews: 1 = full, 2 = split with draggable divider,
 * 3 = one large + two small, 4 = 2x2 grid. Rotates automatically.
 */
class PaneLayout(ctx: Context) : ViewGroup(ctx) {
    private var shown: List<PaneView> = emptyList()
    private var arrangement: Arrangement? = null
    private val divider = DividerHandle(ctx)
    private val pad = ctx.dp(6)
    private val gap = ctx.dp(8)

    var ratio = 0.5f
    var onRatioChanged: (() -> Unit)? = null

    init {
        addView(divider)
        divider.visibility = View.GONE
        divider.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    divider.active = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val a = arrangement
                    if (a != null) {
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        val pos: Float
                        val total: Float
                        if (a.vertical) {
                            pos = ev.rawX - loc[0] - pad - gap / 2f
                            total = (width - 2 * pad - gap).toFloat()
                        } else {
                            pos = ev.rawY - loc[1] - pad - gap / 2f
                            total = (height - 2 * pad - gap).toFloat()
                        }
                        if (total > 0f) {
                            ratio = (pos / total).coerceIn(0.25f, 0.75f)
                            requestLayout()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    divider.active = false
                    onRatioChanged?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    fun addPaneView(v: PaneView) {
        v.visibility = View.GONE
        addView(v)
        divider.bringToFront()
    }

    fun removePaneView(v: PaneView) {
        shown = shown.filter { it !== v }
        removeView(v)
    }

    fun setShown(list: List<PaneView>) {
        val newList = list.take(4)
        shown = newList
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c is PaneView && !newList.contains(c)) c.visibility = View.GONE
        }
        for (v in newList) {
            if (v.visibility != View.VISIBLE) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(180).start()
            }
        }
        divider.visibility = if (newList.size in 2..3) View.VISIBLE else View.GONE
        divider.bringToFront()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val a = Arrange.compute(shown.size, w, h, pad, gap, ratio)
        arrangement = a
        shown.forEachIndexed { i, v ->
            val r = a.rects[i]
            v.measure(
                View.MeasureSpec.makeMeasureSpec(maxOf(r.width(), 0), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxOf(r.height(), 0), View.MeasureSpec.EXACTLY),
            )
        }
        if (a.gapRect != null && divider.visibility == View.VISIBLE) {
            divider.vertical = a.vertical
            val longSide = dp(88)
            val shortSide = dp(26)
            val dw = if (a.vertical) shortSide else longSide
            val dh = if (a.vertical) longSide else shortSide
            divider.measure(
                View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dh, View.MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val a = arrangement ?: return
        shown.forEachIndexed { i, v ->
            val rc = a.rects[i]
            v.layout(rc.left, rc.top, rc.right, rc.bottom)
        }
        val g = a.gapRect
        if (g != null && divider.visibility == View.VISIBLE) {
            val cx = (g.left + g.right) / 2
            val cy = (g.top + g.bottom) / 2
            val hw = divider.measuredWidth / 2
            val hh = divider.measuredHeight / 2
            divider.layout(cx - hw, cy - hh, cx + hw, cy + hh)
        }
    }
}

/* ------------------------------------------------------------------ */
/* One screen: header + progress + WebView                             */
/* ------------------------------------------------------------------ */

class PaneView(ctx: Context, val pane: Pane) : LinearLayout(ctx) {
    val btnReload = IconButton(ctx, IconButton.RELOAD)
    val btnMin = IconButton(ctx, IconButton.MIN)
    val btnMax = IconButton(ctx, IconButton.MAX)
    val btnClose = IconButton(ctx, IconButton.CLOSE)
    val webHolder = FrameLayout(ctx)
    var web: WebView? = null
    var onFocus: (() -> Unit)? = null
    var onMenu: (() -> Unit)? = null

    private val header = LinearLayout(ctx)
    private val dot = StatusDot(ctx)
    private val title = TextView(ctx)
    private val status = TextView(ctx)
    private val progress = ProgressLine(ctx)
    private val ring = GradientDrawable()
    private val radius = ctx.dpf(14f)
    private var compact = false
    private var loading = false
    private val resetStatus = Runnable { render() }

    init {
        orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = radius
        bg.setColor(T.SURFACE)
        background = bg
        ring.cornerRadius = radius
        ring.setColor(Color.TRANSPARENT)
        ring.setStroke(ctx.dp(2), T.LINE)
        foreground = ring
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(T.SURFACE2)
        header.setPadding(ctx.dp(10), 0, ctx.dp(4), 0)

        val dotLp = LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16))
        dotLp.rightMargin = ctx.dp(8)
        header.addView(dot, dotLp)

        val textCol = LinearLayout(ctx)
        textCol.orientation = LinearLayout.VERTICAL
        textCol.gravity = Gravity.CENTER_VERTICAL
        title.textSize = 13f
        title.setTextColor(T.TEXT)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        status.textSize = 10f
        status.setTextColor(T.MUTED)
        status.setSingleLine(true)
        textCol.addView(title)
        textCol.addView(status)
        header.addView(textCol, LinearLayout.LayoutParams(0, MATCH, 1f))

        for (b in listOf(btnReload, btnMin, btnMax, btnClose)) {
            header.addView(b, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        }

        header.setOnClickListener { onFocus?.invoke() }
        header.setOnLongClickListener { onMenu?.invoke(); true }

        addView(header, LinearLayout.LayoutParams(MATCH, ctx.dp(40)))
        addView(progress, LinearLayout.LayoutParams(MATCH, ctx.dp(2)))
        addView(webHolder, LinearLayout.LayoutParams(MATCH, 0, 1f))
        render()
    }

    fun attachWeb(w: WebView) {
        web = w
        w.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onFocus?.invoke()
            false
        }
        webHolder.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    fun detachWeb() {
        val w = web ?: return
        webHolder.removeView(w)
        w.destroy()
        web = null
    }

    fun setFocused(f: Boolean) {
        ring.setStroke(context.dp(2), if (f) pane.color else T.LINE)
        invalidate()
    }

    fun setControls(canMax: Boolean, maximized: Boolean, canMin: Boolean) {
        btnMax.kind = if (maximized) IconButton.RESTORE else IconButton.MAX
        btnMax.invalidate()
        btnMax.visibility = if (canMax || maximized) View.VISIBLE else View.GONE
        btnMin.visibility = if (canMin) View.VISIBLE else View.GONE
    }

    fun setLoading(b: Boolean) {
        loading = b
        if (b) progress.fraction = 0.05f
        render()
    }

    fun setProgress(pct: Int) {
        progress.color = pane.color
        progress.fraction = if (pct >= 100) 1f else pct / 100f
    }

    fun flashDone() {
        status.text = "Finished"
        status.setTextColor(T.GREEN)
        status.visibility = if (compact) View.GONE else View.VISIBLE
        removeCallbacks(resetStatus)
        postDelayed(resetStatus, 3000)
    }

    fun render() {
        title.text = pane.label
        dot.color = pane.color
        dot.mode = if (pane.busy) 1 else if (pane.doneUnseen) 2 else 0
        val txt: String
        val col: Int
        when {
            pane.busy -> { txt = "Responding…"; col = pane.color }
            pane.doneUnseen -> { txt = "Finished"; col = T.GREEN }
            loading -> { txt = "Loading…"; col = T.MUTED }
            else -> { txt = ""; col = T.MUTED }
        }
        status.text = txt
        status.setTextColor(col)
        status.visibility = if (txt.isEmpty() || compact) View.GONE else View.VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val c = w < context.dp(300)
        if (c != compact) {
            compact = c
            btnReload.visibility = if (c) View.GONE else View.VISIBLE
            render()
        }
    }
}

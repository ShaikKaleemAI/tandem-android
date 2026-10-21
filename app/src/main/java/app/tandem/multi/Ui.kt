package app.tandem.multi

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class SheetItem(
    val label: String,
    val sub: String? = null,
    val dot: Int? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

fun roundRect(color: Int, radius: Float, strokeColor: Int? = null, strokeW: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.cornerRadius = radius
    g.setColor(color)
    if (strokeColor != null && strokeW > 0) g.setStroke(strokeW, strokeColor)
    return g
}

private fun Activity.titleView(text: String): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = 17f
    t.typeface = Typeface.DEFAULT_BOLD
    t.setTextColor(T.TEXT)
    t.setPadding(dp(4), 0, dp(4), dp(12))
    return t
}

private fun Activity.baseDialog(bottom: Boolean): Pair<Dialog, LinearLayout> {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val root = LinearLayout(this)
    root.orientation = LinearLayout.VERTICAL
    root.setPadding(dp(18), dp(18), dp(18), dp(if (bottom) 24 else 16))
    val r = dpf(22f)
    val bg = GradientDrawable()
    bg.setColor(T.SURFACE)
    bg.setStroke(dp(1), T.LINE)
    bg.cornerRadii = if (bottom) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    else floatArrayOf(r, r, r, r, r, r, r, r)
    root.background = bg
    d.setContentView(root)
    val w = d.window
    if (w != null) {
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.setDimAmount(0.6f)
        if (bottom) {
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(MATCH, WRAP)
            val lp = w.attributes
            lp.windowAnimations = android.R.style.Animation_InputMethod
            w.attributes = lp
        } else {
            w.setGravity(Gravity.CENTER)
            val width = minOf((resources.displayMetrics.widthPixels * 0.9f).toInt(), dp(420))
            w.setLayout(width, WRAP)
        }
    }
    return Pair(d, root)
}

private fun Activity.pill(text: String, filled: Boolean, fillColor: Int = T.ACCENT): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = 14f
    t.typeface = Typeface.DEFAULT_BOLD
    t.gravity = Gravity.CENTER
    t.setPadding(dp(18), dp(10), dp(18), dp(10))
    if (filled) {
        t.setTextColor(T.ON_ACCENT)
        t.background = roundRect(fillColor, dpf(20f))
    } else {
        t.setTextColor(T.TEXT)
        t.background = roundRect(Color.TRANSPARENT, dpf(20f), T.LINE, dp(1))
    }
    return t
}

private fun Activity.rowRipple(): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(withAlpha(T.TEXT, 0x22)), null, ColorDrawable(Color.WHITE))

/* ------------------------------------------------------------------ */

fun Activity.showSheet(title: String, items: List<SheetItem>) {
    val (d, root) = baseDialog(true)
    root.addView(titleView(title))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    for (item in items) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), 0, dp(6), 0)
        row.background = rowRipple()

        val dc = item.dot
        if (dc != null) {
            val dv = View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(dc)
            dv.background = g
            val lp = LinearLayout.LayoutParams(dp(14), dp(14))
            lp.rightMargin = dp(14)
            row.addView(dv, lp)
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val lbl = TextView(this)
        lbl.text = item.label
        lbl.textSize = 15f
        lbl.setTextColor(if (item.danger) T.RED else T.TEXT)
        col.addView(lbl)
        val sub = item.sub
        if (!sub.isNullOrEmpty()) {
            val st = TextView(this)
            st.text = sub
            st.textSize = 12f
            st.setTextColor(T.MUTED)
            col.addView(st)
        }
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.setOnClickListener {
            d.dismiss()
            item.onClick()
        }
        list.addView(row, LinearLayout.LayoutParams(MATCH, dp(54)))
    }
    val sv = MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.62f).toInt())
    sv.addView(list)
    root.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
    d.show()
}

fun Activity.showInput(
    title: String,
    initial: String,
    placeholder: String,
    okText: String,
    onOk: (String) -> Unit,
) {
    val (d, root) = baseDialog(false)
    root.addView(titleView(title))
    val et = EditText(this)
    et.setText(initial)
    et.setSelectAllOnFocus(true)
    et.setSingleLine(true)
    et.setTextColor(T.TEXT)
    et.setHintTextColor(T.MUTED)
    et.hint = placeholder
    et.textSize = 16f
    et.background = roundRect(T.SURFACE2, dpf(12f), T.LINE, dp(1))
    et.setPadding(dp(14), dp(12), dp(14), dp(12))
    et.imeOptions = EditorInfo.IME_ACTION_DONE
    root.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))

    fun submit() {
        val t = et.text.toString().trim()
        if (t.isNotEmpty()) {
            d.dismiss()
            onOk(t)
        }
    }
    et.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            submit()
            true
        } else {
            false
        }
    }

    val buttons = LinearLayout(this)
    buttons.orientation = LinearLayout.HORIZONTAL
    buttons.gravity = Gravity.END
    buttons.setPadding(0, dp(16), 0, 0)
    val cancel = pill("Cancel", false)
    cancel.setOnClickListener { d.dismiss() }
    val ok = pill(okText, true)
    ok.setOnClickListener { submit() }
    val lp = LinearLayout.LayoutParams(WRAP, WRAP)
    lp.leftMargin = dp(10)
    buttons.addView(cancel)
    buttons.addView(ok, lp)
    root.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))

    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    d.show()
    et.requestFocus()
}

fun Activity.showConfirm(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean,
    onYes: () -> Unit,
) {
    val (d, root) = baseDialog(false)
    root.addView(titleView(title))
    val m = TextView(this)
    m.text = message
    m.textSize = 14f
    m.setTextColor(T.MUTED)
    root.addView(m, LinearLayout.LayoutParams(MATCH, WRAP))

    val buttons = LinearLayout(this)
    buttons.orientation = LinearLayout.HORIZONTAL
    buttons.gravity = Gravity.END
    buttons.setPadding(0, dp(18), 0, 0)
    val cancel = pill("Cancel", false)
    cancel.setOnClickListener { d.dismiss() }
    val yes = pill(confirmText, true, if (danger) T.RED else T.ACCENT)
    yes.setOnClickListener {
        d.dismiss()
        onYes()
    }
    val lp = LinearLayout.LayoutParams(WRAP, WRAP)
    lp.leftMargin = dp(10)
    buttons.addView(cancel)
    buttons.addView(yes, lp)
    root.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
    d.show()
}

fun Activity.showInfo(title: String, message: String) {
    val (d, root) = baseDialog(false)
    root.addView(titleView(title))
    val m = TextView(this)
    m.text = message
    m.textSize = 14f
    m.setTextColor(T.MUTED)
    root.addView(m, LinearLayout.LayoutParams(MATCH, WRAP))
    val ok = pill("Got it", true)
    ok.setOnClickListener { d.dismiss() }
    val lp = LinearLayout.LayoutParams(WRAP, WRAP)
    lp.topMargin = dp(18)
    lp.gravity = Gravity.END
    root.addView(ok, lp)
    d.show()
}

fun Activity.showColors(current: Int, onPick: (Int) -> Unit) {
    val (d, root) = baseDialog(false)
    root.addView(titleView("Screen color"))
    val palette = T.PALETTE
    var i = 0
    while (i < palette.size) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        for (j in i until minOf(i + 4, palette.size)) {
            val c = palette[j]
            val v = View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(c)
            if (c == current) g.setStroke(dp(3), T.TEXT)
            v.background = g
            v.setOnClickListener {
                d.dismiss()
                onPick(c)
            }
            val lp = LinearLayout.LayoutParams(dp(46), dp(46))
            lp.setMargins(dp(8), dp(8), dp(8), dp(8))
            row.addView(v, lp)
        }
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        i += 4
    }
    d.show()
}

fun Activity.showLayoutPicker(current: Int, total: Int, onPick: (Int) -> Unit) {
    val (d, root) = baseDialog(true)
    root.addView(titleView("Layout"))
    val land = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER
    val labels = arrayOf("Focus", "Split", "Trio", "Grid")
    for (n in 1..4) {
        val tile = LinearLayout(this)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER_HORIZONTAL
        tile.setPadding(dp(6), dp(8), dp(6), dp(8))
        val g = LayoutGlyph(this, n, n == current, n <= total)
        g.background = roundRect(
            T.SURFACE2, dpf(10f),
            if (n == current) T.ACCENT else T.LINE,
            dp(if (n == current) 2 else 1),
        )
        tile.addView(g, LinearLayout.LayoutParams(dp(if (land) 64 else 48), dp(if (land) 48 else 64)))
        val tv = TextView(this)
        tv.text = labels[n - 1]
        tv.textSize = 12f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(if (n <= total) T.TEXT else T.MUTED)
        tv.setPadding(0, dp(6), 0, 0)
        tile.addView(tv)
        tile.setOnClickListener {
            d.dismiss()
            onPick(n)
        }
        row.addView(tile, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    d.show()
}

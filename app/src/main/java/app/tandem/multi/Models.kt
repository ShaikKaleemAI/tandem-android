package app.tandem.multi

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = context.dpf(v)
fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

/** Design tokens. */
object T {
    /** true = dark theme. Set once in MainActivity.onCreate before any view is built. */
    var dark = true

    val INK: Int get() = if (dark) 0xFF0B0F1A.toInt() else 0xFFEEF1F7.toInt()
    val SURFACE: Int get() = if (dark) 0xFF121A2B.toInt() else 0xFFFFFFFF.toInt()
    val SURFACE2: Int get() = if (dark) 0xFF19233A.toInt() else 0xFFE4E9F3.toInt()
    val LINE: Int get() = if (dark) 0xFF2A3556.toInt() else 0xFFC9D2E3.toInt()
    val TEXT: Int get() = if (dark) 0xFFEAF0FF.toInt() else 0xFF111827.toInt()
    val MUTED: Int get() = if (dark) 0xFF8C98B6.toInt() else 0xFF566280.toInt()
    val ACCENT: Int get() = if (dark) 0xFF4CC3FF.toInt() else 0xFF0B7FD0.toInt()
    val ON_ACCENT: Int get() = if (dark) 0xFF08101F.toInt() else 0xFFFFFFFF.toInt()
    val GREEN: Int get() = if (dark) 0xFF3DDC97.toInt() else 0xFF0E9F61.toInt()
    val RED: Int get() = if (dark) 0xFFFF6B6B.toInt() else 0xFFD93A3A.toInt()
    val PALETTE = intArrayOf(
        0xFF4CC3FF.toInt(), 0xFFFF7A59.toInt(), 0xFF5BD6A0.toInt(), 0xFFF5C451.toInt(),
        0xFFC77DFF.toInt(), 0xFFFF6FA8.toInt(), 0xFF7C8CFF.toInt(), 0xFFA3E635.toInt(),
    )
}

class Service(val name: String, val url: String)

object Services {
    val all = listOf(
        Service("Claude", "https://claude.ai/new"),
        Service("ChatGPT", "https://chatgpt.com/"),
        Service("Gemini", "https://gemini.google.com/app"),
        Service("Perplexity", "https://www.perplexity.ai/"),
        Service("DeepSeek", "https://chat.deepseek.com/"),
        Service("Grok", "https://grok.com/"),
        Service("Copilot", "https://copilot.microsoft.com/"),
        Service("Le Chat", "https://chat.mistral.ai/chat"),
        Service("Custom URL", ""),
    )
}

/** One screen = one isolated account session. */
class Pane(
    val id: String,
    var label: String,
    var color: Int,
    val startUrl: String,
) {
    var currentUrl: String = startUrl
    var minimized: Boolean = false
    var lastUsed: Long = 0L
    var busy: Boolean = false
    var doneUnseen: Boolean = false
    var view: PaneView? = null

    /** Closed = no WebView in memory, but the account and its saved login are kept. */
    var closed: Boolean = false

    /** 0 = mobile Chrome identity, 1 = desktop Chrome identity (fallback for pages that load blank). */
    var compat: Int = 0
    var blankTries: Int = 0

    /** Optional CSS selector of this site's "stop" button, so finished-alerts work on any site. */
    var selector: String = ""

    /** Last problems seen on this screen, for "Copy diagnostics". */
    val log = ArrayList<String>()
    fun note(s: String) {
        log.add(s.take(300))
        while (log.size > 40) log.removeAt(0)
    }
}

class Arrangement(val rects: List<Rect>, val gapRect: Rect?, val vertical: Boolean)

/** Pure layout maths, shared by the real layout and the layout-picker previews. */
object Arrange {
    fun compute(n: Int, w: Int, h: Int, pad: Int, gap: Int, ratio: Float): Arrangement {
        val l = pad
        val t = pad
        val r = w - pad
        val b = h - pad
        val aw = r - l
        val ah = b - t
        val land = w > h
        return when {
            n <= 1 -> Arrangement(listOf(Rect(l, t, r, b)), null, false)
            n == 2 -> if (land) {
                val x = l + ((aw - gap) * ratio).toInt()
                Arrangement(listOf(Rect(l, t, x, b), Rect(x + gap, t, r, b)), Rect(x, t, x + gap, b), true)
            } else {
                val y = t + ((ah - gap) * ratio).toInt()
                Arrangement(listOf(Rect(l, t, r, y), Rect(l, y + gap, r, b)), Rect(l, y, r, y + gap), false)
            }
            n == 3 -> if (land) {
                val x = l + ((aw - gap) * ratio).toInt()
                val my = t + (ah - gap) / 2
                Arrangement(
                    listOf(Rect(l, t, x, b), Rect(x + gap, t, r, my), Rect(x + gap, my + gap, r, b)),
                    Rect(x, t, x + gap, b), true,
                )
            } else {
                val y = t + ((ah - gap) * ratio).toInt()
                val mx = l + (aw - gap) / 2
                Arrangement(
                    listOf(Rect(l, t, r, y), Rect(l, y + gap, mx, b), Rect(mx + gap, y + gap, r, b)),
                    Rect(l, y, r, y + gap), false,
                )
            }
            else -> {
                val mx = l + (aw - gap) / 2
                val my = t + (ah - gap) / 2
                Arrangement(
                    listOf(
                        Rect(l, t, mx, my), Rect(mx + gap, t, r, my),
                        Rect(l, my + gap, mx, b), Rect(mx + gap, my + gap, r, b),
                    ),
                    null, false,
                )
            }
        }
    }
}

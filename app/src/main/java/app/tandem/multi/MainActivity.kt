package app.tandem.multi

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID

class MainActivity : Activity() {

    private val panes = mutableListOf<Pane>()
    private var focusedId: String? = null
    private var maximizedId: String? = null
    private var profilesOk = false
    private var appVisible = false
    private var ratio = 0.5f
    private var lastBusyCount = 0
    private var themeMode = 0 // 0 = follow system, 1 = dark, 2 = light
    private var locked = false
    private var lastPause = 0L
    private lateinit var lockView: View
    private val reqLock = 14

    private lateinit var prefs: SharedPreferences
    private lateinit var layoutView: PaneLayout
    private lateinit var dock: LinearLayout
    private lateinit var emptyView: View
    private lateinit var emptySub: TextView

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private val blobTokens: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    private val reqFile = 11
    private val reqMic = 12
    private val reqNotif = 13
    private val maxPanes = 8
    private val maxVisible = 4

    /* ================================================================ */
    /* Lifecycle                                                         */
    /* ================================================================ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("tandem", Context.MODE_PRIVATE)
        profilesOk = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        } catch (e: Exception) {
            false
        }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                prefs.edit().putString("crash", android.util.Log.getStackTraceString(e).take(6000)).commit()
            } catch (x: Exception) {
                // ignore
            }
            prevHandler?.uncaughtException(t, e)
        }
        themeMode = prefs.getInt("theme", 0)
        T.dark = when (themeMode) {
            1 -> true
            2 -> false
            else -> systemIsDark(resources.configuration)
        }
        window.statusBarColor = T.INK
        window.navigationBarColor = T.INK
        run {
            @Suppress("DEPRECATION")
            var f = window.decorView.systemUiVisibility
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            f = if (T.dark) f and light.inv() else f or light
            window.decorView.systemUiVisibility = f
        }
        Notifier.ensureChannels(this)
        try {
            WebView.setWebContentsDebuggingEnabled(true)
        } catch (e: Exception) {
            // ignore
        }
        if (profilesOk) purgePendingProfiles()

        buildUi()
        loadState()
        if (panes.isEmpty() && !prefs.getBoolean("seen", false)) {
            prefs.edit().putBoolean("seen", true).apply()
            addPane("Claude", Services.all[0].url)
        } else {
            applyLayout()
        }
        handleIntent(intent)
        if (prefs.getBoolean("lock", false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            lockNow()
        }

        if (!profilesOk) {
            showInfo(
                "Separate logins unavailable",
                "This phone's Android System WebView is too old to keep a different login in each screen. " +
                    "Update \"Android System WebView\" and \"Chrome\" in the Play Store, then reopen Tandem. " +
                    "Until then all screens share one login.",
            )
        }
    }

    private fun systemIsDark(c: Configuration): Boolean =
        (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeMode == 0 && systemIsDark(newConfig) != T.dark) recreate()
    }

    private fun setThemeMode(m: Int) {
        if (m == themeMode) return
        prefs.edit().putInt("theme", m).apply()
        saveState()
        recreate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getStringExtra("pane") ?: return
        val p = panes.firstOrNull { it.id == id } ?: return
        focusPane(p)
    }

    override fun onResume() {
        super.onResume()
        appVisible = true
        if (prefs.getBoolean("lock", false) && !locked && lastPause > 0L &&
            System.currentTimeMillis() - lastPause > 30_000L
        ) {
            lockNow()
        }
        val f = panes.firstOrNull { it.id == focusedId }
        if (f != null && f.doneUnseen) {
            f.doneUnseen = false
            Notifier.cancel(this, f)
            refreshChrome()
        }
    }

    override fun onPause() {
        appVisible = false
        lastPause = System.currentTimeMillis()
        saveState()
        flushCookies()
        super.onPause()
    }

    override fun onStop() {
        flushCookies()
        super.onStop()
    }

    override fun onDestroy() {
        flushCookies()
        try {
            stopService(Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            // ignore
        }
        panes.forEach { it.view?.web?.destroy() }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val web = panes.firstOrNull { it.id == focusedId }?.view?.web
        when {
            web != null && web.canGoBack() -> web.goBack()
            maximizedId != null -> {
                maximizedId = null
                applyLayout()
            }
            panes.any { it.busy } -> {
                toast("Responses keep running in the background")
                moveTaskToBack(true)
            }
            else -> super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == reqLock) {
            if (resultCode == Activity.RESULT_OK) {
                locked = false
                lastPause = 0L
                lockView.visibility = View.GONE
            }
        } else if (requestCode == reqFile) {
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqMic) {
            val req = pendingPermission
            pendingPermission = null
            if (req != null) {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    req.deny()
                }
            }
        }
    }

    /* ================================================================ */
    /* Top-level UI                                                      */
    /* ================================================================ */

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(T.INK)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10), 0, dp(8), 0)

        val logo = LogoView(this)
        bar.addView(logo, LinearLayout.LayoutParams(dp(34), dp(28)))

        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(dock)
        val scrollLp = LinearLayout.LayoutParams(0, MATCH, 1f)
        scrollLp.leftMargin = dp(6)
        bar.addView(scroll, scrollLp)

        val layoutBtn = IconButton(this, IconButton.LAYOUT)
        layoutBtn.setOnClickListener {
            showLayoutPicker(shownPanes().size, panes.size) { n -> setLayoutCount(n) }
        }
        bar.addView(layoutBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        val gearBtn = IconButton(this, IconButton.GEAR)
        gearBtn.setOnClickListener { showSettings() }
        bar.addView(gearBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        val addBtn = IconButton(this, IconButton.PLUS, T.ON_ACCENT, T.ACCENT)
        addBtn.setOnClickListener { showAdd() }
        val addLp = LinearLayout.LayoutParams(dp(38), dp(38))
        addLp.leftMargin = dp(6)
        bar.addView(addBtn, addLp)

        root.addView(bar, LinearLayout.LayoutParams(MATCH, dp(56)))

        val line = View(this)
        line.setBackgroundColor(T.LINE)
        root.addView(line, LinearLayout.LayoutParams(MATCH, 1))

        val frame = FrameLayout(this)
        layoutView = PaneLayout(this)
        layoutView.onRatioChanged = {
            ratio = layoutView.ratio
            saveState()
        }
        frame.addView(layoutView, FrameLayout.LayoutParams(MATCH, MATCH))
        emptyView = buildEmpty()
        frame.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val wrapper = FrameLayout(this)
        wrapper.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        lockView = buildLockView()
        wrapper.addView(lockView, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(wrapper)
    }

    private fun buildLockView(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(T.INK)
        box.isClickable = true
        box.visibility = View.GONE
        box.addView(LogoView(this), LinearLayout.LayoutParams(dp(96), dp(72)))
        val t = TextView(this)
        t.text = "Tandem is locked"
        t.textSize = 20f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(T.TEXT)
        t.setPadding(0, dp(14), 0, dp(18))
        box.addView(t)
        val b = TextView(this)
        b.text = "Unlock"
        b.textSize = 15f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(T.ON_ACCENT)
        b.setPadding(dp(30), dp(12), dp(30), dp(12))
        b.background = roundRect(T.ACCENT, dpf(24f))
        b.setOnClickListener { promptUnlock() }
        box.addView(b, LinearLayout.LayoutParams(WRAP, WRAP))
        return box
    }

    private fun lockNow() {
        locked = true
        lockView.visibility = View.VISIBLE
        promptUnlock()
    }

    private fun promptUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            locked = false
            lockView.visibility = View.GONE
            return
        }
        try {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Tandem", "Unlock to see your AI accounts")
            if (i != null) startActivityForResult(i, reqLock)
        } catch (e: Exception) {
            toast("Could not open the unlock screen")
        }
    }

    private fun toggleLock() {
        val on = prefs.getBoolean("lock", false)
        if (on) {
            prefs.edit().putBoolean("lock", false).apply()
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            toast("App lock off")
            return
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            showInfo("Set a screen lock first", "App lock uses your phone's PIN, pattern, password or fingerprint. Set one in the phone's Settings, then turn this on.")
            return
        }
        prefs.edit().putBoolean("lock", true).apply()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        toast("App lock on. Screenshots are also blocked.")
    }

    private fun buildEmpty(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(32), 0, dp(32), 0)
        box.visibility = View.GONE
        box.addView(LogoView(this), LinearLayout.LayoutParams(dp(96), dp(72)))
        val t = TextView(this)
        t.text = "Your AI workspace"
        t.textSize = 22f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(T.TEXT)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(12), 0, dp(6))
        box.addView(t)
        val s = TextView(this)
        s.text = "Add separate accounts of Claude, ChatGPT, Gemini and more, then run them side by side."
        emptySub = s
        s.textSize = 14f
        s.setTextColor(T.MUTED)
        s.gravity = Gravity.CENTER
        box.addView(s)
        val b = TextView(this)
        b.text = "Add a screen"
        b.textSize = 15f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(T.ON_ACCENT)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(26), dp(12), dp(26), dp(12))
        b.background = roundRect(T.ACCENT, dpf(24f))
        b.setOnClickListener { showAdd() }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.topMargin = dp(22)
        box.addView(b, lp)
        return box
    }

    private fun makeChip(p: Pane, shown: Boolean): View {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.gravity = Gravity.CENTER_VERTICAL
        ll.setPadding(dp(12), dp(7), dp(14), dp(7))

        val dot = StatusDot(this)
        dot.color = p.color
        dot.mode = if (p.busy) 1 else if (p.doneUnseen) 2 else 0
        val dlp = LinearLayout.LayoutParams(dp(16), dp(16))
        dlp.rightMargin = dp(7)
        ll.addView(dot, dlp)

        val tv = TextView(this)
        tv.text = p.label
        tv.textSize = 13f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.maxLines = 1
        tv.setTextColor(if (shown) T.TEXT else T.MUTED)
        ll.addView(tv)

        val focused = p.id == focusedId
        val stroke = if (focused) p.color else if (shown) withAlpha(p.color, 0x66) else T.LINE
        val fill = if (shown) withAlpha(p.color, 0x26) else Color.TRANSPARENT
        ll.background = roundRect(fill, dpf(20f), stroke, dp(if (focused) 2 else 1))

        ll.setOnClickListener { focusPane(p) }
        ll.setOnLongClickListener {
            showPaneMenu(p)
            true
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.rightMargin = dp(8)
        ll.layoutParams = lp
        return ll
    }

    /* ================================================================ */
    /* Layout / state machine                                            */
    /* ================================================================ */

    private fun visiblePanes(): List<Pane> = panes.filter { !it.minimized && !it.closed }

    private fun shownPanes(): List<Pane> {
        val mid = maximizedId
        val m = if (mid != null) panes.firstOrNull { it.id == mid } else null
        return if (m != null) listOf(m) else visiblePanes()
    }

    private fun enforceInvariants() {
        if (panes.isEmpty()) {
            focusedId = null
            maximizedId = null
            return
        }
        if (panes.none { it.id == focusedId }) {
            focusedId = panes.maxByOrNull { it.lastUsed }!!.id
        }
        val mid = maximizedId
        if (mid != null) {
            val m = panes.firstOrNull { it.id == mid }
            if (m == null || m.closed) maximizedId = null else m.minimized = false
        }
        val opened = panes.filter { !it.closed }
        if (opened.isNotEmpty() && opened.none { !it.minimized }) {
            val f = opened.firstOrNull { it.id == focusedId } ?: opened.maxByOrNull { it.lastUsed }!!
            f.minimized = false
            focusedId = f.id
        }
        var v = visiblePanes()
        while (v.size > maxVisible) {
            val victim = v.filter { it.id != focusedId && it.id != maximizedId }
                .minByOrNull { it.lastUsed } ?: break
            victim.minimized = true
            v = visiblePanes()
        }
    }

    private fun applyLayout() {
        enforceInvariants()
        val views = shownPanes().map { ensureView(it) }
        layoutView.ratio = ratio
        layoutView.setShown(views)
        refreshChrome()
    }

    private fun refreshChrome() {
        val shown = shownPanes()
        val shownIds = shown.map { it.id }.toSet()
        for (p in panes) {
            val v = p.view ?: continue
            v.setFocused(p.id == focusedId && shown.size > 1)
            v.setControls(
                canMax = shown.size > 1 || visiblePanes().size > 1,
                maximized = maximizedId == p.id,
                canMin = panes.count { !it.closed } > 1,
            )
            v.render()
        }
        dock.removeAllViews()
        for (p in panes) dock.addView(makeChip(p, shownIds.contains(p.id)))
        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        emptySub.text = if (panes.isEmpty()) {
            "Add separate accounts of Claude, ChatGPT, Gemini and more, then run them side by side."
        } else {
            "All screens are closed. Your logins are saved. Tap a screen in the bar above to open it again."
        }
        saveState()
    }

    private fun setFocus(p: Pane) {
        p.lastUsed = System.currentTimeMillis()
        focusedId = p.id
        if (p.doneUnseen) {
            p.doneUnseen = false
            Notifier.cancel(this, p)
        }
    }

    private fun focusPane(p: Pane) {
        p.closed = false
        p.minimized = false
        if (maximizedId != null) maximizedId = p.id
        setFocus(p)
        applyLayout()
    }

    private fun onPaneTouched(p: Pane) {
        if (focusedId != p.id) {
            setFocus(p)
            refreshChrome()
        }
    }

    private fun setLayoutCount(n: Int) {
        if (n > panes.size) {
            toast("Add another screen to use this layout")
            showAdd()
            return
        }
        maximizedId = null
        val order = panes.sortedByDescending { if (it.id == focusedId) Long.MAX_VALUE else it.lastUsed }
        val keep = order.take(n).map { it.id }.toSet()
        panes.forEach {
            it.minimized = !keep.contains(it.id)
            if (keep.contains(it.id)) it.closed = false
        }
        applyLayout()
    }

    private fun minimizePane(p: Pane) {
        p.minimized = true
        if (maximizedId == p.id) maximizedId = null
        val next = panes.filter { it.id != p.id && !it.minimized && !it.closed }.maxByOrNull { it.lastUsed }
            ?: panes.filter { it.id != p.id && !it.closed }.maxByOrNull { it.lastUsed }
        if (next == null) {
            p.minimized = false
            toast("Add another screen first")
            return
        }
        next.minimized = false
        if (focusedId == p.id) setFocus(next)
        applyLayout()
    }

    private fun toggleMaximize(p: Pane) {
        maximizedId = if (maximizedId == p.id) null else p.id
        setFocus(p)
        applyLayout()
    }

    /* ================================================================ */
    /* Adding / closing                                                  */
    /* ================================================================ */

    private fun newId(): String = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private fun showAdd() {
        if (panes.size >= maxPanes) {
            toast("Maximum $maxPanes screens. Close one first.")
            return
        }
        val items = Services.all.mapIndexed { i, s ->
            val host = if (s.url.isEmpty()) "Any website" else Uri.parse(s.url).host?.removePrefix("www.")
            SheetItem(s.name, host, T.PALETTE[i % T.PALETTE.size]) { onServicePicked(s) }
        }
        showSheet("Add a screen", items)
    }

    private fun onServicePicked(s: Service) {
        if (s.url.isEmpty()) {
            showInput("Web address", "https://", "example.com", "Next") { raw ->
                val url = cleanUrl(raw)
                if (url == null) {
                    toast("Enter a full web address")
                } else {
                    showInput("Name this screen", "Custom", "", "Add") { n -> addPane(n.take(24), url) }
                }
            }
        } else {
            val same = panes.count { it.label == s.name || it.label.startsWith(s.name + " ") }
            val suggestion = if (same == 0) s.name else s.name + " " + (same + 1)
            showInput("Name this screen", suggestion, "e.g. Work, Personal", "Add") { n ->
                addPane(n.take(24), s.url)
            }
        }
    }

    private fun cleanUrl(raw: String): String? {
        var v = raw.trim()
        if (v.isEmpty()) return null
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
        val host = Uri.parse(v).host ?: return null
        return if (host.contains(".")) v else null
    }

    private fun addPane(label: String, url: String) {
        val used = panes.map { it.color }.toSet()
        val color = T.PALETTE.firstOrNull { !used.contains(it) } ?: T.PALETTE[panes.size % T.PALETTE.size]
        val p = Pane(newId(), label, color, url)
        p.lastUsed = System.currentTimeMillis()
        panes.add(p)
        if (maximizedId != null) maximizedId = p.id
        focusedId = p.id
        applyLayout()
    }

    /** X button: removes the screen from view and frees memory, but KEEPS the account and its login. */
    private fun closeScreen(p: Pane) {
        flushCookies()
        val v = p.view
        if (v != null) {
            layoutView.removePaneView(v)
            v.detachWeb()
        }
        p.view = null
        p.closed = true
        p.busy = false
        p.doneUnseen = false
        if (maximizedId == p.id) maximizedId = null
        if (focusedId == p.id) {
            focusedId = panes.filter { it.id != p.id && !it.closed }.maxByOrNull { it.lastUsed }?.id
        }
        applyLayout()
        updateKeepAlive()
        toast("${p.label} closed. Login is saved. Tap it in the top bar to reopen.")
    }

    /** Only reachable from Settings > Accounts: removes the account and wipes its saved login. */
    private fun deleteAccount(p: Pane) {
        clearData(p, false)
        val v = p.view
        if (v != null) {
            layoutView.removePaneView(v)
            v.detachWeb()
        }
        p.view = null
        p.busy = false
        panes.remove(p)
        if (profilesOk) {
            val name = profileName(p)
            if (!tryDeleteProfile(name)) addPendingDelete(name)
        }
        if (focusedId == p.id) focusedId = null
        if (maximizedId == p.id) maximizedId = null
        applyLayout()
        updateKeepAlive()
    }

    private fun tryDeleteProfile(name: String): Boolean = try {
        ProfileStore.getInstance().deleteProfile(name)
        true
    } catch (e: Exception) {
        false
    }

    private fun addPendingDelete(name: String) {
        val set = HashSet(prefs.getStringSet("pendingDelete", emptySet()) ?: emptySet())
        set.add(name)
        prefs.edit().putStringSet("pendingDelete", set).apply()
    }

    /** Profiles that were still in use when an account was deleted get removed on the next start. */
    private fun purgePendingProfiles() {
        val set = HashSet(prefs.getStringSet("pendingDelete", emptySet()) ?: emptySet())
        if (set.isEmpty()) return
        val left = HashSet<String>()
        for (n in set) if (!tryDeleteProfile(n)) left.add(n)
        prefs.edit().putStringSet("pendingDelete", left).apply()
    }

    private fun showPaneMenu(p: Pane) {
        val items = listOf(
            SheetItem("Rename") {
                showInput("Rename screen", p.label, "", "Save") { n ->
                    p.label = n.take(24)
                    refreshChrome()
                }
            },
            SheetItem("Change color") {
                showColors(p.color) { c ->
                    p.color = c
                    refreshChrome()
                }
            },
            SheetItem("Reload") { p.view?.web?.reload() },
            SheetItem("Copy link") { copyLink(p) },
            SheetItem("Open in browser") { openExternal(p) },
            SheetItem("Copy diagnostics", "Paste it to the developer if a page stays blank") { copyDiagnostics(p) },
            SheetItem("Finish detector", "Set this site's Stop button selector for alerts") {
                showInput("CSS selector of the Stop button", p.selector, "e.g. button[aria-label*=\"Stop\"]", "Save") { sel ->
                    p.selector = sel
                    p.view?.web?.evaluateJavascript("window.__tandemSel=" + JSONObject.quote(sel), null)
                    saveState()
                }
            },
            SheetItem("Switch page identity", "Try mobile / desktop mode if a site will not load") { switchCompat(p) },
            SheetItem("Close screen", "Keeps the saved login") { closeScreen(p) },
            SheetItem("Account settings…") { showAccountMenu(p) },
        )
        val all = if (p.closed) listOf(SheetItem("Open screen") { focusPane(p) }) + items else items
        showSheet(p.label, all)
    }

    private fun copyLink(p: Pane) {
        val url = p.view?.web?.url ?: p.currentUrl
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", url))
        toast("Link copied")
    }

    private fun openExternal(p: Pane) {
        val url = p.view?.web?.url ?: p.currentUrl
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No browser found")
        }
    }

    /* ================================================================ */
    /* WebViews                                                          */
    /* ================================================================ */

    private fun profileName(p: Pane): String = "tandem_" + p.id

    private fun ensureView(p: Pane): PaneView {
        p.view?.let { return it }
        val pv = PaneView(this, p)
        pv.onFocus = { onPaneTouched(p) }
        pv.onMenu = { showPaneMenu(p) }
        pv.btnReload.setOnClickListener {
            p.blankTries = 0
            pv.hideProblem()
            p.view?.web?.reload()
        }
        pv.btnMin.setOnClickListener { minimizePane(p) }
        pv.btnMax.setOnClickListener { toggleMaximize(p) }
        pv.btnClose.setOnClickListener { closeScreen(p) }
        layoutView.addPaneView(pv)
        p.view = pv
        buildWeb(p, pv)
        return pv
    }

    private fun buildWeb(p: Pane, pv: PaneView) {
        val w = WebView(this)
        if (profilesOk) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(profileName(p))
                WebViewCompat.setProfile(w, profileName(p))
            } catch (e: Exception) {
                p.note("profile error: $e")
            }
        }
        configure(w, p)
        pv.attachWeb(w)
        // Load only after the screen is visible and has a real size. Loading into a hidden,
        // zero-size WebView is one cause of the empty white screen.
        pv.whenSized { if (p.view === pv && pv.web === w) w.loadUrl(p.currentUrl) }
    }

    private fun recreateWeb(p: Pane) {
        val pv = p.view ?: return
        pv.detachWeb()
        pv.hideProblem()
        buildWeb(p, pv)
    }

    private fun userAgentFor(mode: Int): String {
        val base = WebSettings.getDefaultUserAgent(this)
        val ver = Regex("Chrome/([\\d.]+)").find(base)?.groupValues?.get(1) ?: "124.0.0.0"
        return if (mode == 1) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
        } else if (mode == 2) {
            base
        } else {
            base.replace("; wv", "")
                .replace(Regex(" Build/[^;)]*"), "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
    }

    private fun switchCompat(p: Pane) {
        p.compat = (p.compat + 1) % 3
        p.blankTries = 0
        val w = p.view?.web
        if (w != null) {
            w.settings.userAgentString = userAgentFor(p.compat)
            p.view?.hideProblem()
            w.reload()
        }
        toast(
            when (p.compat) {
                1 -> "Desktop identity on"
                2 -> "Original WebView identity on"
                else -> "Mobile Chrome identity on"
            },
        )
        saveState()
    }

    private fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // ignore
        }
        if (profilesOk) {
            for (p in panes) {
                if (p.closed && p.view == null) continue
                try {
                    ProfileStore.getInstance().getOrCreateProfile(profileName(p)).cookieManager.flush()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun cookieManagerFor(p: Pane): CookieManager {
        if (profilesOk) {
            try {
                return ProfileStore.getInstance().getOrCreateProfile(profileName(p)).cookieManager
            } catch (e: Exception) {
                // fall through
            }
        }
        return CookieManager.getInstance()
    }

    private fun siteKey(host: String?): String? {
        if (host == null) return null
        val parts = host.split(".")
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }

    private fun configure(w: WebView, p: Pane) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Many sign-in pages reject the default WebView identity, so present as plain Chrome.
        s.userAgentString = userAgentFor(p.compat)
        // WebView adds an "X-Requested-With: <app id>" header that Google, Cloudflare and others use to
        // detect embedded browsers. Turn it off.
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            }
        } catch (e: Exception) {
            p.note("header allow-list: $e")
        }

        try {
            val cm = cookieManagerFor(p)
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(w, true)
        } catch (e: Exception) {
            // ignore
        }

        w.addJavascriptInterface(Bridge(p), "Tandem")

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                if (scheme == "http" || scheme == "https") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    // no app can handle it
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                onBusyChanged(p, false, false)
                p.view?.hideProblem()
                p.view?.setLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                p.view?.setLoading(false)
                view.evaluateJavascript("window.__tandemSel=" + JSONObject.quote(p.selector), null)
                view.evaluateJavascript(DETECT_JS, null)
                // Save the login to disk right away so it survives the app being swiped away or killed.
                try {
                    cookieManagerFor(p).flush()
                } catch (e: Exception) {
                    // ignore
                }
                view.postDelayed({ checkBlank(p, view) }, 3500)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                p.note("load error ${error.errorCode} ${error.description} @ ${request.url}")
                if (request.isForMainFrame) {
                    val host = request.url.host ?: "this site"
                    p.view?.showProblem(
                        "Can't open $host",
                        error.description?.toString() ?: "Check your internet connection.",
                        "Try again", { p.view?.web?.reload() },
                        "Open in browser", { openExternal(p) },
                    )
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                p.note("HTTP ${resp.statusCode} @ ${request.url}")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                p.note("render process gone (crashed=${detail.didCrash()})")
                runOnUiThread { recreateWeb(p) }
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    p.currentUrl = url
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                p.view?.setProgress(newProgress)
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    p.note("JS error: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: return false
                    startActivityForResult(intent, reqFile)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    val sameSite = siteKey(request.origin?.host) == siteKey(Uri.parse(p.startUrl).host)
                    if (!wantsAudio || !sameSite) {
                        request.deny()
                    } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingPermission = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqMic)
                    }
                }
            }
        }

        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(p, url, userAgent, contentDisposition, mimeType)
        }
    }

    /* ================================================================ */
    /* Blank-page recovery + diagnostics                                 */
    /* ================================================================ */

    private fun checkBlank(p: Pane, view: WebView) {
        if (p.view?.web !== view) return
        view.evaluateJavascript(BLANK_JS) { raw ->
            if (p.view?.web !== view) return@evaluateJavascript
            val r = (raw ?: "").trim('"')
            val parts = r.split(",")
            val text = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val nodes = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val url = view.url ?: ""
            val skip = url.isEmpty() || url == "about:blank"
            val blank = !skip && text < 1 && nodes < 25
            if (!blank) {
                p.blankTries = 0
                return@evaluateJavascript
            }
            p.note("blank page detected ($r) after try ${p.blankTries} @ $url")
            when (p.blankTries) {
                0 -> {
                    p.blankTries = 1
                    view.reload()
                }
                1, 2 -> {
                    p.blankTries = p.blankTries + 1
                    p.compat = (p.compat + 1) % 3
                    view.settings.userAgentString = userAgentFor(p.compat)
                    view.reload()
                    saveState()
                }
                else -> {
                    p.view?.showProblem(
                        "This page loaded blank",
                        "The site did not draw anything. Try again, or use Copy diagnostics in the screen menu (long-press the header) and send it to the developer.",
                        "Try again", { p.blankTries = 0; p.view?.web?.reload() },
                        "Open in browser", { openExternal(p) },
                    )
                }
            }
        }
    }

    private fun copyDiagnostics(p: Pane) {
        val wv = try {
            WebViewCompat.getCurrentWebViewPackage(this)?.let { it.packageName + " " + it.versionName } ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        val sb = StringBuilder()
        sb.append("Tandem 5.1 diagnostics\n")
        sb.append("Screen: ${p.label} (${p.id})\n")
        sb.append("Start URL: ${p.startUrl}\n")
        sb.append("Current URL: ${p.view?.web?.url ?: p.currentUrl}\n")
        sb.append("Identity mode: ${p.compat}\n")
        sb.append("Separate logins supported: $profilesOk\n")
        sb.append("WebView: $wv\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Recent problems:\n")
        if (p.log.isEmpty()) sb.append("(none recorded)\n") else p.log.forEach { sb.append("- ").append(it).append("\n") }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("diagnostics", sb.toString()))
        toast("Diagnostics copied. Paste it in your message.")
    }

    /* ================================================================ */
    /* Settings / Accounts                                               */
    /* ================================================================ */

    private fun showSettings() {
        val themeName = when (themeMode) {
            1 -> "Dark"
            2 -> "Light"
            else -> "Follow system"
        }
        val items = listOf(
            SheetItem("Ask all open screens", "Send one question to every visible AI (beta)") { showAskAll() },
            SheetItem("Theme", themeName) {
                showSheet(
                    "Theme",
                    listOf(
                        SheetItem("Follow system", if (themeMode == 0) "Selected" else null) { setThemeMode(0) },
                        SheetItem("Dark", if (themeMode == 1) "Selected" else null) { setThemeMode(1) },
                        SheetItem("Light", if (themeMode == 2) "Selected" else null) { setThemeMode(2) },
                    ),
                )
            },
            SheetItem("Accounts", "${panes.size} saved. Rename, sign out or delete") { showAccounts() },
            SheetItem("Keep logins safe", "Phone settings that stop the system closing Tandem") {
                showInfo(
                    "Keep logins safe",
                    "Tandem saves every login to the phone. Some phones (Xiaomi, Redmi, Poco, Oppo, Vivo, Realme, Samsung) " +
                        "close apps in the background and can lose a login that was still being typed in. " +
                        "Open Settings > Apps > Tandem, then turn on Autostart, set Battery to \"No restrictions\", " +
                        "and lock Tandem in the Recent apps screen.",
                )
            },
            SheetItem("App lock", if (prefs.getBoolean("lock", false)) "On. Uses your phone unlock" else "Off. Ask for phone unlock to open Tandem") { toggleLock() },
            SheetItem("Copy last crash report", if (prefs.getString("crash", null) != null) "A crash was recorded" else "No crash recorded") {
                val c = prefs.getString("crash", null)
                if (c == null) {
                    toast("No crash recorded")
                } else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", c))
                    toast("Crash report copied")
                }
            },
            SheetItem("About Tandem", "Version 5.1") {
                showInfo(
                    "Tandem 5.1",
                    "Run several AI accounts side by side with a separate saved login for each. " +
                        "Your logins stay on this phone inside the app.",
                )
            },
            SheetItem("Copy diagnostics for all screens") {
                for (p in panes) copyDiagnosticsText(p)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("diagnostics", allDiagnostics))
                allDiagnostics = ""
                toast("Diagnostics copied")
            },
        )
        showSheet("Settings", items)
    }

    private fun showAskAll() {
        val targets = shownPanes().filter { it.view?.web != null }
        if (targets.isEmpty()) {
            toast("Open at least one screen first")
            return
        }
        showInput("Ask ${targets.size} screen" + (if (targets.size > 1) "s" else ""), "", "Type your question", "Send") { text ->
            val js = "(" + SEND_JS + ")(" + JSONObject.quote(text) + ")"
            for (p in targets) p.view?.web?.evaluateJavascript(js, null)
            toast("Sent to ${targets.size} screen" + (if (targets.size > 1) "s" else ""))
        }
    }

    private var allDiagnostics = ""
    private fun copyDiagnosticsText(p: Pane) {
        allDiagnostics += "== ${p.label}: ${p.view?.web?.url ?: p.currentUrl} (mode ${p.compat})\n"
        p.log.forEach { allDiagnostics += "- $it\n" }
    }

    private fun showAccounts() {
        if (panes.isEmpty()) {
            showInfo("No accounts", "Tap + in the top bar to add one.")
            return
        }
        val items = panes.map { p ->
            val host = Uri.parse(p.startUrl).host?.removePrefix("www.") ?: "website"
            val state = if (p.closed) "closed" else if (p.minimized) "minimized" else "open"
            SheetItem(p.label, "$host, $state", p.color) { showAccountMenu(p) }
        }
        showSheet("Accounts", items)
    }

    private fun showAccountMenu(p: Pane) {
        val items = listOf(
            SheetItem(if (p.closed) "Open screen" else "Go to screen") { focusPane(p) },
            SheetItem("Rename") {
                showInput("Rename account", p.label, "", "Save") { n ->
                    p.label = n.take(24)
                    refreshChrome()
                }
            },
            SheetItem("Change color") {
                showColors(p.color) { c ->
                    p.color = c
                    refreshChrome()
                }
            },
            SheetItem("Sign out (keeps the screen)", danger = true) {
                showConfirm(
                    "Sign out of ${p.label}?",
                    if (profilesOk) "Its saved login and site data will be deleted. The screen stays."
                    else "Screens share one login on this phone, so this signs out of all of them.",
                    "Sign out", true,
                ) { clearData(p, true) }
            },
            SheetItem("Delete account", "Removes the screen and its saved login", danger = true) {
                showConfirm(
                    "Delete ${p.label}?",
                    "The screen and its saved login are removed from this phone. Your account on the website is not affected.",
                    "Delete", true,
                ) { deleteAccount(p) }
            },
        )
        showSheet(p.label, items)
    }

    /* ================================================================ */
    /* Response-finished detection                                       */
    /* ================================================================ */

    inner class Bridge(private val pane: Pane) {
        @JavascriptInterface
        fun state(busy: Boolean) {
            runOnUiThread { onBusyChanged(pane, busy, true) }
        }

        @JavascriptInterface
        fun saveBlob(token: String, b64: String, mime: String, name: String) {
            if (!blobTokens.remove(token)) return
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
                saveToDownloads(bytes, mime.ifBlank { "application/octet-stream" }, safe)
                runOnUiThread { toast("Saved to Downloads: $safe") }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed") }
            }
        }
    }

    private fun onBusyChanged(p: Pane, busy: Boolean, notify: Boolean) {
        if (p.busy == busy) return
        p.busy = busy
        if (busy) {
            p.doneUnseen = false
            maybeAskNotificationPermission()
        } else if (notify) {
            val shown = shownPanes().any { it.id == p.id }
            val looking = appVisible && shown && p.id == focusedId
            if (!appVisible || !shown) Notifier.done(this, p)
            if (looking) {
                p.view?.flashDone()
            } else {
                p.doneUnseen = true
            }
        }
        refreshChrome()
        updateKeepAlive()
    }

    private fun updateKeepAlive() {
        val count = panes.count { it.busy }
        if (count == lastBusyCount) return
        lastBusyCount = count
        try {
            val i = Intent(this, KeepAliveService::class.java)
            if (count > 0) {
                i.putExtra("count", count)
                startForegroundService(i)
            } else {
                stopService(i)
            }
        } catch (e: Exception) {
            // background start not allowed; responses still finish while the app is open
        }
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotif", false)
        ) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
        }
    }

    /* ================================================================ */
    /* Downloads                                                         */
    /* ================================================================ */

    private fun startDownload(p: Pane, url: String, ua: String?, cd: String?, mime: String?) {
        if (url.startsWith("blob:")) {
            blobDownload(p, url, cd, mime)
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("Unsupported download")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            val cookie = cookieManagerFor(p).getCookie(url)
            if (!cookie.isNullOrEmpty()) req.addRequestHeader("Cookie", cookie)
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= 29) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Cannot download this file")
        }
    }

    private fun blobDownload(p: Pane, url: String, cd: String?, mime: String?) {
        val token = UUID.randomUUID().toString()
        blobTokens.add(token)
        val name = URLUtil.guessFileName(url, cd, mime)
        val js = "(function(){fetch(" + JSONObject.quote(url) + ").then(function(r){return r.blob();})" +
            ".then(function(b){var f=new FileReader();f.onloadend=function(){var s=String(f.result);" +
            "Tandem.saveBlob(" + JSONObject.quote(token) + ",s.substring(s.indexOf(',')+1),b.type||''," +
            JSONObject.quote(name) + ");};f.readAsDataURL(b);});})();"
        p.view?.web?.evaluateJavascript(js, null)
    }

    private fun saveToDownloads(bytes: ByteArray, mime: String, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues()
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
            cv.put(MediaStore.Downloads.MIME_TYPE, mime)
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("no uri")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            File(dir, name).writeBytes(bytes)
        }
    }

    /* ================================================================ */
    /* Sign out                                                          */
    /* ================================================================ */

    private fun clearData(p: Pane, reload: Boolean) {
        try {
            val cm = cookieManagerFor(p)
            cm.removeAllCookies(null)
            cm.flush()
            if (profilesOk) {
                ProfileStore.getInstance().getOrCreateProfile(profileName(p)).webStorage.deleteAllData()
            } else {
                WebStorage.getInstance().deleteAllData()
            }
        } catch (e: Exception) {
            // ignore
        }
        val w = p.view?.web
        w?.clearCache(true)
        w?.clearHistory()
        if (reload) {
            p.currentUrl = p.startUrl
            w?.loadUrl(p.startUrl)
            toast("Signed out of ${p.label}")
        }
    }

    /* ================================================================ */
    /* Persistence                                                       */
    /* ================================================================ */

    private fun saveState() {
        val arr = JSONArray()
        for (p in panes) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("label", p.label)
            o.put("color", p.color)
            o.put("start", p.startUrl)
            o.put("url", p.currentUrl)
            o.put("min", p.minimized)
            o.put("closed", p.closed)
            o.put("compat", p.compat)
            o.put("sel", p.selector)
            o.put("used", p.lastUsed)
            arr.put(o)
        }
        prefs.edit()
            .putString("panes2", arr.toString())
            .putString("focused", focusedId)
            .putString("max", maximizedId)
            .putFloat("ratio", ratio)
            .apply()
    }

    private fun loadState() {
        try {
            val arr = JSONArray(prefs.getString("panes2", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Pane(o.getString("id"), o.getString("label"), o.getInt("color"), o.getString("start"))
                p.currentUrl = o.optString("url", p.startUrl)
                p.minimized = o.optBoolean("min", false)
                p.closed = o.optBoolean("closed", false)
                p.compat = o.optInt("compat", 0)
                p.selector = o.optString("sel", "")
                p.lastUsed = o.optLong("used", 0L)
                panes.add(p)
            }
            focusedId = prefs.getString("focused", null)
            maximizedId = prefs.getString("max", null)
            ratio = prefs.getFloat("ratio", 0.5f).coerceIn(0.25f, 0.75f)
        } catch (e: Exception) {
            panes.clear()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * Injected into every page. Watches for the "stop generating" button that
         * chat sites show while an answer streams, and reports busy/idle to the app.
         * Selectors are best-effort and may need updating when a site changes its UI.
         */
        /** Types a message into the page's chat box and presses send. Best effort; works on most chat sites. */
        private val SEND_JS = """
            function(t){
              function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
              var c=[].slice.call(document.querySelectorAll('textarea,[contenteditable="true"],div[role="textbox"]')).filter(vis);
              if(!c.length) return 'noinput';
              var el=c[c.length-1];
              el.focus();
              if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){
                var setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value').set;
                setter.call(el,t);
                el.dispatchEvent(new Event('input',{bubbles:true}));
              } else {
                document.execCommand('selectAll',false,null);
                document.execCommand('insertText',false,t);
              }
              setTimeout(function(){
                var sels=['button[data-testid="send-button"]','button[data-testid="composer-submit-button"]',
                  'button[aria-label="Send message"]','button[aria-label*="Send" i]','button[type="submit"]'];
                var b=null;
                for(var i=0;i<sels.length&&!b;i++){
                  var q=document.querySelectorAll(sels[i]);
                  for(var j=0;j<q.length;j++){ if(vis(q[j])&&!q[j].disabled){b=q[j];break;} }
                }
                if(b){ b.click(); }
                else {
                  var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};
                  el.dispatchEvent(new KeyboardEvent('keydown',o));
                  el.dispatchEvent(new KeyboardEvent('keyup',o));
                }
              },450);
              return 'ok';
            }
        """.trimIndent()

        private val BLANK_JS = """
            (function(){try{var b=document.body;if(!b)return '0,0';
            var t=(b.innerText||'').trim().length;
            var n=b.getElementsByTagName('*').length;
            return t+','+n;}catch(e){return '1,99';}})()
        """.trimIndent()

        private val DETECT_JS = """
            (function(){
              if (window.__tandem) return;
              window.__tandem = true;
              var sels = [
                'button[aria-label="Stop response"]',
                'button[aria-label="Stop streaming"]',
                'button[aria-label="Stop generating"]',
                'button[aria-label="Stop"]',
                'button[data-testid="stop-button"]',
                'button[data-testid="stop-generating-button"]',
                'button[aria-label*="Stop answer" i]',
                'button[aria-label*="Stop generat" i]',
                'button[aria-label*="Stop respon" i]',
                'button[aria-label*="Stop messag" i]',
                'button[aria-label^="Stop" i]',
                'button[title^="Stop" i]',
                '[data-testid*="stop" i]'
              ];
              var last = false, idle = 0;
              setInterval(function(){
                var b = false;
                var list = window.__tandemSel ? sels.concat([window.__tandemSel]) : sels;
                for (var i = 0; i < list.length; i++) {
                  try { if (document.querySelector(list[i])) { b = true; break; } } catch (e) {}
                }
                if (b) {
                  idle = 0;
                  if (!last) { last = true; try { Tandem.state(true); } catch (e) {} }
                } else if (last) {
                  idle++;
                  if (idle >= 3) { last = false; idle = 0; try { Tandem.state(false); } catch (e) {} }
                }
              }, 500);
            })();
        """.trimIndent()
    }
}

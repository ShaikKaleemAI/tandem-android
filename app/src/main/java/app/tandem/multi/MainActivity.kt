package app.tandem.multi

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
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

    private lateinit var prefs: SharedPreferences
    private lateinit var layoutView: PaneLayout
    private lateinit var dock: LinearLayout
    private lateinit var emptyView: View

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
        window.statusBarColor = T.INK
        window.navigationBarColor = T.INK
        Notifier.ensureChannels(this)

        buildUi()
        loadState()
        if (panes.isEmpty() && !prefs.getBoolean("seen", false)) {
            prefs.edit().putBoolean("seen", true).apply()
            addPane("Claude", Services.all[0].url)
        } else {
            applyLayout()
        }
        handleIntent(intent)

        if (!profilesOk) {
            showInfo(
                "Separate logins unavailable",
                "This phone's Android System WebView is too old to keep a different login in each screen. " +
                    "Update \"Android System WebView\" and \"Chrome\" in the Play Store, then reopen Tandem. " +
                    "Until then all screens share one login.",
            )
        }
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
        val f = panes.firstOrNull { it.id == focusedId }
        if (f != null && f.doneUnseen) {
            f.doneUnseen = false
            Notifier.cancel(this, f)
            refreshChrome()
        }
    }

    override fun onPause() {
        appVisible = false
        saveState()
        super.onPause()
    }

    override fun onDestroy() {
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
        if (requestCode == reqFile) {
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
        bar.addView(layoutBtn, LinearLayout.LayoutParams(dp(42), dp(42)))

        val addBtn = IconButton(this, IconButton.PLUS, T.INK, T.ACCENT)
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
        setContentView(root)
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
        s.textSize = 14f
        s.setTextColor(T.MUTED)
        s.gravity = Gravity.CENTER
        box.addView(s)
        val b = TextView(this)
        b.text = "Add a screen"
        b.textSize = 15f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(T.INK)
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

    private fun visiblePanes(): List<Pane> = panes.filter { !it.minimized }

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
            if (m == null) maximizedId = null else m.minimized = false
        }
        if (panes.none { !it.minimized }) {
            panes.first { it.id == focusedId }.minimized = false
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
                canMin = panes.size > 1,
            )
            v.render()
        }
        dock.removeAllViews()
        for (p in panes) dock.addView(makeChip(p, shownIds.contains(p.id)))
        emptyView.visibility = if (panes.isEmpty()) View.VISIBLE else View.GONE
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
        panes.forEach { it.minimized = !keep.contains(it.id) }
        applyLayout()
    }

    private fun minimizePane(p: Pane) {
        p.minimized = true
        if (maximizedId == p.id) maximizedId = null
        val next = panes.filter { it.id != p.id && !it.minimized }.maxByOrNull { it.lastUsed }
            ?: panes.filter { it.id != p.id }.maxByOrNull { it.lastUsed }
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

    private fun closePane(p: Pane) {
        clearData(p, false)
        val v = p.view
        if (v != null) {
            layoutView.removePaneView(v)
            v.detachWeb()
        }
        p.view = null
        panes.remove(p)
        if (profilesOk) {
            try {
                ProfileStore.getInstance().deleteProfile(profileName(p))
            } catch (e: Exception) {
                // still in use; data was already cleared
            }
        }
        if (focusedId == p.id) focusedId = null
        if (maximizedId == p.id) maximizedId = null
        applyLayout()
        updateKeepAlive()
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
            SheetItem("Sign out and clear data", danger = true) {
                showConfirm(
                    "Sign out of ${p.label}?",
                    if (profilesOk) "Its saved login and site data will be deleted."
                    else "Screens share one login on this phone, so this signs out of all of them.",
                    "Sign out", true,
                ) { clearData(p, true) }
            },
            SheetItem("Close screen", danger = true) {
                showConfirm(
                    "Close ${p.label}?",
                    "The screen is removed and its saved login is deleted. Use minimize to keep it.",
                    "Close", true,
                ) { closePane(p) }
            },
        )
        showSheet(p.label, items)
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
        val w = WebView(this)
        if (profilesOk) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(profileName(p))
                WebViewCompat.setProfile(w, profileName(p))
            } catch (e: Exception) {
                // falls back to the shared default profile
            }
        }
        configure(w, p)
        pv.attachWeb(w)
        pv.onFocus = { onPaneTouched(p) }
        pv.onMenu = { showPaneMenu(p) }
        pv.btnReload.setOnClickListener { p.view?.web?.reload() }
        pv.btnMin.setOnClickListener { minimizePane(p) }
        pv.btnMax.setOnClickListener { toggleMaximize(p) }
        pv.btnClose.setOnClickListener {
            showConfirm(
                "Close ${p.label}?",
                "The screen is removed and its saved login is deleted. Use minimize to keep it.",
                "Close", true,
            ) { closePane(p) }
        }
        layoutView.addPaneView(pv)
        p.view = pv
        w.loadUrl(p.currentUrl)
        return pv
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
        // Many sign-in pages reject the default WebView user agent, so present as plain Chrome.
        s.userAgentString = s.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")

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
                p.view?.setLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                p.view?.setLoading(false)
                view.evaluateJavascript(DETECT_JS, null)
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
                'button[aria-label*="Stop messag" i]'
              ];
              var last = false, idle = 0;
              setInterval(function(){
                var b = false;
                for (var i = 0; i < sels.length; i++) {
                  try { if (document.querySelector(sels[i])) { b = true; break; } } catch (e) {}
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

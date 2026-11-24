package com.zillium.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zillium.browser.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    data class BrowserTab(
        val webView: WebView,
        var title: String = "Новая вкладка",
        var url: String = HOME_URL,
        var isDesktopMode: Boolean = false
    )

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = 0
    private var isIncognito = false
    private var customFullscreenView: View? = null
    private var customFullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var defaultUserAgent: String = ""

    companion object {
        private const val HOME_URL = "file:///android_asset/home.html"
        private const val SEARCH_ENGINE_URL = "https://duckduckgo.com/?q="
        private const val PREFS_NAME = "zillium_prefs"
        private const val KEY_BOOKMARKS = "bookmarks_json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBottomBar()
        setupSwipeRefresh()
        setupBackNavigation()

        val initialUrl = intent?.dataString ?: HOME_URL
        createNewTab(initialUrl, switchTo = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.dataString
        if (!url.isNullOrBlank()) {
            createNewTab(url, switchTo = true)
        }
    }

    private fun setupToolbar() {
        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (input.isNotEmpty()) {
                    loadInputUrl(input)
                    hideKeyboard()
                    binding.etUrl.clearFocus()
                }
                true
            } else {
                false
            }
        }

        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            binding.btnClearUrl.visibility =
                if (hasFocus && !binding.etUrl.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        }

        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearUrl.visibility =
                    if (binding.etUrl.hasFocus() && !s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
        }

        binding.btnReload.setOnClickListener {
            currentWebView()?.reload()
        }

        binding.btnMenu.setOnClickListener { view ->
            showBrowserMenu(view)
        }
    }

    private fun setupBottomBar() {
        binding.btnBack.setOnClickListener {
            val webView = currentWebView()
            if (webView?.canGoBack() == true) {
                webView.goBack()
            }
        }

        binding.btnForward.setOnClickListener {
            val webView = currentWebView()
            if (webView?.canGoForward() == true) {
                webView.goForward()
            }
        }

        binding.btnHome.setOnClickListener {
            currentWebView()?.loadUrl(HOME_URL)
        }

        binding.btnTabs.setOnClickListener {
            showTabsDialog()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.zillium_primary),
            ContextCompat.getColor(this, R.color.zillium_accent)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.zillium_surface)
        )
        binding.swipeRefresh.setOnRefreshListener {
            currentWebView()?.reload()
        }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            (currentWebView()?.scrollY ?: 0) > 0
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customFullscreenView != null) {
                    exitFullscreen()
                    return
                }
                val webView = currentWebView()
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                } else if (tabs.size > 1) {
                    closeTab(currentTabIndex)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(initialUrl: String = HOME_URL, switchTo: Boolean = true) {
        val webView = WebView(this)
        if (defaultUserAgent.isEmpty()) {
            defaultUserAgent = webView.settings.userAgentString
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = !isIncognito
            databaseEnabled = !isIncognito
            cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        CookieManager.getInstance().setAcceptCookie(!isIncognito)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !isIncognito)

        val tab = BrowserTab(webView = webView, url = initialUrl)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUri = request?.url ?: return false
                val scheme = targetUri.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "file") {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, targetUri))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null) {
                    tab.url = url
                    if (view == currentWebView()) {
                        updateAddressBar(url)
                        updateNavButtons()
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    tab.url = url
                }
                if (!view?.title.isNullOrBlank()) {
                    tab.title = view?.title ?: tab.title
                }
                if (view == currentWebView()) {
                    binding.swipeRefresh.isRefreshing = false
                    updateAddressBar(tab.url)
                    updateNavButtons()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view != currentWebView()) return
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tab.title = title
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customFullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customFullscreenView = view
                customFullscreenCallback = callback
                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    setTitle(fileName)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Загрузка начата", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        tabs.add(tab)
        updateTabBadge()

        if (switchTo) {
            switchToTab(tabs.lastIndex)
        }

        webView.loadUrl(initialUrl)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index
        val tab = tabs[index]
        binding.webContainer.removeAllViews()
        binding.webContainer.addView(tab.webView)
        updateAddressBar(tab.url)
        updateNavButtons()
        updateTabBadge()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        binding.webContainer.removeView(removed.webView)
        removed.webView.destroy()

        if (tabs.isEmpty()) {
            createNewTab(HOME_URL, switchTo = true)
        } else {
            val nextIndex = index.coerceAtMost(tabs.lastIndex)
            switchToTab(nextIndex)
        }
        updateTabBadge()
    }

    private fun currentTab(): BrowserTab? = tabs.getOrNull(currentTabIndex)

    private fun currentWebView(): WebView? = currentTab()?.webView

    private fun loadInputUrl(rawInput: String) {
        val targetUrl = when {
            rawInput.startsWith("http://", ignoreCase = true) ||
                rawInput.startsWith("https://", ignoreCase = true) ||
                rawInput.startsWith("file://", ignoreCase = true) -> rawInput

            !rawInput.contains(" ") &&
                (Patterns.WEB_URL.matcher(rawInput).matches() || rawInput.contains(".")) ->
                "https://$rawInput"

            else -> SEARCH_ENGINE_URL + Uri.encode(rawInput)
        }
        currentWebView()?.loadUrl(targetUrl)
    }

    private fun updateAddressBar(url: String) {
        if (!binding.etUrl.hasFocus()) {
            if (url == HOME_URL) {
                binding.etUrl.setText("")
            } else {
                binding.etUrl.setText(url)
            }
        }
        val isSecure = url.startsWith("https://") || url == HOME_URL
        binding.ivSecurity.alpha = if (isSecure) 1.0f else 0.35f
    }

    private fun updateNavButtons() {
        val webView = currentWebView()
        val canBack = webView?.canGoBack() == true
        val canForward = webView?.canGoForward() == true
        binding.btnBack.isEnabled = canBack
        binding.btnBack.alpha = if (canBack) 1.0f else 0.35f
        binding.btnForward.isEnabled = canForward
        binding.btnForward.alpha = if (canForward) 1.0f else 0.35f
    }

    private fun updateTabBadge() {
        binding.tvTabCount.text = tabs.size.toString()
    }

    private fun showBrowserMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        popup.menu.findItem(R.id.action_incognito)?.isChecked = isIncognito
        popup.menu.findItem(R.id.action_desktop_site)?.isChecked =
            currentTab()?.isDesktopMode == true

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_tab -> {
                    createNewTab(HOME_URL, switchTo = true)
                    true
                }
                R.id.action_incognito -> {
                    toggleIncognito()
                    true
                }
                R.id.action_desktop_site -> {
                    toggleDesktopSite()
                    true
                }
                R.id.action_add_bookmark -> {
                    addCurrentPageBookmark()
                    true
                }
                R.id.action_bookmarks -> {
                    showBookmarksDialog()
                    true
                }
                R.id.action_share -> {
                    shareCurrentPage()
                    true
                }
                R.id.action_clear_data -> {
                    clearBrowserData()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleIncognito() {
        isIncognito = !isIncognito
        val barColor = ContextCompat.getColor(
            this,
            if (isIncognito) R.color.zillium_incognito_surface else R.color.zillium_surface
        )
        binding.topBar.setBackgroundColor(barColor)

        tabs.forEach { tab ->
            tab.webView.settings.domStorageEnabled = !isIncognito
            tab.webView.settings.cacheMode =
                if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, !isIncognito)
        }
        CookieManager.getInstance().setAcceptCookie(!isIncognito)

        val msgRes = if (isIncognito) R.string.toast_incognito_on else R.string.toast_incognito_off
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
    }

    private fun toggleDesktopSite() {
        val tab = currentTab() ?: return
        tab.isDesktopMode = !tab.isDesktopMode
        tab.webView.settings.userAgentString = if (tab.isDesktopMode) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            defaultUserAgent
        }
        tab.webView.reload()
    }

    private fun showTabsDialog() {
        val titles = tabs.mapIndexed { idx, tab ->
            val prefix = if (idx == currentTabIndex) "● " else "○ "
            "$prefix${tab.title}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.tabs_title)
            .setItems(titles) { _, which ->
                switchToTab(which)
            }
            .setPositiveButton(R.string.btn_new_tab) { _, _ ->
                createNewTab(HOME_URL, switchTo = true)
            }
            .setNeutralButton("Закрыть текущую") { _, _ ->
                closeTab(currentTabIndex)
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun addCurrentPageBookmark() {
        val tab = currentTab() ?: return
        if (tab.url == HOME_URL || tab.url.isBlank()) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        val arr = JSONArray(raw)

        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("url") == tab.url) {
                Toast.makeText(this, R.string.toast_bookmark_added, Toast.LENGTH_SHORT).show()
                return
            }
        }

        val entry = JSONObject().apply {
            put("title", tab.title.ifBlank { tab.url })
            put("url", tab.url)
        }
        arr.put(entry)
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
        Toast.makeText(this, R.string.toast_bookmark_added, Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarksDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        val arr = JSONArray(raw)

        if (arr.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks_title)
                .setMessage(R.string.bookmarks_empty)
                .setPositiveButton(R.string.btn_close, null)
                .show()
            return
        }

        val labels = Array(arr.length()) { i ->
            val obj = arr.getJSONObject(i)
            "${obj.optString("title")}\n${obj.optString("url")}"
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.bookmarks_title)
            .setItems(labels) { _, which ->
                val url = arr.getJSONObject(which).optString("url")
                if (url.isNotEmpty()) {
                    currentWebView()?.loadUrl(url)
                }
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun shareCurrentPage() {
        val url = currentTab()?.url ?: return
        if (url == HOME_URL) return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.menu_share)))
    }

    private fun clearBrowserData() {
        currentWebView()?.clearCache(true)
        tabs.forEach {
            it.webView.clearHistory()
            it.webView.clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        updateNavButtons()
        Toast.makeText(this, R.string.toast_data_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun exitFullscreen() {
        customFullscreenView?.let { binding.fullscreenContainer.removeView(it) }
        customFullscreenView = null
        binding.fullscreenContainer.visibility = View.GONE
        customFullscreenCallback?.onCustomViewHidden()
        customFullscreenCallback = null
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}

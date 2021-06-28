package com.jamal2367.styx.view

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.*
import android.net.Uri
import android.net.http.SslCertificate
import android.os.*
import android.print.*
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.View.OnScrollChangeListener
import android.view.View.OnTouchListener
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebSettings.LayoutAlgorithm
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.collection.ArrayMap
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import com.jamal2367.styx.Capabilities
import com.jamal2367.styx.R
import com.jamal2367.styx.browser.TabModel
import com.jamal2367.styx.constant.*
import com.jamal2367.styx.controller.UIController
import com.jamal2367.styx.database.DomainSettings
import com.jamal2367.styx.di.DatabaseScheduler
import com.jamal2367.styx.di.MainScheduler
import com.jamal2367.styx.di.injector
import com.jamal2367.styx.dialog.StyxDialogBuilder
import com.jamal2367.styx.download.StyxDownloadListener
import com.jamal2367.styx.extensions.addAction
import com.jamal2367.styx.extensions.canScrollVertically
import com.jamal2367.styx.extensions.makeSnackbar
import com.jamal2367.styx.isSupported
import com.jamal2367.styx.log.Logger
import com.jamal2367.styx.network.NetworkConnectivityModel
import com.jamal2367.styx.preference.UserPreferences
import com.jamal2367.styx.preference.userAgent
import com.jamal2367.styx.settings.fragment.DisplaySettingsFragment.Companion.MIN_BROWSER_TEXT_SIZE
import com.jamal2367.styx.ssl.SslState
import com.jamal2367.styx.utils.*
import com.jamal2367.styx.view.find.FindResults
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * [StyxView] acts as a tab for the browser, handling WebView creation and handling logic, as
 * well as properly initialing it. All interactions with the WebView should be made through this
 * class.
 */
class StyxView(
        private val activity: AppCompatActivity,
        tabInitializer: TabInitializer,
        val isIncognito: Boolean,
        private val homePageInitializer: HomePageInitializer,
        private val incognitoPageInitializer: IncognitoPageInitializer,
        private val bookmarkPageInitializer: BookmarkPageInitializer,
        private val downloadPageInitializer: DownloadPageInitializer,
        private val historyPageInitializer: HistoryPageInitializer,
        private val logger: Logger
) {

    /**
     * The unique ID of the view.
     */
    val id = View.generateViewId()

    /**
     * Getter for the [StyxViewTitle] of the current StyxView instance.
     *
     * @return a NonNull instance of StyxViewTitle
     * @return a NonNull instance of StyxViewTitle
     */
    val titleInfo: StyxViewTitle

    /**
     * Meta theme-color content value as extracted from page HTML
     */
    var htmlMetaThemeColor: Int = KHtmlMetaThemeColorInvalid

    /**
     * Define the number of times we should try to fetch HTML meta tehme-color
     */
    var fetchMetaThemeColorTries = KFetchMetaThemeColorTries

    /**
     * A tab initializer that should be run when the view is first attached.
     */
    private var latentTabInitializer: FreezableBundleInitializer? = null

    /**
     * Gets the current WebView instance of the tab.
     *
     * @return the WebView instance of the tab, which can be null.
     */
    var webView: WebView? = null
        private set

    private lateinit var styxWebClient: StyxWebClient

    /**
     * The URL we tried to load
     */
    private var iTargetUrl: Uri = Uri.parse("")

    private val uiController: UIController
    private lateinit var gestureDetector: GestureDetector
    private val paint = Paint()

    /**
     * Sets whether this tab was the result of a new intent sent to the browser.
     */
    var isNewTab: Boolean = false

    /**
     * This method sets the tab as the foreground tab or the background tab.
     */
    var isForeground: Boolean = false
        set(aIsForeground) {
            field = aIsForeground
            if (isForeground) {
                // When frozen tab goes foreground we need to load its bundle in webView
                latentTabInitializer?.apply {
                    // Lazy creation of our WebView
                    createWebView()
                    // Load bundle in WebView
                    initializeContent(this)
                    // Discard tab initializer since we just consumed it
                    latentTabInitializer = null
                }
            }
            uiController.tabChanged(this)
        }

    /**
     * Gets whether or not the page rendering is inverted or not. The main purpose of this is to
     * indicate that JavaScript should be run at the end of a page load to invert only the images
     * back to their non-inverted states.
     *
     * @return true if the page is in inverted mode, false otherwise.
     */
    var invertPage = false
        private set

    /**
     * True if desktop mode is enabled for this tab.
     */
    var desktopMode = false
        set(aDesktopMode) {
            field = aDesktopMode
            // Set our user agent accordingly
            if (aDesktopMode) {
                webView?.settings?.userAgentString = WINDOWS_DESKTOP_USER_AGENT
            } else {
                setUserAgentForPreference(userPreferences)
            }
        }

    /**
     *
     */
    var darkMode = false
        set(aDarkMode) {
            field = aDarkMode
            applyDarkMode()
        }

    val domainSettings: DomainSettings
        get() = DomainSettings(activity.applicationContext, url)

    /**
     *
     */
    private val webViewHandler = WebViewHandler(this)

    /**
     * This method gets the additional headers that should be added with each request the browser
     * makes.
     *
     * @return a non null Map of Strings with the additional request headers.
     */
    internal val requestHeaders = ArrayMap<String, String>()

    private val maxFling: Float

    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject internal lateinit var dialogBuilder: StyxDialogBuilder
    @Inject @field:DatabaseScheduler internal lateinit var databaseScheduler: Scheduler
    @Inject @field:MainScheduler internal lateinit var mainScheduler: Scheduler
    @Inject lateinit var networkConnectivityModel: NetworkConnectivityModel

    private val networkDisposable: Disposable

    /**
     * This method determines whether the current tab is visible or not.
     *
     * @return true if the WebView is non-null and visible, false otherwise.
     */
    val isShown: Boolean
        get() = webView?.isShown == true

    /**
     * Gets the current progress of the WebView.
     *
     * @return returns a number between 0 and 100 with the current progress of the WebView. If the
     * WebView is null, then the progress returned will be 100.
     */
    val progress: Int
        get() = webView?.progress ?: 100

    /**
     * Get the current user agent used by the WebView.
     *
     * @return retuns the current user agent of the WebView instance, or an empty string if the
     * WebView is null.
     */
    private val userAgent: String
        get() = webView?.settings?.userAgentString ?: ""

    /**
     * Gets the favicon currently in use by the page. If the current page does not have a favicon,
     * it returns a default icon.
     *
     * @return a non-null Bitmap with the current favicon.
     */
    val favicon: Bitmap?
        get() = titleInfo.getFavicon()

    /**
     * Get the current title of the page, retrieved from the title object.
     *
     * @return the title of the page, or an empty string if there is no title.
     */
    val title: String
        get() = titleInfo.getTitle()

    /**
     * Get the current [SslCertificate] if there is any associated with the current page.
     */
    val sslCertificate: SslCertificate?
        get() = webView?.certificate

    /**
     * Get the current URL of the WebView, or an empty string if the WebView is null or the URL is
     * null.
     *
     * @return the current URL or an empty string.
     */
    val url: String
        get() {
            return if (webView == null || webView!!.url.isNullOrBlank() || webView!!.url.isSpecialUrl()) {
                iTargetUrl.toString()
                webView?.url ?: ""
            } else {
                webView!!.url
            }!!
        }

    /**
     * Set that flag when the displayed URL differs from the actual URL loaded in our webView?.
     * That's notably the case for error pages.
     */
    var iHideActualUrl = false

    /**
     * Return true if this tab is frozen, meaning it was not yet loaded from its bundle
     */
    val isFrozen : Boolean
        get() = latentTabInitializer?.tabModel?.webView != null

    /**
     * We had forgotten to unregisterReceiver our download listener thus leaking them all whenever we switched between sessions.
     * It turns out android as a hardcoded limit of 1000 per application.
     * So after a while switching between sessions with many tabs we would get an exception saying:
     * "Too many receivers, total of 1000, registered for pid"
     * See: https://stackoverflow.com/q/58179733/3969362
     */
    private var iDownloadListener: StyxDownloadListener? = null

    /**
     * Constructor
     */
    init {
        activity.injector.inject(this)
        uiController = activity as UIController
        titleInfo = StyxViewTitle(activity)
        maxFling = ViewConfiguration.get(activity).scaledMaximumFlingVelocity.toFloat()

        // Mark our URL
        iTargetUrl = Uri.parse(tabInitializer.url())

        if (tabInitializer !is FreezableBundleInitializer) {
            // Create our WebView now
            createWebView()
            initializeContent(tabInitializer)
            desktopMode = domainSettings.get(DomainSettings.DESKTOP_MODE, userPreferences.desktopModeDefault)
            darkMode = domainSettings.get(DomainSettings.DARK_MODE, userPreferences.darkModeDefault)
        } else {
            // Our WebView will only be created whenever our tab goes to the foreground
            latentTabInitializer = tabInitializer
            titleInfo.setTitle(tabInitializer.tabModel.title)
            titleInfo.setFavicon(tabInitializer.tabModel.favicon)
            desktopMode = tabInitializer.tabModel.desktopMode
            darkMode = tabInitializer.tabModel.darkMode
        }

        networkDisposable = networkConnectivityModel.connectivity()
                .observeOn(mainScheduler)
                .subscribe(::setNetworkAvailable)
    }

    /**
     * Create our WebView.
     */
    @SuppressLint("InflateParams")
    private fun createWebView() {
        styxWebClient = StyxWebClient(activity, this)
        // Inflate our WebView as loading it from XML layout is needed to be able to set scrollbars color
        webView = activity.layoutInflater.inflate(R.layout.webview, null) as WebView
        webView?.apply {
            //id = this@StyxView.id
            gestureDetector = GestureDetector(activity, CustomGestureListener(this))

            isFocusableInTouchMode = true
            isFocusable = true

            setBackgroundColor(ThemeUtils.getBackgroundColor(activity))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            }

            isSaveEnabled = true
            setNetworkAvailable(true)
            webChromeClient = StyxChromeClient(activity, this@StyxView)
            webViewClient = styxWebClient
            // We want to receive download complete notifications
            iDownloadListener = StyxDownloadListener(activity)
            setDownloadListener(StyxDownloadListener(activity))
            // For older devices show Tool Bar On Page Top won't work after fling to top.
            // Who cares? I mean those devices are probably from 2014 or older.
            val tl = TouchListener().also { setOnScrollChangeListener(it) }
            setOnTouchListener(tl)
            initializeSettings()
        }

        initializePreferences()
    }

    fun currentSslState(): SslState = styxWebClient.sslState

    fun sslStateObservable(): Observable<SslState> = styxWebClient.sslStateObservable()

    /**
     * This method loads the homepage for the browser. Either it loads the URL stored as the
     * homepage, or loads the startpage or bookmark page if either of those are set as the homepage.
     */
    fun loadHomePage() {
        if (isIncognito) {
            iTargetUrl = Uri.parse(Uris.StyxIncognito)
            initializeContent(incognitoPageInitializer)
        } else {
            iTargetUrl = Uri.parse(Uris.StyxHome)
            initializeContent(homePageInitializer)
        }
    }

    /**
     * This function loads the bookmark page via the [BookmarkPageInitializer].
     */
    fun loadBookmarkPage() {
        iTargetUrl = Uri.parse(Uris.StyxBookmarks)
        initializeContent(bookmarkPageInitializer)
    }

    /**
     * This function loads the download page via the [DownloadPageInitializer].
     */
    fun loadDownloadsPage() {
        iTargetUrl = Uri.parse(Uris.StyxDownloads)
        initializeContent(downloadPageInitializer)
    }

    /**
     *
     */
    fun loadHistoryPage() {
        iTargetUrl = Uri.parse(Uris.StyxHistory)
        initializeContent(historyPageInitializer)
    }

    /**
     * Basically activate our tab initializer which typically loads something in our WebView.
     * [ResultMessageInitializer] being a notable exception as it will only send a message to something to load target URL at a later stage.
     */
    private fun initializeContent(tabInitializer: TabInitializer) {
        webView?.let { tabInitializer.initialize(it, requestHeaders) }
    }

    /**
     * Initialize the preference driven settings of the WebView. This method must be called whenever
     * the preferences are changed within SharedPreferences.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    fun initializePreferences() {
        val settings = webView?.settings ?: return

        styxWebClient.updatePreferences()

        val modifiesHeaders = userPreferences.doNotTrackEnabled
            || userPreferences.saveDataEnabled
            || userPreferences.removeIdentifyingHeadersEnabled

        if (userPreferences.doNotTrackEnabled) {
            requestHeaders[HEADER_DNT] = "1"
        } else {
            requestHeaders.remove(HEADER_DNT)
        }

        if (userPreferences.saveDataEnabled) {
            requestHeaders[HEADER_SAVEDATA] = "on"
        } else {
            requestHeaders.remove(HEADER_SAVEDATA)
        }

        if (userPreferences.removeIdentifyingHeadersEnabled) {
            requestHeaders[HEADER_REQUESTED_WITH] = ""
            requestHeaders[HEADER_WAP_PROFILE] = ""
        } else {
            requestHeaders.remove(HEADER_REQUESTED_WITH)
            requestHeaders.remove(HEADER_WAP_PROFILE)
        }

        settings.defaultTextEncodingName = userPreferences.textEncoding
        setColorMode(userPreferences.renderingMode)

        if (!isIncognito) {
            settings.setGeolocationEnabled(userPreferences.locationEnabled)
        } else {
            settings.setGeolocationEnabled(false)
        }

        setUserAgentForPreference(userPreferences)

        settings.saveFormData = userPreferences.savePasswordsEnabled && !isIncognito

        if (userPreferences.javaScriptEnabled) {
            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
        } else {
            settings.javaScriptEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
        }

        if (userPreferences.textReflowEnabled) {
            settings.layoutAlgorithm = LayoutAlgorithm.NARROW_COLUMNS
            try {
                settings.layoutAlgorithm = LayoutAlgorithm.TEXT_AUTOSIZING
            } catch (e: Exception) {
                // This shouldn't be necessary, but there are a number
                // of KitKat devices that crash trying to set this
                logger.log(TAG, "Problem setting LayoutAlgorithm to TEXT_AUTOSIZING")
            }
        } else {
            settings.layoutAlgorithm = LayoutAlgorithm.NORMAL
        }

        settings.blockNetworkImage = !userPreferences.loadImages
        // Modifying headers causes SEGFAULTS, so disallow multi window if headers are enabled.
        settings.setSupportMultipleWindows(userPreferences.popupsEnabled && !modifiesHeaders)

        settings.useWideViewPort = userPreferences.useWideViewPortEnabled
        settings.loadWithOverviewMode = userPreferences.overviewModeEnabled

        settings.textZoom = userPreferences.browserTextSize +  MIN_BROWSER_TEXT_SIZE

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView,
            !userPreferences.blockThirdPartyCookiesEnabled)

        applyDarkMode()
    }

    /**
     *
     */
    private fun applyDarkMode() {
        val settings = webView?.settings ?: return

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            if (darkMode) {
                WebSettingsCompat.setForceDark(settings,WebSettingsCompat.FORCE_DARK_ON)
            } else {
                WebSettingsCompat.setForceDark(settings,WebSettingsCompat.FORCE_DARK_OFF)
            }
        } else {
            // Fallback to that then
            if (darkMode) {
                setColorMode(RenderingMode.INVERTED_GRAYSCALE)
            } else {
                setColorMode(userPreferences.renderingMode)
            }
        }
    }

    /**
     * Initialize the settings of the WebView that are intrinsic to Styx and cannot be altered
     * by the user. Distinguish between Incognito and Regular tabs here.
     */
    @Suppress("DEPRECATION")
    private fun WebView.initializeSettings() {
        settings.apply {
            // That needs to be false for WebRTC to work at all, don't ask me why
            mediaPlaybackRequiresUserGesture = false

            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(settings,WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
            }

            mixedContentMode = if (!isIncognito) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            if (!isIncognito || Capabilities.FULL_INCOGNITO.isSupported) {
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
            } else {
                domStorageEnabled = false
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowContentAccess = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setNeedInitialFocus(false)

            getPathObservable("appcache")
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe { file ->
                    setAppCachePath(file.path)
                }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                getPathObservable("geolocation")
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe { file ->
                        setGeolocationDatabasePath(file.path)
                    }
            }
        }

    }

    private fun getPathObservable(subFolder: String) = Single.fromCallable {
        activity.getDir(subFolder, 0)
    }

    /**
     *
     */
    fun toggleDarkMode() {
        // Toggle dark mode
        darkMode = !darkMode
        if (darkMode != userPreferences.darkModeDefault)
            domainSettings.set(DomainSettings.DARK_MODE, darkMode)
        else
            domainSettings.remove(DomainSettings.DARK_MODE)
    }

    fun updateDarkMode() {
        val newDarkMode = domainSettings.get(DomainSettings.DARK_MODE, userPreferences.darkModeDefault)
        if (newDarkMode != darkMode)
            darkMode = newDarkMode
    }

    /**
     * This method is used to toggle the user agent between desktop and the current preference of
     * the user.
     */
    fun toggleDesktopUserAgent() {
        // Toggle desktop mode
        desktopMode = !desktopMode
        if (desktopMode != userPreferences.desktopModeDefault)
            domainSettings.set(DomainSettings.DESKTOP_MODE, desktopMode)
        else
            domainSettings.remove(DomainSettings.DESKTOP_MODE)
    }

    fun updateDesktopMode() {
        val newDesktopMode = domainSettings.get(DomainSettings.DESKTOP_MODE, userPreferences.desktopModeDefault)
        if (newDesktopMode != desktopMode)
            desktopMode = newDesktopMode
    }

    /**
     * This method sets the user agent of the current tab based on the user's preference
     */
    private fun setUserAgentForPreference(userPreferences: UserPreferences) {
        webView?.settings?.userAgentString = userPreferences.userAgent(activity.application)
    }

    /**
     * Save the state of this tab Web View and return it as a [Bundle].
     * We get that state bundle either directly from our Web View,
     * or from our frozen tab initializer if ever our Web View was never loaded.
     */
    private fun webViewState(): Bundle = latentTabInitializer?.tabModel?.webView
        ?: Bundle(ClassLoader.getSystemClassLoader()).also {
            webView?.saveState(it)
        }

    /**
     * Save the state of this tab and return it as a [Bundle].
     */
    fun saveState(): Bundle {
        return TabModel(url, title, desktopMode, darkMode, favicon, webViewState()).toBundle()
    }
    /**
     * Pause the current WebView instance.
     */
    fun onPause() {
        webView?.onPause()
        logger.log(TAG, "WebView onPause: ${webView?.id}")
    }

    /**
     * Resume the current WebView instance.
     */
    fun onResume() {
        webView?.onResume()
        logger.log(TAG, "WebView onResume: ${webView?.id}")
    }

    /**
     * Notify the WebView to stop the current load.
     */
    fun stopLoading() {
        webView?.stopLoading()
    }

    /**
     * This method forces the layer type to hardware, which
     * enables hardware rendering on the WebView instance
     * of the current StyxView.
     */
    private fun setHardwareRendering() {
        webView?.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    /**
     * This method forces the layer type to software, which
     * disables hardware rendering on the WebView instance
     * of the current StyxView and makes the CPU render
     * the view.
     */
    fun setSoftwareRendering() {
        webView?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Sets the current rendering color of the WebView instance
     * of the current StyxView. The for modes are normal
     * rendering, inverted rendering, grayscale rendering,
     * and inverted grayscale rendering
     *
     * @param mode the integer mode to set as the rendering mode.
     * see the numbers in documentation above for the
     * values this method accepts.
     */
    private fun setColorMode(mode: RenderingMode) {
        invertPage = false
        when (mode) {
            RenderingMode.NORMAL -> {
                paint.colorFilter = null
                // setSoftwareRendering(); // Some devices get segfaults
                // in the WebView with Hardware Acceleration enabled,
                // the only fix is to disable hardware rendering
                //setNormalRendering()
                // SL: enabled that and the performance gain is very noticeable on  F(x)tec Pro1
                // Notably on: https://www.bbc.com/worklife
                setHardwareRendering()
            }
            RenderingMode.INVERTED -> {
                val filterInvert = ColorMatrixColorFilter(
                    negativeColorArray)
                paint.colorFilter = filterInvert
                setHardwareRendering()
                invertPage = true
            }
            RenderingMode.GRAYSCALE -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                val filterGray = ColorMatrixColorFilter(cm)
                paint.colorFilter = filterGray
                setHardwareRendering()
            }
            RenderingMode.INVERTED_GRAYSCALE -> {
                val matrix = ColorMatrix()
                matrix.set(negativeColorArray)
                val matrixGray = ColorMatrix()
                matrixGray.setSaturation(0f)
                val concat = ColorMatrix()
                concat.setConcat(matrix, matrixGray)
                val filterInvertGray = ColorMatrixColorFilter(concat)
                paint.colorFilter = filterInvertGray
                setHardwareRendering()

                invertPage = true
            }

            RenderingMode.INCREASE_CONTRAST -> {
                val increaseHighContrast = ColorMatrixColorFilter(increaseContrastColorArray)
                paint.colorFilter = increaseHighContrast
                setHardwareRendering()
            }
        }

    }

    /**
     * Pauses the JavaScript timers of the
     * WebView instance, which will trigger a
     * pause for all WebViews in the app.
     */
    fun pauseTimers() {
        webView?.pauseTimers()
        logger.log(TAG, "Pausing JS timers")
    }

    /**
     * Resumes the JavaScript timers of the
     * WebView instance, which will trigger a
     * resume for all WebViews in the app.
     */
    fun resumeTimers() {
        webView?.resumeTimers()
        logger.log(TAG, "Resuming JS timers")
    }

    /**
     * Requests focus down on the WebView instance
     * if the view does not already have focus.
     */
    fun requestFocus() {
        if (webView?.hasFocus() == false) {
            webView?.requestFocus()
        }
    }

    /**
     * Sets the visibility of the WebView to either
     * View.GONE, View.VISIBLE, or View.INVISIBLE.
     * other values passed in will have no effect.
     *
     * @param visible the visibility to set on the WebView.
     */
    fun setVisibility(visible: Int) {
        webView?.visibility = visible
    }

    /**
     * Tells the WebView to reload the current page.
     */
    fun reload() {
        loadUrl(url)
    }

    /**
     * Finds all the instances of the text passed to this
     * method and highlights the instances of that text
     * in the WebView.
     *
     * @param text the text to search for.
     */
    fun find(text: String): FindResults {
        webView?.findAllAsync(text)

        return object : FindResults {
            override fun nextResult() {
                webView?.findNext(true)
            }

            override fun previousResult() {
                webView?.findNext(false)
            }

            override fun clearResults() {
                webView?.clearMatches()
            }
        }
    }

    /**
     * Notify the tab to shutdown and destroy
     * its WebView instance and to remove the reference
     * to it. After this method is called, the current
     * instance of the StyxView is useless as
     * the WebView cannot be recreated using the public
     * api.
     */
    fun destroy() {
        networkDisposable.dispose()
        webView?.let {
            // Check to make sure the WebView has been removed
            // before calling destroy() so that a memory leak is not created
            val parent = it.parent as? ViewGroup
            if (parent != null) {
                logger.log(TAG, "WebView was not detached from window before destroy")
                parent.removeView(it)
            }
            it.stopLoading()
            it.onPause()
            it.clearHistory()
            it.visibility = View.GONE
            it.removeAllViews()
            it.destroy()
        }
    }

    /**
     * Tell the WebView to navigate backwards
     * in its history to the previous page.
     */
    fun goBack() {
        webView?.goBack()
    }

    /**
     * Tell the WebView to navigate forwards
     * in its history to the next page.
     */
    fun goForward() {
        webView?.goForward()
    }

    /**
     * Notifies the [WebView] whether the network is available or not.
     */
    private fun setNetworkAvailable(isAvailable: Boolean) {
        webView?.setNetworkAvailable(isAvailable)
    }

    /**
     * Handles a long click on the page and delegates the URL to the
     * proper dialog if it is not null, otherwise, it tries to get the
     * URL using HitTestResult.
     *
     * @param url the url that should have been obtained from the WebView touch node
     * thingy, if it is null, this method tries to deal with it and find
     * a workaround.
     */
    private fun longClickPage(url: String?, text: String?) {
        val result = webView?.hitTestResult
        val currentUrl = webView?.url
        val newUrl = result?.extra

        if (currentUrl != null && currentUrl.isSpecialUrl()) {
            if (currentUrl.isHistoryUrl()) {
                if (url != null) {
                    dialogBuilder.showLongPressedHistoryLinkDialog(activity, uiController, url)
                } else if (newUrl != null) {
                    dialogBuilder.showLongPressedHistoryLinkDialog(activity, uiController, newUrl)
                }
            } else if (currentUrl.isBookmarkUrl()) {
                if (url != null) {
                    dialogBuilder.showLongPressedDialogForBookmarkUrl(activity, uiController, url)
                } else if (newUrl != null) {
                    dialogBuilder.showLongPressedDialogForBookmarkUrl(activity, uiController, newUrl)
                }
            } else if (currentUrl.isDownloadsUrl()) {
                if (url != null) {
                    dialogBuilder.showLongPressedDialogForDownloadUrl(activity, uiController)
                } else if (newUrl != null) {
                    dialogBuilder.showLongPressedDialogForDownloadUrl(activity, uiController)
                }
            }
        } else {
            if (url != null) {
                if (result != null) {
                    when (result.type) {
                        WebView.HitTestResult.IMAGE_TYPE -> {
                            dialogBuilder.showLongPressImageDialog(activity, uiController, url, result.extra!!, userAgent)
                        }
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            // Ask user if she want to use the link or the image
                            activity.makeSnackbar(
                                activity.getString(R.string.question_what_do_you_want_to_use), Snackbar.LENGTH_LONG, if (userPreferences.toolbarsBottom) Gravity.TOP else Gravity.BOTTOM) //Snackbar.LENGTH_LONG
                                .setAction(R.string.button_link) {
                                    // Use the link then
                                    dialogBuilder.showLongPressLinkDialog(activity, uiController, url, userAgent)
                                }.addAction(R.layout.snackbar_extra_button, R.string.button_image){
                                    dialogBuilder.showLongPressImageDialog(activity, uiController, result.extra!!, url, userAgent)
                                }.show()
                        }
                        else -> {
                            dialogBuilder.showLongPressLinkDialog(activity, uiController, url, text)
                        }
                    }
                } else {
                    dialogBuilder.showLongPressLinkDialog(activity, uiController, url, text)
                }
            } else if (newUrl != null) {
                if (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE || result.type == WebView.HitTestResult.IMAGE_TYPE) {
                    dialogBuilder.showLongPressImageDialog(activity, uiController, newUrl, result.extra!!, userAgent)
                } else {
                    dialogBuilder.showLongPressLinkDialog(activity, uiController, newUrl, text)
                }
            }
        }
    }

    /**
     * Determines whether or not the WebView can go
     * backward or if it as the end of its history.
     *
     * @return true if the WebView can go back, false otherwise.
     */
    fun canGoBack(): Boolean = webView?.canGoBack() == true

    /**
     * Determine whether or not the WebView can go
     * forward or if it is at the front of its history.
     *
     * @return true if it can go forward, false otherwise.
     */
    fun canGoForward(): Boolean = webView?.canGoForward() == true

    /**
     * Loads the URL in the WebView.
     * @param "url" the non-null URL to attempt to load in
     * the WebView.
     */
    fun loadUrl(aUrl: String) {
        iTargetUrl = Uri.parse(aUrl)

        if (iTargetUrl.scheme == Schemes.Styx || iTargetUrl.scheme == Schemes.About) {
            when (iTargetUrl.host) {
                Hosts.Home -> {
                    loadHomePage()
                }
                Hosts.Bookmarks -> {
                    loadBookmarkPage()
                }
                Hosts.History -> {
                    loadHistoryPage()
                }
            }
        } else {
            webView?.loadUrl(aUrl, requestHeaders)
        }
    }

    /**
     * Check relevant user preferences and configuration before showing the tool bar if needed
     */
    fun showToolBarOnScrollUpIfNeeded() {
        if (userPreferences.showToolBarOnScrollUpInPortrait && Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                || userPreferences.showToolBarOnScrollUpInLandscape && Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            uiController.showActionBar()
        }
    }

    /**
     * Check relevant user preferences and configuration before showing the tool bar if needed
     */
    fun showToolBarOnPageTopIfNeeded() {
        if (userPreferences.showToolBarOnPageTopInPortrait && Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                || userPreferences.showToolBarOnPageTopInLandscape && Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            uiController.showActionBar()
        }
    }

    /**
     * The OnTouchListener used by the WebView so we can
     * get scroll events and show/hide the action bar when
     * the page is scrolled up/down.
     */
    private open inner class TouchListenerLollipop : OnTouchListener {

        var location: Float = 0f
        protected var touchingScreen: Boolean = false
        var y: Float = 0f
        var action: Int = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View?, arg1: MotionEvent): Boolean {

            if (view == null) return false

            if (!view.hasFocus()) {
                view.requestFocus()
            }

            action = arg1.action
            y = arg1.y
            // Handle tool bar visibility when doing slow scrolling
            if (action == MotionEvent.ACTION_DOWN) {
                location = y
                touchingScreen=true
            }
            // Only show or hide tool bar when the user stop touching the screen otherwise that looks ugly
            else if (action == MotionEvent.ACTION_UP) {
                val distance = y - location
                touchingScreen=false
                if (view.scrollY < SCROLL_DOWN_THRESHOLD
                        // Touch input won't show tool bar again if no vertical scroll
                        // It can still be accessed using the back button
                        && view.canScrollVertically()) {
                    showToolBarOnPageTopIfNeeded()
                } else if (distance < -SCROLL_UP_THRESHOLD) {
                    // Aggressive hiding of tool bar
                    uiController.hideActionBar()
                }
                location = 0f
            }

            // Handle tool bar visibility upon fling gesture
            gestureDetector.onTouchEvent(arg1)

            return false
        }
    }

    /**
     * Improved touch listener for devices above API 23 Marshmallow
     */
    private inner class TouchListener: TouchListenerLollipop(), OnScrollChangeListener {

        override fun onScrollChange(view: View?, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int) {

            view?.apply {
                if (canScrollVertically()) {
                    // Handle the case after fling all the way to the top of the web page
                    // Are we near the top of our web page and is user finger not on the screen
                    if (scrollY < SCROLL_DOWN_THRESHOLD && !touchingScreen) {
                        showToolBarOnPageTopIfNeeded()
                    }
                }
            }
        }
    }

    /**
     * The SimpleOnGestureListener used by the [TouchListener]
     * in order to delegate show/hide events to the action bar when
     * the user flings the page. Also handles long press events so
     * that we can capture them accurately.
     */
    private inner class CustomGestureListener(private val view: View) : SimpleOnGestureListener() {

        /**
         * Without this, onLongPress is not called when user is zooming using
         * two fingers, but is when using only one.
         *
         *
         * The required behaviour is to not trigger this when the user is
         * zooming, it shouldn't matter how much fingers the user's using.
         */
        private var canTriggerLongPress = true

        override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val power = (velocityY * 100 / maxFling).toInt()
            if (power < -10) {
                uiController.hideActionBar()
            } else if (power > 15
                    // Touch input won't show tool bar again if no top level vertical scroll
                    // It can still be accessed using the back button
                    && view.canScrollVertically()) {
                showToolBarOnScrollUpIfNeeded()
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }

        override fun onLongPress(e: MotionEvent) {
            if (canTriggerLongPress) {
                val msg = webViewHandler.obtainMessage()
                msg.target = webViewHandler
                webView?.requestFocusNodeHref(msg)
            }
        }

        /**
         * Is called when the user is swiping after the doubletap, which in our
         * case means that he is zooming.
         */
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            canTriggerLongPress = false
            return false
        }

        /**
         * Is called when something is starting being pressed, always before
         * onLongPress.
         */
        override fun onShowPress(e: MotionEvent) {
            canTriggerLongPress = true
        }
    }

    /**
     * A Handler used to get the URL from a long click
     * event on the WebView. It does not hold a hard
     * reference to the WebView and therefore will not
     * leak it if the WebView is garbage collected.
     */
    private class WebViewHandler(view: StyxView) : Handler(Looper.getMainLooper()) {

        private val reference: WeakReference<StyxView> = WeakReference(view)

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            val url = msg.data.getString("url")

            val title = msg.data.getString("title")
            reference.get()?.longClickPage(url, title)
        }
    }

    companion object {

        const val KHtmlMetaThemeColorInvalid: Int = Color.TRANSPARENT
        const val KFetchMetaThemeColorTries: Int = 6

        private const val TAG = "StyxView"

        const val HEADER_REQUESTED_WITH = "X-Requested-With"
        const val HEADER_WAP_PROFILE = "X-Wap-Profile"
        private const val HEADER_DNT = "DNT"
        private const val HEADER_SAVEDATA = "Save-Data"

        private val SCROLL_UP_THRESHOLD = Utils.dpToPx(10f)
        private val SCROLL_DOWN_THRESHOLD = Utils.dpToPx(30f)

        private val negativeColorArray = floatArrayOf(
            -1.0f, 0f, 0f, 0f, 255f, // red
            0f, -1.0f, 0f, 0f, 255f, // green
            0f, 0f, -1.0f, 0f, 255f, // blue
            0f, 0f, 0f, 1.0f, 0f // alpha
        )
        private val increaseContrastColorArray = floatArrayOf(
            2.0f, 0f, 0f, 0f, -160f, // red
            0f, 2.0f, 0f, 0f, -160f, // green
            0f, 0f, 2.0f, 0f, -160f, // blue
            0f, 0f, 0f, 1.0f, 0f // alpha
        )
    }
}

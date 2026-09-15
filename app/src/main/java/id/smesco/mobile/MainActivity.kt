package id.smesco.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // ===== Change this URL if your site ever moves =====
    private val startUrl = "https://smesco.kodesis.id/auth"

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var splashOverlay: View
    private var splashHidden = false

    // State for <input type="file"> uploads
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // Pending web permission callbacks waiting on the OS grant
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var webRtcRequest: PermissionRequest? = null

    // ---------- Activity result launchers ----------

    private val initialPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val geoPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            geoCallback?.invoke(geoOrigin, granted, false)
            geoCallback = null
            geoOrigin = null
        }

    private val webRtcPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val req = webRtcRequest
            if (req != null) {
                if (result.values.all { it }) req.grant(req.resources) else req.deny()
            }
            webRtcRequest = null
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleFileChooserResult(result)
        }

    // ---------- Lifecycle ----------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Switch from the launch (splash) theme to the normal app theme
        setTheme(R.style.Theme_SmescoApp)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        splashOverlay = findViewById(R.id.splashOverlay)

        // Safety net: hide the splash after 12s even if the page never reports "finished"
        splashOverlay.postDelayed({ hideSplash() }, 12000)

        setupDownloadListener()
        requestInitialPermissions()
        configureWebView()

        swipeRefresh.setOnRefreshListener { webView.reload() }
        // Only allow pull-to-refresh when the page is scrolled to the very top
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    // ---------- Downloads ----------

    private fun setupDownloadListener() {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))

                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null) request.addRequestHeader("Cookie", cookies)
                request.setDescription("Mengunduh berkas...")
                request.setTitle(fileName)
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)

                Toast.makeText(this@MainActivity, "Mengunduh: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Gagal mengunduh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestInitialPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val toRequest = perms.filter { !hasPermission(it) }
        if (toRequest.isNotEmpty()) {
            initialPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    // ---------- WebView setup ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setGeolocationEnabled(true)
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.safeBrowsingEnabled = true
        s.userAgentString = s.userAgentString + " SmescoApp/1.0"

        // Keep the login session alive between launches
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()
    }

    private fun createWebViewClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("http://") || url.startsWith("https://")) return false
            // tel:, mailto:, whatsapp:, geo:, intent: ... hand off to the system
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                true
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            CookieManager.getInstance().flush()
            swipeRefresh.isRefreshing = false
            hideSplash()
        }
    }

    private fun createWebChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        // GPS / navigator.geolocation
        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            if (hasLocationPermission()) {
                callback.invoke(origin, true, false)
            } else {
                geoOrigin = origin
                geoCallback = callback
                geoPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }

        // Camera / mic via getUserMedia (WebRTC, live camera stream)
        override fun onPermissionRequest(request: PermissionRequest) {
            val needed = mutableListOf<String>()
            request.resources.forEach { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        if (!hasPermission(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
                }
            }
            if (needed.isEmpty()) {
                request.grant(request.resources)
            } else {
                webRtcRequest = request
                webRtcPermissionLauncher.launch(needed.toTypedArray())
            }
        }

        // <input type="file"> uploads, with an option to take a photo
        override fun onShowFileChooser(
            webView: WebView?,
            callback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            openFileChooser(fileChooserParams)
            return true
        }
    }

    // ---------- File chooser + camera capture ----------

    private fun openFileChooser(params: WebChromeClient.FileChooserParams?) {
        val acceptTypes = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
        val allowMultiple =
            params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (acceptTypes.size == 1) acceptTypes[0] else "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            if (acceptTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
        }

        val cameraIntent = createCameraIntent()

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
            putExtra(Intent.EXTRA_TITLE, "Pilih berkas atau ambil foto")
            if (cameraIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }

        try {
            fileChooserLauncher.launch(chooser)
        } catch (e: Exception) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            Toast.makeText(this, "Tidak bisa membuka pemilih berkas", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCameraIntent(): Intent? {
        if (!hasPermission(Manifest.permission.CAMERA)) return null
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        val photoFile = try { createImageFile() } catch (e: Exception) { return null }
        cameraImageUri = FileProvider.getUriForFile(
            this, "${applicationContext.packageName}.fileprovider", photoFile
        )
        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        return intent
    }

    private fun createImageFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(cacheDir, "camera").apply { if (!exists()) mkdirs() }
        return File(dir, "IMG_$stamp.jpg")
    }

    private fun handleFileChooserResult(result: ActivityResult) {
        val cb = filePathCallback ?: return
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            when {
                data?.dataString != null -> results = arrayOf(Uri.parse(data.dataString))
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    results = Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                cameraImageUri != null -> results = arrayOf(cameraImageUri!!)
            }
        }
        cb.onReceiveValue(results)
        filePathCallback = null
        cameraImageUri = null
    }

    // ---------- Helpers ----------

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(350)
            .withEndAction { splashOverlay.visibility = View.GONE }
            .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() { super.onPause(); webView.onPause(); CookieManager.getInstance().flush() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}

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
import android.os.Handler
import android.os.Looper
import android.print.PdfPrintHelper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
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
import android.bluetooth.BluetoothAdapter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

    // Print + Bluetooth printer
    private lateinit var btPrinter: BluetoothPrinter
    private lateinit var prefs: SharedPreferences
    private val printExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jobCounter = AtomicInteger(0)
    private var btPendingContinue: (() -> Unit)? = null
    private var btPendingCancel: (() -> Unit)? = null

    /** URL halaman yang sedang tampil; jembatan JS hanya melayani situs sendiri. */
    @Volatile private var currentUrl: String? = null

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

    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) resumeBluetoothFlow()
            else {
                toast("Izin Bluetooth ditolak, printer tidak bisa dipakai")
                cancelBluetoothFlow()
            }
        }

    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (btPrinter.isEnabled()) resumeBluetoothFlow() else cancelBluetoothFlow()
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
        // Diperlukan agar seluruh halaman (bukan hanya bagian yang terlihat)
        // bisa digambar ke bitmap untuk cetak Bluetooth cadangan
        WebView.enableSlowWholeDocumentDraw()
        setContentView(R.layout.activity_main)

        btPrinter = BluetoothPrinter(this)
        prefs = getSharedPreferences("smesco_print", Context.MODE_PRIVATE)

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
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    // Berkas dibuat di dalam halaman (mis. PDF/Excel dari JavaScript):
                    // isinya dibaca oleh skrip halaman lalu dikirim ke aplikasi
                    val name = contentDispositionName(contentDisposition) ?: ""
                    saveInPageUrl(url, name, openAfter = false)
                    return@setDownloadListener
                }

                val fileName = resolveFileName(url, contentDisposition, mimeType)

                val request = DownloadManager.Request(Uri.parse(url))
                request.addRequestHeader("User-Agent", userAgent)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null) request.addRequestHeader("Cookie", cookies)
                // Jangan pakai MIME mentah dari server: kalau octet-stream,
                // Android ikut menebak dan berkas jatuh menjadi .bin
                request.setMimeType(mimeTypeFromName(fileName) ?: mimeType)
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
                Toast.makeText(this@MainActivity, "Gagal mengunduh: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Menentukan nama berkas dengan urutan: header Content-Disposition (termasuk
     * format RFC 5987 `filename*=UTF-8''nama.csv`), lalu ruas terakhir URL, lalu
     * tebakan bawaan Android. Ekstensi ditambal dari parameter URL bila perlu,
     * supaya hasil export tidak jatuh menjadi .bin.
     */
    private fun resolveFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        var name: String? = null

        if (!contentDisposition.isNullOrBlank()) {
            // filename*=UTF-8''laporan%20mei.csv
            Regex("""filename\*\s*=\s*[^']*''([^;\s]+)""", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.get(1)?.let {
                    name = try { Uri.decode(it) } catch (e: Exception) { it }
                }
            // filename="laporan.csv"
            if (name.isNullOrBlank()) {
                Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                    .find(contentDisposition)?.groupValues?.get(1)?.let { name = it.trim() }
            }
        }

        // Ruas terakhir dari path URL, mis. /export/laporan.csv
        if (name.isNullOrBlank()) {
            val path = try { Uri.parse(url).lastPathSegment } catch (e: Exception) { null }
            if (!path.isNullOrBlank() && path.contains(".")) name = path
        }

        if (name.isNullOrBlank()) {
            name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        }

        var clean = name!!.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()

        // Kalau masih tanpa ekstensi atau jatuh ke .bin, tebak dari URL lalu dari MIME
        if (clean.endsWith(".bin", true) || !clean.contains(".")) {
            val base = clean.removeSuffix(".bin").ifBlank { "download" }
            val ext = extFromUrl(url) ?: extFromMime(mimeType)
            if (ext != null) clean = "$base.$ext"
        }

        return clean.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    /** Menebak ekstensi dari URL, termasuk pola ?format=csv / ?type=xlsx / ?export=pdf */
    private fun extFromUrl(url: String): String? {
        val known = listOf("csv", "xlsx", "xls", "pdf", "docx", "doc", "zip", "txt", "json", "xml", "png", "jpg", "jpeg")
        val lower = url.lowercase()
        Regex("""[?&](?:format|type|ext|export|output)=([a-z0-9]{2,5})""").find(lower)
            ?.groupValues?.get(1)?.let { if (it in known) return it }
        known.forEach { if (lower.contains(".$it?") || lower.endsWith(".$it")) return it }
        return null
    }

    private fun extFromMime(mimeType: String?): String? = when {
        mimeType == null -> null
        mimeType.contains("csv") -> "csv"
        mimeType.contains("spreadsheetml") -> "xlsx"
        mimeType.contains("ms-excel") -> "xls"
        mimeType.contains("pdf") -> "pdf"
        mimeType.contains("wordprocessingml") -> "docx"
        mimeType.contains("msword") -> "doc"
        mimeType.contains("zip") -> "zip"
        mimeType.contains("json") -> "json"
        mimeType.startsWith("text/plain") -> "txt"
        else -> null
    }

    private fun mimeTypeFromName(fileName: String): String? =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "csv" -> "text/csv"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            "json" -> "application/json"
            else -> null
        }

    private fun contentDispositionName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*[^']*''([^;\s]+)""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.get(1)?.let {
                return try { Uri.decode(it) } catch (e: Exception) { it }
            }
        return Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    // ---------- Berkas blob: / data: dari dalam halaman ----------

    /** Minta skrip halaman membaca isi blob:/data: lalu mengirimnya ke [NativeBridge.saveFile]. */
    private fun saveInPageUrl(url: String, name: String, openAfter: Boolean) {
        if (url.startsWith("data:")) {
            saveDataUrl(url, name, openAfter)
            return
        }
        injectBridge(webView)
        val js = "window.__smescoSave && window.__smescoSave(" +
            JSONObject.quote(url) + "," + JSONObject.quote(name) + "," + openAfter + ")"
        webView.evaluateJavascript(js, null)
    }

    private fun saveDataUrl(dataUrl: String, name: String, openAfter: Boolean) {
        printExecutor.execute {
            try {
                val (mime, bytes) = DownloadSaver.decodeDataUrl(dataUrl)
                val fileName = finalFileName(name, mime)
                val saved = DownloadSaver.save(this, fileName, mimeTypeFromName(fileName) ?: mime, bytes)
                runOnUiThread { onFileSaved(saved, openAfter) }
            } catch (e: Exception) {
                runOnUiThread { toast("Gagal menyimpan berkas: ${e.message}") }
            }
        }
    }

    private fun finalFileName(name: String, mime: String): String {
        var clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        if (clean.isBlank()) {
            clean = "smesco_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }
        if (!clean.contains(".") || clean.endsWith(".bin", true)) {
            val ext = extFromMime(mime) ?: when {
                mime.contains("png") -> "png"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.startsWith("text/html") -> "html"
                else -> null
            }
            if (ext != null) clean = clean.removeSuffix(".bin") + "." + ext
        }
        return clean
    }

    private fun onFileSaved(saved: DownloadSaver.Saved, openAfter: Boolean) {
        val open = {
            try {
                startActivity(DownloadSaver.openIntent(saved))
            } catch (e: Exception) {
                toast("Tidak ada aplikasi untuk membuka ${saved.displayName}")
            }
        }
        if (openAfter) {
            toast("Tersimpan di Download: ${saved.displayName}")
            open()
        } else {
            Snackbar.make(webView, "Tersimpan di Download: ${saved.displayName}", Snackbar.LENGTH_LONG)
                .setAction("Buka") { open() }
                .show()
        }
    }

    // ---------- Print ----------

    /** Dipanggil saat halaman menjalankan window.print(). */
    private fun showPrintChooser(title: String) {
        val printerName = savedPrinter()?.name
        val items = arrayOf(
            "Printer biasa / Simpan sebagai PDF",
            "Printer thermal Bluetooth" + (printerName?.let { " ($it)" } ?: ""),
            "Pilih printer Bluetooth & ukuran kertas"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Cetak")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> systemPrint(title)
                    1 -> printPageBluetooth(newJobId())
                    2 -> withBluetooth(forcePick = true, onReady = { p ->
                        toast("Printer: ${p.name}, kertas ${paperMm()}mm")
                    })
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun systemPrint(title: String) {
        val jobName = title.ifBlank { getString(R.string.app_name) }
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        try {
            pm.print(jobName, webView.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
        } catch (e: Exception) {
            toast("Tidak bisa membuka dialog cetak: ${e.message}")
        }
    }

    private fun newJobId() = "job" + jobCounter.incrementAndGet()

    /** Kabari halaman lewat event `smescoprint` (dipakai oleh window.SmescoPrint). */
    private fun reportJob(job: String, ok: Boolean, message: String, extra: JSONObject? = null) {
        mainHandler.post {
            if (!ok && message != "Dibatalkan") toast(message)
            val detail = (extra ?: JSONObject()).put("job", job).put("ok", ok).put("message", message)
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('smescoprint',{detail:$detail}))", null
            )
        }
    }

    // --- Pengaturan printer ---

    private fun paperMm() = prefs.getInt("paper_mm", 58)

    private fun savedPrinter(): BluetoothPrinter.Printer? {
        val addr = prefs.getString("bt_address", null) ?: return null
        return BluetoothPrinter.Printer(prefs.getString("bt_name", null) ?: addr, addr)
    }

    private fun printerJson(): JSONObject? = savedPrinter()?.let {
        JSONObject().put("name", it.name).put("address", it.address)
            .put("paper", paperMm()).put("columns", BluetoothPrinter.charsForPaper(paperMm()))
    }

    /**
     * Pastikan Bluetooth siap: izin, Bluetooth menyala, dan printer sudah dipilih.
     * Setiap langkah yang butuh jawaban pengguna akan memanggil ulang fungsi ini.
     */
    private fun withBluetooth(
        forcePick: Boolean = false,
        onReady: (BluetoothPrinter.Printer) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        if (!btPrinter.isSupported()) {
            toast("Perangkat ini tidak mendukung Bluetooth")
            onCancel(); return
        }
        val again = { withBluetooth(forcePick, onReady, onCancel) }

        val missing = bluetoothPermissions().filter { !hasPermission(it) }
        if (missing.isNotEmpty()) {
            btPendingContinue = again; btPendingCancel = onCancel
            btPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (!btPrinter.isEnabled()) {
            btPendingContinue = again; btPendingCancel = onCancel
            try {
                btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) {
                btPendingContinue = null; btPendingCancel = null
                toast("Nyalakan Bluetooth terlebih dahulu")
                onCancel()
            }
            return
        }
        val saved = savedPrinter()
        val stillPaired = saved != null && btPrinter.pairedDevices().any { it.address == saved.address }
        if (forcePick || !stillPaired) {
            pickPrinter(onPicked = { withBluetooth(false, onReady, onCancel) }, onCancel = onCancel)
            return
        }
        onReady(saved!!)
    }

    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT)
        else emptyList()

    private fun resumeBluetoothFlow() {
        val next = btPendingContinue
        btPendingContinue = null; btPendingCancel = null
        next?.invoke()
    }

    private fun cancelBluetoothFlow() {
        val cancel = btPendingCancel
        btPendingContinue = null; btPendingCancel = null
        cancel?.invoke()
    }

    private fun pickPrinter(onPicked: () -> Unit, onCancel: () -> Unit) {
        val devices = btPrinter.pairedDevices()
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Belum ada printer")
                .setMessage(
                    "Nyalakan printer, lalu pair (sandingkan) dulu di pengaturan Bluetooth HP. " +
                        "PIN printer thermal biasanya 0000 atau 1234. Setelah itu coba cetak lagi."
                )
                .setPositiveButton("Buka pengaturan Bluetooth") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) { }
                    onCancel()
                }
                .setNegativeButton("Batal") { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
                .show()
            return
        }
        val current = prefs.getString("bt_address", null)
        val labels = devices.map { it.name + "\n" + it.address }.toTypedArray()
        var chosen = devices.indexOfFirst { it.address == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Pilih printer Bluetooth")
            .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
            .setPositiveButton("Lanjut") { _, _ ->
                val p = devices[chosen]
                pickPaper { mm ->
                    prefs.edit()
                        .putString("bt_address", p.address)
                        .putString("bt_name", p.name)
                        .putInt("paper_mm", mm)
                        .apply()
                    onPicked()
                }
            }
            .setNeutralButton("Pengaturan Bluetooth") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) { }
                onCancel()
            }
            .setNegativeButton("Batal") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun pickPaper(onPicked: (Int) -> Unit) {
        val sizes = intArrayOf(58, 80)
        var chosen = if (paperMm() >= 80) 1 else 0
        var done = false
        MaterialAlertDialogBuilder(this)
            .setTitle("Lebar kertas")
            .setSingleChoiceItems(arrayOf("58 mm (kecil)", "80 mm (besar)"), chosen) { _, w -> chosen = w }
            .setPositiveButton("Simpan") { _, _ -> done = true; onPicked(sizes[chosen]) }
            .setNegativeButton("Batal", null)
            .setOnDismissListener { if (!done) onPicked(paperMm()) }
            .show()
    }

    // --- Mencetak ke printer Bluetooth ---

    /** Kirim byte ESC/POS yang dibuat di thread latar oleh [build]. */
    private fun sendToBluetooth(job: String, build: () -> ByteArray) {
        withBluetooth(
            onReady = { printer ->
                printExecutor.execute {
                    try {
                        btPrinter.send(printer.address, build())
                        reportJob(job, true, "Tercetak di ${printer.name}")
                    } catch (e: Throwable) {
                        reportJob(job, false, "Gagal mencetak: ${e.message}")
                    }
                }
            },
            onCancel = { reportJob(job, false, "Dibatalkan") }
        )
    }

    /**
     * Cetak halaman yang sedang tampil ke printer thermal. Halaman di-render
     * lewat mesin cetak WebView (jadi CSS @media print ikut dipakai) ke PDF
     * selebar kertas, lalu PDF diubah menjadi gambar ESC/POS. Kalau cara itu
     * gagal, tampilan layar yang digambar ulang dipakai sebagai cadangan.
     */
    private fun printPageBluetooth(job: String) {
        withBluetooth(
            onReady = { printer ->
                toast("Menyiapkan cetakan...")
                val dots = BluetoothPrinter.dotsForPaper(paperMm())
                renderPageToPdf(dots) { pdf ->
                    if (pdf != null) {
                        printExecutor.execute {
                            try {
                                val bmp = BluetoothPrinter.renderPdf(pdf, dots)
                                val data = BluetoothPrinter.encodeBitmap(bmp, dots)
                                bmp.recycle()
                                btPrinter.send(printer.address, data)
                                reportJob(job, true, "Tercetak di ${printer.name}")
                            } catch (e: Throwable) {
                                reportJob(job, false, "Gagal mencetak: ${e.message}")
                            } finally {
                                pdf.delete()
                            }
                        }
                    } else {
                        printScreenshotBluetooth(job, printer, dots)
                    }
                }
            },
            onCancel = { reportJob(job, false, "Dibatalkan") }
        )
    }

    private fun renderPageToPdf(dots: Int, done: (File?) -> Unit) {
        try {
            // Lebar halaman = lebar area cetak (203 dpi), tinggi dibuat panjang
            // supaya struk tidak terpotong menjadi beberapa halaman
            val widthMils = dots * 1000 / 203
            val media = PrintAttributes.MediaSize("smesco_roll_$dots", "Roll", widthMils, 40000)
            val attrs = PrintAttributes.Builder()
                .setMediaSize(media)
                .setResolution(PrintAttributes.Resolution("r203", "203 dpi", 203, 203))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build()
            val dir = File(cacheDir, "print").apply { mkdirs() }
            val out = File(dir, "page_${System.currentTimeMillis()}.pdf")
            val adapter = webView.createPrintDocumentAdapter("smesco")
            var finished = false
            // Jaga-jaga kalau mesin cetak tidak pernah memanggil balik
            val timeout = Runnable { if (!finished) { finished = true; done(null) } }
            mainHandler.postDelayed(timeout, 20000)
            PdfPrintHelper.writePdf(adapter, attrs, out) { ok, _ ->
                if (finished) return@writePdf
                finished = true
                mainHandler.removeCallbacks(timeout)
                done(if (ok && out.length() > 0) out else null)
            }
        } catch (e: Throwable) {
            done(null)
        }
    }

    /** Cadangan: gambar seluruh isi WebView ke bitmap selebar kertas. */
    private fun printScreenshotBluetooth(job: String, printer: BluetoothPrinter.Printer, dots: Int) {
        val bmp = try {
            val viewW = webView.width.coerceAtLeast(1)
            @Suppress("DEPRECATION")
            val contentH = (webView.contentHeight * webView.scale).toInt().coerceAtLeast(webView.height)
            val ratio = dots.toFloat() / viewW
            val h = (contentH * ratio).toInt().coerceIn(1, 20000)
            Bitmap.createBitmap(dots, h, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it)
                c.drawColor(Color.WHITE)
                c.scale(ratio, ratio)
                webView.draw(c)
            }
        } catch (e: Throwable) {
            reportJob(job, false, "Gagal menyiapkan cetakan: ${e.message}")
            return
        }
        printExecutor.execute {
            try {
                val data = BluetoothPrinter.encodeBitmap(BluetoothPrinter.trimBottom(bmp), dots)
                btPrinter.send(printer.address, data)
                reportJob(job, true, "Tercetak di ${printer.name}")
            } catch (e: Throwable) {
                reportJob(job, false, "Gagal mencetak: ${e.message}")
            }
        }
    }

    // ---------- Jembatan JavaScript ----------

    /** `window.SmescoNative`: dipakai skrip bawaan (window.print, unduhan blob). */
    inner class NativeBridge {
        @JavascriptInterface
        fun print(title: String?) {
            if (!isTrustedUrl(currentUrl)) return
            runOnUiThread { showPrintChooser(title ?: "") }
        }

        @JavascriptInterface
        fun saveFile(dataUrl: String?, name: String?, openAfter: Boolean) {
            if (!isTrustedUrl(currentUrl) || dataUrl == null) return
            runOnUiThread { saveDataUrl(dataUrl, name ?: "", openAfter) }
        }

        @JavascriptInterface
        fun saveFailed(message: String?) {
            runOnUiThread { toast(message ?: "Berkas tidak bisa disimpan") }
        }
    }

    /**
     * `window.SmescoPrinter`: API printer untuk website. Fungsi cetak langsung
     * mengembalikan id job; hasilnya dikirim lewat event `smescoprint`.
     * Versi Promise tersedia sebagai `window.SmescoPrint` (lihat README).
     */
    inner class PrinterBridge {
        private fun trusted() = isTrustedUrl(currentUrl)

        @JavascriptInterface
        fun isAvailable(): Boolean = trusted() && btPrinter.isSupported()

        @JavascriptInterface
        fun getPrinter(): String = if (trusted()) (printerJson()?.toString() ?: "null") else "null"

        @JavascriptInterface
        fun getColumns(): Int = BluetoothPrinter.charsForPaper(paperMm())

        @JavascriptInterface
        fun selectPrinter(): String {
            val job = newJobId()
            if (!trusted()) return job
            runOnUiThread {
                withBluetooth(
                    forcePick = true,
                    onReady = { reportJob(job, true, "Printer dipilih: ${it.name}", printerJson()) },
                    onCancel = { reportJob(job, false, "Dibatalkan") }
                )
            }
            return job
        }

        @JavascriptInterface
        fun printText(text: String?): String = submit { BluetoothPrinter.encodeText(text ?: "") }

        @JavascriptInterface
        fun printRaw(base64: String?): String =
            submit { Base64.decode(base64 ?: "", Base64.DEFAULT) }

        @JavascriptInterface
        fun printImage(dataUrl: String?): String = submit {
            val bytes = DownloadSaver.decodeDataUrl(dataUrl ?: "").second
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Gambar tidak valid")
            BluetoothPrinter.encodeBitmap(bmp, BluetoothPrinter.dotsForPaper(paperMm()))
        }

        @JavascriptInterface
        fun printPage(): String {
            val job = newJobId()
            if (trusted()) runOnUiThread { printPageBluetooth(job) }
            return job
        }

        @JavascriptInterface
        fun systemPrint(title: String?) {
            if (trusted()) runOnUiThread { systemPrint(title ?: "") }
        }

        private fun submit(build: () -> ByteArray): String {
            val job = newJobId()
            if (trusted()) runOnUiThread { sendToBluetooth(job, build) }
            return job
        }
    }

    // ---------- Permissions ----------

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

        webView.addJavascriptInterface(NativeBridge(), "SmescoNative")
        webView.addJavascriptInterface(PrinterBridge(), "SmescoPrinter")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val host = Uri.parse(startUrl).host ?: ""
            val rules = mutableSetOf("https://$host", "http://$host")
            parentDomain(host)?.let { rules += "https://*.$it"; rules += "https://$it" }
            WebViewCompat.addDocumentStartJavaScript(webView, PageScripts.BRIDGE, rules)
        }
    }

    /** "smesco.kodesis.id" -> "kodesis.id" */
    private fun parentDomain(host: String): String? {
        val parts = host.split('.')
        return if (parts.size > 2) parts.takeLast(2).joinToString(".") else null
    }

    /** Situs sendiri (domain startUrl atau subdomainnya). */
    private fun isTrustedUrl(url: String?): Boolean {
        val host = try { Uri.parse(url ?: return false).host } catch (e: Exception) { null } ?: return false
        val own = Uri.parse(startUrl).host ?: return false
        val base = parentDomain(own) ?: own
        return host == own || host == base || host.endsWith(".$base")
    }

    /** Cadangan bila WebView terlalu tua untuk skrip document-start. Aman dipanggil berulang. */
    private fun injectBridge(view: WebView?) {
        if (view != null && isTrustedUrl(view.url)) view.evaluateJavascript(PageScripts.BRIDGE, null)
    }

    private fun createWebViewClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("http://") || url.startsWith("https://")) return false
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                saveInPageUrl(url, "", openAfter = true)
                return true
            }
            // tel:, mailto:, whatsapp:, geo:, intent: ... hand off to the system
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                true
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            currentUrl = url
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            currentUrl = url
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            injectBridge(view)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            currentUrl = url
            injectBridge(view)
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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
    override fun onDestroy() { printExecutor.shutdown(); webView.destroy(); super.onDestroy() }
}

package id.smesco.mobile

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Printer thermal Bluetooth (ESC/POS) lewat Bluetooth klasik (SPP).
 * Semua fungsi yang membuka koneksi bersifat blocking: panggil dari thread latar.
 * Izin Bluetooth harus sudah diberikan sebelum fungsi-fungsi ini dipanggil.
 */
@SuppressLint("MissingPermission")
class BluetoothPrinter(private val context: Context) {

    data class Printer(val name: String, val address: String)

    private val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isSupported() = adapter != null

    fun isEnabled() = adapter?.isEnabled == true

    /** Perangkat yang sudah di-pair di pengaturan Bluetooth Android. */
    fun pairedDevices(): List<Printer> {
        val devices = adapter?.bondedDevices ?: return emptyList()
        return devices
            .map { Printer(it.name ?: it.address, it.address) }
            .sortedWith(compareByDescending<Printer> { looksLikePrinter(it.name) }.thenBy { it.name })
    }

    private fun looksLikePrinter(name: String): Boolean {
        val n = name.lowercase()
        return listOf("print", "pos", "mtp", "rpp", "thermal", "epson", "xprinter", "bluetooth printer", "pt-", "zj")
            .any { n.contains(it) }
    }

    /** Membuka koneksi, mengirim [data], lalu menutup koneksi. */
    @Throws(IOException::class)
    fun send(address: String, data: ByteArray) {
        val bt = adapter ?: throw IOException("Perangkat tidak mendukung Bluetooth")
        if (!bt.isEnabled) throw IOException("Bluetooth sedang mati")
        val device = bt.getRemoteDevice(address)
        try { bt.cancelDiscovery() } catch (_: Exception) { }

        val socket = connect(device)
        try {
            val out = socket.outputStream
            writeChunked(out, data)
            out.flush()
            // Beri waktu printer mengosongkan buffer sebelum koneksi diputus,
            // kalau tidak, ujung struk sering terpotong.
            Thread.sleep(400L + data.size / 40L)
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(spp) },
            { device.createInsecureRfcommSocketToServiceRecord(spp) },
            {
                // Cadangan untuk printer murah yang tidak mengiklankan SDP dengan benar
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            }
        )
        var last: Exception? = null
        for (create in attempts) {
            var socket: BluetoothSocket? = null
            try {
                socket = create()
                socket.connect()
                return socket
            } catch (e: Exception) {
                last = e
                try { socket?.close() } catch (_: Exception) { }
            }
        }
        throw IOException("Tidak bisa terhubung ke printer: ${last?.message}", last)
    }

    private fun writeChunked(out: OutputStream, data: ByteArray) {
        val chunk = 512
        var i = 0
        while (i < data.size) {
            val n = minOf(chunk, data.size - i)
            out.write(data, i, n)
            out.flush()
            i += n
            Thread.sleep(15)
        }
    }

    companion object {
        /** Lebar area cetak dalam titik (203 dpi): 58mm = 384, 80mm = 576. */
        fun dotsForPaper(paperMm: Int) = if (paperMm >= 80) 576 else 384

        /** Jumlah karakter per baris dengan font A. */
        fun charsForPaper(paperMm: Int) = if (paperMm >= 80) 48 else 32

        val INIT = byteArrayOf(0x1B, 0x40)
        private val FEED_AND_CUT = byteArrayOf(0x1B, 0x64, 4, 0x1D, 0x56, 66, 0)

        /** Teks biasa. Baris baru "\n". Karakter di luar Latin-1 diganti "?". */
        fun encodeText(text: String, cut: Boolean = true): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(INIT)
            out.write(text.replace("\r\n", "\n").toByteArray(Charsets.ISO_8859_1))
            if (!text.endsWith("\n")) out.write('\n'.code)
            out.write(if (cut) FEED_AND_CUT else byteArrayOf(0x1B, 0x64, 4))
            return out.toByteArray()
        }

        /**
         * Mengubah bitmap menjadi perintah raster ESC/POS (GS v 0). Bitmap
         * diperkecil ke [widthDots], diubah ke hitam-putih dengan dithering,
         * dan dikirim per pita supaya buffer printer tidak meluap.
         */
        fun encodeBitmap(source: Bitmap, widthDots: Int, cut: Boolean = true): ByteArray {
            val bmp = scaleToWidth(source, widthDots)
            val w = bmp.width
            val h = bmp.height
            val bytesPerRow = (w + 7) / 8
            val mono = toMonochrome(bmp)
            if (bmp !== source) bmp.recycle()

            val out = ByteArrayOutputStream()
            out.write(INIT)
            val band = 128
            var y0 = 0
            while (y0 < h) {
                val rows = minOf(band, h - y0)
                out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
                out.write(bytesPerRow and 0xFF); out.write((bytesPerRow shr 8) and 0xFF)
                out.write(rows and 0xFF); out.write((rows shr 8) and 0xFF)
                for (y in y0 until y0 + rows) {
                    val rowBase = y * w
                    for (bx in 0 until bytesPerRow) {
                        var b = 0
                        for (bit in 0 until 8) {
                            val x = bx * 8 + bit
                            if (x < w && mono[rowBase + x]) b = b or (0x80 shr bit)
                        }
                        out.write(b)
                    }
                }
                y0 += rows
            }
            out.write(if (cut) FEED_AND_CUT else byteArrayOf(0x1B, 0x64, 4))
            return out.toByteArray()
        }

        private fun scaleToWidth(src: Bitmap, width: Int): Bitmap {
            if (src.width == width) return src
            val h = maxOf(1, (src.height.toLong() * width / src.width).toInt())
            return Bitmap.createScaledBitmap(src, width, h, true)
        }

        /** Dithering Floyd–Steinberg; true = titik hitam. */
        private fun toMonochrome(bmp: Bitmap): BooleanArray {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val gray = FloatArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                val a = Color.alpha(c) / 255f
                // Piksel transparan dianggap putih
                val l = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                gray[i] = l * a + 255f * (1 - a)
            }
            val black = BooleanArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val old = gray[i]
                    val isBlack = old < 128f
                    black[i] = isBlack
                    val err = old - if (isBlack) 0f else 255f
                    if (x + 1 < w) gray[i + 1] += err * 7 / 16
                    if (y + 1 < h) {
                        if (x > 0) gray[i + w - 1] += err * 3 / 16
                        gray[i + w] += err * 5 / 16
                        if (x + 1 < w) gray[i + w + 1] += err * 1 / 16
                    }
                }
            }
            return black
        }

        /**
         * Merender semua halaman PDF menjadi satu bitmap panjang selebar
         * [widthDots], dengan ruang kosong di akhir struk dibuang.
         */
        fun renderPdf(pdf: File, widthDots: Int): Bitmap {
            val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd).use { renderer ->
                val parts = mutableListOf<Bitmap>()
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val h = maxOf(1, (page.height.toLong() * widthDots / page.width).toInt())
                        val bmp = Bitmap.createBitmap(widthDots, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        // Hanya halaman terakhir yang dipangkas, supaya jarak antarhalaman tetap rapi
                        parts.add(if (i == renderer.pageCount - 1) trimBottom(bmp) else bmp)
                    }
                }
                return stack(parts, widthDots)
            }
        }

        fun trimBottom(bmp: Bitmap, padding: Int = 8): Bitmap {
            val w = bmp.width
            val row = IntArray(w)
            var last = -1
            for (y in bmp.height - 1 downTo 0) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                if (row.any { Color.red(it) < 200 || Color.green(it) < 200 || Color.blue(it) < 200 }) {
                    last = y
                    break
                }
            }
            val keep = minOf(bmp.height, maxOf(1, last + 1 + padding))
            if (keep == bmp.height) return bmp
            val out = Bitmap.createBitmap(bmp, 0, 0, w, keep)
            bmp.recycle()
            return out
        }

        private fun stack(parts: List<Bitmap>, width: Int): Bitmap {
            val total = maxOf(1, parts.sumOf { it.height })
            val out = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.WHITE)
            val canvas = Canvas(out)
            var y = 0f
            parts.forEach {
                canvas.drawBitmap(it, 0f, y, null)
                y += it.height
                it.recycle()
            }
            return out
        }
    }
}

package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Menjalankan PrintDocumentAdapter milik WebView tanpa dialog print, dan
 * menulis hasilnya ke berkas PDF. Dipakai untuk mencetak halaman ke printer
 * thermal Bluetooth: halaman di-render dengan CSS @media print, lalu PDF-nya
 * diubah menjadi gambar ESC/POS.
 *
 * Kelas ini sengaja berada di paket android.print karena konstruktor
 * LayoutResultCallback dan WriteResultCallback tidak publik. Pemanggil wajib
 * menangkap Throwable dan menyiapkan jalur cadangan kalau trik ini ditolak
 * oleh versi Android tertentu.
 *
 * Harus dipanggil dari main thread; [done] juga dipanggil di main thread.
 */
object PdfPrintHelper {

    fun writePdf(
        adapter: PrintDocumentAdapter,
        attributes: PrintAttributes,
        out: File,
        done: (ok: Boolean, error: String?) -> Unit
    ) {
        adapter.onStart()
        adapter.onLayout(
            null,
            attributes,
            CancellationSignal(),
            object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    val pfd = try {
                        ParcelFileDescriptor.open(
                            out,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE or
                                ParcelFileDescriptor.MODE_READ_WRITE
                        )
                    } catch (e: Exception) {
                        adapter.onFinish()
                        done(false, e.message)
                        return
                    }
                    adapter.onWrite(
                        arrayOf(PageRange.ALL_PAGES),
                        pfd,
                        CancellationSignal(),
                        object : PrintDocumentAdapter.WriteResultCallback() {
                            override fun onWriteFinished(pages: Array<out PageRange>?) {
                                finish(true, null)
                            }

                            override fun onWriteFailed(error: CharSequence?) {
                                finish(false, error?.toString() ?: "Gagal menulis PDF")
                            }

                            override fun onWriteCancelled() {
                                finish(false, "Dibatalkan")
                            }

                            private fun finish(ok: Boolean, err: String?) {
                                try { pfd.close() } catch (_: Exception) { }
                                adapter.onFinish()
                                done(ok, err)
                            }
                        }
                    )
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    adapter.onFinish()
                    done(false, error?.toString() ?: "Gagal menata halaman")
                }

                override fun onLayoutCancelled() {
                    adapter.onFinish()
                    done(false, "Dibatalkan")
                }
            },
            null
        )
    }
}

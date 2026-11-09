package id.smesco.mobile

/**
 * Skrip yang disuntikkan ke setiap halaman situs Smesco. Aman dijalankan
 * berkali-kali. Isinya:
 *  - window.print()       -> dialog cetak aplikasi (printer biasa/PDF atau Bluetooth)
 *  - unduhan blob:/data:  -> isi berkas dibaca lalu disimpan ke folder Download
 *  - window.SmescoPrint   -> versi Promise dari window.SmescoPrinter
 *
 * Catatan: jangan pakai tanda dolar di sini, karena ini raw string Kotlin.
 */
object PageScripts {

    val BRIDGE = """
(function () {
  if (window.__smescoBridge) return;
  var N = window.SmescoNative;
  if (!N) return;
  window.__smescoBridge = true;

  // ---- window.print() ----
  window.print = function () { N.print(document.title || ''); };

  // ---- Unduhan berkas yang dibuat di dalam halaman ----
  // Simpan referensi Blob supaya tetap bisa dibaca walau URL-nya sudah di-revoke.
  var blobs = {};
  try {
    var origCreate = URL.createObjectURL;
    URL.createObjectURL = function (obj) {
      var u = origCreate.apply(URL, arguments);
      try { if (obj instanceof Blob) blobs[u] = obj; } catch (e) {}
      return u;
    };
    var origRevoke = URL.revokeObjectURL;
    URL.revokeObjectURL = function (u) {
      setTimeout(function () { delete blobs[u]; }, 60000);
      return origRevoke.apply(URL, arguments);
    };
  } catch (e) {}

  function isLocal(u) {
    return typeof u === 'string' && (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0);
  }

  function readBlob(b, name, openAfter) {
    var r = new FileReader();
    r.onloadend = function () {
      if (r.result) N.saveFile(String(r.result), name || '', !!openAfter);
      else N.saveFailed('Berkas tidak bisa dibaca');
    };
    r.readAsDataURL(b);
  }

  window.__smescoSave = function (url, name, openAfter) {
    if (url.indexOf('data:') === 0) { N.saveFile(url, name || '', !!openAfter); return; }
    if (blobs[url]) { readBlob(blobs[url], name, openAfter); return; }
    fetch(url).then(function (r) { return r.blob(); })
      .then(function (b) { readBlob(b, name, openAfter); })
      .catch(function () { N.saveFailed('Berkas tidak bisa dibaca (mungkin sudah kedaluwarsa)'); });
  };

  function handleAnchor(a) {
    if (!a || !isLocal(a.href)) return false;
    var name = a.getAttribute('download');
    // Tanpa atribut download berarti "buka", jadi langsung tampilkan setelah disimpan
    window.__smescoSave(a.href, name || '', name === null);
    return true;
  }

  document.addEventListener('click', function (e) {
    var t = e.target;
    var a = t && t.closest ? t.closest('a[href]') : null;
    if (a && !e.defaultPrevented && handleAnchor(a)) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  // Pustaka seperti FileSaver/jsPDF/SheetJS memakai <a> yang tidak ditempel ke halaman
  try {
    var AP = HTMLAnchorElement.prototype;
    var origClick = AP.click;
    AP.click = function () {
      if (!this.isConnected && handleAnchor(this)) return;
      return origClick.apply(this, arguments);
    };
    var origDispatch = AP.dispatchEvent;
    AP.dispatchEvent = function (ev) {
      if (ev && ev.type === 'click' && !this.isConnected && handleAnchor(this)) return true;
      return origDispatch.apply(this, arguments);
    };
  } catch (e) {}

  var origOpen = window.open;
  window.open = function (url) {
    if (isLocal(url)) { window.__smescoSave(url, '', true); return null; }
    return origOpen.apply(window, arguments);
  };

  // ---- Printer Bluetooth (Promise) ----
  var P = window.SmescoPrinter;
  if (!P) return;
  var pending = {};
  window.addEventListener('smescoprint', function (e) {
    var d = e.detail || {};
    var cb = pending[d.job];
    if (!cb) return;
    delete pending[d.job];
    if (d.ok) cb[0](d); else cb[1](new Error(d.message || 'Gagal mencetak'));
  });
  function job(id) {
    return new Promise(function (res, rej) { pending[id] = [res, rej]; });
  }
  window.SmescoPrint = {
    isAvailable: function () { return P.isAvailable(); },
    getPrinter: function () { return JSON.parse(P.getPrinter()); },
    columns: function () { return P.getColumns(); },
    selectPrinter: function () { return job(P.selectPrinter()); },
    text: function (t) { return job(P.printText(String(t))); },
    raw: function (bytes) {
      var s = '';
      if (typeof bytes === 'string') s = bytes;
      else { var u = new Uint8Array(bytes); for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); s = btoa(s); }
      return job(P.printRaw(s));
    },
    image: function (src) {
      if (src && src.toDataURL) src = src.toDataURL('image/png');
      return job(P.printImage(String(src)));
    },
    page: function () { return job(P.printPage()); },
    dialog: function () { P.systemPrint(document.title || ''); }
  };
  try { window.dispatchEvent(new Event('smescoprintready')); } catch (e) {}
})();
""".trimIndent()
}

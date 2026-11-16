package com.catatanku.app

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/** Rilis GitHub yang lebih baru dari versi terpasang. Catatan rilis sudah dibersihkan dari karakter kendali. */
data class UpdateRelease(
    val version: String,
    val notes: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
) {
    val sizeLabel: String get() = "%.1f MB".format(bytes / 1_000_000.0)
    val page: String get() = "https://github.com/${Updater.REPO}/releases/tag/v$version"
}

data class UpdateProgress(val percent: Int, val note: String)

/** Keadaan pemeriksaan pembaruan. File APK yang sudah diperiksa disimpan di [UpdateState.Available.ready]. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Failed(val message: String) : UpdateState
    data class Available(
        val release: UpdateRelease,
        val progress: UpdateProgress? = null,
        val ready: Boolean = false,
        val error: String? = null,
    ) : UpdateState
}

/** Perbandingan versi angka. Bebas Android agar bisa diuji di JVM. Akhiran seperti "-uji" diabaikan. */
internal object Versions {
    fun isNewer(remote: String, current: String): Boolean {
        val a = parts(remote) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int>? {
        val core = v.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
        if (core.isEmpty()) return null
        val list = core.split('.').map { it.toIntOrNull() ?: return null }
        return list.takeIf { it.size in 1..4 }
    }
}

/** Aturan nama berkas dan tautan unduhan rilis. Bebas Android agar bisa diuji di JVM. */
internal object UpdateNaming {
    private const val PREFIX = "Catatanku-"
    private const val TRUSTED = "https://github.com/papertop221/Catatanku/releases/download/"

    /** "Catatanku-1.8.apk" → "1.8"; selain pola itu → null. */
    fun versionOf(fileName: String): String? {
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(".apk")) return null
        val version = fileName.removePrefix(PREFIX).removeSuffix(".apk")
        return version.takeIf { isVersion(it) }
    }

    fun isVersion(v: String): Boolean {
        if (v.isEmpty() || v.length > 20) return false
        val parts = v.split('.')
        return parts.size in 1..4 && parts.all { it.isNotEmpty() && it.length <= 6 && it.all(Char::isDigit) }
    }

    /** Hanya berkas APK dari repositori ini, lewat HTTPS. */
    fun isTrustedUrl(url: String): Boolean =
        url.startsWith(TRUSTED) && url.endsWith(".apk") && url.none { it.isWhitespace() }

    fun cleanNotes(body: String): String = body
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .lineSequence().take(120).joinToString("\n")
        .take(4000).trim()
}

/** Membaca jawaban GitHub Releases API. Hanya aset APK yang punya checksum SHA-256 dan tautan resmi yang diterima. */
internal object UpdateParser {
    fun parse(json: String, currentVersion: String): UpdateRelease? {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val assets = root.optJSONArray("assets") ?: return null
        val notes = UpdateNaming.cleanNotes(root.optString("body"))
        var best: UpdateRelease? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val version = UpdateNaming.versionOf(asset.optString("name")) ?: continue
            if (!Versions.isNewer(version, currentVersion)) continue
            val digest = asset.optString("digest")
            if (!digest.startsWith("sha256:")) continue
            val sha = digest.removePrefix("sha256:").lowercase()
            if (sha.length != 64 || sha.any { it !in "0123456789abcdef" }) continue
            val url = asset.optString("browser_download_url")
            if (!UpdateNaming.isTrustedUrl(url)) continue
            val bytes = asset.optLong("size", 0)
            if (bytes < 1_000_000L) continue
            val release = UpdateRelease(version, notes, url, sha, bytes)
            if (best == null || Versions.isNewer(version, best.version)) best = release
        }
        return best
    }
}

/**
 * Pemeriksa dan pemasang pembaruan dari GitHub Releases repositori Catatanku.
 *
 * Pemeriksaan otomatis berjalan paling banyak sekali sehari dan bisa dimatikan. Tidak ada data pengguna yang
 * dikirim: hanya permintaan HTTPS untuk nomor versi terbaru. Sebelum dipasang, ukuran, SHA-256 dari rilis, nama
 * paket, nomor versi, dan sertifikat tanda tangan APK diperiksa agar hanya APK resmi yang bisa menggantikan aplikasi.
 */
class Updater private constructor(private val context: Context) {
    companion object {
        const val REPO = "papertop221/Catatanku"
        private const val API = "https://api.github.com/repos/$REPO/releases/latest"
        private const val MAX_JSON = 512 * 1024
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val ACTION_STATUS = "com.catatanku.app.UPDATE_STATUS"

        @Volatile private var instance: Updater? = null
        fun get(context: Context): Updater = instance ?: synchronized(this) {
            instance ?: Updater(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val dm get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val packageInfo: PackageInfo? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    /** Versi yang tampil di Pengaturan; versi build uji berakhiran "-uji". */
    val currentVersion: String = packageInfo?.versionName.orEmpty().ifBlank { "?" }
    val currentCode: Long = versionCodeOf(packageInfo)

    /** Build uji tidak boleh memasang pembaruan (paket dan kunci tanda tangannya berbeda). */
    private val debugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    val events = MutableSharedFlow<String>(extraBufferCapacity = 4)

    var autoCheck: Boolean
        get() = prefs.getBoolean("auto", true)
        set(on) { prefs.edit().putBoolean("auto", on).apply() }

    val lastCheck: Long get() = prefs.getLong("last_check", 0L)

    private var job: Job? = null
    private var watcher: Job? = null
    @Volatile private var downloading = false
    @Volatile private var installing = false
    @Volatile private var currentRelease: UpdateRelease? = null
    @Volatile private var readyFile: File? = null

    init {
        scope.launch { deleteOldFiles() }
        resume()
    }

    // ============================== Periksa ==============================

    fun check(manual: Boolean) {
        if (job?.isActive == true || downloading) return
        if (!manual) {
            if (debugBuild || !autoCheck) return
            if (System.currentTimeMillis() - lastCheck < DAY_MS) return
        }
        job = scope.launch {
            _state.value = UpdateState.Checking
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            try {
                val release = UpdateParser.parse(fetch(API), currentVersion)
                if (release == null) {
                    _state.value = UpdateState.UpToDate
                    if (manual) events.emit("Catatanku sudah versi terbaru ($currentVersion).")
                } else {
                    currentRelease = release
                    _state.value = UpdateState.Available(release)
                    if (!manual && prefs.getString("seen", null) != release.version) {
                        prefs.edit().putString("seen", release.version).apply()
                        events.emit("Catatanku ${release.version} tersedia. Buka Pengaturan → Pembaruan.")
                    } else if (manual) {
                        events.emit("Catatanku ${release.version} tersedia.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val message = reasonOf(e)
                val previous = _state.value
                _state.value = if (previous is UpdateState.Available) previous.copy(error = message) else UpdateState.Failed(message)
                if (manual) events.emit(message)
            }
        }
    }

    private fun fetch(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Catatanku/$currentVersion")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub menjawab kode $code")
            if (connection.contentLengthLong > MAX_JSON) throw IOException("Jawaban GitHub terlalu besar")
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_JSON) throw IOException("Jawaban GitHub terlalu besar")
                    out.write(buf, 0, n)
                }
                return out.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun reasonOf(e: Throwable): String = when (e) {
        is UnknownHostException -> "Tidak ada koneksi internet."
        is SocketTimeoutException -> "GitHub tidak menjawab. Coba lagi nanti."
        is SSLException -> "Koneksi aman ke GitHub gagal."
        else -> "Tidak bisa memeriksa pembaruan: ${e.message ?: "galat jaringan"}"
    }

    // ============================== Unduh ==============================

    fun download() {
        val release = currentRelease ?: return
        if (downloading) return
        if (debugBuild) { events.tryEmit("Build uji tidak bisa memasang pembaruan."); return }
        val dir = context.getExternalFilesDir("updates")
        if (dir == null) { fail(release, "Penyimpanan tidak tersedia."); return }
        if (dir.usableSpace < release.bytes + 50L * 1024 * 1024) {
            fail(release, "Ruang penyimpanan tidak cukup (butuh ±${release.sizeLabel}).")
            return
        }
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("Catatanku-${release.version}")) f.delete()
        }
        val part = File(dir, "Catatanku-${release.version}.apk.part")
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("Catatanku ${release.version}")
            .setDescription("Pembaruan aplikasi")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, "updates", part.name)
        val id = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            fail(release, "Unduhan tidak bisa dimulai: ${e.message}")
            return
        }
        prefs.edit().putLong("dl_id", id).apply()
        saveRelease(release)
        _state.value = UpdateState.Available(release, UpdateProgress(0, "Memulai…"))
        watch(id, release, autoInstall = true)
    }

    fun cancelDownload() {
        val id = prefs.getLong("dl_id", -1L)
        watcher?.cancel()
        if (id >= 0) runCatching { dm.remove(id) }
        clearDownload()
        currentRelease?.let { _state.value = UpdateState.Available(it) } ?: run { _state.value = UpdateState.Idle }
        events.tryEmit("Unduhan pembaruan dibatalkan.")
    }

    private fun watch(dlId: Long, release: UpdateRelease, autoInstall: Boolean) {
        downloading = true
        watcher = scope.launch {
            try {
                val dir = context.getExternalFilesDir("updates")
                if (dir == null) {
                    clearDownload()
                    fail(release, "Penyimpanan tidak tersedia.")
                    return@launch
                }
                val part = File(dir, "Catatanku-${release.version}.apk.part")
                while (isActive) {
                    val row = dm.query(DownloadManager.Query().setFilterById(dlId))?.use { c ->
                        if (!c.moveToFirst()) null else Triple(
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                        )
                    }
                    if (row == null) {       // dibatalkan dari notifikasi
                        clearDownload()
                        _state.value = UpdateState.Available(release)
                        return@launch
                    }
                    val (status, done, reason) = row
                    val percent = (done * 100 / release.bytes).toInt().coerceIn(0, 100)
                    val mb = "${done / 1_000_000} / ${release.bytes / 1_000_000} MB"
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> { finish(dlId, release, part, autoInstall); return@launch }
                        DownloadManager.STATUS_FAILED -> {
                            runCatching { dm.remove(dlId) }
                            clearDownload()
                            fail(release, "Unduhan pembaruan gagal (kode $reason). Periksa koneksi lalu coba lagi.")
                            return@launch
                        }
                        DownloadManager.STATUS_PAUSED -> progress(release, percent, when (reason) {
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Menunggu Wi-Fi · $mb"
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Menunggu jaringan · $mb"
                            else -> "Dijeda, akan dilanjutkan otomatis · $mb"
                        })
                        DownloadManager.STATUS_PENDING -> progress(release, percent, "Menunggu antrean…")
                        else -> progress(release, percent, mb)
                    }
                    delay(800)
                }
            } finally {
                downloading = false
            }
        }
    }

    private fun progress(release: UpdateRelease, percent: Int, note: String) {
        _state.value = UpdateState.Available(release, UpdateProgress(percent, note))
    }

    /** Ukuran dan SHA-256 dari rilis, lalu tanda tangan APK, sebelum berkas boleh dipasang. */
    private suspend fun finish(dlId: Long, release: UpdateRelease, part: File, autoInstall: Boolean) {
        _state.value = UpdateState.Available(release, UpdateProgress(100, "Memeriksa keutuhan berkas…"))
        val apk = File(part.parentFile, "Catatanku-${release.version}.apk")
        withContext(Dispatchers.IO) {
            try {
                if (!part.exists() || part.length() != release.bytes) throw IOException("ukuran berkas tidak cocok")
                if (sha256(part) != release.sha256) throw IOException("checksum tidak cocok")
                apk.delete()
                if (!part.renameTo(apk)) throw IOException("berkas tidak bisa disimpan")
                runCatching { dm.remove(dlId) }       // setelah diganti nama, berkas hasil unduhan tidak ikut terhapus
                verifyApk(apk, release)
                readyFile = apk
                clearDownload()
                _state.value = UpdateState.Available(release, ready = true)
                if (autoInstall) install() else events.emit("Pembaruan ${release.version} sudah terunduh. Buka Pengaturan → Pembaruan untuk memasang.")
            } catch (e: Exception) {
                runCatching { dm.remove(dlId) }
                part.delete()
                apk.delete()
                clearDownload()
                fail(release, "Pembaruan tidak bisa dipakai: ${e.message ?: "berkas tidak valid"}.")
            }
        }
    }

    private fun verifyApk(file: File, release: UpdateRelease) {
        val pm = context.packageManager
        val flags = signatureFlags()
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("berkas bukan APK yang sah")
        if (archive.packageName != context.packageName) throw IOException("APK ini bukan Catatanku")
        if (versionCodeOf(archive) <= currentCode) throw IOException("APK bukan versi yang lebih baru")
        if (archive.versionName?.let { Versions.isNewer(it, currentVersion) } != true) throw IOException("nomor versi APK tidak cocok")
        val installed = signatureOf(pm.getPackageInfo(context.packageName, flags))
        val downloaded = signatureOf(archive)
        if (installed.isEmpty() || installed != downloaded) throw IOException("tanda tangan APK berbeda dari aplikasi ini")
        check(UpdateNaming.isTrustedUrl(release.url))
    }

    // ============================== Pasang ==============================

    fun install() {
        if (installing) return
        val release = currentRelease
        val file = readyFile
        if (release == null || file == null || !file.exists()) {
            events.tryEmit("Berkas pembaruan tidak ditemukan; unduh ulang.")
            _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            return
        }
        if (debugBuild) { events.tryEmit("Build uji tidak bisa memasang pembaruan."); return }
        if (!context.packageManager.canRequestPackageInstalls()) {
            openInstallSettings()
            _state.value = UpdateState.Available(release, ready = true, error = "Izinkan pemasangan dari Catatanku di setelan HP, lalu tekan Pasang pembaruan.")
            events.tryEmit("Izinkan pemasangan dari Catatanku, lalu tekan Pasang pembaruan lagi.")
            return
        }
        installing = true
        scope.launch { commit(file, release) }
    }

    private suspend fun commit(file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() != release.bytes || sha256(file) != release.sha256) {
                throw IOException("verifikasi ulang berkas pembaruan gagal")
            }
            verifyApk(file, release)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                }
                val status = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
            events.emit("Setujui pemasangan di layar berikutnya.")
        } catch (e: Exception) {
            installing = false
            _state.value = UpdateState.Available(release, ready = true, error = "Pemasangan tidak bisa dimulai: ${e.message}")
            events.emit("Pemasangan tidak bisa dimulai: ${e.message}")
        }
    }

    internal fun installResult(status: Int, message: String?) {
        installing = false
        val release = currentRelease
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                readyFile?.delete()
                readyFile = null
                clearDownload()
                _state.value = UpdateState.Idle
                events.tryEmit("Pembaruan terpasang.")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                if (release != null) _state.value = UpdateState.Available(release, ready = true)
                events.tryEmit("Pemasangan dibatalkan. Tekan Pasang pembaruan untuk mencoba lagi.")
            }
            else -> {
                val text = message?.take(300)?.ifBlank { null } ?: "Pemasangan gagal (kode $status)."
                if (release != null) _state.value = UpdateState.Available(release, ready = true, error = text)
                events.tryEmit(text)
            }
        }
    }

    private fun openInstallSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ============================== Bantu ==============================

    private fun resume() {
        val id = prefs.getLong("dl_id", -1L)
        if (id < 0) return
        val release = readRelease()
        if (release == null) {
            clearDownload()
            return
        }
        currentRelease = release
        _state.value = UpdateState.Available(release, UpdateProgress(0, "Melanjutkan unduhan…"))
        watch(id, release, autoInstall = false)
    }

    private fun clearDownload() {
        prefs.edit().remove("dl_id").remove("dl_release").apply()
        downloading = false
    }

    private fun fail(release: UpdateRelease, message: String) {
        _state.value = UpdateState.Available(release, error = message)
        events.tryEmit(message)
    }

    private fun saveRelease(release: UpdateRelease) {
        prefs.edit().putString("dl_release", JSONObject().apply {
            put("version", release.version)
            put("notes", release.notes)
            put("url", release.url)
            put("sha256", release.sha256)
            put("bytes", release.bytes)
        }.toString()).apply()
    }

    private fun readRelease(): UpdateRelease? = runCatching {
        val o = JSONObject(prefs.getString("dl_release", null) ?: return null)
        UpdateRelease(
            o.getString("version"), o.optString("notes"), o.getString("url"),
            o.getString("sha256"), o.getLong("bytes"),
        )
    }.getOrNull()

    /** Sisa unduhan/pemasangan versi lama tidak perlu menahan ruang penyimpanan. */
    private fun deleteOldFiles() {
        if (prefs.getLong("dl_id", -1L) >= 0) return
        val dir = context.getExternalFilesDir("updates") ?: return
        dir.listFiles()?.forEach { f ->
            val version = UpdateNaming.versionOf(f.name.removeSuffix(".part"))
            if (version != null && !Versions.isNewer(version, currentVersion)) f.delete()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun signatureOf(info: PackageInfo): List<String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptyList()
            signing.apkContentsSigners.ifEmpty { signing.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptyList()
        }
        return certs.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.sorted()
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo?): Long =
        if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode ?: 0L else (info?.versionCode ?: 0).toLong()
}

/** Menerima hasil dari layar pemasangan sistem dan meneruskannya ke [Updater]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Updater.get(context).installResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

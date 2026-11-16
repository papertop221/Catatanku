package com.catatanku.app

import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Pintu masuk dari luar aplikasi: menu teks terpilih di aplikasi lain, tile Setelan Cepat, pintasan ikon,
// kalender sistem, dan pengaturan baterai khas pabrikan.

/** Aksi yang dipahami [MainActivity] dari tile, pintasan ikon, dan aplikasi otomasi (mis. Tasker). */
object Actions {
    const val NEW_NOTE = "com.catatanku.app.NEW_NOTE"
    const val SHOW_REMINDERS = "com.catatanku.app.SHOW_REMINDERS"
    const val SHOW_TASKS = "com.catatanku.app.SHOW_TASKS"
}

/**
 * Pembatasan latar belakang khas pabrikan. Di luar Android baku, sebagian HP menghentikan aplikasi yang tidak
 * tampil dan ikut membuang alarmnya, sehingga pengingat terlambat atau tidak berbunyi. Aplikasi tidak bisa
 * mematikan pembatasan itu sendiri; yang bisa dilakukan adalah memakai jenis alarm yang paling jarang ditahan,
 * mengantar pengguna ke setelan yang tepat, dan mengukur apakah pengingat benar-benar tepat waktu.
 */
object Oem {
    private val maker = Build.MANUFACTURER.orEmpty().lowercase()

    /** Kelompok antarmuka pabrikan; null = tidak dikenal sebagai pembatas agresif. */
    val family: String? = when (maker) {
        "oppo", "realme", "oneplus" -> "oppo"
        "vivo", "iqoo" -> "vivo"
        "xiaomi", "redmi", "poco" -> "xiaomi"
        "huawei", "honor" -> "huawei"
        "tecno", "infinix", "itel" -> "transsion"
        else -> null
    }
    val aggressive get() = family != null
    val brand: String get() = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }

    /** Langkah di setelan HP. Nama menu berbeda-beda antarversi; ini yang paling umum. */
    val steps: List<String> = when (family) {
        "oppo" -> listOf(
            "Setelan → Aplikasi → Luncurkan otomatis: nyalakan Catatanku.",
            "Setelan → Aplikasi → Catatanku → Penggunaan baterai: izinkan aktivitas latar belakang.",
            "Di layar aplikasi terbaru, kunci Catatanku (ikon gembok) agar tidak ikut dibersihkan.",
        )
        "vivo" -> listOf(
            "Setelan → Baterai → Manajemen konsumsi daya latar belakang → Catatanku: jangan batasi.",
            "Setelan → Aplikasi → Akses aplikasi khusus → Mulai otomatis: izinkan Catatanku.",
            "Di layar aplikasi terbaru, kunci Catatanku (ikon gembok) agar tidak ikut dibersihkan.",
        )
        "xiaomi" -> listOf(
            "Setelan → Aplikasi → Kelola aplikasi → Catatanku → Mulai otomatis: nyalakan.",
            "Di halaman yang sama → Penghemat baterai: Tanpa pembatasan.",
            "Di layar aplikasi terbaru, kunci Catatanku (ikon gembok).",
        )
        "huawei" -> listOf(
            "Setelan → Baterai → Peluncuran aplikasi → Catatanku: kelola manual, lalu nyalakan ketiga pilihannya.",
            "Di layar aplikasi terbaru, kunci Catatanku (ikon gembok).",
        )
        "transsion" -> listOf(
            "Phone Master → Kelola mulai otomatis: izinkan Catatanku.",
            "Setelan → Baterai: keluarkan Catatanku dari pembatasan latar belakang.",
        )
        else -> emptyList()
    }

    /** Halaman "mulai otomatis" yang dikenal. Namanya berasal dari daftar komunitas dan bisa berubah antarversi, jadi dicoba satu per satu. */
    private val pages: List<Pair<String, String>> = when (family) {
        "oppo" -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        "vivo" -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        )
        "xiaomi" -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
        "huawei" -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        "transsion" -> listOf("com.transsion.phonemaster" to "com.itel.autobootmanager.activity.AutoBootMgrActivity")
        else -> emptyList()
    }

    /** Membuka halaman mulai otomatis pabrikan; bila tidak ada yang bisa dibuka, halaman info aplikasi. True = halaman pabrikan terbuka. */
    fun openAutostart(ctx: Context): Boolean {
        for ((pkg, cls) in pages) {
            val ok = runCatching { ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return true
        }
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    /** Aplikasi sudah dikecualikan dari pengoptimalan baterai Android (Doze dan App Standby). */
    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) != false

    /** Meminta pengecualian lewat dialog sistem; bila dialognya tidak ada, membuka daftar pengoptimalan baterai. */
    fun requestBatteryUnrestricted(ctx: Context) {
        val direct = runCatching {
            @Suppress("BatteryLife")
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!direct) runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Menyalin pengingat ke aplikasi kalender HP lewat layar "acara baru" (tanpa izin kalender; pengguna yang menyimpan).
 * Kalender bawaan biasanya tidak dibatasi penghemat baterai, jadi ini cadangan untuk pengingat yang tidak boleh terlewat.
 */
fun addToCalendar(ctx: Context, title: String, body: String, at: Long, repeat: Repeat): Boolean = runCatching {
    val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title.take(120))
        .putExtra(CalendarContract.Events.DESCRIPTION, body.take(1000))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + 30 * 60_000L)
    when (repeat) {
        Repeat.Daily -> "FREQ=DAILY"; Repeat.Weekly -> "FREQ=WEEKLY"; Repeat.Monthly -> "FREQ=MONTHLY"; Repeat.None -> null
    }?.let { i.putExtra(CalendarContract.Events.RRULE, it) }
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/** Pintasan di ikon aplikasi (tekan lama ikon Catatanku). Didaftarkan dari kode agar berlaku juga untuk build uji yang id-nya berbeda. */
fun publishShortcuts(ctx: Context) = runCatching {
    fun item(id: String, label: String, action: String, icon: Int) = android.content.pm.ShortcutInfo.Builder(ctx, id)
        .setShortLabel(label).setIcon(android.graphics.drawable.Icon.createWithResource(ctx, icon))
        .setIntent(Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)).build()
    ctx.getSystemService(android.content.pm.ShortcutManager::class.java)?.dynamicShortcuts = listOf(
        item("baru", "Catatan baru", Actions.NEW_NOTE, R.drawable.ic_tile),
        item("pengingat", "Pengingat", Actions.SHOW_REMINDERS, R.drawable.ic_bell_dark),
        item("tugas", "Tugas belum selesai", Actions.SHOW_TASKS, R.drawable.ic_tasks),
    )
}

/** Catatan yang dibuat di luar layar utama (menu teks terpilih): simpan, setel alarmnya, dan beri tahu layar bila sedang terbuka. */
fun saveFromOutside(ctx: Context, note: Note): Long {
    val id = NoteDb.get(ctx).upsert(note)
    if (note.hasReminder) Reminders.sync(ctx, note.copy(id = id))
    ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("dirty", true).apply()      // ikut cadangan otomatis berikutnya
    Reminders.changed.tryEmit(id)
    return id
}

/** Tile Setelan Cepat "Catatan baru": menulis catatan dari mana saja tanpa mencari ikon aplikasi. */
class QuickNoteTile : TileService() {
    override fun onClick() {
        val open = Intent(this, MainActivity::class.java).setAction(Actions.NEW_NOTE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(open)
    }
}

/**
 * Muncul sebagai "Catatanku" di menu teks terpilih aplikasi mana pun (browser, obrolan, PDF). Teksnya bisa
 * disimpan sebagai catatan, atau diolah AI lokal lalu dikembalikan ke aplikasi asal. Teks tidak pernah keluar dari HP.
 *
 * Layar ini hanya menerima teks dan menulis catatan baru; ia tidak menampilkan catatan yang ada, jadi aman
 * dipakai walau kunci aplikasi aktif.
 */
class ProcessTextActivity : ComponentActivity() {
    private var result by mutableStateOf<String?>(null)     // null = belum ada aksi AI yang dijalankan
    private var running by mutableStateOf(false)
    private var label by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var job: Job? = null
    private val ai by lazy { LocalAi.get(this) }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun run(action: AiAction, text: String) {
        label = action.label; result = ""; error = null; running = true
        job = lifecycleScope.launch {
            try {
                val out = ai.generate(AiText.prompt(action, "", text)) { result = AiText.cleanBlock(it) }
                result = AiText.cleanBlock(out)
                if (result.isNullOrBlank()) error = "Model tidak menghasilkan teks. Coba lagi dengan teks yang lebih pendek."
            } catch (e: CancellationException) {
            } catch (e: Throwable) { error = "AI gagal: ${e.message}" } finally { running = false }
        }
    }

    private fun save(text: String, remindAt: Long = 0) {
        saveFromOutside(this, Note(body = text.trim(), remindAt = remindAt))
        toast(if (remindAt != 0L) "Disimpan ke Catatanku, pengingat ${whenLabel(remindAt)}" else "Disimpan ke Catatanku")
        finish()
    }

    override fun onDestroy() {
        job?.cancel()
        // Model tidak perlu menahan RAM setelah lembar ini ditutup.
        @Suppress("DEPRECATION") ai.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        val readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) != false
        if (text.isEmpty()) { finish(); return }
        val time = TimeParse.best(text)?.at?.toMillis()
        val dynamic = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dynamic_color", false)
        setContent {
            CatatankuTheme(dynamic) {
                val aiState by ai.state.collectAsStateWithLifecycle()
                val hasModel = aiState !is AiState.NoModel
                val out = result
                Sheet(if (out == null) "Catatanku" else label, onDismiss = ::finish) {
                    if (out == null) {
                        Text(text, Modifier.padding(top = 6.dp, bottom = 10.dp), style = NoteType.rowSnippet, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        @androidx.compose.runtime.Composable
                        fun Option(title: String, hint: String, enabled: Boolean = true, hintColor: androidx.compose.ui.graphics.Color = muted, onClick: () -> Unit) = Row(
                            Modifier.fillMaxWidth().clip(SmallShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else muted.copy(alpha = 0.5f))
                            Text(hint, Modifier.padding(start = 12.dp), color = hintColor, style = MaterialTheme.typography.bodySmall)
                        }
                        Option("Simpan sebagai catatan", "Tanpa AI") { save(text) }
                        // Waktu yang disebut di teks dikenali tanpa model.
                        if (time != null) Option("Simpan dan ingatkan", whenLabel(time), hintColor = timeColor) { save(text, time) }
                        Hairline(Modifier.padding(vertical = 4.dp))
                        Option("Ringkas", "Menjadi 3–5 poin", hasModel) { run(AiAction.Summarize, text) }
                        Option("Perbaiki ejaan", "Makna tetap", hasModel) { run(AiAction.Fix, text) }
                        Option("Ambil daftar tugas", "Menjadi daftar centang", hasModel) { run(AiAction.Tasks, text) }
                        if (!hasModel) Muted("Aksi AI butuh model. Buka Catatanku → Pengaturan → Model AI untuk mengunduhnya.", Modifier.padding(top = 6.dp))
                        else if (text.length > AiText.maxInput) Muted("Teks panjang: AI hanya membaca ±${AiText.maxInput} huruf pertama.", Modifier.padding(top = 6.dp))
                    } else {
                        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        Column(Modifier.heightIn(min = 80.dp, max = 340.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                            if (running && out.isEmpty()) Muted(if (aiState is AiState.Loading) "Memuat model ke memori…" else "Menulis…", style = MaterialTheme.typography.bodyMedium)
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                            if (out.isNotEmpty()) Text(out, style = NoteType.body, color = MaterialTheme.colorScheme.onSurface)
                        }
                        when {
                            running -> SecondaryButton("Hentikan", Modifier.fillMaxWidth()) { job?.cancel() }
                            out.isBlank() -> SecondaryButton("Kembali ke pilihan", Modifier.fillMaxWidth()) { result = null; error = null }
                            else -> {
                                // Aplikasi asal mengizinkan teksnya diganti (kolom yang bisa disunting): itu aksi utamanya.
                                if (!readOnly) PrimaryButton("Ganti teks terpilih", Modifier.fillMaxWidth()) {
                                    setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, out)); finish()
                                } else PrimaryButton("Simpan sebagai catatan", Modifier.fillMaxWidth()) { save(out) }
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextAction("Salin hasil") { copyText(this@ProcessTextActivity, out); toast("Hasil disalin"); finish() }
                                    if (!readOnly) TextAction("Simpan sebagai catatan") { save(out) }
                                    TextAction("Buang hasil") { result = null }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

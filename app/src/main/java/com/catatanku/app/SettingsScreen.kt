package com.catatanku.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

// Pengaturan: bagian-bagian datar dipisah garis tipis. Tiap baris = judul, keterangan, dan satu kontrol.

/** Kepala bagian, dengan garis tipis di atasnya sebagai pemisah. */
@Composable
private fun Section(title: String) {
    Hairline(Modifier.padding(top = 12.dp))
    SectionLabel(title, Modifier.padding(start = Gutter, top = 16.dp, bottom = 4.dp))
}

/** Satu baris pengaturan: judul, keterangan kecil, dan satu kontrol di kanan. */
@Composable
private fun SettingRow(title: String, note: String, error: Boolean = false, control: @Composable () -> Unit) = Row(
    Modifier.heightIn(min = 56.dp).padding(start = Gutter, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f).padding(end = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(note, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else muted)
    }
    control()
}

private fun openAppNotificationSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Satu model di daftar: nama, ukuran, dan satu aksi. Rincian muncul saat barisnya diketuk. */
@Composable
private fun ModelRow(
    name: String, badge: String?, meta: String, open: Boolean, onToggle: () -> Unit, progress: Download?, detail: String,
    canDelete: Boolean, onDelete: () -> Unit, action: @Composable () -> Unit,
) = Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Rincian model", onClick = onToggle).padding(start = Gutter, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (badge != null) Badge(badge)
            }
            Muted(meta, style = MaterialTheme.typography.labelSmall)
        }
        action()
    }
    if (progress != null) {
        LinearProgressIndicator({ progress.percent / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp))
        Muted("${progress.percent}%  ·  ${progress.note}", Modifier.padding(top = 4.dp), MaterialTheme.typography.labelSmall)
    }
    if (open) {
        Muted(detail, Modifier.padding(top = 4.dp))
        if (canDelete) TextAction("Hapus model", Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.error, onClick = onDelete)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, snack: SnackbarHostState) {
    val aiState by vm.ai.state.collectAsStateWithLifecycle()
    val download by vm.ai.download.collectAsStateWithLifecycle()
    val revision by vm.ai.revision.collectAsStateWithLifecycle()      // memicu gambar ulang saat model berubah
    var gpu by remember { mutableStateOf(vm.ai.useGpu) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmDownload by remember { mutableStateOf<ModelSpec?>(null) }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importModel) }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportTo) }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFrom) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setBackupFolder) }
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }     // model yang rinciannya sedang dibuka
    // Izin bisa berubah di pengaturan sistem; periksa lagi tiap kali layar ini kembali tampil.
    var canNotify by remember { mutableStateOf(Reminders.canNotify(ctx)) }
    var canExact by remember { mutableStateOf(Reminders.canExact(ctx)) }
    var battery by remember { mutableStateOf(Oem.batteryUnrestricted(ctx)) }
    var robust by remember { mutableStateOf(Reminders.robust(ctx)) }
    var test by remember { mutableStateOf(Reminders.testState(ctx)) }
    var late by remember { mutableStateOf(Reminders.lastLate(ctx)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canNotify = Reminders.canNotify(ctx); canExact = Reminders.canExact(ctx); battery = Oem.batteryUnrestricted(ctx)
        test = Reminders.testState(ctx); late = Reminders.lastLate(ctx); now = System.currentTimeMillis()
    }
    // Hasil uji pengingat tiba selagi layar ini terbuka.
    LaunchedEffect(Unit) { Reminders.changed.collect { test = Reminders.testState(ctx); now = System.currentTimeMillis() } }
    LaunchedEffect(test) { while (test.first > 0 && test.second == null && now < test.first + 5 * 60_000L) { delay(15_000); now = System.currentTimeMillis() } }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = Reminders.canNotify(ctx)
        if (!granted) openAppNotificationSettings(ctx)      // sudah pernah ditolak: sistem tidak menampilkan dialog lagi
    }
    val ram = vm.ai.deviceRamGb
    val recommended = ModelCatalog.recommended(ram)
    val busy = aiState is AiState.Importing || aiState is AiState.Loading
    val active = revision.let { vm.ai.activeId }

    Scaffold(snackbarHost = { Notices(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(AppIcons.Back, "Kembali ke daftar") { vm.go(Screen.List) }
                Text("Pengaturan", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
            }

            // ---------- Model AI: satu baris per model; ketuk untuk rincian ----------
            SectionLabel("Model AI", Modifier.padding(start = Gutter, top = 12.dp, bottom = 4.dp))
            val status = when (val st = aiState) {
                AiState.NoModel -> "Belum ada model. Unduh sekali; sesudahnya AI berjalan tanpa internet."
                is AiState.Importing -> "Menyalin berkas… ${st.percent}%"
                AiState.Idle -> "${vm.ai.activeName} aktif. Model dimuat ke memori hanya saat dipakai."
                AiState.Loading -> "Memuat ${vm.ai.activeName} ke memori…"
                AiState.Ready -> "${vm.ai.activeName} aktif dan sedang di memori."
                is AiState.Failed -> st.message
            }
            Text(
                status + if (ram > 0) "  RAM HP ini ±$ram GB." else "",
                Modifier.padding(horizontal = Gutter).padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = if (aiState is AiState.Failed) MaterialTheme.colorScheme.error else muted,
            )
            (aiState as? AiState.Importing)?.let { LinearProgressIndicator({ it.percent / 100f }, Modifier.fillMaxWidth().padding(horizontal = Gutter)) }
            if (aiState is AiState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Gutter))

            ModelCatalog.all.forEach { spec ->
                val installed = revision.let { vm.ai.isInstalled(spec.id) }
                if (spec.legacy && !installed) return@forEach
                val dl = download?.takeIf { it.id == spec.id }
                val heavy = ram in 1 until spec.minRamGb
                val isActive = installed && active == spec.id
                ModelRow(
                    spec.name, if (isActive) "Aktif" else spec.tier,
                    "Unduhan ${spec.sizeLabel}  ·  untuk RAM ${spec.recRamGb} GB ke atas" + if (heavy) "  ·  terlalu berat untuk HP ini" else "",
                    expanded == spec.id, { expanded = if (expanded == spec.id) null else spec.id }, dl,
                    spec.blurb + if (spec == recommended && ram > 0) " Disarankan untuk HP ini." else "",
                    installed && !busy, { confirmDelete = spec.id },
                ) {
                    when {
                        dl != null -> SmallButton("Batalkan") { vm.ai.cancelDownload() }
                        isActive -> {}
                        installed -> SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(spec.id) }
                        else -> SmallButton("Unduh", primary = spec == recommended && ram > 0, enabled = download == null) { confirmDownload = spec }
                    }
                }
            }
            // Model dari berkas .litertlm milik pengguna
            val importInstalled = revision.let { vm.ai.isInstalled(ModelCatalog.IMPORT_ID) }
            val importActive = active == ModelCatalog.IMPORT_ID
            ModelRow(
                "Dari berkas", if (importActive) "Aktif" else null, "Berkas .litertlm milikmu sendiri",
                expanded == ModelCatalog.IMPORT_ID, { expanded = if (expanded == ModelCatalog.IMPORT_ID) null else ModelCatalog.IMPORT_ID }, null,
                "Sudah punya berkas model di HP? Impor saja, tanpa mengunduh ulang.", importInstalled && !busy, { confirmDelete = ModelCatalog.IMPORT_ID },
            ) {
                if (importInstalled && !importActive) SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(ModelCatalog.IMPORT_ID) }
                else SmallButton(if (importInstalled) "Ganti berkas" else "Pilih berkas", enabled = !busy) { pickModel.launch(arrayOf("*/*")) }
            }
            if (download != null) Muted("Unduhan berjalan di latar; aplikasi boleh ditutup.", Modifier.padding(horizontal = Gutter))

            if (aiState !is AiState.NoModel && aiState !is AiState.Importing) {
                Section("Kinerja AI")
                SettingRow("Mode ringan", "Untuk RAM terbatas: memori lebih hemat dan model langsung dilepas, tetapi AI membaca teks lebih pendek.") {
                    Switch(vm.lightAi, vm::updateLightAi, enabled = !busy)
                }
                SettingRow("Akselerasi GPU", "Matikan bila model gagal dimuat.") { Switch(gpu, { gpu = it; vm.setGpu(it) }, enabled = !busy) }
                val idle = if (vm.lightAi) "20 detik" else "3 menit"
                SettingRow(
                    "Memori",
                    if (aiState is AiState.Ready) "Model sedang memakai RAM; dilepas sendiri setelah $idle tak dipakai, atau begitu aplikasi ditinggalkan."
                    else "Model tidak memakai RAM sekarang. Dimuat saat dipakai, lalu dilepas setelah $idle menganggur.",
                ) {
                    if (aiState is AiState.Ready) SmallButton("Lepas", onClick = vm::unloadModel)
                    else SmallButton("Uji muat", enabled = !busy, onClick = vm::loadModel)
                }
                SettingRow("Isi judul & tag otomatis", "Setelah catatan ditutup, AI mengisi judul dan tag yang masih kosong di latar.") {
                    Switch(vm.autoOrganize, vm::updateAutoOrganize)
                }
            }

            // ---------- Pengingat ----------
            Section("Pengingat")
            if (!canNotify) SettingRow("Notifikasi dimatikan", "Tanpa izin ini pengingat tidak akan muncul.", error = true) {
                SmallButton("Izinkan", primary = true) {
                    if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppNotificationSettings(ctx)
                }
            }
            if (!canExact) SettingRow("Alarm tepat waktu dimatikan", "Pengingat bisa terlambat beberapa menit.", error = true) {
                SmallButton("Izinkan") {
                    if (Build.VERSION.SDK_INT >= 31) runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                    }
                }
            }
            if (canNotify && canExact) SettingRow(
                "Pengingat aktif", "${vm.reminderCount} catatan punya pengingat. Tetap berbunyi saat aplikasi ditutup dan setelah HP dinyalakan ulang.",
            ) { SmallButton("Atur bunyi") { openAppNotificationSettings(ctx) } }

            // ---------- Keandalan: penghemat baterai pabrikan ----------
            Section(if (Oem.aggressive) "Agar pengingat tidak ditahan HP ${Oem.brand}" else "Agar pengingat tidak ditahan HP")
            if (late.first > 0) SettingRow(
                "Pengingat terakhir terlambat ${late.first / 60_000} menit", "Terjadi ${whenLabel(late.second)}. HP menahan Catatanku di latar; kerjakan langkah di bawah, lalu uji lagi.", error = true,
            ) { SmallButton("Tutup pesan") { Reminders.clearLate(ctx); late = 0L to 0L } }
            SettingRow(
                "Jadwalkan sebagai alarm sistem",
                "Jenis alarm yang paling jarang ditahan penghemat baterai. Ikon alarm tampil di bilah status selama ada pengingat.",
            ) { Switch(robust, { robust = it; Reminders.setRobust(ctx, it) }) }
            SettingRow(
                "Pengoptimalan baterai",
                if (battery) "Catatanku sudah dikecualikan." else "Catatanku masih dibatasi saat HP diam lama. Kecualikan agar pengingat tepat waktu.",
                error = !battery,
            ) { if (!battery) SmallButton("Kecualikan", primary = true) { Oem.requestBatteryUnrestricted(ctx) } }
            if (Oem.aggressive) {
                Column(Modifier.padding(start = Gutter, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    Text("Setelan khusus ${Oem.brand}", style = MaterialTheme.typography.bodyLarge)
                    Muted("HP ini bisa menghentikan aplikasi di latar dan ikut membuang pengingatnya. Aplikasi tidak bisa mengubahnya sendiri; lakukan sekali:", Modifier.padding(top = 2.dp))
                    Oem.steps.forEachIndexed { i, s -> Muted("${i + 1}. $s", Modifier.padding(top = 4.dp)) }
                    Muted("Nama menu bisa sedikit berbeda antarversi.", Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 8.dp)) { SmallButton("Buka setelan mulai otomatis") { if (!Oem.openAutostart(ctx)) vm.say("Halaman khususnya tidak ditemukan; dibuka info aplikasi. Cari Penggunaan baterai di sana.") } }
                }
            } else Muted(
                "Sebagian HP (Oppo, Vivo, Realme, Xiaomi) punya pembatas tambahan. Bila uji di bawah gagal, cari \"mulai otomatis\" dan " +
                    "\"aktivitas latar belakang\" untuk Catatanku di setelan HP.",
                Modifier.padding(horizontal = Gutter, vertical = 4.dp),
            )
            val (due, lateMs) = test
            SettingRow(
                "Uji pengingat",
                when {
                    due == 0L -> "Notifikasi uji berbunyi 2 menit lagi. Sesudah menekan, tutup Catatanku dari layar aplikasi terbaru dan kunci layar HP."
                    lateMs != null && lateMs < 60_000L -> "Uji ${whenLabel(due)}: berbunyi tepat waktu."
                    lateMs != null -> "Uji ${whenLabel(due)}: terlambat ${lateMs / 60_000} menit. Kerjakan langkah di atas, lalu uji lagi."
                    now < due + 90_000L -> "Menunggu: berbunyi ${whenLabel(due)}. Tutup Catatanku dari layar aplikasi terbaru dan kunci layar HP."
                    else -> "Uji ${whenLabel(due)} tidak berbunyi. HP menahan Catatanku; kerjakan langkah di atas, lalu uji lagi."
                },
                error = due != 0L && ((lateMs != null && lateMs >= 60_000L) || (lateMs == null && now >= due + 90_000L)),
            ) { SmallButton(if (due == 0L) "Mulai uji" else "Uji lagi", enabled = canNotify) { Reminders.startTest(ctx); test = Reminders.testState(ctx); now = System.currentTimeMillis() } }

            // ---------- Tampilan ----------
            if (Build.VERSION.SDK_INT >= 31) {
                Section("Tampilan")
                SettingRow("Warna mengikuti wallpaper", "Bawaannya warna Catatanku sendiri. Mode gelap tetap berlatar hitam pekat.") {
                    Switch(vm.dynamicColor, vm::updateDynamicColor)
                }
            }

            // ---------- Keamanan ----------
            Section("Keamanan")
            SettingRow("Kunci aplikasi", "Buka dengan sidik jari atau PIN layar HP. Isi notifikasi pengingat ikut disembunyikan.") {
                Switch(vm.lockEnabled, { vm.updateLock(it) })
            }

            // ---------- Cadangan ----------
            Section("Cadangan")
            val folder = vm.backupLabel
            SettingRow(
                "Cadangan otomatis",
                when {
                    folder == null -> "Pilih folder; cadangan ditulis tiap ada perubahan, ${AutoBackup.KEEP} berkas terakhir disimpan."
                    vm.backupError != null -> "Gagal menulis ke $folder: ${vm.backupError}. Pilih ulang foldernya."
                    vm.backupAt > 0 -> "$folder  ·  terakhir ${whenLabel(vm.backupAt)}"
                    else -> folder
                },
                error = vm.backupError != null,
            ) {
                if (folder == null || vm.backupError != null) SmallButton("Pilih folder", primary = true) { pickFolder.launch(null) }
                else SmallButton("Matikan") { vm.setBackupFolder(null) }
            }
            SettingRow("Ekspor dan impor", "Satu berkas JSON berisi semua catatan, Sampah, dan pengingat. Tidak dienkripsi.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Impor") { importFrom.launch(arrayOf("application/json", "*/*")) }
                    SmallButton("Ekspor") { exportTo.launch("catatanku-cadangan.json") }
                }
            }

            // ---------- Privasi ----------
            Section("Privasi")
            Muted(
                "Catatan, pengingat, dan pemrosesan AI tetap di perangkat. Internet hanya dipakai saat kamu menekan Unduh, " +
                    "untuk mengambil berkas model dari huggingface.co.",
                Modifier.padding(horizontal = Gutter),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
    confirmDownload?.let { spec ->
        val enough = vm.ai.freeSpaceFor(spec)
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Unduh ${spec.name}?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "${spec.sizeLabel} dari huggingface.co. " +
                        (if (!enough) "Ruang penyimpanan tidak cukup; kosongkan dulu. " else "") +
                        (if (ram in 1 until spec.minRamGb) "RAM HP ini (±$ram GB) kemungkinan tidak cukup. " else "") +
                        "Unduhan lanjut otomatis bila koneksi putus."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    PrimaryButton("Unduh lewat Wi-Fi", enabled = enough) { confirmDownload = null; vm.ai.startDownload(spec, wifiOnly = true) }
                    TextAction("Pakai data seluler juga", enabled = enough) { confirmDownload = null; vm.ai.startDownload(spec, wifiOnly = false) }
                    TextAction("Batal") { confirmDownload = null }
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }
    confirmDelete?.let { id ->
        Confirm("Hapus model ini?", "Catatan tidak terpengaruh. Model bisa dipasang lagi kapan saja.", "Hapus model", destructive = true,
            onDismiss = { confirmDelete = null }) { vm.deleteModel(id) }
    }
}

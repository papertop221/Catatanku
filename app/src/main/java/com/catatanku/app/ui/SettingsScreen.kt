package com.catatanku.app.ui

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catatanku.app.ai.engine.AiState
import com.catatanku.app.ai.engine.Download
import com.catatanku.app.ai.modelcatalog.ModelCatalog
import com.catatanku.app.ai.modelcatalog.ModelSpec
import com.catatanku.app.ai.performance.AiBackendKind
import com.catatanku.app.ai.performance.ContextProfile
import com.catatanku.app.data.repository.AutoBackup
import com.catatanku.app.domain.model.Screen
import com.catatanku.app.reminders.Oem
import com.catatanku.app.reminders.Reminders
import com.catatanku.app.reminders.whenLabel
import kotlinx.coroutines.delay

@Composable
private fun Section(title: String) {
    Hairline(Modifier.padding(top = 12.dp))
    SectionLabel(title, Modifier.padding(start = Gutter, top = 16.dp, bottom = 4.dp))
}

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, snack: SnackbarHostState) {
    val aiState by vm.ai.state.collectAsStateWithLifecycle()
    val download by vm.ai.download.collectAsStateWithLifecycle()
    val revision by vm.ai.revision.collectAsStateWithLifecycle()
    val activeProfile by vm.ai.activeProfile.collectAsStateWithLifecycle()
    val benchRunning by vm.ai.benchmarkRunning.collectAsStateWithLifecycle()
    val embInstalled by vm.embeddings.installed.collectAsStateWithLifecycle()

    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmDownload by remember { mutableStateOf<ModelSpec?>(null) }
    var askExportPass by remember { mutableStateOf(false) }
    var pendingExportPass by remember { mutableStateOf<CharArray?>(null) }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importModel) }
    val pickEmbModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importEmbeddingModel) }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val pass = pendingExportPass
        pendingExportPass = null
        if (uri != null) vm.exportTo(uri, pass) else pass?.fill('\u0000')
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.importFrom(u) } }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setBackupFolder) }
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
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
    LaunchedEffect(Unit) { Reminders.changed.collect { test = Reminders.testState(ctx); now = System.currentTimeMillis() } }
    LaunchedEffect(test) { while (test.first > 0 && test.second == null && now < test.first + 5 * 60_000L) { delay(15_000); now = System.currentTimeMillis() } }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = Reminders.canNotify(ctx)
        if (!granted) openAppNotificationSettings(ctx)
    }
    val ram = vm.ai.deviceRamGb
    val recommended = ModelCatalog.recommended(ram)
    val busy = aiState is AiState.Importing || aiState is AiState.Loading || benchRunning
    val active = revision.let { vm.ai.activeId }

    Scaffold(snackbarHost = { Notices(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(AppIcons.Back, "Kembali ke daftar") { vm.go(Screen.List) }
                Text("Pengaturan", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
            }

            // ---------- Model AI ----------
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
                status + if (ram > 0) "  RAM fisik HP ±$ram GB." else "",
                Modifier.padding(horizontal = Gutter).padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = if (aiState is AiState.Failed) MaterialTheme.colorScheme.error else muted,
            )
            (aiState as? AiState.Importing)?.let { LinearProgressIndicator({ it.percent / 100f }, Modifier.fillMaxWidth().padding(horizontal = Gutter)) }
            if (aiState is AiState.Loading || benchRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Gutter))

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
                activeProfile?.let { prof ->
                    Muted(
                        "Setelan aktif: ${prof.backend.label} · ${prof.contextProfile.label} (${prof.source.label}).",
                        Modifier.padding(horizontal = Gutter, vertical = 4.dp),
                    )
                }
                SettingRow("Mode ringan", "Untuk RAM terbatas: memakai batas bacaan Hemat dan model segera dilepas dari memori.") {
                    Switch(vm.lightAi, vm::updateLightAi, enabled = !busy)
                }
                val gpuSelected = activeProfile?.backend == AiBackendKind.GPU
                SettingRow("Akselerasi GPU", "Memakai GPU untuk teks (otomatis kembali ke CPU bila gagal). CPU umumnya lebih hemat RAM.") {
                    Switch(gpuSelected, { vm.setGpu(it) }, enabled = !busy)
                }
                Column(Modifier.padding(horizontal = Gutter, vertical = 6.dp)) {
                    Text("Panjang bacaan AI", style = MaterialTheme.typography.bodyLarge)
                    Muted(
                        if (vm.lightAi) "Dikunci ke Hemat selama Mode ringan aktif."
                        else "Hemat (±2.400 huruf), Seimbang (±4.200), Panjang (±6.000). Makin panjang memakai RAM lebih besar.",
                        Modifier.padding(bottom = 6.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ContextProfile.entries.forEach { cp ->
                            val selected = activeProfile?.contextProfile == cp
                            Choice(cp.label, selected = selected, enabled = !busy && !vm.lightAi) { vm.setContextProfile(cp) }
                        }
                    }
                }
                SettingRow(
                    "Penyesuaian kecepatan",
                    if (benchRunning) "Sedang mengukur kecepatan CPU dan GPU di HP ini…"
                    else "Ukur kecepatan dan pemakaian RAM di HP ini untuk memilih setelan terbaik.",
                ) {
                    SmallButton(if (benchRunning) "Mengukur…" else "Ukur kecepatan", enabled = !busy, onClick = vm::runAiBenchmark)
                }
                val idleLabel = if (vm.lightAi) "30 detik" else "3 menit"
                SettingRow(
                    "Memori",
                    if (aiState is AiState.Ready) "Model sedang memakai RAM; dilepas sendiri setelah $idleLabel tak dipakai, atau begitu aplikasi ditinggalkan."
                    else "Model tidak memakai RAM sekarang. Dimuat saat dipakai, lalu dilepas otomatis saat tidak dipakai.",
                ) {
                    if (aiState is AiState.Ready) SmallButton("Lepas model", onClick = vm::unloadModel)
                    else SmallButton("Uji muat", enabled = !busy, onClick = vm::loadModel)
                }
                SettingRow("Isi judul & tag otomatis", "Setelah catatan ditutup, AI mengisi judul dan tag yang masih kosong di latar.") {
                    Switch(vm.autoOrganize, vm::updateAutoOrganize)
                }
            }

            // ---------- Pencarian makna ----------
            Section("Pencarian makna (opsional)")
            SettingRow(
                "Model pencarian makna",
                if (embInstalled) "Aktif. Pencarian menggabungkan kecocokan kata dan kemiripan makna di perangkat."
                else "Belum dipasang. Pencarian kata bawaan tetap instan tanpa model ini.",
            ) {
                SmallButton(if (embInstalled) "Ganti berkas" else "Pilih berkas") { pickEmbModel.launch(arrayOf("*/*")) }
            }
            if (embInstalled) TextAction(
                "Hapus model",
                Modifier.padding(start = 8.dp),
                color = MaterialTheme.colorScheme.error,
            ) { confirmDelete = ModelCatalog.EMBEDDING_IMPORT_ID }

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

            // ---------- Keandalan ----------
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
                    folder == null -> "Pilih folder; cadangan ZIP (termasuk lampiran) ditulis tiap ada perubahan, ${AutoBackup.KEEP} berkas terakhir disimpan."
                    vm.backupError != null -> "Gagal menulis ke $folder: ${vm.backupError}. Pilih ulang foldernya."
                    vm.backupAt > 0 -> "$folder  ·  terakhir ${whenLabel(vm.backupAt)}"
                    else -> folder
                },
                error = vm.backupError != null,
            ) {
                if (folder == null || vm.backupError != null) SmallButton("Pilih folder", primary = true) { pickFolder.launch(null) }
                else SmallButton("Matikan") { vm.setBackupFolder(null) }
            }
            vm.backupProgress?.let { bp ->
                Column(Modifier.padding(horizontal = Gutter, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted(bp.detail.ifBlank { bp.phase.label }, Modifier.weight(1f))
                        TextAction("Batalkan", onClick = vm::cancelBackupJob)
                    }
                    if (bp.total > 0) LinearProgressIndicator({ bp.fraction }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            SettingRow(
                "Ekspor dan impor lengkap",
                "Arsip ZIP membawa semua catatan, Sampah, pengingat, riwayat versi, serta lampiran gambar dan suara (bisa dilindungi kata sandi). Tetap mendukung berkas JSON lama.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Impor", enabled = vm.backupProgress == null) { importFrom.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) }
                    SmallButton("Ekspor", enabled = vm.backupProgress == null) { askExportPass = true }
                }
            }

            // ---------- Privasi ----------
            Section("Privasi")
            Muted(
                "Catatan, pengingat, dan pemrosesan AI tetap di perangkat. Internet hanya dipakai saat kamu menekan Unduh, " +
                    "untuk mengambil berkas model dari huggingface.co.",
                Modifier.padding(horizontal = Gutter),
            )
            Muted(
                "Catatanku ${vm.appVersion}",
                Modifier.padding(horizontal = Gutter).padding(top = 8.dp),
                MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (askExportPass) {
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askExportPass = false },
            title = { Text("Ekspor cadangan", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Muted("Kosongkan sandi untuk arsip ZIP biasa, atau isi sandi untuk mengunci arsip. Sandi tidak disimpan di HP; bila lupa, cadangan tidak bisa dibuka.")
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("Sandi enkripsi (opsional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = SmallShape,
                    )
                }
            },
            confirmButton = {
                TextAction("Ekspor berkas") {
                    askExportPass = false
                    val chars = pass.takeIf { it.isNotEmpty() }?.toCharArray()
                    pendingExportPass = chars
                    val defName = if (chars != null) "catatanku-cadangan.enc.zip" else "catatanku-cadangan.zip"
                    pass = ""
                    exportTo.launch(defName)
                }
            },
            dismissButton = { TextAction("Batal") { pass = ""; askExportPass = false } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    vm.pendingEncryptedRestoreUri?.let { uri ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pass = ""; vm.dismissEncryptedRestorePrompt() },
            title = { Text("Buka cadangan terenkripsi", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Muted("Arsip ini dilindungi kata sandi. Masukkan sandi yang dipakai saat ekspor.")
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("Kata sandi cadangan") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = SmallShape,
                    )
                }
            },
            confirmButton = {
                TextAction("Pulihkan cadangan", enabled = pass.isNotEmpty()) {
                    val chars = pass.toCharArray()
                    pass = ""
                    vm.dismissEncryptedRestorePrompt()
                    vm.importFrom(uri, chars)
                }
            },
            dismissButton = { TextAction("Batal") { pass = ""; vm.dismissEncryptedRestorePrompt() } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
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
        val isEmbedding = id == ModelCatalog.EMBEDDING_IMPORT_ID
        Confirm(
            if (isEmbedding) "Hapus model pencarian makna?" else "Hapus model ini?",
            "Catatan tidak terpengaruh. Model bisa dipasang lagi kapan saja.",
            "Hapus model",
            destructive = true,
            onDismiss = { confirmDelete = null },
        ) {
            if (isEmbedding) vm.deleteEmbeddingModel() else vm.deleteModel(id)
        }
    }
}

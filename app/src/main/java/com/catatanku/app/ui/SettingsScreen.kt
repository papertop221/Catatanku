package com.catatanku.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.catatanku.app.security.AppPinCrypto
import com.catatanku.app.security.DeviceVaultKey
import com.catatanku.app.security.LockTimeout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.style.TextOverflow
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
private fun Section(title: String, detail: String? = null) {
    Hairline(Modifier.padding(top = 6.dp))
    Row(Modifier.padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title)
        if (detail != null) Muted("  ·  $detail", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SettingRow(title: String, note: String? = null, error: Boolean = false, control: @Composable () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = Gutter, end = 12.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!note.isNullOrBlank()) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else muted)
        }
    }
    control()
}

@Composable
private fun ExpandRow(
    title: String, summary: String, open: Boolean, error: Boolean = false,
    onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(role = Role.Button, onClick = onToggle)
            .padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(if (open) "Tutup" else "Atur", style = MaterialTheme.typography.labelLarge, color = if (error) MaterialTheme.colorScheme.error else muted)
    }
    if (open) Column(Modifier.padding(bottom = 4.dp), content = content)
}

private fun openAppNotificationSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openAppDetailsSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ModelRow(
    name: String, badge: String?, meta: String, open: Boolean, onToggle: () -> Unit, progress: Download?, detail: String,
    canDelete: Boolean, onDelete: () -> Unit, action: @Composable () -> Unit,
) = Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Rincian model", onClick = onToggle).padding(start = Gutter, end = 12.dp, top = 5.dp, bottom = 5.dp)) {
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (badge != null) Badge(badge)
            }
            Muted(meta, style = MaterialTheme.typography.labelSmall)
        }
        action()
    }
    if (progress != null) {
        LinearProgressIndicator({ progress.percent / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp))
        Muted("${progress.percent}%  ·  ${progress.note}", Modifier.padding(top = 2.dp), MaterialTheme.typography.labelSmall)
    }
    if (open) {
        Muted(detail, Modifier.padding(top = 3.dp))
        if (canDelete) TextAction("Hapus model", color = MaterialTheme.colorScheme.error, onClick = onDelete)
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
    var askPinDialog by remember { mutableStateOf(false) }
    var askAutoBackupPass by remember { mutableStateOf(false) }
    var confirmDisableAutoBackupEnc by remember { mutableStateOf(false) }
    var confirmEnableAutoWipe by remember { mutableStateOf(false) }
    var confirmPanicWipe by remember { mutableStateOf(false) }

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
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var canNotify by remember { mutableStateOf(Reminders.canNotify(ctx)) }
    var canExact by remember { mutableStateOf(Reminders.canExact(ctx)) }
    var canMic by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var battery by remember { mutableStateOf(Oem.batteryUnrestricted(ctx)) }
    var robust by remember { mutableStateOf(Reminders.robust(ctx)) }
    var test by remember { mutableStateOf(Reminders.testState(ctx)) }
    var late by remember { mutableStateOf(Reminders.lastLate(ctx)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canNotify = Reminders.canNotify(ctx); canExact = Reminders.canExact(ctx)
        canMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        battery = Oem.batteryUnrestricted(ctx)
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

    fun toggleGroup(k: String) { openGroup = if (openGroup == k) null else k }

    Scaffold(snackbarHost = { Notices(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(AppIcons.Back, "Kembali ke daftar") { vm.go(Screen.List) }
                Text("Pengaturan", Modifier.padding(start = 2.dp), style = MaterialTheme.typography.titleLarge)
            }

            // ---------- Keamanan ----------
            SectionLabel("Keamanan", Modifier.padding(start = Gutter, top = 4.dp, bottom = 2.dp))
            SettingRow("Kunci aplikasi", "Kunci app, RAM, notifikasi & Brankas") {
                Switch(vm.lockEnabled, { vm.updateLock(it) })
            }
            if (vm.lockEnabled) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Gutter, end = 12.dp, top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlowRow(
                        Modifier.weight(1f).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LockTimeout.entries.forEach { t ->
                            Choice(t.label, selected = vm.lockTimeout == t) { vm.updateLockTimeout(t) }
                        }
                    }
                    SmallButton("Kunci") { vm.lockNow() }
                }
            }
            ExpandRow(
                "PIN, hapus 10× & darurat",
                "${if (vm.hasAppPin) "PIN aktif" else "Tanpa PIN"}  ·  Hapus 10× ${if (vm.wipeAfterFailures) "aktif" else "mati"}",
                openGroup == "sec",
                onToggle = { toggleGroup("sec") },
            ) {
                SettingRow(
                    "PIN / sandi khusus",
                    if (vm.hasAppPin) "Alternatif biometrik · 4–64 karakter" else "Opsional · 4–64 karakter",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (vm.hasAppPin) TextAction("Hapus", color = MaterialTheme.colorScheme.error) { vm.removeAppPin() }
                        SmallButton(if (vm.hasAppPin) "Ubah PIN" else "Pasang PIN") { askPinDialog = true }
                    }
                }
                SettingRow(
                    "Hapus otomatis gagal 10×",
                    "${AppPinCrypto.DEFAULT_WIPE_ATTEMPTS}× salah beruntun = hapus total",
                ) {
                    Switch(vm.wipeAfterFailures, { on -> if (on) confirmEnableAutoWipe = true else vm.updateWipeAfterFailures(false) })
                }
                SettingRow("Hapus darurat", "Musnahkan kunci Keystore & semua data") {
                    TextAction("Hapus semua", color = MaterialTheme.colorScheme.error) { confirmPanicWipe = true }
                }
            }

            // ---------- Privasi ----------
            Section("Privasi")
            SettingRow("Blokir tangkapan layar", "Anti-screenshot & buramkan Recents") {
                Switch(vm.screenShield, vm::updateScreenShield)
            }
            SettingRow("Keyboard penyamaran", "Cegah papan ketik merekam ketikan") {
                Switch(vm.incognitoKeyboard, vm::updateIncognitoKeyboard)
            }
            SettingRow("Hapus papan klip 30 dtk", "Tandai salinan sensitif & bersihkan") {
                Switch(vm.clipboardAutoClear, vm::updateClipboardAutoClear)
            }
            SettingRow("Mode Offline Mutlak", "Blokir seluruh unduhan model jaringan") {
                Switch(vm.strictOffline, vm::updateStrictOffline)
            }

            val hasKeystore = remember(vm.locked) { DeviceVaultKey.hasHardwareOrKeystoreBacking(ctx) }
            val modelAudit = if (!vm.ai.hasModel) "Belum terpasang"
            else "${vm.ai.activeName} (${if (vm.ai.isActiveModelInPrivateStorage()) "privat" else "eksternal"}" +
                (vm.ai.activeModelVerifiedShaPrefix()?.let { " · $it…" } ?: "") + ")"
            val permList = listOfNotNull(
                if (canMic) "Mikrofon" else null,
                if (canNotify) "Notifikasi" else null,
                if (canExact) "Alarm tepat" else null,
            ).ifEmpty { listOf("Nol izin sensitif") }.joinToString(", ")

            ExpandRow(
                "Audit privasi & izin HP",
                "AES-256-GCM · ${vm.vaultCount} Brankas · $permList",
                openGroup == "audit",
                onToggle = { toggleGroup("audit") },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Gutter, end = 12.dp, top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Muted(
                        "Penyimpanan: ${if (hasKeystore) "Android Keystore" else "kunci lokal"}\nModel AI: $modelAudit\nIzin aktif: $permList",
                        Modifier.weight(1f).padding(end = 8.dp),
                    )
                    SmallButton("Izin HP") { vm.suppressNextLeaveLock(); openAppDetailsSettings(ctx) }
                }
            }

            // ---------- Cadangan ----------
            Section("Cadangan")
            val folder = vm.backupLabel
            SettingRow(
                "Cadangan otomatis",
                when {
                    folder == null -> "Pilih folder · simpan ${AutoBackup.KEEP} arsip terbaru"
                    vm.backupError != null -> "Gagal ke $folder: ${vm.backupError}"
                    vm.backupAt > 0 -> "$folder  ·  ${whenLabel(vm.backupAt)}"
                    else -> folder
                },
                error = vm.backupError != null,
            ) {
                if (folder == null || vm.backupError != null) SmallButton("Pilih folder", primary = true) { vm.suppressNextLeaveLock(); pickFolder.launch(null) }
                else SmallButton("Matikan") { vm.setBackupFolder(null) }
            }
            SettingRow(
                "Enkripsi cadangan (.enc.zip)",
                when {
                    vm.autoBackupEncrypted -> "Aktif · dipulihkan memakai sandi"
                    vm.lockEnabled && folder != null -> "Wajib disetel saat Kunci aktif"
                    else -> "Lindungi arsip harian & isi Brankas"
                },
                error = vm.lockEnabled && folder != null && !vm.autoBackupEncrypted,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vm.autoBackupEncrypted) TextAction("Matikan", color = MaterialTheme.colorScheme.error) { confirmDisableAutoBackupEnc = true }
                    SmallButton(if (vm.autoBackupEncrypted) "Ubah sandi" else "Atur sandi", primary = vm.lockEnabled && folder != null && !vm.autoBackupEncrypted) {
                        askAutoBackupPass = true
                    }
                }
            }
            vm.backupProgress?.let { bp ->
                Column(Modifier.padding(horizontal = Gutter, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted(bp.detail.ifBlank { bp.phase.label }, Modifier.weight(1f))
                        TextAction("Batalkan", onClick = vm::cancelBackupJob)
                    }
                    if (bp.total > 0) LinearProgressIndicator({ bp.fraction }, Modifier.fillMaxWidth().padding(top = 2.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
                }
            }
            SettingRow("Ekspor & impor lengkap", "ZIP, .enc.zip (Brankas), atau JSON") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("Impor", enabled = vm.backupProgress == null) {
                        vm.suppressNextLeaveLock()
                        importFrom.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
                    }
                    SmallButton("Ekspor", enabled = vm.backupProgress == null) { askExportPass = true }
                }
            }

            // ---------- Model AI ----------
            Section("Model AI", if (ram > 0) "RAM ±$ram GB" else null)
            val statusNotice = when (val st = aiState) {
                AiState.NoModel -> if (vm.strictOffline) "Offline mutlak aktif · impor berkas .litertlm lokal" else null
                is AiState.Importing -> "Menyalin berkas… ${st.percent}%"
                AiState.Loading -> "Memuat ${vm.ai.activeName} ke RAM…"
                is AiState.Failed -> st.message
                else -> if (vm.strictOffline) "Offline mutlak aktif · unduhan jaringan dimatikan" else null
            }
            if (statusNotice != null) {
                Text(
                    statusNotice,
                    Modifier.padding(horizontal = Gutter, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aiState is AiState.Failed) MaterialTheme.colorScheme.error else muted,
                )
            }
            (aiState as? AiState.Importing)?.let { LinearProgressIndicator({ it.percent / 100f }, Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 2.dp)) }
            if (aiState is AiState.Loading || benchRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 2.dp))

            ModelCatalog.all.forEach { spec ->
                val installed = revision.let { vm.ai.isInstalled(spec.id) }
                if (spec.legacy && !installed) return@forEach
                val dl = download?.takeIf { it.id == spec.id }
                val heavy = ram in 1 until spec.minRamGb
                val isActive = installed && active == spec.id
                ModelRow(
                    spec.name, if (isActive) "Aktif" else spec.tier,
                    "${spec.sizeLabel}  ·  RAM ${spec.recRamGb}+ GB" + if (heavy) "  ·  berat" else "",
                    expanded == spec.id, { expanded = if (expanded == spec.id) null else spec.id }, dl,
                    spec.blurb + if (spec == recommended && ram > 0) " Disarankan untuk HP ini." else "",
                    installed && !busy, { confirmDelete = spec.id },
                ) {
                    when {
                        dl != null -> SmallButton("Batal") { vm.ai.cancelDownload() }
                        isActive -> {}
                        installed -> SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(spec.id) }
                        else -> SmallButton("Unduh", primary = spec == recommended && ram > 0, enabled = download == null && !vm.strictOffline) { confirmDownload = spec }
                    }
                }
            }
            val importInstalled = revision.let { vm.ai.isInstalled(ModelCatalog.IMPORT_ID) }
            val importActive = active == ModelCatalog.IMPORT_ID
            ModelRow(
                "Dari berkas", if (importActive) "Aktif" else null, "Impor .litertlm lokal",
                expanded == ModelCatalog.IMPORT_ID, { expanded = if (expanded == ModelCatalog.IMPORT_ID) null else ModelCatalog.IMPORT_ID }, null,
                "Sudah punya berkas model di HP? Impor langsung tanpa internet.", importInstalled && !busy, { confirmDelete = ModelCatalog.IMPORT_ID },
            ) {
                if (importInstalled && !importActive) SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(ModelCatalog.IMPORT_ID) }
                else SmallButton(if (importInstalled) "Ganti" else "Pilih", enabled = !busy) { vm.suppressNextLeaveLock(); pickModel.launch(arrayOf("*/*")) }
            }
            if (download != null) Muted("Unduhan berjalan di latar.", Modifier.padding(horizontal = Gutter, vertical = 2.dp))

            val hasAiModel = aiState !is AiState.NoModel && aiState !is AiState.Importing
            val aiPerfSummary = buildString {
                if (hasAiModel) {
                    append(activeProfile?.backend?.label ?: "CPU")
                    append(" · ")
                    append(activeProfile?.contextProfile?.label ?: "Hemat")
                    if (vm.lightAi) append(" (ringan)")
                } else {
                    append("Belum ada model AI")
                }
                append("  ·  Makna: ")
                append(if (embInstalled) "aktif" else "mati")
            }
            ExpandRow("Kinerja AI & pencarian makna", aiPerfSummary, openGroup == "ai", onToggle = { toggleGroup("ai") }) {
                if (hasAiModel) {
                    SettingRow("Mode ringan", "Batas Hemat & segera lepas dari RAM") {
                        Switch(vm.lightAi, vm::updateLightAi, enabled = !busy)
                    }
                    val gpuSelected = activeProfile?.backend == AiBackendKind.GPU
                    SettingRow("Akselerasi GPU", "Kembali ke CPU otomatis bila gagal") {
                        Switch(gpuSelected, { vm.setGpu(it) }, enabled = !busy)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = Gutter, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Panjang bacaan", style = MaterialTheme.typography.bodyLarge)
                            Muted(if (vm.lightAi) "Terkunci ke Hemat" else "2.4k / 4.2k / 6k huruf")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ContextProfile.entries.forEach { cp ->
                                Choice(cp.label, selected = activeProfile?.contextProfile == cp, enabled = !busy && !vm.lightAi) {
                                    vm.setContextProfile(cp)
                                }
                            }
                        }
                    }
                    SettingRow(
                        "Penyesuaian kecepatan",
                        if (benchRunning) "Sedang mengukur CPU & GPU…"
                        else activeProfile?.let { "${it.backend.label} · ${it.contextProfile.label} (${it.source.label})" } ?: "Ukur kecepatan HP",
                    ) {
                        SmallButton(if (benchRunning) "Mengukur…" else "Ukur", enabled = !busy, onClick = vm::runAiBenchmark)
                    }
                    val idleLabel = if (vm.lightAi) "30 detik" else "3 menit"
                    SettingRow(
                        "Memori RAM",
                        if (aiState is AiState.Ready) "Aktif di RAM · dilepas otomatis $idleLabel"
                        else "Dilepas · dimuat hanya saat dipakai",
                    ) {
                        if (aiState is AiState.Ready) SmallButton("Lepas", onClick = vm::unloadModel)
                        else SmallButton("Uji muat", enabled = !busy, onClick = vm::loadModel)
                    }
                    SettingRow("Judul & tag otomatis", "Isi otomatis setelah catatan ditutup") {
                        Switch(vm.autoOrganize, vm::updateAutoOrganize)
                    }
                }
                SettingRow(
                    "Model pencarian makna",
                    if (embInstalled) "Aktif · makna + kecocokan kata" else "Opsional · pencarian kata tetap instan",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (embInstalled) TextAction("Hapus", color = MaterialTheme.colorScheme.error) { confirmDelete = ModelCatalog.EMBEDDING_IMPORT_ID }
                        SmallButton(if (embInstalled) "Ganti" else "Pilih") { vm.suppressNextLeaveLock(); pickEmbModel.launch(arrayOf("*/*")) }
                    }
                }
            }

            // ---------- Pengingat ----------
            Section("Pengingat")
            if (!canNotify) SettingRow("Notifikasi dimatikan", "Wajib agar pengingat tampil.", error = true) {
                SmallButton("Izinkan", primary = true) {
                    vm.suppressNextLeaveLock()
                    if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppNotificationSettings(ctx)
                }
            }
            if (!canExact) SettingRow("Alarm tepat dimatikan", "Pengingat bisa terlambat.", error = true) {
                SmallButton("Izinkan") {
                    if (Build.VERSION.SDK_INT >= 31) runCatching {
                        vm.suppressNextLeaveLock()
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                    }
                }
            }
            if (late.first > 0) SettingRow(
                "Terakhir terlambat ${late.first / 60_000} mnt",
                "${whenLabel(late.second)} · ditahan sistem HP.",
                error = true,
            ) { SmallButton("Tutup") { Reminders.clearLate(ctx); late = 0L to 0L } }
            if (!battery) SettingRow("Baterai masih dibatasi", "Kecualikan agar alarm tepat waktu.", error = true) {
                SmallButton("Kecualikan", primary = true) { vm.suppressNextLeaveLock(); Oem.requestBatteryUnrestricted(ctx) }
            }
            SettingRow("Jadwalkan sebagai alarm sistem", "Prioritas tinggi · tahan hemat baterai") {
                Switch(robust, { robust = it; Reminders.setRobust(ctx, it) })
            }
            val (due, lateMs) = test
            val testFailed = due != 0L && ((lateMs != null && lateMs >= 60_000L) || (lateMs == null && now >= due + 90_000L))
            val diagSummary = when {
                testFailed -> "Uji ${whenLabel(due)} gagal · periksa setelan ${Oem.brand}"
                due != 0L && lateMs != null -> "${vm.reminderCount} pengingat · uji terakhir tepat waktu"
                due != 0L -> "Menunggu uji ${whenLabel(due)}"
                else -> "${vm.reminderCount} pengingat  ·  ${if (battery) "Baterai bebas" else "Perlu perhatian"}  ·  Uji 2 mnt"
            }
            ExpandRow("Keandalan ${Oem.brand}, suara & uji", diagSummary, openGroup == "alarm", error = testFailed, onToggle = { toggleGroup("alarm") }) {
                if (canNotify && canExact) SettingRow("Suara & notifikasi", "${vm.reminderCount} catatan punya pengingat") {
                    SmallButton("Atur bunyi") { vm.suppressNextLeaveLock(); openAppNotificationSettings(ctx) }
                }
                if (Oem.aggressive) {
                    Column(Modifier.padding(start = Gutter, end = 12.dp, top = 4.dp, bottom = 6.dp)) {
                        Text("Langkah khusus ${Oem.brand}", style = MaterialTheme.typography.bodyMedium)
                        Oem.steps.forEachIndexed { i, s -> Muted("${i + 1}. $s", Modifier.padding(top = 2.dp)) }
                        Row(Modifier.padding(top = 6.dp)) {
                            SmallButton("Setelan mulai otomatis") {
                                vm.suppressNextLeaveLock()
                                if (!Oem.openAutostart(ctx)) vm.say("Halaman khusus tidak ditemukan; dibuka info aplikasi.")
                            }
                        }
                    }
                } else {
                    Muted(
                        "Bila uji gagal di Oppo/Vivo/Realme/Xiaomi, aktifkan \"mulai otomatis\" dan bebaskan aktivitas latar belakang.",
                        Modifier.padding(horizontal = Gutter, vertical = 4.dp),
                    )
                }
                SettingRow(
                    "Uji pengingat (2 menit)",
                    when {
                        due == 0L -> "Kirim notifikasi uji dalam 2 menit lalu kunci layar."
                        lateMs != null && lateMs < 60_000L -> "Uji ${whenLabel(due)}: berbunyi tepat waktu."
                        lateMs != null -> "Uji ${whenLabel(due)}: terlambat ${lateMs / 60_000} menit."
                        now < due + 90_000L -> "Menunggu ${whenLabel(due)}. Tutup app & kunci layar."
                        else -> "Uji ${whenLabel(due)} tidak berbunyi; periksa batasan baterai."
                    },
                    error = testFailed,
                ) {
                    SmallButton(if (due == 0L) "Mulai uji" else "Uji lagi", enabled = canNotify) {
                        Reminders.startTest(ctx); test = Reminders.testState(ctx); now = System.currentTimeMillis()
                    }
                }
            }

            // ---------- Tampilan ----------
            if (Build.VERSION.SDK_INT >= 31) {
                Section("Tampilan")
                SettingRow("Warna mengikuti wallpaper", "Mode gelap tetap hitam pekat") {
                    Switch(vm.dynamicColor, vm::updateDynamicColor)
                }
            }

            Muted(
                "Catatanku ${vm.appVersion}  ·  100% lokal di perangkat, nol pelacak.",
                Modifier.padding(horizontal = Gutter, vertical = 14.dp),
                MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (askExportPass) {
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askExportPass = false },
            title = { Text("Ekspor cadangan", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Muted("Kosongkan untuk ZIP biasa, atau isi sandi (.enc.zip). Sandi tidak disimpan.")
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
                    vm.suppressNextLeaveLock()
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

    if (askPinDialog) {
        var pin1 by remember { mutableStateOf("") }
        var pin2 by remember { mutableStateOf("") }
        val validFormat = pin1.length in AppPinCrypto.MIN_PIN_LEN..AppPinCrypto.MAX_PIN_LEN && pin1.any { !it.isWhitespace() }
        val matches = validFormat && pin1 == pin2
        AlertDialog(
            onDismissRequest = { pin1 = ""; pin2 = ""; askPinDialog = false },
            title = { Text(if (vm.hasAppPin) "Ubah PIN Catatanku" else "Pasang PIN Catatanku", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Muted("Minimal ${AppPinCrypto.MIN_PIN_LEN} karakter. Jika lupa, catatan terkunci tidak bisa dibuka.")
                    OutlinedTextField(
                        value = pin1,
                        onValueChange = { pin1 = it.take(AppPinCrypto.MAX_PIN_LEN) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("PIN atau sandi baru") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = SmallShape,
                    )
                    OutlinedTextField(
                        value = pin2,
                        onValueChange = { pin2 = it.take(AppPinCrypto.MAX_PIN_LEN) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("Ulangi PIN atau sandi") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = SmallShape,
                    )
                    if (pin2.isNotEmpty() && !matches) {
                        Text(
                            if (!validFormat) "Minimal ${AppPinCrypto.MIN_PIN_LEN} karakter non-spasi." else "Kedua isian belum sama.",
                            Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextAction("Simpan PIN", enabled = matches) {
                    val chars = pin1.toCharArray()
                    pin1 = ""; pin2 = ""
                    askPinDialog = false
                    vm.setAppPin(chars)
                }
            },
            dismissButton = { TextAction("Batal") { pin1 = ""; pin2 = ""; askPinDialog = false } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    if (askAutoBackupPass) {
        var pass1 by remember { mutableStateOf("") }
        var pass2 by remember { mutableStateOf("") }
        val valid = pass1.length >= 6 && pass1 == pass2
        AlertDialog(
            onDismissRequest = { pass1 = ""; pass2 = ""; askAutoBackupPass = false },
            title = { Text("Sandi cadangan otomatis", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Muted("Arsip harian dienkripsi memakai sandi ini. Tidak disimpan di HP; jangan sampai lupa.")
                    OutlinedTextField(
                        value = pass1,
                        onValueChange = { pass1 = it.take(128) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("Sandi pemulihan (min. 6 karakter)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = SmallShape,
                    )
                    OutlinedTextField(
                        value = pass2,
                        onValueChange = { pass2 = it.take(128) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("Ulangi sandi pemulihan") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = SmallShape,
                    )
                    if (pass2.isNotEmpty() && !valid) {
                        Text(
                            if (pass1.length < 6) "Gunakan minimal 6 karakter." else "Kedua isian sandi belum sama.",
                            Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextAction("Simpan sandi", enabled = valid) {
                    val chars = pass1.toCharArray()
                    pass1 = ""; pass2 = ""
                    askAutoBackupPass = false
                    vm.setAutoBackupPassphrase(chars)
                }
            },
            dismissButton = { TextAction("Batal") { pass1 = ""; pass2 = ""; askAutoBackupPass = false } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    if (confirmDisableAutoBackupEnc) Confirm(
        "Matikan enkripsi cadangan otomatis?",
        "Cadangan otomatis berikutnya akan ditulis sebagai ZIP biasa (tanpa isi Brankas), dan ditahan bila Kunci Aplikasi aktif.",
        "Matikan enkripsi",
        destructive = true,
        onDismiss = { confirmDisableAutoBackupEnc = false },
    ) { vm.clearAutoBackupEncryption() }

    if (confirmEnableAutoWipe) Confirm(
        "Aktifkan hapus otomatis?",
        "Bila PIN salah dimasukkan ${AppPinCrypto.DEFAULT_WIPE_ATTEMPTS} kali berturut-turut, seluruh catatan, lampiran, dan kunci enkripsi dihapus permanen tanpa bisa dipulihkan.",
        "Aktifkan hapus otomatis",
        destructive = true,
        onDismiss = { confirmEnableAutoWipe = false },
    ) { vm.updateWipeAfterFailures(true) }

    if (confirmPanicWipe) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typed = ""; confirmPanicWipe = false },
            title = { Text("Hapus semua data?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Muted("Menghancurkan kunci Keystore, seluruh catatan, lampiran, pengingat, dan PIN secara permanen.")
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.take(16) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("Ketik HAPUS untuk konfirmasi") },
                        singleLine = true,
                        shape = SmallShape,
                    )
                }
            },
            confirmButton = {
                TextAction(
                    "Hapus semua data",
                    enabled = typed.trim().equals("HAPUS", ignoreCase = true),
                    color = MaterialTheme.colorScheme.error,
                ) {
                    typed = ""
                    confirmPanicWipe = false
                    vm.panicWipe()
                }
            },
            dismissButton = { TextAction("Batal") { typed = ""; confirmPanicWipe = false } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }
}

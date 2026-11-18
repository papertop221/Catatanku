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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
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

/** Satu baris pengaturan: nama di kiri, nilai/kontrol di kanan. [note] hanya untuk keadaan, bukan penjelasan. */
@Composable
private fun SettingRow(
    title: String, note: String? = null, error: Boolean = false, value: String? = null,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null, control: @Composable () -> Unit = {},
) = Row(
    Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .heightIn(min = 52.dp).padding(start = Gutter, end = 14.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!note.isNullOrBlank()) {
            Text(note, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else muted)
        }
    }
    if (value != null) Text(value, Modifier.padding(end = 6.dp), style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1)
    control()
}

/** Baris yang membuka rincian di bawahnya. */
@Composable
private fun ExpandRow(
    title: String, open: Boolean, value: String? = null, error: Boolean = false,
    onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    SettingRow(title, value = value, error = error, onClick = onToggle, titleColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) {
        androidx.compose.material3.Icon(if (open) AppIcons.ChevronUp else AppIcons.ChevronDown, if (open) "Tutup rincian" else "Buka rincian", Modifier.padding(end = 6.dp).size(18.dp), muted)
    }
    androidx.compose.animation.AnimatedVisibility(
        open,
        enter = androidx.compose.animation.expandVertically(tween(220)) + fadeIn(tween(200)),
        exit = androidx.compose.animation.shrinkVertically(tween(180)) + fadeOut(tween(120)),
    ) { Column(Modifier.padding(bottom = 6.dp), content = content) }
}

private data class Tile(val title: String, val status: String, val alert: Boolean = false)

/** Petak dashboard: datar, tanpa bayangan; status satu baris, merah bila perlu tindakan. */
@Composable
private fun DashTile(t: Tile, modifier: Modifier, onClick: () -> Unit) = Column(
    modifier.heightIn(min = 104.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .clickable(role = Role.Button, onClick = onClick).padding(16.dp),
) {
    Text(t.title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.weight(1f).heightIn(min = 18.dp))
    Text(
        t.status, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (t.alert) MaterialTheme.colorScheme.error else muted,
    )
}

@Composable
private fun AuditLine(label: String, value: String) = Row {
    Muted(label, Modifier.weight(0.4f))
    Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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
    name: String, active: Boolean, meta: String, open: Boolean, onToggle: () -> Unit, progress: Download?, detail: String,
    canDelete: Boolean, onDelete: () -> Unit, action: @Composable () -> Unit,
) = Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Rincian model", onClick = onToggle).padding(start = Gutter, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(meta, Modifier.padding(top = 2.dp), MaterialTheme.typography.labelSmall)
        }
        if (active) Text("Aktif", Modifier.padding(end = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        action()
    }
    if (progress != null) {
        LinearProgressIndicator({ progress.percent / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp))
        Muted("${progress.percent}%  ·  ${progress.note}", Modifier.padding(top = 4.dp), MaterialTheme.typography.labelSmall)
    }
    if (open) {
        Muted(detail, Modifier.padding(top = 6.dp))
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

    // Status ringkas untuk dashboard; dihitung dari keadaan yang sama dengan halaman rinciannya.
    val (due0, late0) = test
    val testFailed0 = due0 != 0L && ((late0 != null && late0 >= 60_000L) || (late0 == null && now >= due0 + 90_000L))
    val reminderIssues = listOf(!canNotify, !canExact, !battery, testFailed0).count { it }
    val privacyOn = listOf(vm.screenShield, vm.incognitoKeyboard, vm.clipboardAutoClear, vm.strictOffline).count { it }
    val backupAlert = vm.backupError != null || (vm.lockEnabled && vm.backupLabel != null && !vm.autoBackupEncrypted)
    val tiles = listOf(
        Tile("Keamanan", if (vm.lockEnabled) "Terkunci · ${vm.lockTimeout.label.lowercase()}" else "Tidak dikunci"),
        Tile("Privasi", "$privacyOn dari 4 aktif"),
        Tile(
            "Cadangan",
            when {
                vm.backupError != null -> "Gagal"
                backupAlert -> "Perlu enkripsi"
                vm.backupLabel == null -> "Manual"
                vm.backupAt > 0 -> "Otomatis · ${shortDate(vm.backupAt)}"
                else -> "Otomatis"
            },
            backupAlert,
        ),
        Tile(
            "Asisten",
            when (aiState) {
                AiState.NoModel -> "Belum aktif"
                is AiState.Failed -> "Galat"
                else -> vm.ai.activeName
            },
            aiState is AiState.Failed,
        ),
        Tile("Pengingat", if (reminderIssues > 0) "$reminderIssues perlu tindakan" else if (vm.reminderCount > 0) "${vm.reminderCount} terjadwal" else "Siap", reminderIssues > 0),
        Tile("Tampilan", vm.themeMode.label + if (vm.dynamicColor && Build.VERSION.SDK_INT >= 31) " · dinamis" else ""),
    )
    BackHandler(vm.settingsSection != null) { vm.settingsSection = null; openGroup = null }

    Scaffold(snackbarHost = { Notices(snack) }) { pad ->
        AnimatedContent(
            vm.settingsSection,
            Modifier.padding(pad).fillMaxSize(),
            transitionSpec = {
                val forward = targetState != null
                (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { (if (forward) it else -it) / 14 }).togetherWith(fadeOut(tween(120)))
            },
            label = "kategori",
        ) { sec ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(AppIcons.Back, if (sec == null) "Kembali ke daftar" else "Kembali ke Pengaturan") {
                        if (sec == null) vm.go(Screen.List) else { vm.settingsSection = null; openGroup = null }
                    }
                }
                Text(sec ?: "Pengaturan", Modifier.padding(horizontal = Gutter).padding(bottom = if (sec == null) 20.dp else 12.dp), style = MaterialTheme.typography.headlineSmall)
                if (sec == null) {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        tiles.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { t -> DashTile(t, Modifier.weight(1f)) { vm.settingsSection = t.title } }
                            }
                        }
                    }
                    Muted(
                        "Catatanku ${vm.appVersion}",
                        Modifier.padding(start = Gutter, end = Gutter, top = 32.dp, bottom = 28.dp),
                        MaterialTheme.typography.labelSmall,
                    )
                } else {
                    when (sec) {
                    "Keamanan" -> {
                SettingRow("Kunci aplikasi") { Switch(vm.lockEnabled, { vm.updateLock(it) }) }
                if (vm.lockEnabled) {
                    Column(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 2.dp, bottom = 8.dp)) {
                        Muted("Kunci otomatis", Modifier.padding(bottom = 8.dp), MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LockTimeout.entries.forEach { t -> Choice(t.label, selected = vm.lockTimeout == t) { vm.updateLockTimeout(t) } }
                        }
                    }
                }
                SettingRow("PIN aplikasi", value = if (vm.hasAppPin) "Aktif" else "Nonaktif", onClick = { askPinDialog = true })
                if (vm.hasAppPin) SettingRow("Hapus PIN", titleColor = muted, onClick = { vm.removeAppPin() })
                SettingRow("Hapus setelah ${AppPinCrypto.DEFAULT_WIPE_ATTEMPTS} kali gagal") {
                    Switch(vm.wipeAfterFailures, { on -> if (on) confirmEnableAutoWipe = true else vm.updateWipeAfterFailures(false) })
                }
                SettingRow("Hapus semua data", titleColor = MaterialTheme.colorScheme.error, onClick = { confirmPanicWipe = true })

                    }
                    "Privasi" -> {
                SettingRow("Lindungi layar") { Switch(vm.screenShield, vm::updateScreenShield) }
                SettingRow("Keyboard privat") { Switch(vm.incognitoKeyboard, vm::updateIncognitoKeyboard) }
                SettingRow("Bersihkan papan klip") { Switch(vm.clipboardAutoClear, vm::updateClipboardAutoClear) }
                SettingRow("Mode offline") { Switch(vm.strictOffline, vm::updateStrictOffline) }

                val hasKeystore = remember(vm.locked) { DeviceVaultKey.hasHardwareOrKeystoreBacking(ctx) }
                val modelAudit = if (!vm.ai.hasModel) "Belum terpasang"
                else "${vm.ai.activeName} (${if (vm.ai.isActiveModelInPrivateStorage()) "privat" else "eksternal"}" +
                    (vm.ai.activeModelVerifiedShaPrefix()?.let { " · $it…" } ?: "") + ")"
                val permList = listOfNotNull(
                    if (canMic) "Mikrofon" else null,
                    if (canNotify) "Notifikasi" else null,
                    if (canExact) "Alarm presisi" else null,
                ).ifEmpty { listOf("Tidak ada") }.joinToString(", ")
                ExpandRow("Audit privasi", openGroup == "audit", onToggle = { toggleGroup("audit") }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AuditLine("Enkripsi", "AES-256-GCM")
                        AuditLine("Kunci", if (hasKeystore) "Android Keystore" else "Kunci lokal")
                        AuditLine("Brankas", "${vm.vaultCount} catatan")
                        AuditLine("Model bahasa", modelAudit)
                        AuditLine("Izin aktif", permList)
                        TextAction("Buka izin aplikasi", Modifier.padding(top = 2.dp)) { vm.suppressNextLeaveLock(); openAppDetailsSettings(ctx) }
                    }
                }

                    }
                    "Cadangan" -> {
                val folder = vm.backupLabel
                SettingRow(
                    "Cadangan otomatis",
                    note = when {
                        vm.backupError != null -> "Gagal: ${vm.backupError}"
                        folder != null && vm.backupAt > 0 -> "Terakhir ${whenLabel(vm.backupAt)}"
                        else -> null
                    },
                    error = vm.backupError != null,
                    value = if (folder == null) "Nonaktif" else folder,
                    onClick = if (folder == null || vm.backupError != null) ({ vm.suppressNextLeaveLock(); pickFolder.launch(null) }) else null,
                ) {
                    if (folder != null && vm.backupError == null) TextAction("Matikan", color = muted) { vm.setBackupFolder(null) }
                }
                val encRequired = vm.lockEnabled && folder != null && !vm.autoBackupEncrypted
                SettingRow(
                    "Enkripsi cadangan",
                    note = if (encRequired) "Wajib saat Kunci aplikasi aktif" else null,
                    error = encRequired,
                    value = if (vm.autoBackupEncrypted) "Aktif" else "Nonaktif",
                    onClick = { askAutoBackupPass = true },
                )
                if (vm.autoBackupEncrypted) SettingRow("Matikan enkripsi", titleColor = muted, onClick = { confirmDisableAutoBackupEnc = true })
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
                val idle = vm.backupProgress == null
                SettingRow("Ekspor data", titleColor = if (idle) MaterialTheme.colorScheme.onSurface else muted, onClick = if (idle) ({ askExportPass = true }) else null)
                SettingRow("Impor data", titleColor = if (idle) MaterialTheme.colorScheme.onSurface else muted, onClick = if (idle) ({
                    vm.suppressNextLeaveLock()
                    importFrom.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
                }) else null)

                    }
                    "Asisten" -> {
                val statusNotice = when (val st = aiState) {
                    AiState.NoModel -> if (vm.strictOffline) "Mode offline aktif: impor berkas model lokal." else null
                    is AiState.Importing -> "Menyalin berkas… ${st.percent}%"
                    AiState.Loading -> "Memuat ${vm.ai.activeName}…"
                    is AiState.Failed -> st.message
                    else -> null
                }
                if (statusNotice != null) {
                    Text(
                        statusNotice,
                        Modifier.padding(horizontal = Gutter, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (aiState is AiState.Failed) MaterialTheme.colorScheme.error else muted,
                    )
                }
                (aiState as? AiState.Importing)?.let { LinearProgressIndicator({ it.percent / 100f }, Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp)) }
                if (aiState is AiState.Loading || benchRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp))

                ModelCatalog.all.forEach { spec ->
                    val installed = revision.let { vm.ai.isInstalled(spec.id) }
                    if (spec.legacy && !installed) return@forEach
                    val dl = download?.takeIf { it.id == spec.id }
                    val heavy = ram in 1 until spec.minRamGb
                    val isActive = installed && active == spec.id
                    ModelRow(
                        spec.name, isActive,
                        listOfNotNull(spec.sizeLabel, "RAM ${spec.recRamGb} GB+", if (spec == recommended && ram > 0 && !installed) "Disarankan" else null, if (heavy) "Berat" else null).joinToString("  ·  "),
                        expanded == spec.id, { expanded = if (expanded == spec.id) null else spec.id }, dl,
                        spec.blurb,
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
                    "Berkas lokal", importActive, ".litertlm",
                    expanded == ModelCatalog.IMPORT_ID, { expanded = if (expanded == ModelCatalog.IMPORT_ID) null else ModelCatalog.IMPORT_ID }, null,
                    "Impor berkas model dari penyimpanan HP, tanpa internet.", importInstalled && !busy, { confirmDelete = ModelCatalog.IMPORT_ID },
                ) {
                    if (importInstalled && !importActive) SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(ModelCatalog.IMPORT_ID) }
                    else SmallButton(if (importInstalled) "Ganti" else "Impor", enabled = !busy) { vm.suppressNextLeaveLock(); pickModel.launch(arrayOf("*/*")) }
                }

                val hasAiModel = aiState !is AiState.NoModel && aiState !is AiState.Importing
                ExpandRow("Lanjutan", openGroup == "ai", value = if (hasAiModel) (activeProfile?.backend?.label ?: "CPU") else null, onToggle = { toggleGroup("ai") }) {
                    if (hasAiModel) {
                        SettingRow("Mode hemat memori") { Switch(vm.lightAi, vm::updateLightAi, enabled = !busy) }
                        val gpuSelected = activeProfile?.backend == AiBackendKind.GPU
                        SettingRow("Akselerasi GPU") { Switch(gpuSelected, { vm.setGpu(it) }, enabled = !busy) }
                        Column(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 10.dp)) {
                            Text("Kapasitas konteks", style = MaterialTheme.typography.bodyLarge)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ContextProfile.entries.forEach { cp ->
                                    Choice(cp.label, selected = activeProfile?.contextProfile == cp, enabled = !busy && !vm.lightAi) { vm.setContextProfile(cp) }
                                }
                            }
                        }
                        SettingRow(
                            "Kalibrasi kinerja",
                            value = if (benchRunning) "Mengukur…" else activeProfile?.let { "${it.backend.label} · ${it.contextProfile.label}" },
                            onClick = if (!busy) ({ vm.runAiBenchmark() }) else null,
                        )
                        SettingRow(
                            "Memori",
                            value = if (aiState is AiState.Ready) "Dimuat" else "Dilepas",
                        ) {
                            if (aiState is AiState.Ready) TextAction("Lepas", onClick = vm::unloadModel)
                            else TextAction("Muat", enabled = !busy, onClick = vm::loadModel)
                        }
                        SettingRow("Metadata otomatis") { Switch(vm.autoOrganize, vm::updateAutoOrganize) }
                    }
                }

                    }
                    "Pengingat" -> {
                if (!canNotify) SettingRow("Izin notifikasi", note = "Nonaktif, pengingat tidak tampil", error = true) {
                    SmallButton("Izinkan", primary = true) {
                        vm.suppressNextLeaveLock()
                        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppNotificationSettings(ctx)
                    }
                }
                if (!canExact) SettingRow("Alarm presisi", note = "Nonaktif, pengingat bisa terlambat", error = true) {
                    SmallButton("Izinkan") {
                        if (Build.VERSION.SDK_INT >= 31) runCatching {
                            vm.suppressNextLeaveLock()
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                        }
                    }
                }
                if (!battery) SettingRow("Optimasi baterai", note = "Masih membatasi pengingat", error = true) {
                    SmallButton("Kecualikan", primary = true) { vm.suppressNextLeaveLock(); Oem.requestBatteryUnrestricted(ctx) }
                }
                if (late.first > 0) SettingRow(
                    "Terlambat ${late.first / 60_000} menit",
                    note = "${whenLabel(late.second)} · ditahan sistem",
                    error = true,
                ) { TextAction("Tutup", color = muted) { Reminders.clearLate(ctx); late = 0L to 0L } }
                SettingRow("Prioritas alarm") { Switch(robust, { robust = it; Reminders.setRobust(ctx, it) }) }
                if (canNotify && canExact) SettingRow("Suara notifikasi", onClick = { vm.suppressNextLeaveLock(); openAppNotificationSettings(ctx) })
                val (due, lateMs) = test
                val testFailed = due != 0L && ((lateMs != null && lateMs >= 60_000L) || (lateMs == null && now >= due + 90_000L))
                val diagValue = when {
                    testFailed -> "Gagal"
                    due != 0L && lateMs != null -> "Tepat waktu"
                    due != 0L -> "Menunggu"
                    else -> null
                }
                ExpandRow("Diagnostik", openGroup == "alarm", value = diagValue, error = testFailed, onToggle = { toggleGroup("alarm") }) {
                    SettingRow(
                        "Uji pengingat",
                        note = when {
                            due == 0L -> "Berbunyi 2 menit lagi; tutup aplikasi dan kunci layar."
                            lateMs != null && lateMs < 60_000L -> "${whenLabel(due)}: tepat waktu."
                            lateMs != null -> "${whenLabel(due)}: terlambat ${lateMs / 60_000} menit."
                            now < due + 90_000L -> "Menunggu ${whenLabel(due)}."
                            else -> "${whenLabel(due)}: tidak berbunyi."
                        },
                        error = testFailed,
                    ) {
                        SmallButton(if (due == 0L) "Mulai" else "Ulangi", enabled = canNotify) {
                            Reminders.startTest(ctx); test = Reminders.testState(ctx); now = System.currentTimeMillis()
                        }
                    }
                    if (Oem.aggressive) {
                        Column(Modifier.padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 6.dp)) {
                            Text("Pengaturan ${Oem.brand}", style = MaterialTheme.typography.bodyLarge)
                            Oem.steps.forEachIndexed { i, st -> Muted("${i + 1}.  $st", Modifier.padding(top = 4.dp)) }
                            TextAction("Buka mulai otomatis", Modifier.padding(top = 4.dp)) {
                                vm.suppressNextLeaveLock()
                                if (!Oem.openAutostart(ctx)) vm.say("Halaman khusus tidak ditemukan; dibuka info aplikasi.")
                            }
                        }
                    }
                }

                    }
                    "Tampilan" -> {
                Column(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 10.dp)) {
                    Text("Tema", style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { m -> Choice(m.label, selected = vm.themeMode == m) { vm.updateThemeMode(m) } }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) SettingRow("Warna dinamis") { Switch(vm.dynamicColor, vm::updateDynamicColor) }
                    }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    if (askExportPass) {
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askExportPass = false },
            title = { Text("Ekspor data", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Muted("Isi sandi untuk mengenkripsi arsip. Kosongkan untuk ZIP biasa.")
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        placeholder = { Text("Sandi (opsional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = SmallShape,
                    )
                }
            },
            confirmButton = {
                TextAction("Ekspor data") {
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
            title = { Text("Cadangan terenkripsi", style = MaterialTheme.typography.titleLarge) },
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
        Confirm(
            "Hapus model ini?",
            "Catatan tidak terpengaruh. Model bisa dipasang lagi kapan saja.",
            "Hapus model",
            destructive = true,
            onDismiss = { confirmDelete = null },
        ) { vm.deleteModel(id) }
    }

    if (askPinDialog) {
        var pin1 by remember { mutableStateOf("") }
        var pin2 by remember { mutableStateOf("") }
        val validFormat = pin1.length in AppPinCrypto.MIN_PIN_LEN..AppPinCrypto.MAX_PIN_LEN && pin1.any { !it.isWhitespace() }
        val matches = validFormat && pin1 == pin2
        AlertDialog(
            onDismissRequest = { pin1 = ""; pin2 = ""; askPinDialog = false },
            title = { Text(if (vm.hasAppPin) "Ubah PIN" else "Atur PIN", style = MaterialTheme.typography.titleLarge) },
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
            title = { Text("Enkripsi cadangan", style = MaterialTheme.typography.titleLarge) },
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
        "Matikan enkripsi cadangan?",
        "Cadangan otomatis berikutnya akan ditulis sebagai ZIP biasa (tanpa isi Brankas), dan ditahan bila Kunci Aplikasi aktif.",
        "Matikan enkripsi",
        destructive = true,
        onDismiss = { confirmDisableAutoBackupEnc = false },
    ) { vm.clearAutoBackupEncryption() }

    if (confirmEnableAutoWipe) Confirm(
        "Hapus setelah ${AppPinCrypto.DEFAULT_WIPE_ATTEMPTS} kali gagal?",
        "Bila PIN salah dimasukkan ${AppPinCrypto.DEFAULT_WIPE_ATTEMPTS} kali berturut-turut, seluruh catatan, lampiran, dan kunci enkripsi dihapus permanen tanpa bisa dipulihkan.",
        "Aktifkan",
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

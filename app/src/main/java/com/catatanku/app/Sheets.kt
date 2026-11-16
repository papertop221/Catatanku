package com.catatanku.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Date

// Lembar bawah dan dialog: kunci, pengingat, bantu tulis, rencana asisten, riwayat versi, folder.

// ============================== KUNCI ==============================

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.Lock, null, Modifier.size(32.dp), muted)
            Text("Catatanku terkunci", Modifier.padding(top = 14.dp, bottom = 18.dp), style = MaterialTheme.typography.titleMedium)
            PrimaryButton("Buka kunci", onClick = onUnlock)
        }
    }
}

// ============================== PENGINGAT ==============================

/** Lembar pengingat: pilihan cepat, tanggal & jam, pengulangan, dan gaya alarm. Dipakai dari daftar maupun editor. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReminderSheet(vm: AppViewModel) {
    val note = vm.reminderFor ?: return
    val ctx = LocalContext.current
    val now = remember(note.id) { LocalDateTime.now().withSecond(0).withNano(0) }
    val tomorrow = now.toLocalDate().plusDays(1)
    val presets = remember(note.id) {
        listOfNotNull(
            "1 jam lagi" to now.plusHours(1),
            if (now.hour < 18) "Malam ini 19.00" to now.toLocalDate().atTime(19, 0) else null,
            "Besok 07.00" to tomorrow.atTime(7, 0),
            "Besok 19.00" to tomorrow.atTime(19, 0),
            "Senin 07.00" to now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(7, 0),
        ).distinctBy { it.second }      // besok bisa saja hari Senin
    }
    val suggested = vm.reminderSeed
    var at by remember(note.id) {
        mutableStateOf((suggested ?: note.remindAt.takeIf { it > System.currentTimeMillis() })?.toLocal() ?: tomorrow.atTime(7, 0))
    }
    var repeat by remember(note.id) { mutableStateOf(note.remindRepeat) }
    var alarm by remember(note.id) { mutableStateOf(note.remindAlarm) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val valid = at.isAfter(LocalDateTime.now())
    fun save() = vm.saveReminder(at.toMillis(), repeat, alarm)
    // Android 13+: notifikasi butuh izin. Pengingat tetap disimpan apa pun jawabannya; pesan sesudahnya menjelaskan.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { save() }

    Sheet("Pengingat", vm::closeReminder, AppIcons.Bell, timeColor) {
        Muted(
            (if (suggested != null) "Waktu diambil dari isi catatan; periksa dulu.  " else "") + note.heading.take(60),
            Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { (label, t) -> Choice(label, selected = at == t) { at = t } }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val field = Modifier.heightIn(min = 48.dp).clip(SmallShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            Box(field.weight(1f).clickable(role = Role.Button, onClickLabel = "Ubah tanggal") { pickDate = true }.padding(horizontal = 14.dp), Alignment.CenterStart) {
                Text(SimpleDateFormat("EEEE, d MMM yyyy", ID).format(Date(at.toMillis())), style = MaterialTheme.typography.bodyLarge)
            }
            Box(field.clickable(role = Role.Button, onClickLabel = "Ubah jam") { pickTime = true }.padding(horizontal = 18.dp), Alignment.Center) {
                Text("%02d.%02d".format(at.hour, at.minute), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (!valid) Text("Waktu itu sudah lewat. Pilih waktu yang akan datang.", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        SectionLabel("Ulangi", Modifier.padding(top = 16.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Repeat.entries.forEach { r -> Choice(r.label, selected = repeat == r) { repeat = r } }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text("Bunyi seperti alarm", style = MaterialTheme.typography.bodyLarge)
                Muted("Berdering terus sampai ditanggapi, memakai volume alarm.")
            }
            Switch(alarm, { alarm = it })
        }
        PrimaryButton("Simpan pengingat", Modifier.fillMaxWidth().padding(top = 16.dp), enabled = valid) {
            if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else save()
        }
        // Cadangan untuk yang tidak boleh terlewat: kalender bawaan HP biasanya tidak dibatasi penghemat baterai.
        TextAction("Salin juga ke Kalender HP", Modifier.fillMaxWidth().padding(top = 4.dp), enabled = valid) {
            if (!addToCalendar(ctx, note.heading.ifBlank { "Pengingat catatan" }, note.body, at.toMillis(), repeat)) vm.say("Tidak ada aplikasi kalender di HP ini.")
        }
        if (note.hasReminder) TextAction("Hapus pengingat", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error) { vm.removeReminder() }
    }

    if (pickDate) {
        val today = remember { LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = at.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextAction("Pilih tanggal") {
                    state.selectedDateMillis?.let { ms -> at = LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC).toLocalDate().atTime(at.toLocalTime()) }
                    pickDate = false
                }
            },
            dismissButton = { TextAction("Batal") { pickDate = false } },
        ) { DatePicker(state, showModeToggle = false) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(at.hour, at.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { Box(Modifier.fillMaxWidth(), Alignment.Center) { TimePicker(state) } },
            confirmButton = { TextAction("Pilih jam") { at = at.toLocalDate().atTime(LocalTime.of(state.hour, state.minute)); pickTime = false } },
            dismissButton = { TextAction("Batal") { pickTime = false } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }
}

// ============================== ASISTEN PERINTAH ==============================

/** Uraian satu langkah rencana: (apa yang dikerjakan, rinciannya). */
private fun agentLabel(item: AgentItem): Pair<String, String?> {
    val s = item.step
    val name = if (s.target == Agent.PREV) "catatan yang sama" else item.target?.let { "“${it.heading.take(48)}”" } ?: "“${s.target.orEmpty()}”"
    fun time(at: LocalDateTime?, repeat: Repeat, alarm: Boolean) = at?.let {
        listOfNotNull(whenLabel(it.toMillis()), repeat.takeIf { r -> r != Repeat.None }?.label?.lowercase(), if (alarm) "gaya alarm" else null).joinToString("  ·  ")
    }
    return when (s) {
        is AgentStep.Create -> "Buat catatan “${s.title.ifBlank { s.body.lineSequence().first() }.take(48)}”" to listOfNotNull(
            time(s.at, s.repeat, s.alarm)?.let { "Pengingat $it" },
            s.body.lines().count { it.startsWith("☐") }.takeIf { it > 0 }?.let { "$it butir daftar centang" },
            s.folder.ifBlank { null }?.let { "Folder $it" },
            s.tags.joinToString(" ") { "#$it" }.ifBlank { null },
        ).joinToString("\n").ifBlank { null }
        is AgentStep.Remind -> "Setel pengingat $name" to time(s.at, s.repeat, s.alarm)
        is AgentStep.Unremind -> "Hapus pengingat $name" to null
        is AgentStep.Append -> (if (s.task) "Tambah tugas ke $name" else "Tambah teks ke $name") to (if (s.task) "☐ " else "") + s.text.replaceFirstChar { it.uppercase() }
        is AgentStep.Check -> "Centang butir di $name" to (item.target?.let { Agent.findTask(it.body, s.text) }?.replaceFirst("☐", "☑") ?: s.text)
        is AgentStep.Tag -> "Beri tag ke $name" to s.tags.joinToString(" ") { "#$it" }
        is AgentStep.Move -> "Pindahkan $name" to "Ke folder ${s.folder}"
        is AgentStep.Pin -> (if (s.on) "Sematkan $name" else "Lepas sematan $name") to null
        is AgentStep.Archive -> "Arsipkan $name" to null
        is AgentStep.Trash -> "Pindahkan $name ke Sampah" to "Bisa dipulihkan selama 30 hari."
    }
}

/** Lembar asisten: rencana ditampilkan dulu, dijalankan hanya setelah disetujui, dan hasilnya bisa diurungkan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentSheet(vm: AppViewModel) {
    val run = vm.agent ?: return
    val ctx = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.runAgent() }
    Sheet(if (run.results != null) "Selesai" else "Rencana", vm::closeAgent) {
        Muted("“${run.command.take(140)}”", Modifier.padding(top = 4.dp, bottom = 12.dp))
        when {
            run.planning -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Muted("Membaca perintah…", Modifier.padding(vertical = 12.dp), MaterialTheme.typography.bodyMedium)
                SecondaryButton("Batalkan", Modifier.fillMaxWidth(), onClick = vm::closeAgent)
            }
            run.error != null -> {
                Text(run.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Muted(
                    "Contoh yang dipahami:\n• ingatkan saya bayar listrik besok jam 7 malam\n• catat belanja: susu, telur, roti\n" +
                        "• tambahkan tugas beli oli ke catatan servis motor\n• centang beli oli di catatan servis motor\n" +
                        "• beri tag kerja ke catatan rapat, lalu sematkan\n• arsipkan catatan resep nasi goreng",
                    Modifier.padding(top = 10.dp, bottom = 12.dp), MaterialTheme.typography.bodyMedium,
                )
                SecondaryButton("Tutup", Modifier.fillMaxWidth(), onClick = vm::closeAgent)
            }
            run.results != null -> {
                run.results.forEach { r ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Icon(AppIcons.Check, null, Modifier.padding(top = 3.dp, end = 8.dp).size(16.dp), muted)
                        Text(r, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Tutup", Modifier.fillMaxWidth(), onClick = vm::closeAgent)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (vm.agentCanUndo) TextAction("Urungkan perintah", onClick = vm::undoAgent) else Spacer(Modifier.width(1.dp))
                    if (vm.stats.byId[run.touched]?.inTrash == false) TextAction("Buka catatan", onClick = vm::openAgentNote)
                }
            }
            else -> {
                run.items.forEachIndexed { i, item ->
                    val (label, detail) = agentLabel(item)
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Muted("${i + 1}", Modifier.width(24.dp).padding(top = 2.dp), MaterialTheme.typography.labelLarge)
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            if (item.problem != null) Text(item.problem, Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            else detail?.let { Muted(it, Modifier.padding(top = 2.dp)) }
                            // Beberapa catatan cocok: pengguna yang menentukan sasarannya, bukan tebakan.
                            if (item.options.size > 1) {
                                Muted("Bukan itu? Pilih catatannya:", Modifier.padding(top = 8.dp, bottom = 4.dp), MaterialTheme.typography.labelSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item.options.forEach { n -> Choice(n.heading.take(26).ifBlank { "(kosong)" }, selected = n.id == item.target?.id) { vm.agentPick(i, n) } }
                                }
                            }
                        }
                    }
                }
                PrimaryButton(if (run.items.size > 1) "Jalankan ${run.items.size} langkah" else "Jalankan rencana", Modifier.fillMaxWidth().padding(top = 14.dp), enabled = vm.agentReady) {
                    if (vm.agentSetsReminder && Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.runAgent()
                }
                TextAction("Batalkan", Modifier.fillMaxWidth().padding(top = 4.dp), onClick = vm::closeAgent)
            }
        }
    }
}

// ============================== BANTU TULIS ==============================

@Composable
private fun ActionRow(label: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(SmallShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else muted.copy(alpha = 0.5f))
        Muted(hint, Modifier.padding(start = 12.dp), MaterialTheme.typography.bodySmall)
    }
}

/** Satu lembar untuk seluruh alur bantu tulis: pilih aksi → lihat hasil → putuskan. Hasil tak pernah diterapkan otomatis. */
@Composable
internal fun AiSheet(vm: AppViewModel, hasModel: Boolean, loadingModel: Boolean, onCopy: (String) -> Unit) {
    val job = vm.aiJob
    var instr by rememberSaveable { mutableStateOf("") }
    Sheet(job?.action?.label ?: "Bantu tulis", vm::dismissAi) {
        Spacer(Modifier.height(8.dp))
        when {
            !hasModel -> {
                Muted("Bantu tulis memakai model AI yang berjalan di HP ini. Unduh sekali; sesudahnya bekerja tanpa internet.", style = MaterialTheme.typography.bodyMedium)
                PrimaryButton("Pilih model", Modifier.fillMaxWidth().padding(top = 16.dp)) { vm.dismissAi(); vm.saveDraft(); vm.go(Screen.Settings) }
            }
            job == null -> {
                val canRun = vm.draft.body.isNotBlank()
                if (!canRun) Muted("Tulis sesuatu dulu; bantu tulis bekerja dari isi catatan.", Modifier.padding(bottom = 8.dp), MaterialTheme.typography.bodyMedium)
                AiAction.entries.filter { it != AiAction.Custom }.forEach { a -> ActionRow(a.label, a.hint, canRun) { vm.runAi(a) } }
                ActionRow("Isi judul, folder & tag", "Hanya yang masih kosong", canRun) { vm.aiSheet = false; vm.organizeDraft() }
                ActionRow("Sarankan pengingat", "Dari jadwal atau tenggat di catatan", canRun && !vm.draft.hasReminder) { vm.suggestReminder() }
                Row(
                    Modifier.padding(top = 10.dp).fillMaxWidth().clip(SmallShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 14.dp, end = 4.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainField(instr, { instr = it }, "Atau tulis instruksi sendiri", Modifier.weight(1f), ime = ImeAction.Send) {
                        if (instr.isNotBlank() && canRun) vm.runAi(AiAction.Custom, instr)
                    }
                    TextAction("Kirim instruksi", enabled = instr.isNotBlank() && canRun) { vm.runAi(AiAction.Custom, instr) }
                }
            }
            else -> {
                if (job.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                job.progress?.let { Muted(it, Modifier.padding(top = 8.dp), MaterialTheme.typography.labelMedium) }
                Column(Modifier.heightIn(min = 80.dp, max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    if (job.running && job.text.isEmpty()) Muted(if (loadingModel) "Memuat model ke memori…" else "Menulis…", style = MaterialTheme.typography.bodyMedium)
                    job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (job.text.isNotEmpty()) Text(job.text, style = NoteType.body, color = MaterialTheme.colorScheme.onSurface)
                }
                if (job.running) SecondaryButton("Hentikan", Modifier.fillMaxWidth(), onClick = vm::stopAi)
                else if (job.text.isNotBlank()) {
                    // Satu aksi utama yang paling wajar untuk jenis hasilnya; sisanya sekunder.
                    val primaryReplaces = job.action.replaces
                    PrimaryButton(
                        if (job.action.forTitle) "Pakai sebagai judul" else if (primaryReplaces) "Ganti isi catatan" else "Tambahkan ke catatan", Modifier.fillMaxWidth(),
                    ) { vm.applyAi(replace = primaryReplaces) }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (!job.action.forTitle) TextAction(if (primaryReplaces) "Tambahkan saja" else "Ganti isi") { vm.applyAi(replace = !primaryReplaces) }
                        TextAction("Salin hasil") { onCopy(job.text) }
                        TextAction("Buang hasil", onClick = vm::dismissAi)
                    }
                } else SecondaryButton("Tutup", Modifier.fillMaxWidth(), onClick = vm::dismissAi)
            }
        }
    }
}

// ============================== RIWAYAT VERSI ==============================

/** Riwayat versi: salinan lama yang disimpan otomatis. Memulihkan tidak menghapus versi lain. */
@Composable
internal fun VersionsSheet(vm: AppViewModel, list: List<Version>) {
    var preview by remember { mutableStateOf<Version?>(null) }
    Sheet(preview?.let { "Versi ${whenLabel(it.saved)}" } ?: "Riwayat versi", vm::closeVersions) {
        val v = preview
        when {
            list.isEmpty() -> Muted(
                "Belum ada versi lama. Setiap kali catatan ini diubah, keadaan sebelumnya disimpan di sini (${NoteDb.KEEP_VERSIONS} versi terakhir).",
                Modifier.padding(vertical = 12.dp), MaterialTheme.typography.bodyMedium,
            )
            v == null -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                list.forEach { item ->
                    Column(Modifier.fillMaxWidth().clip(SmallShape).clickable(role = Role.Button) { preview = item }.heightIn(min = 48.dp).padding(vertical = 8.dp)) {
                        Text(whenLabel(item.saved) + "  ·  ${AiText.wordCount(item.body)} kata", style = MaterialTheme.typography.bodyMedium)
                        Muted(item.title.ifBlank { item.body.trim().lineSequence().firstOrNull().orEmpty() }.take(80))
                    }
                }
            }
            else -> {
                Column(Modifier.heightIn(min = 80.dp, max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    if (v.title.isNotBlank()) Text(v.title, Modifier.padding(bottom = 6.dp), style = NoteType.rowTitle, color = MaterialTheme.colorScheme.onSurface)
                    Text(v.body, style = NoteType.rowSnippet, color = MaterialTheme.colorScheme.onSurface)
                }
                PrimaryButton("Pulihkan versi ini", Modifier.fillMaxWidth()) { vm.restoreVersion(v) }
                TextAction("Kembali ke daftar versi", Modifier.fillMaxWidth().padding(top = 4.dp)) { preview = null }
            }
        }
    }
}

// ============================== FOLDER ==============================

@Composable
internal fun FolderDialog(current: String, folders: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var new by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                (listOf("") + folders).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(SmallShape).clickable(role = Role.Button) { onPick(f); onDismiss() }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (f.isEmpty()) "Tanpa folder" else f, Modifier.weight(1f), color = if (f.isEmpty()) muted else MaterialTheme.colorScheme.onSurface)
                        if (f == current) Icon(AppIcons.Check, "Dipilih", Modifier.size(18.dp))
                    }
                }
                OutlinedTextField(new, { new = it.take(30) }, Modifier.padding(top = 8.dp), placeholder = { Text("Nama folder baru") }, singleLine = true, shape = SmallShape)
            }
        },
        confirmButton = { TextAction("Buat folder", enabled = new.isNotBlank()) { onPick(new.trim()); onDismiss() } },
        dismissButton = { TextAction("Tutup", onClick = onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
}

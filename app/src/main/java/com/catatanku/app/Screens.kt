package com.catatanku.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================== Bagian bersama ==============================

private val ID: Locale = Locale.forLanguageTag("id-ID")
private val Gutter = 20.dp

/** Tanggal sependek mungkin: jam bila hari ini, "15 Nov" bila tahun ini, lengkap bila lebih lama. */
private fun shortDate(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val pattern = when {
        c[Calendar.YEAR] != now[Calendar.YEAR] -> "d MMM yyyy"
        c[Calendar.DAY_OF_YEAR] != now[Calendar.DAY_OF_YEAR] -> "d MMM"
        else -> "HH.mm"
    }
    return SimpleDateFormat(pattern, ID).format(Date(t))
}

private val muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val aiColor @Composable get() = MaterialTheme.colorScheme.tertiary

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall) =
    Text(text, modifier, color = muted, style = style)

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = muted, style = MaterialTheme.typography.labelMedium)

/** Tombol kecil berikon untuk aksi AI; amber selalu berarti AI. */
@Composable
private fun AiChip(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable(enabled = enabled && !busy, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        else Icon(AppIcons.Sparkle, null, Modifier.size(14.dp), MaterialTheme.colorScheme.onTertiaryContainer)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** Kolom teks tanpa bingkai dengan placeholder; dasar untuk kolom cari, judul, isi, dan tag. */
@Composable
private fun PlainField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge, singleLine: Boolean = true,
    ime: ImeAction = ImeAction.Default, onIme: () -> Unit = {},
) = BasicTextField(
    value, onChange, modifier,
    textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
    singleLine = singleLine,
    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ime),
    keyboardActions = KeyboardActions(onDone = { onIme() }, onSearch = { onIme() }, onSend = { onIme() }),
    decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = muted.copy(alpha = 0.7f)); inner() } },
)

@Composable
private fun Confirm(title: String, text: String, ok: String, onDismiss: () -> Unit, onOk: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) }, text = { Text(text) },
    confirmButton = { TextButton({ onDismiss(); onOk() }) { Text(ok) } },
    dismissButton = { TextButton(onDismiss) { Text("Batal") } },
)

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Catatan", text))
}

/** Chip pilihan netral (bukan AI): terisi bila dipilih. */
@Composable
private fun Choice(text: String, selected: Boolean = false, onClick: () -> Unit) = Text(
    text,
    Modifier.clip(CircleShape)
        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    style = MaterialTheme.typography.labelLarge,
    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
)

// ============================== KUNCI ==============================

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.Lock, null, Modifier.size(36.dp), muted)
            Text("Catatanku terkunci", Modifier.padding(top = 14.dp, bottom = 18.dp), style = MaterialTheme.typography.titleMedium)
            Button(onUnlock) { Text("Buka kunci") }
        }
    }
}

// ============================== PENGINGAT ==============================

/** Keterangan pengingat sebuah catatan: waktu, pengulangan, dan gaya alarm. */
private fun reminderText(n: Note): String = listOfNotNull(
    (if (n.remindFired) "Terlewat · " else "") + whenLabel(n.remindAt),
    n.remindRepeat.takeIf { it != Repeat.None }?.label?.lowercase(),
    if (n.remindAlarm) "alarm" else null,
).joinToString("  ·  ")

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

    ModalBottomSheet(
        onDismissRequest = vm::closeReminder,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Gutter).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Bell, null, Modifier.size(18.dp))
                Text("Pengingat", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
            Muted(
                (if (suggested != null) "Waktu diambil dari isi catatan; periksa dulu.  " else "") + note.heading.take(60),
                Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { (label, t) -> Choice(label, selected = at == t) { at = t } }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val field = Modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                Text(
                    SimpleDateFormat("EEEE, d MMM yyyy", ID).format(Date(at.toMillis())),
                    field.weight(1f).clickable { pickDate = true }.padding(horizontal = 14.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "%02d.%02d".format(at.hour, at.minute),
                    field.clickable { pickTime = true }.padding(horizontal = 18.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (!valid) Text("Waktu itu sudah lewat.", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            SectionLabel("Ulangi", Modifier.padding(top = 16.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Repeat.entries.forEach { r -> Choice(r.label, selected = repeat == r) { repeat = r } }
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bunyi seperti alarm", style = MaterialTheme.typography.bodyLarge)
                    Muted("Berdering terus sampai ditanggapi, memakai volume alarm.")
                }
                Switch(alarm, { alarm = it })
            }
            Button(
                {
                    if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else save()
                },
                Modifier.fillMaxWidth().padding(top = 16.dp), enabled = valid,
            ) { Text("Simpan pengingat") }
            if (note.hasReminder) TextButton({ vm.removeReminder() }, Modifier.fillMaxWidth()) { Text("Hapus pengingat") }
        }
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
                TextButton({
                    state.selectedDateMillis?.let { ms -> at = LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC).toLocalDate().atTime(at.toLocalTime()) }
                    pickDate = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton({ pickDate = false }) { Text("Batal") } },
        ) { DatePicker(state, showModeToggle = false) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(at.hour, at.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { Box(Modifier.fillMaxWidth(), Alignment.Center) { TimePicker(state) } },
            confirmButton = { TextButton({ at = at.toLocalDate().atTime(LocalTime.of(state.hour, state.minute)); pickTime = false }) { Text("Pilih") } },
            dismissButton = { TextButton({ pickTime = false }) { Text("Batal") } },
        )
    }
}

// ============================== DAFTAR ==============================

@Composable
fun ListScreen(vm: AppViewModel, snack: SnackbarHostState) {
    val aiState by vm.ai.state.collectAsStateWithLifecycle()
    val hasModel = aiState !is AiState.NoModel
    val shown = vm.visibleNotes()
    val view = vm.view
    val searching = vm.query.isNotBlank()
    var viewMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmOrganize by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            if (view == NoteView.All || view is NoteView.Folder) FloatingActionButton(
                { vm.open(null) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Default.Add, "Catatan baru") }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ---- Kepala: pemilih tampilan + menu ----
            Row(Modifier.padding(start = Gutter, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.clip(MaterialTheme.shapes.small).clickable { viewMenu = true }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (view) {
                                NoteView.All -> "Catatan"
                                is NoteView.Folder -> view.name
                                NoteView.Reminders -> "Pengingat"
                                NoteView.Archive -> "Arsip"
                                NoteView.Trash -> "Sampah"
                            },
                            style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Icon(AppIcons.Chevron, "Ganti tampilan", Modifier.padding(start = 2.dp).size(22.dp), muted)
                    }
                    DropdownMenu(viewMenu, { viewMenu = false }) {
                        fun pick(v: NoteView) { vm.view = v; vm.tagFilter = null; viewMenu = false }
                        DropdownMenuItem({ Text("Semua catatan") }, { pick(NoteView.All) })
                        vm.folders.forEach { f ->
                            DropdownMenuItem({ Text(f) }, { pick(NoteView.Folder(f)) },
                                leadingIcon = { Icon(AppIcons.Folder, null, Modifier.size(18.dp)) })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            { Text("Pengingat" + if (vm.reminderCount > 0) " (${vm.reminderCount})" else "") }, { pick(NoteView.Reminders) },
                            leadingIcon = { Icon(AppIcons.BellOutline, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenuItem({ Text("Arsip") }, { pick(NoteView.Archive) })
                        DropdownMenuItem({ Text("Sampah" + if (vm.trashCount > 0) " (${vm.trashCount})" else "") }, { pick(NoteView.Trash) })
                    }
                }
                Box {
                    IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(moreMenu, { moreMenu = false }) {
                        Sort.entries.forEach { s ->
                            DropdownMenuItem(
                                { Text(s.label, color = if (vm.sort == s) MaterialTheme.colorScheme.onSurface else muted) },
                                { vm.updateSort(s); moreMenu = false },
                                trailingIcon = { if (vm.sort == s) Text("✓") },
                            )
                        }
                        HorizontalDivider()
                        if (hasModel) DropdownMenuItem(
                            { Text("Rapikan dengan AI (${vm.organizableCount})") },
                            { moreMenu = false; confirmOrganize = true },
                            leadingIcon = { Icon(AppIcons.Sparkle, null, Modifier.size(18.dp), aiColor) },
                            enabled = vm.batch == null && vm.organizableCount > 0,
                        )
                        if (view == NoteView.Trash) DropdownMenuItem(
                            { Text("Kosongkan sampah") }, { moreMenu = false; confirmEmpty = true }, enabled = vm.trashCount > 0,
                        )
                        DropdownMenuItem({ Text("Rekap & tugas") }, { moreMenu = false; vm.openAsk() })
                        DropdownMenuItem({ Text("Pengaturan") }, { moreMenu = false; vm.go(Screen.Settings) })
                    }
                }
            }

            // ---- Satu kolom untuk mencari DAN bertanya ----
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(48.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp), muted)
                Spacer(Modifier.width(10.dp))
                PlainField(
                    vm.query, vm::updateQuery, if (hasModel) "Cari atau tanya catatanmu" else "Cari catatan",
                    Modifier.weight(1f), ime = ImeAction.Search,
                )
                if (vm.query.isNotEmpty()) IconButton({ vm.updateQuery("") }) { Icon(Icons.Default.Close, "Hapus pencarian", Modifier.size(18.dp), muted) }
            }
            if (searching && hasModel && view != NoteView.Trash) Row(
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiChip("Tanya AI") { vm.openAsk(vm.query) }
                if (vm.aiTerms.isEmpty()) AiChip("Perluas pencarian", busy = vm.aiBusy, onClick = vm::expandQueryWithAi)
            }
            if (vm.aiTerms.isNotEmpty()) Muted("Juga mencari: " + vm.aiTerms.joinToString(", "), Modifier.padding(horizontal = Gutter, vertical = 4.dp))

            vm.batch?.let { (done, total) ->
                Row(Modifier.padding(start = Gutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Muted("AI merapikan catatan $done/$total")
                        LinearProgressIndicator({ done / total.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp), color = aiColor)
                    }
                    TextButton(vm::stopBatch) { Text("Hentikan") }
                }
            }

            if (!hasModel && !vm.hideAiBanner && !searching) Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.tertiaryContainer).clickable { vm.go(Screen.Settings) }
                    .padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Sparkle, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    "Aktifkan AI lokal — pilih model", Modifier.weight(1f).padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                IconButton(vm::dismissAiBanner) { Icon(Icons.Default.Close, "Tutup", Modifier.size(16.dp), MaterialTheme.colorScheme.onTertiaryContainer) }
            }

            val tags = vm.allTags
            if (tags.isNotEmpty() && view != NoteView.Trash) LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(tags) { t ->
                    val on = vm.tagFilter == t
                    Text(
                        "#$t",
                        Modifier.clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { vm.tagFilter = if (on) null else t }.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else muted,
                    )
                }
            }
            if (view == NoteView.Trash) Muted("Dihapus permanen setelah 30 hari.", Modifier.padding(horizontal = Gutter, vertical = 6.dp))

            if (shown.isEmpty()) Box(Modifier.fillMaxSize().padding(40.dp), Alignment.Center) {
                Muted(
                    when {
                        searching || vm.tagFilter != null -> "Tidak ada yang cocok."
                        view == NoteView.Trash -> "Sampah kosong."
                        view == NoteView.Archive -> "Arsip kosong."
                        view == NoteView.Reminders -> "Belum ada pengingat.\nBuka catatan, lalu tekan ikon lonceng."
                        else -> "Belum ada catatan.\nTekan + untuk mulai menulis."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Bagian "Disematkan" hanya muncul bila memang ada dan tidak sedang mencari.
                val split = !searching && shown.any { it.pinned } && shown.any { !it.pinned }
                if (split) item(key = "h-pin") { SectionLabel("Disematkan", Modifier.padding(start = 4.dp, top = 4.dp)) }
                items(shown.filter { !split || it.pinned }, key = { it.id }) { n -> NoteCard(vm, n, view) }
                if (split) {
                    item(key = "h-rest") { SectionLabel("Lainnya", Modifier.padding(start = 4.dp, top = 10.dp)) }
                    items(shown.filter { !it.pinned }, key = { it.id }) { n -> NoteCard(vm, n, view) }
                }
            }
        }
    }
    if (confirmEmpty) Confirm(
        "Kosongkan sampah?", "${vm.trashCount} catatan akan dihapus permanen.", "Hapus permanen",
        { confirmEmpty = false }) { vm.emptyTrash() }
    if (confirmOrganize) Confirm(
        "Rapikan ${vm.organizableCount} catatan?",
        "AI mengisi judul, tag, dan folder yang masih kosong. Isian yang sudah ada dan isi catatan tidak diubah. " +
            "Bisa dihentikan kapan saja; periksa hasilnya sesudahnya.",
        "Mulai", { confirmOrganize = false }) { vm.organizeAll() }
    ReminderSheet(vm)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(vm: AppViewModel, n: Note, view: NoteView) {
    var menu by remember { mutableStateOf(false) }
    // Catatan tanpa judul memakai baris pertamanya sebagai judul, bukan label "(tanpa judul)".
    val lines = n.body.trim().lines().filter { it.isNotBlank() }
    val title = n.title.ifBlank { lines.firstOrNull().orEmpty() }
    val snippet = (if (n.title.isBlank()) lines.drop(1) else lines).joinToString("\n")
    Box {
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)
                .combinedClickable(onClick = { vm.open(n) }, onLongClick = { menu = true }).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.pinned) Icon(AppIcons.Pin, "Disematkan", Modifier.padding(start = 8.dp).size(14.dp), muted)
            }
            if (snippet.isNotBlank()) Text(
                snippet, Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (n.hasReminder && !n.inTrash) {
                    // Pengingat yang sudah berbunyi tetapi belum ditandai selesai tampil merah agar tidak terlupa.
                    val tint = if (n.remindFired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Icon(AppIcons.Bell, "Pengingat", Modifier.size(12.dp), tint)
                    Text(
                        " " + whenLabel(n.remindAt) + if (n.remindRepeat != Repeat.None) " ↻" else "",
                        style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1,
                    )
                    Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = muted.copy(alpha = 0.85f))
                }
                Text(
                    listOfNotNull(
                        if (n.archived && !n.inTrash && view != NoteView.Archive) "Arsip" else null,
                        if (view !is NoteView.Folder && n.folder.isNotBlank()) n.folder else null,
                        n.tags.take(3).joinToString(" ") { "#$it" }.ifBlank { null },
                        shortDate(n.updated),
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Tekan lama: aksi cepat tanpa membuka catatan.
        DropdownMenu(menu, { menu = false }) {
            if (n.inTrash) DropdownMenuItem({ Text("Pulihkan") }, { menu = false; vm.restore(n) })
            else {
                if (!n.archived) DropdownMenuItem({ Text(if (n.pinned) "Lepas sematan" else "Sematkan") }, { menu = false; vm.togglePin(n) })
                DropdownMenuItem({ Text(if (n.hasReminder) "Ubah pengingat" else "Ingatkan saya") }, { menu = false; vm.openReminder(n) })
                DropdownMenuItem({ Text(if (n.archived) "Keluarkan dari arsip" else "Arsipkan") }, { menu = false; vm.toggleArchive(n) })
                DropdownMenuItem({ Text("Hapus") }, { menu = false; vm.trash(n) })
            }
        }
    }
}

// ============================== EDITOR ==============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: AppViewModel, snack: SnackbarHostState) {
    val d = vm.draft
    val ctx = LocalContext.current
    val aiState by vm.ai.state.collectAsStateWithLifecycle()
    val hasModel = aiState !is AiState.NoModel
    var menu by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var related by remember { mutableStateOf<List<Note>>(emptyList()) }
    var hint by remember { mutableStateOf<TimeParse.Found?>(null) }        // waktu yang dikenali di isi catatan
    var hintDismissed by remember(d.id) { mutableStateOf(false) }

    // Isi disimpan sebagai TextFieldValue agar posisi kursor diketahui (untuk tombol centang/poin).
    var stored by remember { mutableStateOf(TextFieldValue(d.body)) }
    val bodyField = if (stored.text == d.body) stored
    else TextFieldValue(d.body, TextRange(d.body.length)).also { stored = it }     // diubah dari luar (mis. AI)
    fun setBody(v: TextFieldValue) { stored = v; vm.edit { it.copy(body = v.text) } }
    fun prefix(checklist: Boolean) {
        val (t, c) = AiText.toggleLinePrefix(bodyField.text, bodyField.selection.start, checklist)
        setBody(TextFieldValue(t, TextRange(c)))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveDraft() }       // simpan otomatis
    LaunchedEffect(d.title, d.body, d.tags, vm.index) { delay(400); related = vm.relatedToDraft() }
    LaunchedEffect(d.title, d.body, d.remindAt) { delay(700); hint = vm.reminderHint() }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snack) },
        // Alat selalu menempel di atas papan ketik, jadi terjangkau tanpa menggulir.
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ prefix(true) }) { Icon(AppIcons.CheckBox, "Daftar centang", Modifier.size(22.dp)) }
                    IconButton({ prefix(false) }) { Icon(AppIcons.Bullets, "Poin", Modifier.size(22.dp)) }
                    IconButton({ folderDialog = true }) { Icon(AppIcons.Folder, "Folder", Modifier.size(22.dp)) }
                    Spacer(Modifier.weight(1f))
                    Muted("${AiText.wordCount(d.body)} kata", Modifier.padding(end = 12.dp), MaterialTheme.typography.labelSmall)
                    AiChip("AI", busy = vm.aiBusy) { vm.aiSheet = true }
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---- Bilah atas ----
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(vm::closeEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Simpan dan kembali") }
                Spacer(Modifier.weight(1f))
                if (!d.inTrash) IconButton({ vm.openReminder() }) {
                    Icon(if (d.hasReminder) AppIcons.Bell else AppIcons.BellOutline, if (d.hasReminder) "Ubah pengingat" else "Setel pengingat", Modifier.size(22.dp))
                }
                if (!d.inTrash) IconButton({ vm.edit { it.copy(pinned = !it.pinned) } }) {
                    Icon(if (d.pinned) AppIcons.Pin else AppIcons.PinOutline, if (d.pinned) "Lepas sematan" else "Sematkan", Modifier.size(22.dp))
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Menu catatan") }
                    DropdownMenu(menu, { menu = false }) {
                        val plain = (if (d.title.isNotBlank()) d.title + "\n\n" else "") + d.body
                        DropdownMenuItem({ Text("Bagikan") }, {
                            menu = false
                            ctx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, d.title).putExtra(Intent.EXTRA_TEXT, plain), null))
                        }, enabled = !d.isBlank)
                        DropdownMenuItem({ Text("Salin teks") }, { menu = false; copy(ctx, plain) }, enabled = !d.isBlank)
                        DropdownMenuItem({ Text("Riwayat versi") }, { menu = false; vm.openVersions() }, enabled = d.id != 0L)
                        HorizontalDivider()
                        if (d.inTrash) {
                            DropdownMenuItem({ Text("Pulihkan") }, { menu = false; vm.restoreDraft() })
                            DropdownMenuItem({ Text("Hapus permanen") }, { menu = false; confirmForever = true })
                        } else {
                            DropdownMenuItem({ Text(if (d.archived) "Keluarkan dari arsip" else "Arsipkan") },
                                { menu = false; vm.toggleArchive() }, enabled = !d.isBlank)
                            DropdownMenuItem({ Text("Hapus") }, { menu = false; vm.trashDraft() })
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = Gutter)) {
                if (d.inTrash) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ada di Sampah", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    TextButton(vm::restoreDraft) { Text("Pulihkan") }
                }
                PlainField(
                    d.title, { v -> vm.edit { it.copy(title = v) } }, "Judul", Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.headlineSmall, ime = ImeAction.Next,
                )
                // Satu baris meta: folder · waktu ubah
                Muted(
                    listOfNotNull(d.folder.ifBlank { null }, if (d.id != 0L) "Diubah ${shortDate(d.updated)}" else "Catatan baru")
                        .joinToString("  ·  "),
                    Modifier.padding(top = 4.dp, bottom = if (d.hasReminder || (hint != null && !hintDismissed)) 8.dp else 14.dp), MaterialTheme.typography.labelSmall,
                )
                // ---- Pengingat: yang sudah disetel, atau tawaran dari waktu yang disebut di catatan ----
                if (d.hasReminder && !d.inTrash) Row(
                    Modifier.padding(bottom = 14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { vm.openReminder() }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (d.remindFired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Icon(AppIcons.Bell, null, Modifier.size(14.dp), tint)
                    Text(reminderText(d), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = tint)
                } else hint?.takeIf { !hintDismissed }?.let { h ->
                    Row(
                        Modifier.padding(bottom = 14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { vm.openReminder(seed = h.at.toMillis()) }.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.BellOutline, null, Modifier.size(14.dp), muted)
                        Text("Ingatkan ${whenLabel(h.at.toMillis())}?", Modifier.padding(start = 6.dp, top = 7.dp, bottom = 7.dp), style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Default.Close, "Abaikan saran pengingat", Modifier.padding(start = 2.dp).clip(CircleShape).clickable { hintDismissed = true }.padding(8.dp).size(13.dp), muted)
                    }
                }
                BasicTextField(
                    bodyField, ::setBody, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        Box { if (bodyField.text.isEmpty()) Text("Mulai menulis…", style = MaterialTheme.typography.bodyLarge, color = muted.copy(alpha = 0.7f)); inner() }
                    },
                )
                if (vm.undoBody != null) TextButton(vm::undoAiReplace, contentPadding = PaddingValues(0.dp)) { Text("Urungkan perubahan AI") }
                if (d.body.length > AiText.maxInput && hasModel) Muted(
                    "Catatan panjang: Ringkas, Daftar tugas, dan Perbaiki ejaan memproses seluruh isi bagian demi bagian. " +
                        "Aksi AI lain hanya membaca ±${AiText.maxInput} huruf pertama.",
                    Modifier.padding(top = 8.dp),
                )

                // ---- Tag ----
                FlowRow(
                    Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    d.tags.forEach { t ->
                        Row(
                            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { vm.edit { it.copy(tags = it.tags - t) } }.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#$t", style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Default.Close, "Hapus tag $t", Modifier.padding(start = 4.dp).size(13.dp), muted)
                        }
                    }
                    PlainField(
                        tagInput, { tagInput = it.take(28) }, "+ tag", Modifier.widthIn(min = 64.dp, max = 160.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge, ime = ImeAction.Done,
                    ) { vm.addTag(tagInput); tagInput = "" }
                    if (!d.isBlank && vm.suggestedTags.isEmpty())
                        if (hasModel) AiChip("Sarankan tag", busy = vm.aiBusy, onClick = vm::suggestTags)
                        else TextButton(vm::suggestTags, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Sarankan tag") }
                }
                if (vm.suggestedTags.isNotEmpty()) FlowRow(
                    Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    vm.suggestedTags.forEach { t ->
                        Text(
                            "+ $t",
                            Modifier.clip(CircleShape)
                                .background(if (vm.suggestedByAi) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { vm.addTag(t) }.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (vm.suggestedByAi) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (aiState is AiState.Loading) Muted("Memuat model ke memori…", Modifier.padding(top = 8.dp))

                if (related.isNotEmpty()) {
                    SectionLabel("Terkait", Modifier.padding(top = 24.dp, bottom = 2.dp))
                    related.forEach { n -> SourceRow(n) { vm.openRelated(n) } }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (vm.aiSheet || vm.aiJob != null) AiSheet(vm, hasModel, aiState is AiState.Loading) { copy(ctx, it) }
    if (confirmForever) Confirm("Hapus permanen?", "Tindakan ini tidak bisa dibatalkan.", "Hapus",
        { confirmForever = false }) { vm.deleteDraftForever() }
    if (folderDialog) FolderDialog(d.folder, vm.folders, { folderDialog = false }) { f -> vm.edit { it.copy(folder = f) } }
    vm.versions?.let { VersionsSheet(vm, it) }
    ReminderSheet(vm)
}

/** Riwayat versi: salinan lama yang disimpan otomatis. Memulihkan tidak menghapus versi lain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionsSheet(vm: AppViewModel, list: List<Version>) {
    var preview by remember { mutableStateOf<Version?>(null) }
    ModalBottomSheet(
        onDismissRequest = vm::closeVersions,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Gutter).padding(bottom = 20.dp)) {
            Text(preview?.let { "Versi ${whenLabel(it.saved)}" } ?: "Riwayat versi", style = MaterialTheme.typography.titleMedium)
            val v = preview
            when {
                list.isEmpty() -> Muted(
                    "Belum ada versi lama. Setiap kali catatan ini diubah, keadaan sebelumnya disimpan di sini " +
                        "(${NoteDb.KEEP_VERSIONS} versi terakhir).",
                    Modifier.padding(vertical = 12.dp), MaterialTheme.typography.bodyMedium,
                )
                v == null -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    list.forEach { item ->
                        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { preview = item }.padding(vertical = 10.dp)) {
                            Text(whenLabel(item.saved) + "  ·  ${AiText.wordCount(item.body)} kata", style = MaterialTheme.typography.bodyMedium)
                            Muted(item.title.ifBlank { item.body.trim().lineSequence().firstOrNull().orEmpty() }.take(80))
                        }
                    }
                }
                else -> {
                    Column(Modifier.heightIn(min = 80.dp, max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                        if (v.title.isNotBlank()) Text(v.title, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
                        Text(v.body, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button({ vm.restoreVersion(v) }, Modifier.fillMaxWidth()) { Text("Pulihkan versi ini") }
                    TextButton({ preview = null }, Modifier.fillMaxWidth()) { Text("Kembali ke daftar") }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(n: Note, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 8.dp)) {
        val first = n.body.trim().lineSequence().firstOrNull().orEmpty()
        Text(n.title.ifBlank { first }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (n.title.isNotBlank() && first.isNotBlank()) Muted(first.take(120), style = MaterialTheme.typography.bodySmall.copy(lineHeight = MaterialTheme.typography.bodySmall.lineHeight))
    }
}

/** Satu lembar untuk seluruh alur AI: pilih aksi → lihat hasil → putuskan. Hasil tak pernah diterapkan otomatis. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AiSheet(vm: AppViewModel, hasModel: Boolean, loadingModel: Boolean, onCopy: (String) -> Unit) {
    val job = vm.aiJob
    var instr by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = vm::dismissAi,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = Gutter).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Sparkle, null, Modifier.size(18.dp), aiColor)
                Text(job?.action?.label ?: "Asisten AI", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            when {
                !hasModel -> {
                    Muted("Belum ada model AI. Unduh sekali, lalu semuanya berjalan tanpa internet.", style = MaterialTheme.typography.bodyMedium)
                    Button({ vm.dismissAi(); vm.saveDraft(); vm.go(Screen.Settings) }, Modifier.padding(top = 16.dp)) { Text("Pilih model") }
                }
                job == null -> {
                    val canRun = vm.draft.body.isNotBlank()
                    if (!canRun) Muted("Tulis sesuatu dulu; AI bekerja dari isi catatan.", Modifier.padding(bottom = 8.dp), MaterialTheme.typography.bodyMedium)
                    AiAction.entries.filter { it != AiAction.Custom }.forEach { a -> ActionRow(a.label, a.hint, canRun) { vm.runAi(a) } }
                    ActionRow("Isi judul, folder & tag", "Hanya yang masih kosong", canRun) { vm.aiSheet = false; vm.organizeDraft() }
                    ActionRow("Sarankan pengingat", "Dari jadwal atau tenggat di catatan", canRun && !vm.draft.hasReminder) { vm.suggestReminder() }
                    Row(
                        Modifier.padding(top = 10.dp).fillMaxWidth().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlainField(instr, { instr = it }, "Atau tulis instruksi sendiri…", Modifier.weight(1f), ime = ImeAction.Send) {
                            if (instr.isNotBlank() && canRun) vm.runAi(AiAction.Custom, instr)
                        }
                        TextButton({ vm.runAi(AiAction.Custom, instr) }, enabled = instr.isNotBlank() && canRun) { Text("Kirim") }
                    }
                }
                else -> {
                    if (job.running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = aiColor)
                    job.progress?.let { Muted(it, Modifier.padding(top = 8.dp), MaterialTheme.typography.labelMedium) }
                    Column(Modifier.heightIn(min = 80.dp, max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                        if (job.running && job.text.isEmpty()) Muted(if (loadingModel) "Memuat model ke memori…" else "Menulis…", style = MaterialTheme.typography.bodyMedium)
                        job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (job.text.isNotEmpty()) Text(job.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (job.running) OutlinedButton(vm::stopAi, Modifier.fillMaxWidth()) { Text("Hentikan") }
                    else if (job.text.isNotBlank()) {
                        // Satu aksi utama yang paling wajar untuk jenis hasilnya; sisanya sekunder.
                        val primaryReplaces = job.action.replaces
                        Button({ vm.applyAi(replace = primaryReplaces) }, Modifier.fillMaxWidth()) {
                            Text(if (job.action.forTitle) "Pakai sebagai judul" else if (primaryReplaces) "Ganti isi catatan" else "Tambahkan ke catatan")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (!job.action.forTitle) TextButton({ vm.applyAi(replace = !primaryReplaces) }) { Text(if (primaryReplaces) "Tambahkan" else "Ganti isi") }
                            TextButton({ onCopy(job.text) }) { Text("Salin") }
                            TextButton(vm::dismissAi) { Text("Buang") }
                        }
                    } else TextButton(vm::dismissAi, Modifier.fillMaxWidth()) { Text("Tutup") }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = enabled, onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else muted.copy(alpha = 0.5f))
        Muted(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FolderDialog(current: String, folders: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var new by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                (listOf("") + folders).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPick(f); onDismiss() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (f.isEmpty()) "Tanpa folder" else f, Modifier.weight(1f), color = if (f.isEmpty()) muted else MaterialTheme.colorScheme.onSurface)
                        if (f == current) Text("✓")
                    }
                }
                OutlinedTextField(new, { new = it.take(30) }, Modifier.padding(top = 8.dp), placeholder = { Text("Folder baru") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onPick(new.trim()); onDismiss() }, enabled = new.isNotBlank()) { Text("Buat") } },
        dismissButton = { TextButton(onDismiss) { Text("Tutup") } },
    )
}

// ============================== TANYA CATATAN ==============================

@Composable
fun AskScreen(vm: AppViewModel, snack: SnackbarHostState) {
    val aiState by vm.ai.state.collectAsStateWithLifecycle()
    Scaffold(modifier = Modifier.imePadding(), snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(vm::closeAsk) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
                Icon(AppIcons.Sparkle, null, Modifier.padding(start = 4.dp).size(18.dp), aiColor)
                Text("Tanya catatan", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainField(
                    vm.askQuery, { vm.askQuery = it }, "mis. kapan servis motor berikutnya?",
                    Modifier.weight(1f).padding(vertical = 12.dp), singleLine = false, ime = ImeAction.Send,
                ) { vm.ask(vm.askQuery) }
                if (vm.askRunning) TextButton(vm::stopAi) { Text("Hentikan") }
                else TextButton({ vm.ask(vm.askQuery) }, enabled = vm.askQuery.isNotBlank()) { Text("Tanya") }
            }
            Column(Modifier.padding(horizontal = Gutter)) {
                if (vm.askRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = aiColor)
                    if (vm.askAnswer.isEmpty()) Muted(
                        if (aiState is AiState.Loading) "Memuat model ke memori…" else "Membaca catatan…",
                        Modifier.padding(top = 12.dp), MaterialTheme.typography.bodyMedium,
                    )
                }
                if (vm.askAnswer.isNotEmpty()) Text(vm.askAnswer, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyLarge)
                if (vm.askSources.isNotEmpty()) {
                    SectionLabel("Sumber", Modifier.padding(top = 24.dp, bottom = 2.dp))
                    vm.askSources.forEachIndexed { i, n ->
                        Row {
                            Muted("${i + 1}", Modifier.width(22.dp).padding(top = 9.dp), MaterialTheme.typography.labelMedium)
                            SourceRow(n) { vm.open(n) }
                        }
                    }
                    Muted("AI bisa keliru. Untuk hal penting, buka catatan sumbernya.", Modifier.padding(top = 12.dp))
                } else if (vm.askAnswer.isEmpty() && !vm.askRunning) {
                    Muted(
                        "AI menjawab hanya dari catatanmu dan menunjukkan catatan mana yang dipakai.",
                        Modifier.padding(top = 12.dp), MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiChip("Rekap 7 hari", onClick = vm::askRecap)
                        Choice("Tugas belum selesai", onClick = vm::askOpenTasks)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ============================== PENGATURAN ==============================

@Composable
private fun Group(content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer),
) { content() }

@Composable
private fun Badge(text: String, ai: Boolean = false) = Text(
    text,
    Modifier.clip(CircleShape).background(if (ai) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
        .padding(horizontal = 8.dp, vertical = 3.dp),
    style = MaterialTheme.typography.labelSmall,
    color = if (ai) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun SmallButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
    if (primary) Button(onClick, Modifier.height(36.dp), enabled = enabled, contentPadding = pad) { Text(text) }
    else OutlinedButton(onClick, Modifier.height(36.dp), enabled = enabled, contentPadding = pad) { Text(text) }
}

/** Satu baris pengaturan: judul, keterangan kecil, dan satu kontrol di kanan. */
@Composable
private fun SettingRow(title: String, note: String, error: Boolean = false, control: @Composable () -> Unit) = Row(
    Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canNotify = Reminders.canNotify(ctx); canExact = Reminders.canExact(ctx) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = Reminders.canNotify(ctx)
        if (!granted) openAppNotificationSettings(ctx)      // sudah pernah ditolak: sistem tidak menampilkan dialog lagi
    }
    val ram = vm.ai.deviceRamGb
    val recommended = ModelCatalog.recommended(ram)
    val busy = aiState is AiState.Importing || aiState is AiState.Loading
    val active = revision.let { vm.ai.activeId }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.go(Screen.List) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
                Text("Pengaturan", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ---------- Model AI: satu baris per model; ketuk untuk rincian ----------
                SectionLabel("Model AI", Modifier.padding(start = 4.dp, top = 8.dp))
                val status = when (val st = aiState) {
                    AiState.NoModel -> "Belum ada model. Unduh sekali; sesudahnya AI berjalan tanpa internet."
                    is AiState.Importing -> "Menyalin berkas… ${st.percent}%"
                    AiState.Idle -> "${vm.ai.activeName} aktif."
                    AiState.Loading -> "Memuat ${vm.ai.activeName} ke memori…"
                    AiState.Ready -> "${vm.ai.activeName} aktif dan sedang di memori."
                    is AiState.Failed -> st.message
                }
                Text(
                    status + if (ram > 0) "  RAM HP ini ±$ram GB." else "",
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (aiState is AiState.Failed) MaterialTheme.colorScheme.error else muted,
                )
                (aiState as? AiState.Importing)?.let { LinearProgressIndicator({ it.percent / 100f }, Modifier.fillMaxWidth(), color = aiColor) }
                if (aiState is AiState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = aiColor)

                Group {
                    ModelCatalog.all.forEach { spec ->
                        val installed = revision.let { vm.ai.isInstalled(spec.id) }
                        if (spec.legacy && !installed) return@forEach
                        val dl = download?.takeIf { it.id == spec.id }
                        val heavy = ram in 1 until spec.minRamGb
                        val open = expanded == spec.id
                        Column(Modifier.fillMaxWidth().clickable { expanded = if (open) null else spec.id }.padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(spec.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                        Badge(spec.tier, ai = spec == recommended && ram > 0 && !installed)
                                    }
                                    Muted(
                                        "${spec.sizeLabel}  ·  RAM ${spec.recRamGb} GB+" + if (heavy) "  ·  terlalu berat" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                when {
                                    dl != null -> SmallButton("Batal") { vm.ai.cancelDownload() }
                                    installed && active == spec.id -> Badge("Aktif", ai = true)
                                    installed -> SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(spec.id) }
                                    else -> SmallButton("Unduh", primary = spec == recommended && ram > 0, enabled = download == null) { confirmDownload = spec }
                                }
                            }
                            if (dl != null) {
                                LinearProgressIndicator({ dl.percent / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp), color = aiColor)
                                Muted("${dl.percent}%  ·  ${dl.note}", Modifier.padding(top = 4.dp), MaterialTheme.typography.labelSmall)
                            }
                            if (open) {
                                Muted(spec.blurb + if (spec == recommended && ram > 0) " Disarankan untuk HP ini." else "", Modifier.padding(top = 6.dp))
                                if (installed) Text(
                                    "Hapus model", Modifier.padding(top = 4.dp).clip(MaterialTheme.shapes.small).clickable(enabled = !busy) { confirmDelete = spec.id }.padding(vertical = 4.dp),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    // Model dari berkas .litertlm milik pengguna
                    val importInstalled = revision.let { vm.ai.isInstalled(ModelCatalog.IMPORT_ID) }
                    val importActive = active == ModelCatalog.IMPORT_ID
                    val importOpen = expanded == ModelCatalog.IMPORT_ID
                    Column(Modifier.fillMaxWidth().clickable { expanded = if (importOpen) null else ModelCatalog.IMPORT_ID }.padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Dari berkas", style = MaterialTheme.typography.bodyLarge)
                                    if (importActive) Badge("Aktif", ai = true)
                                }
                                Muted("Berkas .litertlm milikmu sendiri", style = MaterialTheme.typography.labelSmall)
                            }
                            if (importInstalled && !importActive) SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(ModelCatalog.IMPORT_ID) }
                            else SmallButton(if (importInstalled) "Ganti" else "Pilih", enabled = !busy) { pickModel.launch(arrayOf("*/*")) }
                        }
                        if (importOpen) {
                            Muted("Sudah punya berkas model di HP? Impor saja, tanpa mengunduh ulang.", Modifier.padding(top = 6.dp))
                            if (importInstalled) Text(
                                "Hapus model", Modifier.padding(top = 4.dp).clip(MaterialTheme.shapes.small).clickable(enabled = !busy) { confirmDelete = ModelCatalog.IMPORT_ID }.padding(vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (aiState !is AiState.NoModel && aiState !is AiState.Importing) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingRow("Akselerasi GPU", "Matikan bila model gagal dimuat.") { Switch(gpu, { gpu = it; vm.setGpu(it) }, enabled = !busy) }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingRow("Mode ringan", "Untuk RAM terbatas: memori lebih hemat dan model langsung dilepas, tetapi AI membaca teks lebih pendek.") {
                            Switch(vm.lightAi, vm::updateLightAi, enabled = !busy)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        val idle = if (vm.lightAi) "20 detik" else "3 menit"
                        SettingRow(
                            "Memori",
                            if (aiState is AiState.Ready) "Model memakai RAM; dilepas sendiri setelah $idle tak dipakai."
                            else "Model dimuat saat dipakai, lalu dilepas setelah $idle menganggur.",
                        ) {
                            if (aiState is AiState.Ready) SmallButton("Lepas", onClick = vm::unloadModel)
                            else SmallButton("Uji muat", enabled = !busy, onClick = vm::loadModel)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingRow("Rapikan otomatis", "Setelah catatan ditutup, AI mengisi judul dan tag yang kosong di latar.") {
                            Switch(vm.autoOrganize, vm::updateAutoOrganize)
                        }
                    }
                }
                if (download != null) Muted("Unduhan berjalan di latar; aplikasi boleh ditutup.", Modifier.padding(horizontal = 4.dp))

                // ---------- Pengingat ----------
                SectionLabel("Pengingat", Modifier.padding(start = 4.dp, top = 16.dp))
                Group {
                    if (!canNotify) SettingRow("Notifikasi dimatikan", "Tanpa izin ini pengingat tidak akan muncul.") {
                        SmallButton("Izinkan", primary = true) {
                            if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppNotificationSettings(ctx)
                        }
                    }
                    if (!canExact) {
                        if (!canNotify) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingRow("Alarm tepat waktu dimatikan", "Pengingat bisa terlambat beberapa menit.") {
                            SmallButton("Izinkan") {
                                if (Build.VERSION.SDK_INT >= 31) runCatching {
                                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                                }
                            }
                        }
                    }
                    if (canNotify && canExact) SettingRow(
                        "Aktif", "${vm.reminderCount} catatan punya pengingat. Pengingat tetap berbunyi saat aplikasi ditutup dan setelah HP dinyalakan ulang.",
                    ) { SmallButton("Bunyi") { openAppNotificationSettings(ctx) } }
                }
                Muted(
                    "Di sebagian HP (Xiaomi, Oppo, Vivo, Realme), penghemat baterai bisa menahan pengingat. " +
                        "Izinkan Catatanku berjalan di latar belakang bila pengingat sering terlambat.",
                    Modifier.padding(horizontal = 4.dp),
                )

                // ---------- Keamanan ----------
                SectionLabel("Keamanan", Modifier.padding(start = 4.dp, top = 16.dp))
                Group {
                    SettingRow("Kunci aplikasi", "Buka dengan sidik jari atau PIN layar HP. Isi notifikasi pengingat ikut disembunyikan.") {
                        Switch(vm.lockEnabled, { vm.updateLock(it) })
                    }
                }

                // ---------- Cadangan ----------
                SectionLabel("Cadangan", Modifier.padding(start = 4.dp, top = 16.dp))
                Group {
                    val folder = vm.backupLabel
                    SettingRow(
                        "Cadangan otomatis",
                        when {
                            folder == null -> "Pilih folder; cadangan ditulis tiap ada perubahan, ${AutoBackup.KEEP} berkas terakhir disimpan."
                            vm.backupError != null -> "Gagal menulis ke $folder: ${vm.backupError}"
                            vm.backupAt > 0 -> "$folder  ·  terakhir ${whenLabel(vm.backupAt)}"
                            else -> folder
                        },
                        error = vm.backupError != null,
                    ) {
                        if (folder == null) SmallButton("Pilih folder", primary = true) { pickFolder.launch(null) }
                        else SmallButton("Matikan") { vm.setBackupFolder(null) }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Muted("Berkas JSON berisi semua catatan, Sampah, dan pengingat. Tidak dienkripsi.", Modifier.weight(1f))
                        SmallButton("Impor") { importFrom.launch(arrayOf("application/json", "*/*")) }
                        SmallButton("Ekspor") { exportTo.launch("catatanku-cadangan.json") }
                    }
                }

                // ---------- Privasi ----------
                SectionLabel("Privasi", Modifier.padding(start = 4.dp, top = 16.dp))
                Muted(
                    "Catatan, pengingat, dan pemrosesan AI tetap di perangkat. Internet hanya dipakai saat kamu menekan Unduh, " +
                        "untuk mengambil berkas model dari huggingface.co.",
                    Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    confirmDownload?.let { spec ->
        val enough = vm.ai.freeSpaceFor(spec)
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Unduh ${spec.name}?") },
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
                    Button({ confirmDownload = null; vm.ai.startDownload(spec, wifiOnly = true) }, enabled = enough) { Text("Unduh lewat Wi-Fi") }
                    TextButton({ confirmDownload = null; vm.ai.startDownload(spec, wifiOnly = false) }, enabled = enough) { Text("Pakai data seluler juga") }
                    TextButton({ confirmDownload = null }) { Text("Batal") }
                }
            },
        )
    }
    confirmDelete?.let { id ->
        Confirm("Hapus model ini?", "Catatan tidak terpengaruh. Model bisa dipasang lagi kapan saja.", "Hapus",
            { confirmDelete = null }) { vm.deleteModel(id) }
    }
}

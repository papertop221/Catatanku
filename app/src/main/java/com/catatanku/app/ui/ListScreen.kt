package com.catatanku.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catatanku.app.ai.engine.AiState
import com.catatanku.app.domain.model.AgentStep
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.NoteView
import com.catatanku.app.domain.model.Repeat
import com.catatanku.app.domain.model.Screen
import com.catatanku.app.domain.model.Sort
import com.catatanku.app.reminders.whenLabel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val WORDS = Regex("\\s+")

@Composable
fun ListScreen(vm: AppViewModel, snack: SnackbarHostState) {
    val aiState by vm.ai.state.collectAsStateWithLifecycle()
    val hasModel = aiState !is AiState.NoModel
    val shown = vm.shown
    val view = vm.view
    val searching = vm.query.isNotBlank()
    var viewMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmOrganize by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<String?>(null) }
    var dissolveFolderTarget by remember { mutableStateOf<String?>(null) }
    var moveFolderNote by remember { mutableStateOf<Note?>(null) }
    var deleteForeverNote by remember { mutableStateOf<Note?>(null) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(vm.searchFocusTick) {
        if (vm.searchFocusTick > 0) runCatching { searchFocus.requestFocus() }
    }
    val steps = remember(vm.query) { vm.commandSteps(vm.query) }
    val isCommand = steps != null && view != NoteView.Vault
    val canCreate = view == NoteView.All || view is NoteView.Folder || view == NoteView.Vault

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { Notices(snack) },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
                Hairline()
                val tags = vm.allTags
                if (tags.isNotEmpty() && view != NoteView.Trash && view != NoteView.Vault && !searching) LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(tags, key = { it }) { t ->
                        val on = vm.tagFilter == t
                        Box(
                            Modifier.heightIn(min = 44.dp).clip(SmallShape).clickable(role = Role.Button) { vm.tagFilter = if (on) null else t }.padding(horizontal = 8.dp),
                            Alignment.Center,
                        ) {
                            Text(
                                "#$t",
                                Modifier.clip(SmallShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background).padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else muted,
                            )
                        }
                    }
                }
                val offerAgent = isCommand || (hasModel && view != NoteView.Vault && vm.query.trim().split(WORDS).size >= 3)
                if (searching && view != NoteView.Trash && view != NoteView.Vault && (hasModel || isCommand)) Row(Modifier.padding(horizontal = 8.dp)) {
                    if (offerAgent) TextAction("Jalankan perintah") { vm.startAgent(vm.query) }
                    if (hasModel) {
                        TextAction("Tanya catatan") { vm.ask(vm.query) }
                        if (vm.aiTerms.isEmpty()) TextAction(if (vm.aiBusy) "Memperluas…" else "Perluas pencarian", enabled = !vm.aiBusy, onClick = vm::expandQueryWithAi)
                    }
                }
                Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).height(48.dp).clip(SmallShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Search, null, Modifier.size(20.dp), muted)
                        Spacer(Modifier.width(10.dp))
                        PlainField(
                            vm.query, vm::updateQuery,
                            when {
                                view == NoteView.Vault -> "Cari di Brankas"
                                hasModel -> "Cari, tanya, atau beri perintah"
                                else -> "Cari atau beri perintah"
                            },
                            Modifier.weight(1f).focusRequester(searchFocus), ime = ImeAction.Search,
                        ) {
                            if (isCommand && steps.none { it is AgentStep.Trash || it is AgentStep.Archive }) vm.startAgent(vm.query)
                        }
                        if (vm.query.isNotEmpty()) IconAction(AppIcons.Close, "Hapus teks pencarian", muted, 18.dp) { vm.updateQuery("") }
                    }
                    if (canCreate) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(48.dp).clip(SmallShape).background(MaterialTheme.colorScheme.primary).clickable(role = Role.Button) { vm.open(null) },
                            Alignment.Center,
                        ) { Icon(AppIcons.Plus, "Catatan baru", Modifier.size(24.dp), MaterialTheme.colorScheme.onPrimary) }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(start = Gutter, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.clip(SmallShape).clickable(role = Role.Button) { viewMenu = true }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (view) {
                                NoteView.All -> "Catatan"
                                is NoteView.Folder -> view.name
                                NoteView.Reminders -> "Pengingat"
                                NoteView.Archive -> "Arsip"
                                NoteView.Trash -> "Sampah"
                                NoteView.Vault -> "Brankas"
                            },
                            style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Icon(AppIcons.ChevronDown, "Ganti tampilan", Modifier.padding(start = 4.dp).size(20.dp), muted)
                    }
                    Menu(viewMenu, { viewMenu = false }) {
                        fun pick(v: NoteView) { vm.switchView(v); viewMenu = false }
                        DropdownMenuItem({ Text("Semua catatan") }, { pick(NoteView.All) })
                        vm.folders.forEach { f ->
                            DropdownMenuItem({ Text(f) }, { pick(NoteView.Folder(f)) }, leadingIcon = { Icon(AppIcons.Folder, null, Modifier.size(18.dp)) })
                        }
                        Hairline()
                        DropdownMenuItem({ Text("Pengingat" + if (vm.reminderCount > 0) " (${vm.reminderCount})" else "") }, { pick(NoteView.Reminders) })
                        DropdownMenuItem({ Text("Arsip") }, { pick(NoteView.Archive) })
                        DropdownMenuItem({ Text("Sampah" + if (vm.trashCount > 0) " (${vm.trashCount})" else "") }, { pick(NoteView.Trash) })
                        Hairline()
                        DropdownMenuItem(
                            { Text("Brankas" + if (vm.vaultCount > 0) " (${vm.vaultCount})" else "") },
                            { viewMenu = false; vm.requestOpenVault() },
                            leadingIcon = { Icon(AppIcons.Lock, null, Modifier.size(18.dp)) },
                        )
                    }
                }
                if (view == NoteView.Vault) {
                    TextAction("Kunci Brankas") { vm.lockVault() }
                }
                Box {
                    IconAction(AppIcons.More, "Menu") { moreMenu = true }
                    Menu(moreMenu, { moreMenu = false }) {
                        Sort.entries.forEach { s ->
                            DropdownMenuItem(
                                { Text("Urutkan: ${s.label}") }, { vm.updateSort(s); moreMenu = false },
                                trailingIcon = { if (vm.sort == s) Icon(AppIcons.Check, "Dipilih", Modifier.size(18.dp)) },
                            )
                        }
                        Hairline()
                        DropdownMenuItem({ Text("Tugas belum selesai") }, { moreMenu = false; vm.askOpenTasks() })
                        DropdownMenuItem({ Text("Rekap 7 hari") }, { moreMenu = false; vm.askRecap() })
                        if (hasModel) DropdownMenuItem(
                            { Text("Isi judul & tag kosong (${vm.organizableCount})") }, { moreMenu = false; confirmOrganize = true },
                            enabled = vm.batch == null && vm.organizableCount > 0,
                        )
                        if (view is NoteView.Folder) {
                            Hairline()
                            DropdownMenuItem({ Text("Ubah nama folder") }, { moreMenu = false; renameFolderTarget = view.name })
                            DropdownMenuItem({ Text("Bubarkan folder") }, { moreMenu = false; dissolveFolderTarget = view.name })
                        }
                        if (view == NoteView.Trash) DropdownMenuItem({ Text("Kosongkan Sampah") }, { moreMenu = false; confirmEmpty = true }, enabled = vm.trashCount > 0)
                        if (vm.lockEnabled) {
                            Hairline()
                            DropdownMenuItem({ Text("Kunci sekarang") }, { moreMenu = false; vm.lockNow() }, leadingIcon = { Icon(AppIcons.Lock, null, Modifier.size(18.dp)) })
                        }
                        Hairline()
                        DropdownMenuItem({ Text("Pengaturan") }, { moreMenu = false; vm.go(Screen.Settings) })
                    }
                }
            }

            val today = vm.today
            val showToday = view == NoteView.All && !searching && vm.tagFilter == null && !vm.askActive && (today.isNotEmpty() || vm.openTaskCount > 0)
            val split = !searching && shown.any { it.pinned } && shown.any { !it.pinned }
            val (pinned, rest) = remember(shown, split) { if (split) shown.partition { it.pinned } else shown to emptyList() }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (vm.askActive) item(key = "ask", contentType = "ask") { AnswerBlock(vm, aiState is AiState.Loading) }
                if (vm.aiTerms.isNotEmpty()) item(key = "terms") { Muted("Juga mencari: " + vm.aiTerms.joinToString(", "), Modifier.padding(horizontal = Gutter, vertical = 6.dp)) }
                vm.batch?.let { (done, total) ->
                    item(key = "batch") {
                        Row(Modifier.padding(start = Gutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Muted("Mengisi judul dan tag: $done dari $total catatan")
                                LinearProgressIndicator({ done / total.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                            TextAction("Hentikan", onClick = vm::stopBatch)
                        }
                    }
                }
                if (!hasModel && !vm.hideAiBanner && !searching && vm.loaded && view == NoteView.All && !vm.askActive) item(key = "banner") {
                    Row(Modifier.padding(start = Gutter, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Fitur AI belum aktif.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = muted,
                        )
                        TextAction("Pilih model") { vm.go(Screen.Settings) }
                        IconAction(AppIcons.Close, "Sembunyikan pesan ini", muted, 18.dp, onClick = vm::dismissAiBanner)
                    }
                }
                if (showToday) item(key = "today", contentType = "today") { TodayBlock(vm, today) }
                if (view == NoteView.Trash) item(key = "trash-note") { Muted("Catatan di Sampah dihapus permanen setelah 30 hari.", Modifier.padding(horizontal = Gutter, vertical = 8.dp)) }
                if (view == NoteView.Vault) item(key = "vault-note") { Muted("Brankas terenkripsi dan tersembunyi dari daftar, pencarian biasa, serta ekspor tanpa sandi.", Modifier.padding(horizontal = Gutter, vertical = 8.dp)) }

                when {
                    !vm.loaded -> item(key = "loading") { Skeleton() }
                    shown.isEmpty() && !vm.askActive -> item(key = "empty") {
                        EmptyState(
                            when {
                                isCommand -> "Ini terbaca sebagai perintah." to "Tekan “Jalankan perintah” untuk melihat rencananya sebelum dijalankan."
                                searching || vm.tagFilter != null -> "Tidak ada catatan yang cocok." to "Coba kata lain, atau hapus filter tag."
                                view == NoteView.Trash -> "Sampah kosong." to null
                                view == NoteView.Archive -> "Arsip kosong." to "Geser catatan ke kanan di daftar untuk mengarsipkannya."
                                view == NoteView.Reminders -> "Belum ada pengingat." to "Geser catatan ke kiri di daftar, atau tulis “ingatkan saya … besok jam 7” di kolom cari."
                                view == NoteView.Vault -> "Brankas masih kosong." to "Ketuk tombol + untuk menulis catatan rahasia, atau pindahkan catatan lewat menu tekan-lama."
                                else -> "Belum ada catatan." to "Ketuk tombol + di kanan bawah untuk mulai menulis."
                            }
                        )
                    }
                    else -> {
                        if (split) item(key = "h-pin", contentType = "label") { SectionLabel("Disematkan", Modifier.padding(start = Gutter, top = 12.dp, bottom = 2.dp)) }
                        items(pinned, key = { it.id }, contentType = { "note" }) { n ->
                            NoteRow(vm, n, view, onMoveFolder = { moveFolderNote = n }, onDeleteForever = { deleteForeverNote = n })
                        }
                        if (split) {
                            item(key = "h-rest", contentType = "label") { SectionLabel("Lainnya", Modifier.padding(start = Gutter, top = 16.dp, bottom = 2.dp)) }
                            items(rest, key = { it.id }, contentType = { "note" }) { n ->
                                NoteRow(vm, n, view, onMoveFolder = { moveFolderNote = n }, onDeleteForever = { deleteForeverNote = n })
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmEmpty) Confirm(
        "Kosongkan Sampah?", "${vm.trashCount} catatan akan dihapus permanen dan tidak bisa dipulihkan.", "Hapus permanen", destructive = true,
        onDismiss = { confirmEmpty = false }) { vm.emptyTrash() }
    if (confirmOrganize) Confirm(
        "Isi ${vm.organizableCount} catatan?",
        "AI mengisi judul, tag, dan folder yang masih kosong. Isian yang sudah ada dan isi catatan tidak diubah. " +
            "Bisa dihentikan kapan saja; periksa hasilnya sesudahnya.",
        "Mulai mengisi", onDismiss = { confirmOrganize = false }) { vm.organizeAll() }
    renameFolderTarget?.let { old ->
        RenameFolderDialog(old, onDismiss = { renameFolderTarget = null }) { newName -> vm.renameFolder(old, newName) }
    }
    dissolveFolderTarget?.let { name ->
        Confirm(
            "Bubarkan folder $name?",
            "Catatan di dalamnya tidak dihapus, hanya dikembalikan ke Tanpa folder. Bisa diurungkan.",
            "Bubarkan folder", onDismiss = { dissolveFolderTarget = null },
        ) { vm.dissolveFolder(name) }
    }
    moveFolderNote?.let { target ->
        FolderDialog(target.folder, vm.folders, onDismiss = { moveFolderNote = null }) { f -> vm.moveNoteToFolder(target, f) }
    }
    deleteForeverNote?.let { target ->
        Confirm(
            "Hapus permanen?",
            "Catatan “${target.heading.take(40).ifBlank { "(kosong)" }}” tidak bisa dipulihkan lagi.",
            "Hapus permanen", destructive = true, onDismiss = { deleteForeverNote = null },
        ) { vm.deleteNoteForever(target) }
    }
    ReminderSheet(vm)
    AgentSheet(vm)
}

@Composable
private fun Skeleton() = Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
    repeat(5) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(180.dp).height(14.dp).clip(SmallShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
            Box(Modifier.fillMaxWidth().height(12.dp).clip(SmallShape).background(MaterialTheme.colorScheme.surfaceContainer))
        }
    }
}

@Composable
private fun EmptyState(text: Pair<String, String?>) = Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 56.dp)) {
    Text(text.first, style = MaterialTheme.typography.titleMedium)
    text.second?.let { Muted(it, Modifier.padding(top = 6.dp), MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun TodayBlock(vm: AppViewModel, due: List<Note>) = Column(Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = vm::toggleToday).heightIn(min = 48.dp).padding(horizontal = Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Clock, null, Modifier.size(16.dp), timeColor)
        Text("Hari ini", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = timeColor)
        Muted(
            "  ·  " + listOfNotNull(
                due.size.takeIf { it > 0 }?.let { "$it pengingat" }, vm.openTaskCount.takeIf { it > 0 }?.let { "$it tugas" },
            ).joinToString(", "),
            Modifier.weight(1f), MaterialTheme.typography.labelLarge,
        )
        Icon(if (vm.todayOpen) AppIcons.ChevronUp else AppIcons.ChevronDown, if (vm.todayOpen) "Lipat Hari ini" else "Buka Hari ini", Modifier.size(20.dp), muted)
    }
    if (vm.todayOpen) {
        due.forEach { n ->
            Row(Modifier.fillMaxWidth().clickable { vm.open(n) }.padding(start = Gutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(n.heading.take(80).ifBlank { "(tanpa judul)" }, style = NoteType.rowSnippet, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (n.remindFired) "Terlewat · " else "") + whenLabel(n.remindAt), style = MaterialTheme.typography.labelSmall,
                        color = if (n.remindFired) MaterialTheme.colorScheme.error else timeColor,
                    )
                }
                if (n.remindFired) TextAction("Selesai") { vm.finishReminder(n) }
            }
        }
        if (vm.openTaskCount > 0) Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = vm::askOpenTasks).heightIn(min = 48.dp).padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${vm.openTaskCount} tugas belum dicentang", Modifier.weight(1f), style = NoteType.rowSnippet, color = MaterialTheme.colorScheme.onSurface)
            Icon(AppIcons.ChevronRight, "Lihat tugas", Modifier.size(18.dp), muted)
        }
    }
    Hairline(Modifier.padding(top = 4.dp))
}

@Composable
private fun AnswerBlock(vm: AppViewModel, loadingModel: Boolean) = Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
    Row(Modifier.padding(start = Gutter, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(vm.askQuery, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        if (vm.askRunning) TextAction("Hentikan", onClick = vm::stopAi)
        IconAction(AppIcons.Close, "Tutup jawaban", muted, 18.dp, onClick = vm::closeAsk)
    }
    Column(Modifier.padding(horizontal = Gutter).padding(bottom = 12.dp)) {
        if (vm.askRunning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.askAnswer.isEmpty()) Muted(if (loadingModel) "Memuat model ke memori…" else "Membaca catatan…", Modifier.padding(top = 10.dp), MaterialTheme.typography.bodyMedium)
        }
        if (!vm.askByAi && vm.openTasksList.isNotEmpty()) {
            Muted("Ketuk tugas untuk mencentangnya, atau ketuk judul catatan untuk membuka.", Modifier.padding(bottom = 6.dp))
            vm.openTasksList.forEachIndexed { idx, (note, tasks) ->
                if (idx > 0) Spacer(Modifier.height(8.dp))
                Text(
                    note.heading.take(60).ifBlank { "(tanpa judul)" },
                    Modifier.clip(SmallShape).clickable(role = Role.Button) { vm.open(note) }.padding(vertical = 4.dp),
                    style = NoteType.rowTitle, color = MaterialTheme.colorScheme.onSurface,
                )
                tasks.forEach { task ->
                    Row(
                        Modifier.fillMaxWidth().clip(SmallShape)
                            .clickable(role = Role.Button, onClickLabel = "Centang tugas") { vm.toggleOpenTask(note, task) }
                            .heightIn(min = 40.dp).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(task, style = NoteType.body, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        } else {
            if (vm.askAnswer.isNotEmpty()) Text(vm.askAnswer, style = NoteType.body, color = MaterialTheme.colorScheme.onSurface)
            if (vm.askSources.isNotEmpty()) {
                SectionLabel("Sumber", Modifier.padding(top = 14.dp))
                vm.askSources.forEach { n -> SourceRow(n) { vm.open(n) } }
                if (vm.askByAi) Muted("AI bisa keliru. Untuk hal penting, buka catatan sumbernya.", Modifier.padding(top = 4.dp))
            }
        }
    }
    Hairline()
}

@Composable
private fun SwipeRow(startLabel: String?, endLabel: String?, onStart: () -> Unit, onEnd: () -> Unit, content: @Composable () -> Unit) {
    if (startLabel == null && endLabel == null) { content(); return }
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    val surface = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth()) {
        val x = offset.value
        if (x != 0f) Box(
            Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = Gutter),
            if (x > 0) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            val armed = kotlin.math.abs(x) >= threshold
            Text(
                (if (x > 0) startLabel else endLabel).orEmpty(), style = MaterialTheme.typography.labelLarge,
                color = if (armed) MaterialTheme.colorScheme.onSurface else muted,
            )
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.background(surface).draggable(
                rememberDraggableState { d ->
                    val next = (offset.value + d).coerceIn(if (endLabel == null) 0f else -threshold * 1.4f, if (startLabel == null) 0f else threshold * 1.4f)
                    scope.launch { offset.snapTo(next) }
                },
                Orientation.Horizontal,
                onDragStopped = {
                    val v = offset.value
                    offset.animateTo(0f)
                    if (v >= threshold) onStart() else if (v <= -threshold) onEnd()
                },
            ),
        ) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(vm: AppViewModel, n: Note, view: NoteView, onMoveFolder: () -> Unit, onDeleteForever: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val (title, snippet) = remember(n.title, n.body, n.attachments) {
        val lines = n.body.take(400).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(4).toList()
        n.heading to (if (n.title.isBlank()) lines.drop(1) else lines).joinToString(" ")
    }
    val meta = remember(n, view) {
        listOfNotNull(
            if (n.archived && !n.inTrash && view != NoteView.Archive) "Arsip" else null,
            if (view !is NoteView.Folder && n.folder.isNotBlank()) n.folder else null,
            if (n.imageCount > 0) "${n.imageCount} gambar" else null,
            if (n.audioCount > 0) "${n.audioCount} suara" else null,
            n.tags.take(3).joinToString(" ") { "#$it" }.ifBlank { null },
            shortDate(n.updated),
        ).joinToString("  ·  ")
    }
    val canSwipe = !n.inTrash && !n.vault
    Box {
        SwipeRow(
            startLabel = if (!canSwipe) null else if (n.archived) "Keluarkan dari arsip" else "Arsipkan",
            endLabel = if (!canSwipe) null else if (n.hasReminder) "Ubah pengingat" else "Ingatkan",
            onStart = { vm.toggleArchive(n) }, onEnd = { vm.openReminder(n) },
        ) {
            Column(
                Modifier.fillMaxWidth().combinedClickable(onClick = { vm.open(n) }, onLongClick = { menu = true }, onLongClickLabel = "Aksi cepat")
                    .padding(horizontal = Gutter).padding(top = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title.ifBlank { "(kosong)" }, Modifier.weight(1f), style = NoteType.rowTitle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.pinned) Icon(AppIcons.PinFilled, "Disematkan", Modifier.padding(start = 8.dp).size(14.dp), muted)
                }
                if (snippet.isNotBlank()) Text(snippet, Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = NoteType.rowSnippet, color = muted)
                Row(Modifier.padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (n.hasReminder && !n.inTrash) {
                        val tint = if (n.remindFired) MaterialTheme.colorScheme.error else timeColor
                        val remLabel = remember(n.remindAt, n.remindRepeat, n.remindFired) {
                            " " + (if (n.remindFired) "Terlewat · " else "") + whenLabel(n.remindAt) +
                                if (n.remindRepeat != Repeat.None) ", ${n.remindRepeat.label.lowercase()}" else ""
                        }
                        Icon(AppIcons.Bell, null, Modifier.size(12.dp), tint)
                        Text(remLabel, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                        Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Hairline()
            }
        }
        Menu(menu, { menu = false }) {
            if (n.inTrash) {
                DropdownMenuItem({ Text("Pulihkan") }, { menu = false; vm.restore(n) })
                DropdownMenuItem({ Text("Hapus permanen", color = MaterialTheme.colorScheme.error) }, { menu = false; onDeleteForever() })
            } else if (n.vault) {
                DropdownMenuItem({ Text(if (n.pinned) "Lepas sematan" else "Sematkan") }, { menu = false; vm.togglePin(n) })
                if (n.hasReminder && n.remindFired) DropdownMenuItem({ Text("Tandai pengingat selesai") }, { menu = false; vm.finishReminder(n) })
                DropdownMenuItem({ Text(if (n.hasReminder) "Ubah pengingat" else "Setel pengingat") }, { menu = false; vm.openReminder(n) })
                DropdownMenuItem({ Text("Keluarkan dari Brankas") }, { menu = false; vm.moveNoteToVault(n, false) })
                Hairline()
                DropdownMenuItem({ Text("Hapus permanen", color = MaterialTheme.colorScheme.error) }, { menu = false; onDeleteForever() })
            } else {
                if (!n.archived) DropdownMenuItem({ Text(if (n.pinned) "Lepas sematan" else "Sematkan") }, { menu = false; vm.togglePin(n) })
                if (n.hasReminder && n.remindFired) DropdownMenuItem({ Text("Tandai pengingat selesai") }, { menu = false; vm.finishReminder(n) })
                DropdownMenuItem({ Text(if (n.hasReminder) "Ubah pengingat" else "Setel pengingat") }, { menu = false; vm.openReminder(n) })
                DropdownMenuItem({ Text("Pindahkan ke folder") }, { menu = false; onMoveFolder() })
                DropdownMenuItem({ Text("Pindahkan ke Brankas") }, { menu = false; vm.moveNoteToVault(n, true) })
                DropdownMenuItem({ Text(if (n.archived) "Keluarkan dari arsip" else "Arsipkan") }, { menu = false; vm.toggleArchive(n) })
                DropdownMenuItem({ Text("Pindahkan ke Sampah") }, { menu = false; vm.trash(n) })
            }
        }
    }
}

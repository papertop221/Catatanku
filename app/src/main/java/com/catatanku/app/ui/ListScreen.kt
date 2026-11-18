package com.catatanku.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.sp
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

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchVisible = searchOpen || vm.query.isNotEmpty()
    LaunchedEffect(vm.searchFocusTick) { if (vm.searchFocusTick > 0) searchOpen = true }
    LaunchedEffect(searchOpen, vm.searchFocusTick) {
        if (searchOpen) { kotlinx.coroutines.delay(60); runCatching { searchFocus.requestFocus() } }
    }
    fun closeSearch() { searchOpen = false; vm.updateQuery("") }
    androidx.activity.compose.BackHandler(searchVisible) { closeSearch() }
    val dark = vm.themeMode.isDark()
    val dateLine = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", ID).format(java.time.LocalDate.now()).replaceFirstChar { it.titlecase(ID) } }

    Scaffold(snackbarHost = { Notices(snack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            val today = vm.today
            val showToday = view == NoteView.All && !searching && vm.tagFilter == null && !vm.askActive && (today.isNotEmpty() || vm.openTaskCount > 0)
            val split = !searching && shown.any { it.pinned } && shown.any { !it.pinned }
            val (pinned, rest) = remember(shown, split) { if (split) shown.partition { it.pinned } else shown to emptyList() }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 128.dp)) {
                item(key = "header", contentType = "header") {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(dateLine, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = accent)
                            if (view == NoteView.Vault) TextAction("Kunci") { vm.lockVault() }
                            IconAction(if (dark) AppIcons.Sun else AppIcons.Moon, if (dark) "Ganti ke tema terang" else "Ganti ke tema gelap") {
                                vm.updateThemeMode(if (dark) ThemeMode.Light else ThemeMode.Dark)
                            }
                            Box {
                                IconAction(AppIcons.More, "Menu") { moreMenu = true }
                    Menu(moreMenu, { moreMenu = false }) {
                                SectionLabel("Urutkan", Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
                                Sort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        { Text(s.label) }, { vm.updateSort(s); moreMenu = false },
                                        trailingIcon = { if (vm.sort == s) Icon(AppIcons.Check, "Dipilih", Modifier.size(18.dp)) },
                                    )
                                }
                                Hairline()
                                DropdownMenuItem({ Text("Tugas terbuka") }, { moreMenu = false; vm.askOpenTasks() })
                                DropdownMenuItem({ Text("Ringkasan mingguan") }, { moreMenu = false; vm.askRecap() })
                                if (hasModel) DropdownMenuItem(
                                    { Text("Lengkapi metadata") }, { moreMenu = false; confirmOrganize = true },
                                    enabled = vm.batch == null && vm.organizableCount > 0,
                                )
                                if (view is NoteView.Folder) {
                                    Hairline()
                                    DropdownMenuItem({ Text("Ubah nama folder") }, { moreMenu = false; renameFolderTarget = view.name })
                                    DropdownMenuItem({ Text("Hapus folder") }, { moreMenu = false; dissolveFolderTarget = view.name })
                                }
                                if (view == NoteView.Trash) DropdownMenuItem({ Text("Kosongkan Sampah") }, { moreMenu = false; confirmEmpty = true }, enabled = vm.trashCount > 0)
                                if (vm.lockEnabled) {
                                    Hairline()
                                    DropdownMenuItem({ Text("Kunci sekarang") }, { moreMenu = false; vm.lockNow() })
                                }
                                Hairline()
                                DropdownMenuItem({ Text("Pengaturan") }, { moreMenu = false; vm.go(Screen.Settings) })
                            }
                            }
                        }
                        Box(Modifier.padding(start = Gutter - 4.dp, end = Gutter)) {
                            Row(
                                Modifier.clip(SmallShape).clickable(role = Role.Button, onClickLabel = "Ganti tampilan") { viewMenu = true }.padding(horizontal = 4.dp),
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
                                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Icon(AppIcons.ChevronDown, null, Modifier.padding(start = 6.dp, top = 6.dp).size(20.dp), muted)
                            }
                            Menu(viewMenu, { viewMenu = false }) {
                                fun pick(v: NoteView) { vm.switchView(v); viewMenu = false }
                                DropdownMenuItem({ Text("Semua catatan") }, { pick(NoteView.All) })
                                vm.folders.forEach { f ->
                                    DropdownMenuItem({ Text(f) }, { pick(NoteView.Folder(f)) })
                                }
                                Hairline()
                                DropdownMenuItem({ Text("Pengingat" + if (vm.reminderCount > 0) " (${vm.reminderCount})" else "") }, { pick(NoteView.Reminders) })
                                DropdownMenuItem({ Text("Arsip") }, { pick(NoteView.Archive) })
                                DropdownMenuItem({ Text("Sampah" + if (vm.trashCount > 0) " (${vm.trashCount})" else "") }, { pick(NoteView.Trash) })
                                Hairline()
                                DropdownMenuItem(
                                    { Text("Brankas" + if (vm.vaultCount > 0) " (${vm.vaultCount})" else "") },
                                    { viewMenu = false; vm.requestOpenVault() },
                                )
                            }
                        }
                        val summary = when (view) {
                            NoteView.Trash -> "Dihapus permanen setelah 30 hari"
                            NoteView.Vault -> "${shown.size} catatan · terenkripsi"
                            else -> listOfNotNull(
                                "${shown.size} catatan",
                                today.size.takeIf { it > 0 && view == NoteView.All }?.let { "$it pengingat hari ini" },
                            ).joinToString(" · ")
                        }
                        Muted(summary, Modifier.padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 14.dp), MaterialTheme.typography.bodyMedium)
                        val tags = vm.allTags
                        if (tags.isNotEmpty() && view != NoteView.Trash && view != NoteView.Vault) LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 10.dp),
                        ) {
                            items(tags, key = { it }) { t ->
                                val on = vm.tagFilter == t
                                Box(
                                    Modifier.heightIn(min = 36.dp).clip(PillShape)
                                        .background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainer)
                                        .clickable(role = Role.Button) { vm.tagFilter = if (on) null else t }.padding(horizontal = 14.dp),
                                    Alignment.Center,
                                ) {
                                    Text("#$t", style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
                if (vm.askActive) item(key = "ask", contentType = "ask") { AnswerBlock(vm, aiState is AiState.Loading) }
                if (vm.aiTerms.isNotEmpty()) item(key = "terms") { Muted("Juga mencari: " + vm.aiTerms.joinToString(", "), Modifier.padding(horizontal = Gutter, vertical = 4.dp)) }
                vm.batch?.let { (done, total) ->
                    item(key = "batch") {
                        Row(Modifier.padding(start = Gutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Muted("Melengkapi metadata  ·  $done/$total")
                                LinearProgressIndicator({ done / total.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                            TextAction("Hentikan", onClick = vm::stopBatch)
                        }
                    }
                }
                if (!hasModel && !vm.hideAiBanner && !searching && vm.loaded && view == NoteView.All && !vm.askActive) item(key = "banner") {
                    Row(Modifier.padding(start = Gutter, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Asisten belum aktif", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = muted)
                        TextAction("Aktifkan") { vm.go(Screen.Settings) }
                        IconAction(AppIcons.Close, "Sembunyikan pesan ini", muted, 18.dp, onClick = vm::dismissAiBanner)
                    }
                }
                if (showToday) item(key = "today", contentType = "today") { TodayBlock(vm, today) }

                when {
                    !vm.loaded -> item(key = "loading") { Skeleton() }
                    shown.isEmpty() && !vm.askActive -> item(key = "empty") {
                        EmptyState(
                            when {
                                isCommand -> "Perintah terdeteksi" to "Ketuk Jalankan untuk melihat rencananya."
                                searching || vm.tagFilter != null -> "Tidak ada hasil" to "Coba kata lain atau hapus filter tag."
                                view == NoteView.Trash -> "Sampah kosong" to null
                                view == NoteView.Archive -> "Arsip kosong" to "Geser catatan ke kanan untuk mengarsipkan."
                                view == NoteView.Reminders -> "Belum ada pengingat" to "Geser catatan ke kiri untuk menyetelnya."
                                view == NoteView.Vault -> "Brankas kosong" to "Ketuk + untuk menulis catatan rahasia."
                                else -> "Belum ada catatan" to "Ketuk + untuk mulai menulis."
                            }
                        )
                    }
                    else -> {
                        if (split) item(key = "h-pin", contentType = "label") { SectionLabel("Disematkan", Modifier.padding(start = Gutter, top = 14.dp, bottom = 2.dp)) }
                        items(pinned, key = { it.id }, contentType = { "note" }) { n ->
                            Box(Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(220), fadeOutSpec = tween(120))) { NoteRow(vm, n, view, onMoveFolder = { moveFolderNote = n }, onDeleteForever = { deleteForeverNote = n }) }
                        }
                        if (split) {
                            item(key = "h-rest", contentType = "label") { SectionLabel("Lainnya", Modifier.padding(start = Gutter, top = 24.dp, bottom = 2.dp)) }
                            items(rest, key = { it.id }, contentType = { "note" }) { n ->
                                Box(Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(220), fadeOutSpec = tween(120))) { NoteRow(vm, n, view, onMoveFolder = { moveFolderNote = n }, onDeleteForever = { deleteForeverNote = n }) }
                            }
                        }
                    }
                }
            }

            // ---------- Dok mengambang ----------
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedVisibility(
                    searchVisible,
                    enter = fadeIn(tween(160)) + expandVertically(tween(200), expandFrom = Alignment.Bottom),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160), shrinkTowards = Alignment.Bottom),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val offerAgent = isCommand || (hasModel && view != NoteView.Vault && vm.query.trim().split(WORDS).size >= 3)
                        if (searching && view != NoteView.Trash && view != NoteView.Vault && (hasModel || isCommand)) Row(
                            Modifier.clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 6.dp),
                        ) {
                            if (offerAgent) TextAction("Jalankan") { vm.startAgent(vm.query) }
                            if (hasModel) {
                                TextAction("Tanyakan") { vm.ask(vm.query) }
                                if (vm.aiTerms.isEmpty()) TextAction(if (vm.aiBusy) "Memperluas…" else "Perluas", enabled = !vm.aiBusy, onClick = vm::expandQueryWithAi)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 18.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Search, null, Modifier.size(18.dp), muted)
                            Spacer(Modifier.width(10.dp))
                            PlainField(
                                vm.query, vm::updateQuery,
                                if (view == NoteView.Vault) "Cari di Brankas" else "Cari atau beri perintah",
                                Modifier.weight(1f).focusRequester(searchFocus), ime = ImeAction.Search,
                            ) {
                                if (isCommand && steps.none { it is AgentStep.Trash || it is AgentStep.Archive }) vm.startAgent(vm.query)
                            }
                            IconAction(AppIcons.Close, "Tutup pencarian", muted, 18.dp) { closeSearch() }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).height(60.dp).clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        val activeBg = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest
                        DockItem(AppIcons.Notes, "Semua catatan", view == NoteView.All && !searchVisible, activeBg) { closeSearch(); vm.switchView(NoteView.All) }
                        DockItem(AppIcons.Search, "Cari", searchVisible, activeBg) { if (searchVisible) closeSearch() else searchOpen = true }
                        DockItem(AppIcons.Bell, "Pengingat", view == NoteView.Reminders && !searchVisible, activeBg) { closeSearch(); vm.switchView(NoteView.Reminders) }
                        DockItem(AppIcons.Sliders, "Pengaturan", false, activeBg) { closeSearch(); vm.go(Screen.Settings) }
                    }
                    if (canCreate) {
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.size(60.dp).clip(CircleShape).background(accent).clickable(role = Role.Button, onClickLabel = "Catatan baru") { vm.open(null) },
                            Alignment.Center,
                        ) { Icon(AppIcons.Plus, "Catatan baru", Modifier.size(24.dp), MaterialTheme.colorScheme.onSecondary) }
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
private fun DockItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, activeBg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (active) activeBg else androidx.compose.ui.graphics.Color.Transparent, tween(200), label = "dok")
    Box(
        Modifier.size(width = 60.dp, height = 46.dp).clip(PillShape).background(bg).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        Alignment.Center,
    ) { Icon(icon, label, Modifier.size(21.dp), if (active) MaterialTheme.colorScheme.onSurface else muted) }
}

@Composable
private fun Skeleton() = Column(Modifier.padding(horizontal = Gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    repeat(5) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun TodayBlock(vm: AppViewModel, due: List<Note>) = Column(
    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp).fillMaxWidth().clip(GroupShape)
        .background(MaterialTheme.colorScheme.surfaceContainer).animateContentSize(tween(220)),
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = vm::toggleToday).heightIn(min = 52.dp).padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Hari ini", style = MaterialTheme.typography.titleSmall, color = timeColor)
        Muted(
            "   " + listOfNotNull(
                due.size.takeIf { it > 0 }?.let { "$it pengingat" }, vm.openTaskCount.takeIf { it > 0 }?.let { "$it tugas" },
            ).joinToString(" · "),
            Modifier.weight(1f), MaterialTheme.typography.bodyMedium,
        )
        Icon(if (vm.todayOpen) AppIcons.ChevronUp else AppIcons.ChevronDown, if (vm.todayOpen) "Lipat Hari ini" else "Buka Hari ini", Modifier.size(18.dp), muted)
    }
    if (vm.todayOpen) {
        due.forEach { n ->
            Row(Modifier.fillMaxWidth().clickable { vm.open(n) }.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n.heading.take(80).ifBlank { "(tanpa judul)" }, Modifier.weight(1f).padding(vertical = 12.dp), style = NoteType.rowSnippet, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (n.remindFired) "Terlewat" else whenLabel(n.remindAt), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium,
                    color = if (n.remindFired) MaterialTheme.colorScheme.error else timeColor,
                )
                if (n.remindFired) TextAction("Selesai") { vm.finishReminder(n) }
            }
        }
        if (vm.openTaskCount > 0) Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = vm::askOpenTasks).heightIn(min = 48.dp).padding(start = 18.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${vm.openTaskCount} tugas terbuka", Modifier.weight(1f), style = NoteType.rowSnippet, color = MaterialTheme.colorScheme.onSurface)
            Icon(AppIcons.ChevronRight, "Lihat tugas", Modifier.size(18.dp), muted)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun AnswerBlock(vm: AppViewModel, loadingModel: Boolean) = Column(Modifier.fillMaxWidth().animateContentSize(tween(220)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
    Row(Modifier.padding(start = Gutter, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(vm.askQuery, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        if (vm.askRunning) TextAction("Hentikan", onClick = vm::stopAi)
        IconAction(AppIcons.Close, "Tutup jawaban", muted, 18.dp, onClick = vm::closeAsk)
    }
    Column(Modifier.padding(horizontal = Gutter).padding(bottom = 16.dp)) {
        if (vm.askRunning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.askAnswer.isEmpty()) Muted(if (loadingModel) "Memuat model ke memori…" else "Membaca catatan…", Modifier.padding(top = 6.dp), MaterialTheme.typography.bodyMedium)
        }
        if (!vm.askByAi && vm.openTasksList.isNotEmpty()) {
            Muted("Ketuk tugas untuk mencentang.", Modifier.padding(bottom = 6.dp))
            vm.openTasksList.forEachIndexed { idx, (note, tasks) ->
                if (idx > 0) Spacer(Modifier.height(6.dp))
                Text(
                    note.heading.take(60).ifBlank { "(tanpa judul)" },
                    Modifier.clip(SmallShape).clickable(role = Role.Button) { vm.open(note) }.padding(vertical = 3.dp),
                    style = NoteType.rowTitle, color = MaterialTheme.colorScheme.onSurface,
                )
                tasks.forEach { task ->
                    Row(
                        Modifier.fillMaxWidth().clip(SmallShape)
                            .clickable(role = Role.Button, onClickLabel = "Centang tugas") { vm.toggleOpenTask(note, task) }
                            .heightIn(min = 38.dp).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(task, style = NoteType.body, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        } else {
            if (vm.askAnswer.isNotEmpty()) Text(vm.askAnswer, style = NoteType.body, color = MaterialTheme.colorScheme.onSurface)
            if (vm.askSources.isNotEmpty()) {
                SectionLabel("Sumber", Modifier.padding(top = 16.dp, bottom = 2.dp))
                vm.askSources.forEach { n -> SourceRow(n) { vm.open(n) } }
                if (vm.askByAi) Muted("Periksa sumber untuk hal penting.", Modifier.padding(top = 4.dp))
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
    val time = remember(n.remindAt, n.remindFired, n.updated, n.inTrash) {
        when {
            n.hasReminder && !n.inTrash && n.remindFired -> "Terlewat"
            n.hasReminder && !n.inTrash -> whenLabel(n.remindAt).removePrefix("Hari ini ")
            else -> shortDate(n.updated)
        }
    }
    val canSwipe = !n.inTrash && !n.vault
    Box {
        SwipeRow(
            startLabel = if (!canSwipe) null else if (n.archived) "Keluarkan dari arsip" else "Arsipkan",
            endLabel = if (!canSwipe) null else if (n.hasReminder) "Ubah pengingat" else "Ingatkan",
            onStart = { vm.toggleArchive(n) }, onEnd = { vm.openReminder(n) },
        ) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { vm.open(n) }, onLongClick = { menu = true }, onLongClickLabel = "Aksi cepat")
                    .padding(horizontal = Gutter, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title.ifBlank { "(kosong)" }, Modifier.weight(1f, fill = false), style = NoteType.rowTitle.copy(fontSize = 17.sp, lineHeight = 23.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n.pinned) Icon(AppIcons.PinFilled, "Disematkan", Modifier.padding(start = 6.dp).size(12.dp), muted)
                    }
                    if (snippet.isNotBlank()) Text(snippet, Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = NoteType.rowSnippet, color = muted)
                }
                Text(
                    time, Modifier.padding(start = 12.dp, top = 3.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    color = when { n.hasReminder && !n.inTrash && n.remindFired -> MaterialTheme.colorScheme.error; n.hasReminder && !n.inTrash -> timeColor; else -> muted },
                )
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

package com.catatanku.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catatanku.app.ai.engine.AiState
import com.catatanku.app.ai.inference.AiText
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.usecase.TimeParse
import com.catatanku.app.domain.usecase.TextFormat
import com.catatanku.app.media.Attachments
import com.catatanku.app.media.AudioPlayer
import com.catatanku.app.media.VoiceRecorder
import com.catatanku.app.reminders.toMillis
import com.catatanku.app.reminders.whenLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
private fun Tool(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) = Box(
    Modifier.size(48.dp).clip(SmallShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
    Alignment.Center,
) { Icon(icon, label, Modifier.size(20.dp), tint) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: AppViewModel, snack: SnackbarHostState) {
    val d = vm.draft
    val ctx = LocalContext.current
    val aiState by vm.ai.state.collectAsStateWithLifecycle()
    val hasModel = aiState !is AiState.NoModel
    val canMultimodal = hasModel && vm.ai.activeSupportsMultimodal
    var menu by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var related by remember { mutableStateOf<List<Note>>(emptyList()) }
    var hint by remember { mutableStateOf<TimeParse.Found?>(null) }
    var hintDismissed by remember(d.id) { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    var formatMenu by remember { mutableStateOf(false) }
    var findOpen by remember(d.id) { mutableStateOf(false) }
    var findQuery by remember(d.id) { mutableStateOf("") }
    var replaceWith by remember(d.id) { mutableStateOf("") }
    var askTemplateName by remember { mutableStateOf(false) }
    var backlinks by remember { mutableStateOf<List<Note>>(emptyList()) }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let { vm.exportDraft(it, markdown = true) } }
    val exportTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { vm.exportDraft(it, markdown = false) } }

    val scroll = rememberScrollState()
    val bodyFocus = remember { FocusRequester() }
    val recorder = remember { VoiceRecorder(ctx) }
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { recorder.cancel(); player.stop() } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::attachImage) }
    val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (!recorder.start()) vm.say("Mikrofon tidak bisa dimulai.")
        } else vm.say("Izin mikrofon diperlukan untuk merekam catatan suara.")
    }

    val undoStack = remember(d.id) { mutableStateListOf<Pair<String, String>>() }
    val redoStack = remember(d.id) { mutableStateListOf<Pair<String, String>>() }
    var lastSnap by remember(d.id) { mutableStateOf(d.title to d.body) }
    var applyingUndo by remember(d.id) { mutableStateOf(false) }

    fun pushUndo(snap: Pair<String, String> = lastSnap) {
        if (undoStack.lastOrNull() != snap) {
            if (undoStack.size >= 30) undoStack.removeAt(0)
            undoStack.add(snap)
        }
        redoStack.clear()
    }

    var stored by remember(d.id) { mutableStateOf(TextFieldValue(d.body)) }
    val bodyField = if (stored.text == d.body) stored
    else TextFieldValue(d.body, TextRange(d.body.length)).also { stored = it }

    fun setBody(v: TextFieldValue) {
        if (v.text == stored.text && v.selection.collapsed && v.selection != stored.selection) {
            AiText.toggleCheckAt(v.text, v.selection.start)?.let { (flipped, nextCursor) ->
                pushUndo(d.title to d.body)
                lastSnap = d.title to flipped
                stored = TextFieldValue(flipped, TextRange(nextCursor))
                vm.edit { it.copy(body = flipped) }
                return
            }
        }
        val next = AiText.continueListOnEnter(stored.text, v.text, v.selection.start)?.let { (txt, cur) ->
            TextFieldValue(txt, TextRange(cur))
        } ?: v
        stored = next
        vm.edit { it.copy(body = next.text) }
    }

    fun prefix(checklist: Boolean) {
        pushUndo(d.title to d.body)
        val (t, c) = AiText.toggleLinePrefix(bodyField.text, bodyField.selection.start, checklist)
        lastSnap = d.title to t
        stored = TextFieldValue(t, TextRange(c))
        vm.edit { it.copy(body = t) }
    }

    fun applyEdit(e: TextFormat.Edit) {
        pushUndo(d.title to d.body)
        lastSnap = d.title to e.text
        stored = TextFieldValue(e.text, TextRange(e.selStart, e.selEnd))
        vm.edit { it.copy(body = e.text) }
        runCatching { bodyFocus.requestFocus() }
    }

    fun undoEdit() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.add(d.title to d.body)
        applyingUndo = true
        lastSnap = prev
        stored = TextFieldValue(prev.second, TextRange(prev.second.length))
        vm.edit { it.copy(title = prev.first, body = prev.second) }
    }

    fun redoEdit() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(d.title to d.body)
        applyingUndo = true
        lastSnap = next
        stored = TextFieldValue(next.second, TextRange(next.second.length))
        vm.edit { it.copy(title = next.first, body = next.second) }
    }

    fun flushPendingTag() {
        if (tagInput.isNotBlank()) {
            tagInput.split(',').forEach { if (it.isNotBlank()) vm.addTag(it) }
            tagInput = ""
        }
    }

    fun closeWithFlush() {
        if (recorder.recording) recorder.stop()?.let(vm::attachRecordedAudio)
        player.stop()
        flushPendingTag()
        vm.closeEditor()
    }

    BackHandler { closeWithFlush() }

    var prevNoteId by remember { mutableStateOf(d.id) }
    LaunchedEffect(d.id) {
        if (d.id != prevNoteId) {
            prevNoteId = d.id
            scroll.scrollTo(0)
        }
        // Saat layar editor sedang memudar keluar (animasi), jangan memunculkan papan ketik lagi.
        if (d.id == 0L && d.isBlank && vm.screen == com.catatanku.app.domain.model.Screen.Editor) {
            runCatching { bodyFocus.requestFocus() }
        }
    }

    LaunchedEffect(d.title, d.body) {
        if (applyingUndo) { applyingUndo = false; return@LaunchedEffect }
        val cur = d.title to d.body
        if (cur == lastSnap) return@LaunchedEffect
        delay(450)
        pushUndo(lastSnap)
        lastSnap = cur
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { flushPendingTag(); vm.saveDraft() }
    LaunchedEffect(d) { delay(700); vm.saveDraft() }
    LaunchedEffect(d.title, d.body, d.tags, vm.index) { delay(500); related = withContext(Dispatchers.Default) { vm.relatedTo(d) } }
    LaunchedEffect(d.title, d.body, d.remindAt) { delay(700); hint = withContext(Dispatchers.Default) { vm.reminderHint(d) } }
    LaunchedEffect(d.title, d.id, vm.index) { delay(600); backlinks = withContext(Dispatchers.Default) { vm.backlinks(d) } }

    val mutedCol = muted
    val linkCol = accent
    val hlCol = timeColor.copy(alpha = 0.28f)
    val activeFind = if (findOpen) findQuery else ""
    val bodyVisual = remember(mutedCol, linkCol, hlCol, activeFind) {
        VisualTransformation { src ->
            val m = if (activeFind.isEmpty()) emptyList() else TextFormat.matches(src.text, activeFind)
            TransformedText(formatAnnotated(src.text, mutedCol, linkCol, hlCol, m), OffsetMapping.Identity)
        }
    }
    val cursor = bodyField.selection
    val linkHere = remember(bodyField.text, cursor) { if (cursor.collapsed) TextFormat.linkAt(bodyField.text, cursor.start) else null }
    val partial = remember(bodyField.text, cursor) { if (cursor.collapsed) TextFormat.partialLink(bodyField.text, cursor.start) else null }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { Notices(snack) },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
                if (recorder.recording) {
                    Hairline()
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = Gutter, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Merekam  ·  ${Attachments.formatDuration(recorder.seconds)}",
                            Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
                        )
                        TextAction("Batal", color = MaterialTheme.colorScheme.error) { recorder.cancel() }
                        SmallButton("Simpan", primary = true) {
                            recorder.stop()?.let(vm::attachRecordedAudio) ?: vm.say("Rekaman terlalu pendek.")
                        }
                    }
                }
                if (findOpen) {
                    Hairline()
                    val count = remember(d.body, findQuery) { TextFormat.matches(d.body, findQuery).size }
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp), Alignment.CenterStart) {
                                PlainField(findQuery, { findQuery = it.take(80) }, "Cari di catatan")
                            }
                            Muted(if (findQuery.isEmpty()) "" else "$count hasil", Modifier.padding(horizontal = 10.dp), MaterialTheme.typography.labelMedium)
                            IconAction(AppIcons.Close, "Tutup cari & ganti", muted, 18.dp) { findOpen = false }
                        }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(PillShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp), Alignment.CenterStart) {
                                PlainField(replaceWith, { replaceWith = it.take(80) }, "Ganti dengan")
                            }
                            TextAction("Ganti semua", enabled = count > 0 && !d.inTrash) {
                                val (t, n) = TextFormat.replaceAll(d.body, findQuery, replaceWith)
                                applyEdit(TextFormat.Edit(t, t.length, t.length))
                                vm.say("$n diganti.", "Urungkan") { undoEdit() }
                            }
                        }
                    }
                }
                if (linkHere != null || partial != null) {
                    Hairline()
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (linkHere != null) {
                            val exists = remember(linkHere, vm.notes) { vm.linkTarget(linkHere) != null }
                            TextAction((if (exists) "Buka " else "Buat ") + "“${linkHere.take(40)}”", color = accent) { flushPendingTag(); vm.openLink(linkHere) }
                        } else if (partial != null) {
                            val options = remember(partial, vm.notes) { vm.linkSuggestions(partial) }
                            if (options.isEmpty()) Muted(if (partial.isBlank()) "Ketik judul catatan" else "Belum ada catatan “$partial”", Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                            options.forEach { t -> Choice(t.take(32)) { applyEdit(TextFormat.completeLink(bodyField.text, cursor.start, t)) } }
                        }
                    }
                }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Tool(AppIcons.TextFormat, "Format teks") { formatMenu = true }
                        Menu(formatMenu, { formatMenu = false }) {
                            val sel = bodyField.selection
                            DropdownMenuItem({ Text("Subjudul") }, { formatMenu = false; applyEdit(TextFormat.toggleLinePrefix(bodyField.text, sel.start, "## ")) })
                            DropdownMenuItem({ Text("Tebal") }, { formatMenu = false; applyEdit(TextFormat.wrap(bodyField.text, sel.start, sel.end, "**")) })
                            DropdownMenuItem({ Text("Miring") }, { formatMenu = false; applyEdit(TextFormat.wrap(bodyField.text, sel.start, sel.end, "_")) })
                            DropdownMenuItem({ Text("Kutipan") }, { formatMenu = false; applyEdit(TextFormat.toggleLinePrefix(bodyField.text, sel.start, "> ")) })
                            DropdownMenuItem({ Text("Tautan ke catatan") }, {
                                formatMenu = false
                                val t = bodyField.text; val c = sel.end.coerceIn(0, t.length)
                                val next = t.substring(0, c) + "[[" + t.substring(c)
                                applyEdit(TextFormat.Edit(next, c + 2, c + 2))
                            })
                        }
                    }
                    Tool(AppIcons.CheckBox, "Daftar centang") { prefix(true) }
                    Tool(AppIcons.Bullets, "Daftar poin") { prefix(false) }
                    Tool(AppIcons.Image, "Lampirkan gambar") { vm.suppressNextLeaveLock(); pickImage.launch("image/*") }
                    Tool(
                        if (recorder.recording) AppIcons.Stop else AppIcons.Mic,
                        if (recorder.recording) "Selesai merekam" else "Rekam suara",
                        if (recorder.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    ) {
                        if (recorder.recording) {
                            recorder.stop()?.let(vm::attachRecordedAudio) ?: vm.say("Rekaman terlalu pendek.")
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            player.stop()
                            if (!recorder.start()) vm.say("Mikrofon tidak bisa dimulai.")
                        } else {
                            vm.suppressNextLeaveLock()
                            askAudio.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    if (!d.vault) Tool(AppIcons.Folder, "Pindahkan ke folder") { folderDialog = true }
                    Spacer(Modifier.weight(1f))
                    TextAction(if (vm.aiBusy) "Bekerja…" else "Asisten") {
                        val sel = bodyField.selection
                        vm.aiSelection = if (!sel.collapsed && sel.max <= d.body.length) sel.min until sel.max else null
                        vm.aiSheet = true
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(AppIcons.Back, "Kembali ke daftar", onClick = ::closeWithFlush)
                Muted(
                    when { d.isBlank && d.id == 0L -> ""; vm.draftSaved -> "Tersimpan"; else -> "Menyimpan…" },
                    Modifier.weight(1f).padding(start = 2.dp), MaterialTheme.typography.labelMedium,
                )
                if (undoStack.isNotEmpty()) IconAction(AppIcons.Undo, "Urungkan ketikan", onClick = ::undoEdit)
                if (redoStack.isNotEmpty()) IconAction(AppIcons.Redo, "Ulangi ketikan", onClick = ::redoEdit)
                if (!d.inTrash) IconAction(
                    if (d.hasReminder) AppIcons.BellFilled else AppIcons.Bell, if (d.hasReminder) "Ubah pengingat" else "Setel pengingat",
                    if (d.hasReminder) timeColor else MaterialTheme.colorScheme.onSurface,
                ) { vm.openReminder() }
                if (!d.inTrash) IconAction(if (d.pinned) AppIcons.PinFilled else AppIcons.Pin, if (d.pinned) "Lepas sematan" else "Sematkan") { vm.edit { it.copy(pinned = !it.pinned) } }
                Box {
                    IconAction(AppIcons.More, "Menu catatan") { menu = true }
                    Menu(menu, { menu = false }) {
                        val plain = (if (d.title.isNotBlank()) d.title + "\n\n" else "") + d.body
                        DropdownMenuItem({ Text("Bagikan") }, {
                            menu = false
                            vm.suppressNextLeaveLock()
                            ctx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, d.title).putExtra(Intent.EXTRA_TEXT, plain), null))
                        }, enabled = !d.isBlank)
                        DropdownMenuItem({ Text("Salin") }, { menu = false; copyText(ctx, plain); vm.say("Teks disalin.") }, enabled = !d.isBlank)
                        DropdownMenuItem({ Text("Cari & ganti") }, { menu = false; findOpen = true }, enabled = !d.isBlank)
                        DropdownMenuItem({ Text("Templat") }, { menu = false; vm.openTemplates() }, enabled = !d.inTrash)
                        DropdownMenuItem({ Text("Simpan sebagai templat") }, { menu = false; askTemplateName = true }, enabled = d.body.isNotBlank())
                        DropdownMenuItem({ Text("Gandakan") }, { menu = false; vm.duplicateDraft() }, enabled = !d.isBlank && !d.inTrash)
                        if (!d.vault) {
                            DropdownMenuItem({ Text("Ekspor .md") }, { menu = false; vm.suppressNextLeaveLock(); exportMd.launch(vm.exportFileName(true)) }, enabled = !d.isBlank)
                            DropdownMenuItem({ Text("Ekspor .txt") }, { menu = false; vm.suppressNextLeaveLock(); exportTxt.launch(vm.exportFileName(false)) }, enabled = !d.isBlank)
                        }
                        DropdownMenuItem({ Text("Riwayat versi") }, { menu = false; vm.openVersions() }, enabled = d.id != 0L)
                        if (!d.inTrash) {
                            DropdownMenuItem(
                                { Text(if (d.vault) "Keluarkan dari Brankas" else "Pindahkan ke Brankas") },
                                { menu = false; vm.moveDraftToVault(!d.vault) },
                                enabled = !d.isBlank || d.id != 0L,
                            )
                        }
                        Hairline()
                        if (d.inTrash || d.vault) {
                            if (d.inTrash) DropdownMenuItem({ Text("Pulihkan") }, { menu = false; vm.restoreDraft() })
                            DropdownMenuItem({ Text("Hapus permanen", color = MaterialTheme.colorScheme.error) }, { menu = false; confirmForever = true })
                        } else {
                            DropdownMenuItem({ Text(if (d.archived) "Keluarkan dari arsip" else "Arsipkan") }, { menu = false; vm.toggleArchive() }, enabled = !d.isBlank)
                            DropdownMenuItem({ Text("Pindahkan ke Sampah") }, { menu = false; vm.trashDraft() })
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = Gutter).padding(top = 4.dp)) {
                if (d.inTrash) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Di Sampah", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    TextAction("Pulihkan", onClick = vm::restoreDraft)
                }
                PlainField(
                    d.title, { v -> vm.edit { it.copy(title = v.replace('\n', ' ')) } }, "Judul",
                    Modifier.fillMaxWidth(), style = NoteType.title, ime = ImeAction.Next,
                    onIme = { runCatching { bodyFocus.requestFocus() } },
                )
                Muted(
                    listOfNotNull(
                        if (d.vault) "Brankas" else null,
                        d.folder.ifBlank { null }, if (d.id != 0L) shortDate(d.updated) else null,
                        remember(d.body) { AiText.wordCount(d.body) }.takeIf { it > 0 }?.let { "$it kata" },
                    ).joinToString("  ·  "),
                    Modifier.padding(top = 6.dp, bottom = 10.dp), MaterialTheme.typography.labelSmall,
                )
                if (d.hasReminder && !d.inTrash) Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f, fill = false).clip(SmallShape).clickable(role = Role.Button) { vm.openReminder() }.heightIn(min = 38.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (d.remindFired) MaterialTheme.colorScheme.error else timeColor
                        Icon(AppIcons.Bell, null, Modifier.size(14.dp), tint)
                        Text(reminderText(d), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = tint)
                    }
                    if (d.remindFired) TextAction("Selesai", Modifier.padding(start = 6.dp)) { vm.finishReminder(d) }
                } else hint?.takeIf { !hintDismissed && !d.hasReminder }?.let { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Bell, null, Modifier.size(14.dp), timeColor)
                        Text(whenLabel(h.at.toMillis()), Modifier.weight(1f, fill = false).padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = timeColor)
                        TextAction("Setel pengingat") { vm.openReminder(seed = h.at.toMillis()) }
                        IconAction(AppIcons.Close, "Abaikan saran pengingat", muted, 16.dp) { hintDismissed = true }
                    }
                }

                if (d.attachments.isNotEmpty()) {
                    Column(Modifier.padding(top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.attachments.forEach { name ->
                            val exists = name in vm.existingAttachments
                            if (Attachments.isImage(name)) {
                                AttachmentImageRow(
                                    name = name,
                                    exists = exists,
                                    hasModel = canMultimodal,
                                    onOpen = { previewImage = name },
                                    onRead = { vm.runMediaAi(name, false) },
                                    onTasks = { vm.runMediaAi(name, true) },
                                    onRemove = { vm.removeAttachment(name) },
                                )
                            } else if (Attachments.isAudio(name)) {
                                AttachmentAudioRow(
                                    name = name,
                                    exists = exists,
                                    playing = player.playing == name,
                                    hasModel = canMultimodal,
                                    onToggle = { player.toggle(ctx, name) },
                                    onTranscribe = { vm.runMediaAi(name, false) },
                                    onTasks = { vm.runMediaAi(name, true) },
                                    onRemove = { if (player.playing == name) player.stop(); vm.removeAttachment(name) },
                                )
                            }
                        }
                    }
                }

                BasicTextField(
                    bodyField, ::setBody, Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 4.dp).focusRequester(bodyFocus),
                    textStyle = NoteType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    visualTransformation = bodyVisual,
                    decorationBox = { inner -> Box { if (bodyField.text.isEmpty()) Text("Tulis sesuatu", style = NoteType.body, color = muted); inner() } },
                )
                if (d.body.isBlank() && !d.inTrash) TextAction("Mulai dari templat", Modifier.padding(top = 4.dp), color = accent, onClick = vm::openTemplates)
                if (vm.canUndoAi) TextAction("Urungkan perubahan asisten", Modifier.padding(top = 4.dp), onClick = vm::undoAiChange)
                if (d.body.length > AiText.maxInput && hasModel) Muted(
                    "Catatan panjang: sebagian aksi asisten membaca ±${AiText.maxInput} huruf pertama.",
                    Modifier.padding(top = 4.dp),
                )

                Hairline(Modifier.padding(top = 20.dp))
                FlowRow(
                    Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    d.tags.forEach { t ->
                        Row(
                            Modifier.clip(SmallShape).clickable(role = Role.Button, onClickLabel = "Hapus tag $t") { vm.edit { it.copy(tags = it.tags - t) } }.heightIn(min = 40.dp).padding(end = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#$t", style = MaterialTheme.typography.labelLarge)
                            Icon(AppIcons.Close, null, Modifier.padding(start = 4.dp).size(12.dp), muted)
                        }
                    }
                    Box(Modifier.heightIn(min = 40.dp).widthIn(min = 72.dp, max = 180.dp), Alignment.CenterStart) {
                        PlainField(
                            tagInput,
                            { raw ->
                                if (',' in raw) {
                                    val parts = raw.split(',')
                                    parts.dropLast(1).forEach { if (it.isNotBlank()) vm.addTag(it) }
                                    tagInput = parts.last().take(28)
                                } else tagInput = raw.take(28)
                            },
                            "Tambah tag", style = MaterialTheme.typography.labelLarge, ime = ImeAction.Done,
                        ) { flushPendingTag() }
                    }
                    if (!d.isBlank && vm.suggestedTags.isEmpty()) TextAction(if (vm.aiBusy) "Mencari tag…" else "Sarankan tag", enabled = !vm.aiBusy, onClick = vm::suggestTags)
                }
                if (vm.suggestedTags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.suggestedTags.forEach { t -> Choice("+ $t") { vm.addTag(t) } }
                }
                if (aiState is AiState.Loading) Muted("Memuat model ke memori…", Modifier.padding(top = 8.dp))

                if (backlinks.isNotEmpty()) {
                    SectionLabel("Ditautkan dari", Modifier.padding(top = GroupGap, bottom = 2.dp))
                    backlinks.forEach { n -> SourceRow(n) { flushPendingTag(); vm.openRelated(n) } }
                }
                if (related.isNotEmpty()) {
                    SectionLabel("Terkait", Modifier.padding(top = GroupGap, bottom = 2.dp))
                    related.forEach { n -> SourceRow(n) { flushPendingTag(); vm.openRelated(n) } }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    previewImage?.let { name ->
        val (done, bmp) = rememberAttachmentBitmap(name, Attachments.MAX_IMAGE_PX)
        AlertDialog(
            onDismissRequest = { previewImage = null },
            text = {
                if (bmp != null) Image(bmp, "Gambar lampiran", Modifier.fillMaxWidth().clip(SmallShape), contentScale = ContentScale.FillWidth)
                else if (done) Muted("Gambar tidak ditemukan")
            },
            confirmButton = { TextAction("Tutup") { previewImage = null } },
            dismissButton = {
                TextAction("Hapus gambar", color = MaterialTheme.colorScheme.error) {
                    val target = name; previewImage = null; vm.removeAttachment(target)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    if (vm.templateSheet || vm.aiJob?.isTemplate == true) TemplateSheet(vm, hasModel, aiState is AiState.Loading)
    else if (vm.aiSheet || vm.aiJob != null) AiSheet(vm, hasModel, aiState is AiState.Loading) { copyText(ctx, it); vm.say("Teks disalin.") }
    if (askTemplateName) NameDialog("Simpan sebagai templat", d.title.ifBlank { d.heading }.take(40), "Nama templat", "Simpan templat", { askTemplateName = false }) { vm.saveDraftAsTemplate(it) }
    if (confirmForever) Confirm("Hapus permanen?", "Catatan ini tidak bisa dipulihkan lagi.", "Hapus permanen", destructive = true,
        onDismiss = { confirmForever = false }) { vm.deleteDraftForever() }
    if (folderDialog) FolderDialog(d.folder, vm.folders, { folderDialog = false }) { f -> vm.edit { it.copy(folder = f) } }
    vm.versions?.let { VersionsSheet(vm, it) }
    ReminderSheet(vm)
}

@Composable
private fun rememberAttachmentBitmap(name: String, maxPx: Int): Pair<Boolean, ImageBitmap?> {
    val ctx = LocalContext.current
    val state by produceState<Pair<Boolean, ImageBitmap?>>(false to null, name, maxPx) {
        value = withContext(Dispatchers.IO) {
            true to runCatching { Attachments.decodeSampled(ctx, name, maxPx)?.asImageBitmap() }.getOrNull()
        }
    }
    return state
}

@Composable
private fun AttachmentImageRow(
    name: String, exists: Boolean, hasModel: Boolean, onOpen: () -> Unit, onRead: () -> Unit, onTasks: () -> Unit, onRemove: () -> Unit,
) {
    val (done, bmp) = rememberAttachmentBitmap(name, 720)
    Column(Modifier.fillMaxWidth().clip(SmallShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (exists && bmp != null) {
            Image(
                bmp, "Gambar lampiran",
                Modifier.fillMaxWidth().heightIn(max = 160.dp).clickable(role = Role.Button, onClickLabel = "Lihat gambar penuh", onClick = onOpen),
                contentScale = ContentScale.Crop,
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!exists || (done && bmp == null)) {
                Text("Gambar tidak ditemukan", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextAction("Hapus", color = MaterialTheme.colorScheme.error, onClick = onRemove)
            } else {
                Spacer(Modifier.weight(1f))
                if (hasModel) {
                    TextAction("Ekstrak teks", onClick = onRead)
                    TextAction("Ekstrak tugas", onClick = onTasks)
                }
                IconAction(AppIcons.Close, "Hapus gambar", muted, 18.dp, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun AttachmentAudioRow(
    name: String, exists: Boolean, playing: Boolean, hasModel: Boolean, onToggle: () -> Unit, onTranscribe: () -> Unit, onTasks: () -> Unit, onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    val dur by produceState(0, name, exists) {
        value = if (!exists) 0 else withContext(Dispatchers.IO) { Attachments.audioDurationSec(ctx, name) }
    }
    Column(Modifier.fillMaxWidth().clip(SmallShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!exists) {
                Text("Rekaman tidak ditemukan", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextAction("Hapus", color = MaterialTheme.colorScheme.error, onClick = onRemove)
            } else {
                IconAction(if (playing) AppIcons.Stop else AppIcons.Play, if (playing) "Hentikan putar" else "Putar rekaman", onClick = onToggle)
                Column(Modifier.weight(1f)) {
                    Text(Attachments.formatDuration(dur), style = MaterialTheme.typography.bodyMedium)
                }
                if (hasModel) {
                    TextAction("Transkripsi", onClick = onTranscribe)
                    TextAction("Ekstrak tugas", onClick = onTasks)
                }
                IconAction(AppIcons.Close, "Hapus rekaman", muted, 18.dp, onClick = onRemove)
            }
        }
    }
}

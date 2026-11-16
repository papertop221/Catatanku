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
import com.catatanku.app.media.Attachments
import com.catatanku.app.media.AudioPlayer
import com.catatanku.app.media.VoiceRecorder
import com.catatanku.app.reminders.toMillis
import com.catatanku.app.reminders.whenLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
private fun Tool(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) = Row(
    Modifier.clip(SmallShape).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(icon, null, Modifier.size(20.dp), tint)
    Text(label, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelLarge, color = tint)
}

private fun checklistStyle(mutedColor: Color) = VisualTransformation { src ->
    val s = src.text
    if ('☑' !in s) return@VisualTransformation TransformedText(src, OffsetMapping.Identity)
    val builder = AnnotatedString.Builder(s)
    var pos = 0
    while (pos < s.length) {
        val end = s.indexOf('\n', pos).let { if (it < 0) s.length else it }
        var box = pos
        while (box < end && s[box] == ' ') box++
        if (box < end && s[box] == '☑' && box + 1 <= end) {
            builder.addStyle(SpanStyle(color = mutedColor), box, box + 1)
            if (box + 1 < end) {
                builder.addStyle(SpanStyle(color = mutedColor, textDecoration = TextDecoration.LineThrough), box + 1, end)
            }
        }
        pos = end + 1
    }
    TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
}

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
        if (d.id == 0L && d.isBlank) {
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

    val mutedCol = muted
    val bodyVisual = remember(mutedCol) { checklistStyle(mutedCol) }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { Notices(snack) },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
                if (recorder.recording) {
                    Hairline()
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = Gutter, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Mic, null, Modifier.size(18.dp), MaterialTheme.colorScheme.error)
                        Text(
                            "Merekam suara  ·  ${Attachments.formatDuration(recorder.seconds)}",
                            Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge,
                        )
                        TextAction("Batal", color = MaterialTheme.colorScheme.error) { recorder.cancel() }
                        SmallButton("Simpan rekaman", primary = true) {
                            recorder.stop()?.let(vm::attachRecordedAudio) ?: vm.say("Rekaman terlalu pendek.")
                        }
                    }
                }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tool(AppIcons.CheckBox, "Centang") { prefix(true) }
                    Tool(AppIcons.Bullets, "Poin") { prefix(false) }
                    Tool(AppIcons.Image, "Gambar") { pickImage.launch("image/*") }
                    Tool(
                        if (recorder.recording) AppIcons.Stop else AppIcons.Mic,
                        if (recorder.recording) "Selesai" else "Suara",
                        if (recorder.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    ) {
                        if (recorder.recording) {
                            recorder.stop()?.let(vm::attachRecordedAudio) ?: vm.say("Rekaman terlalu pendek.")
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            player.stop()
                            if (!recorder.start()) vm.say("Mikrofon tidak bisa dimulai.")
                        } else {
                            askAudio.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    Tool(AppIcons.Folder, "Folder") { folderDialog = true }
                    Tool(AppIcons.Pen, if (vm.aiBusy) "Bekerja…" else "Bantu tulis") { vm.aiSheet = true }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(AppIcons.Back, "Kembali ke daftar", onClick = ::closeWithFlush)
                Muted(
                    when { d.isBlank && d.id == 0L -> ""; vm.draftSaved -> "Tersimpan"; else -> "Menyimpan…" },
                    Modifier.weight(1f).padding(start = 4.dp), MaterialTheme.typography.labelMedium,
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
                            ctx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, d.title).putExtra(Intent.EXTRA_TEXT, plain), null))
                        }, enabled = !d.isBlank)
                        DropdownMenuItem({ Text("Salin teks") }, { menu = false; copyText(ctx, plain); vm.say("Teks disalin.") }, enabled = !d.isBlank)
                        DropdownMenuItem({ Text("Riwayat versi") }, { menu = false; vm.openVersions() }, enabled = d.id != 0L)
                        Hairline()
                        if (d.inTrash) {
                            DropdownMenuItem({ Text("Pulihkan") }, { menu = false; vm.restoreDraft() })
                            DropdownMenuItem({ Text("Hapus permanen", color = MaterialTheme.colorScheme.error) }, { menu = false; confirmForever = true })
                        } else {
                            DropdownMenuItem({ Text(if (d.archived) "Keluarkan dari arsip" else "Arsipkan") }, { menu = false; vm.toggleArchive() }, enabled = !d.isBlank)
                            DropdownMenuItem({ Text("Pindahkan ke Sampah") }, { menu = false; vm.trashDraft() })
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = Gutter)) {
                if (d.inTrash) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Catatan ini ada di Sampah.", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    TextAction("Pulihkan", onClick = vm::restoreDraft)
                }
                PlainField(
                    d.title, { v -> vm.edit { it.copy(title = v.replace('\n', ' ')) } }, "Judul",
                    Modifier.fillMaxWidth(), style = NoteType.title, ime = ImeAction.Next,
                    onIme = { runCatching { bodyFocus.requestFocus() } },
                )
                Muted(
                    listOfNotNull(
                        d.folder.ifBlank { null }, if (d.id != 0L) "Diubah ${shortDate(d.updated)}" else "Catatan baru",
                        remember(d.body) { AiText.wordCount(d.body) }.takeIf { it > 0 }?.let { "$it kata" },
                    ).joinToString("  ·  "),
                    Modifier.padding(top = 4.dp, bottom = 6.dp), MaterialTheme.typography.labelSmall,
                )
                if (d.hasReminder && !d.inTrash) Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f, fill = false).clip(SmallShape).clickable(role = Role.Button) { vm.openReminder() }.heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (d.remindFired) MaterialTheme.colorScheme.error else timeColor
                        Icon(AppIcons.Bell, null, Modifier.size(14.dp), tint)
                        Text(reminderText(d), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = tint)
                    }
                    if (d.remindFired) TextAction("Selesai", Modifier.padding(start = 8.dp)) { vm.finishReminder(d) }
                } else hint?.takeIf { !hintDismissed && !d.hasReminder }?.let { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Bell, null, Modifier.size(14.dp), timeColor)
                        Text("Pengingat: ${whenLabel(h.at.toMillis())}", Modifier.weight(1f, fill = false).padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = timeColor)
                        TextAction("Tambahkan") { vm.openReminder(seed = h.at.toMillis()) }
                        IconAction(AppIcons.Close, "Abaikan saran pengingat", muted, 16.dp) { hintDismissed = true }
                    }
                }

                if (d.attachments.isNotEmpty()) {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    bodyField, ::setBody, Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = 8.dp).focusRequester(bodyFocus),
                    textStyle = NoteType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    visualTransformation = bodyVisual,
                    decorationBox = { inner -> Box { if (bodyField.text.isEmpty()) Text("Mulai menulis…", style = NoteType.body, color = muted); inner() } },
                )
                if (vm.canUndoAi) TextAction("Urungkan perubahan AI", Modifier.padding(top = 4.dp), onClick = vm::undoAiChange)
                if (d.body.length > AiText.maxInput && hasModel) Muted(
                    "Catatan panjang: Ringkas, Ambil daftar tugas, dan Perbaiki ejaan memproses seluruh isi bagian demi bagian. " +
                        "Aksi AI lain hanya membaca ±${AiText.maxInput} huruf pertama.",
                    Modifier.padding(top = 8.dp),
                )

                Hairline(Modifier.padding(top = 20.dp))
                FlowRow(
                    Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    d.tags.forEach { t ->
                        Row(
                            Modifier.clip(SmallShape).clickable(role = Role.Button, onClickLabel = "Hapus tag $t") { vm.edit { it.copy(tags = it.tags - t) } }.heightIn(min = 44.dp).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#$t", style = MaterialTheme.typography.labelLarge)
                            Icon(AppIcons.Close, null, Modifier.padding(start = 4.dp).size(12.dp), muted)
                        }
                    }
                    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 72.dp, max = 180.dp).padding(horizontal = 6.dp), Alignment.CenterStart) {
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

                if (related.isNotEmpty()) {
                    SectionLabel("Catatan terkait", Modifier.padding(top = 20.dp, bottom = 2.dp))
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
                else if (done) Muted("Gambar tidak ditemukan.")
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

    if (vm.aiSheet || vm.aiJob != null) AiSheet(vm, hasModel, aiState is AiState.Loading) { copyText(ctx, it); vm.say("Teks disalin.") }
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
                Modifier.fillMaxWidth().heightIn(max = 220.dp).clickable(role = Role.Button, onClickLabel = "Lihat gambar penuh", onClick = onOpen),
                contentScale = ContentScale.Crop,
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!exists || (done && bmp == null)) {
                Text("Lampiran gambar hilang ($name)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextAction("Hapus rujukan", color = MaterialTheme.colorScheme.error, onClick = onRemove)
            } else {
                Muted("Gambar", Modifier.weight(1f))
                if (hasModel) {
                    TextAction("Baca gambar", onClick = onRead)
                    TextAction("Ambil tugas", onClick = onTasks)
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
                Text("Lampiran suara hilang ($name)", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextAction("Hapus rujukan", color = MaterialTheme.colorScheme.error, onClick = onRemove)
            } else {
                IconAction(if (playing) AppIcons.Stop else AppIcons.Play, if (playing) "Hentikan putar" else "Putar rekaman", onClick = onToggle)
                Column(Modifier.weight(1f)) {
                    Text(if (playing) "Memutar rekaman…" else "Catatan suara", style = MaterialTheme.typography.bodyMedium)
                    Muted("Durasi ${Attachments.formatDuration(dur)}")
                }
                if (hasModel) {
                    TextAction("Transkripsikan", onClick = onTranscribe)
                    TextAction("Ambil tugas", onClick = onTasks)
                }
                IconAction(AppIcons.Close, "Hapus rekaman", muted, 18.dp, onClick = onRemove)
            }
        }
    }
}

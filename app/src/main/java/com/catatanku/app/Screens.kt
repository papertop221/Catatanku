package com.catatanku.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
            Text(
                listOfNotNull(
                    if (n.archived && !n.inTrash && view != NoteView.Archive) "Arsip" else null,
                    if (view !is NoteView.Folder && n.folder.isNotBlank()) n.folder else null,
                    n.tags.take(3).joinToString(" ") { "#$it" }.ifBlank { null },
                    shortDate(n.updated),
                ).joinToString("  ·  "),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall,
                color = muted.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // Tekan lama: aksi cepat tanpa membuka catatan.
        DropdownMenu(menu, { menu = false }) {
            if (n.inTrash) DropdownMenuItem({ Text("Pulihkan") }, { menu = false; vm.restore(n) })
            else {
                if (!n.archived) DropdownMenuItem({ Text(if (n.pinned) "Lepas sematan" else "Sematkan") }, { menu = false; vm.togglePin(n) })
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
                    Modifier.padding(top = 4.dp, bottom = 14.dp), MaterialTheme.typography.labelSmall,
                )
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
                if (d.body.length > AiText.maxInput && hasModel)
                    Muted("AI hanya membaca ±${AiText.maxInput} karakter pertama catatan ini.", Modifier.padding(top = 8.dp))

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
                } else if (vm.askAnswer.isEmpty() && !vm.askRunning) Muted(
                    "AI menjawab hanya dari catatanmu dan menunjukkan catatan mana yang dipakai.",
                    Modifier.padding(top = 12.dp), MaterialTheme.typography.bodyMedium,
                )
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
                // ---------- Model AI ----------
                SectionLabel("Model AI", Modifier.padding(start = 4.dp, top = 8.dp))
                val status = when (val s = aiState) {
                    AiState.NoModel -> "Belum ada model. Unduh sekali; sesudahnya AI berjalan tanpa internet."
                    is AiState.Importing -> "Menyalin berkas… ${s.percent}%"
                    AiState.Idle -> "${vm.ai.activeName} aktif."
                    AiState.Loading -> "Memuat ${vm.ai.activeName} ke memori…"
                    AiState.Ready -> "${vm.ai.activeName} aktif dan sudah di memori."
                    is AiState.Failed -> s.message
                }
                Text(
                    status + if (ram > 0) "  RAM HP ini ±$ram GB." else "",
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    color = if (aiState is AiState.Failed) MaterialTheme.colorScheme.error else muted,
                )
                (aiState as? AiState.Importing)?.let { LinearProgressIndicator({ it.percent / 100f }, Modifier.fillMaxWidth(), color = aiColor) }
                if (aiState is AiState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = aiColor)

                Group {
                    ModelCatalog.all.forEachIndexed { i, spec ->
                        val installed = revision.let { vm.ai.isInstalled(spec.id) }
                        val dl = download?.takeIf { it.id == spec.id }
                        val heavy = ram in 1 until spec.minRamGb
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(spec.name, style = MaterialTheme.typography.titleMedium)
                                when {
                                    active == spec.id && installed -> Badge("Aktif", ai = true)
                                    installed -> Badge("Terpasang")
                                    spec == recommended && ram > 0 -> Badge("Disarankan")
                                }
                                Spacer(Modifier.weight(1f))
                                when {
                                    dl != null -> SmallButton("Batal") { vm.ai.cancelDownload() }
                                    installed && active != spec.id -> SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(spec.id) }
                                    !installed -> SmallButton("Unduh", primary = spec == recommended && ram > 0, enabled = download == null) { confirmDownload = spec }
                                }
                            }
                            Muted(
                                "${spec.sizeLabel}  ·  RAM ${spec.recRamGb} GB+" + if (heavy) "  ·  terlalu berat untuk HP ini" else "",
                                Modifier.padding(top = 2.dp), MaterialTheme.typography.labelSmall,
                            )
                            Muted(spec.blurb, Modifier.padding(top = 6.dp))
                            if (dl != null) {
                                LinearProgressIndicator({ dl.percent / 100f }, Modifier.fillMaxWidth().padding(top = 10.dp), color = aiColor)
                                Muted("${dl.percent}%  ·  ${dl.note}", Modifier.padding(top = 4.dp), MaterialTheme.typography.labelSmall)
                            }
                            if (installed) Text(
                                "Hapus", Modifier.padding(top = 6.dp).clip(MaterialTheme.shapes.small).clickable(enabled = !busy) { confirmDelete = spec.id }.padding(vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge, color = muted,
                            )
                        }
                    }
                }
                if (download != null) Muted("Unduhan berjalan di latar; aplikasi boleh ditutup.", Modifier.padding(horizontal = 4.dp))

                val importInstalled = revision.let { vm.ai.isInstalled(ModelCatalog.IMPORT_ID) }
                Group {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Dari berkas", style = MaterialTheme.typography.titleMedium)
                            if (importInstalled) Badge(if (active == ModelCatalog.IMPORT_ID) "Aktif" else "Terpasang", ai = active == ModelCatalog.IMPORT_ID)
                            Spacer(Modifier.weight(1f))
                            if (importInstalled && active != ModelCatalog.IMPORT_ID) SmallButton("Pakai", primary = true, enabled = !busy) { vm.activateModel(ModelCatalog.IMPORT_ID) }
                            else SmallButton(if (importInstalled) "Ganti" else "Pilih berkas", enabled = !busy) { pickModel.launch(arrayOf("*/*")) }
                        }
                        Muted("Sudah punya berkas .litertlm di HP? Impor saja, tanpa mengunduh ulang.", Modifier.padding(top = 6.dp))
                        if (importInstalled) Text(
                            "Hapus", Modifier.padding(top = 6.dp).clip(MaterialTheme.shapes.small).clickable(enabled = !busy) { confirmDelete = ModelCatalog.IMPORT_ID }.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge, color = muted,
                        )
                    }
                }

                if (aiState !is AiState.NoModel && aiState !is AiState.Importing) Group {
                    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Akselerasi GPU", style = MaterialTheme.typography.bodyLarge)
                            Muted("Matikan bila model gagal dimuat.")
                        }
                        Switch(gpu, { gpu = it; vm.setGpu(it) }, enabled = !busy)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Memori", style = MaterialTheme.typography.bodyLarge)
                            Muted(if (aiState is AiState.Ready) "Model sedang memakai RAM." else "Model dimuat saat pertama dipakai.")
                        }
                        if (aiState is AiState.Ready) SmallButton("Lepas", onClick = vm::unloadModel)
                        else SmallButton("Uji muat", enabled = !busy, onClick = vm::loadModel)
                    }
                }

                // ---------- Cadangan ----------
                SectionLabel("Cadangan", Modifier.padding(start = 4.dp, top = 16.dp))
                Group {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Muted("Catatan hanya ada di HP ini. Ekspor berkala.", Modifier.weight(1f))
                        SmallButton("Impor") { importFrom.launch(arrayOf("application/json", "*/*")) }
                        SmallButton("Ekspor", primary = true) { exportTo.launch("catatanku-cadangan.json") }
                    }
                }

                // ---------- Privasi ----------
                SectionLabel("Privasi", Modifier.padding(start = 4.dp, top = 16.dp))
                Muted(
                    "Catatan dan pemrosesan AI tetap di perangkat. Internet hanya dipakai saat kamu menekan Unduh, " +
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

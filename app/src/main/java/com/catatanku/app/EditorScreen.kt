package com.catatanku.app

import android.content.Intent
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Editor: isi catatan di depan. Tidak ada tombol simpan; alat menempel di atas papan ketik dengan ikon berlabel.

/** Alat di bilah bawah editor: ikon selalu ditemani kata. */
@Composable
private fun Tool(icon: ImageVector, label: String, onClick: () -> Unit) = Row(
    Modifier.clip(SmallShape).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(icon, null, Modifier.size(20.dp))
    Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
}

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

    // Simpan otomatis: setelah jeda mengetik, dan saat aplikasi ditinggalkan.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.saveDraft() }
    LaunchedEffect(d) { delay(700); vm.saveDraft() }
    // Catatan terkait dan pengenal waktu dihitung di thread latar, setelah jeda mengetik.
    LaunchedEffect(d.title, d.body, d.tags, vm.index) { delay(500); related = withContext(Dispatchers.Default) { vm.relatedTo(d) } }
    LaunchedEffect(d.title, d.body, d.remindAt) { delay(700); hint = withContext(Dispatchers.Default) { vm.reminderHint(d) } }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { Notices(snack) },
        // Alat selalu menempel di atas papan ketik, jadi terjangkau tanpa menggulir.
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
                Hairline()
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tool(AppIcons.CheckBox, "Centang") { prefix(true) }
                    Tool(AppIcons.Bullets, "Poin") { prefix(false) }
                    Tool(AppIcons.Folder, "Folder") { folderDialog = true }
                    Spacer(Modifier.weight(1f))
                    Tool(AppIcons.Pen, if (vm.aiBusy) "Bekerja…" else "Bantu tulis") { vm.aiSheet = true }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---- Bilah atas ----
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(AppIcons.Back, "Kembali ke daftar", onClick = vm::closeEditor)
                // Penanda simpan otomatis: pengguna tidak perlu menebak apakah tulisannya sudah aman.
                Muted(
                    when { d.isBlank && d.id == 0L -> ""; vm.draftSaved -> "Tersimpan"; else -> "Menyimpan…" },
                    Modifier.weight(1f).padding(start = 4.dp), MaterialTheme.typography.labelMedium,
                )
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
                PlainField(d.title, { v -> vm.edit { it.copy(title = v) } }, "Judul", Modifier.fillMaxWidth(), style = NoteType.title, ime = ImeAction.Next)
                // Satu baris meta: folder · waktu ubah · jumlah kata
                Muted(
                    listOfNotNull(
                        d.folder.ifBlank { null }, if (d.id != 0L) "Diubah ${shortDate(d.updated)}" else "Catatan baru",
                        remember(d.body) { AiText.wordCount(d.body) }.takeIf { it > 0 }?.let { "$it kata" },
                    ).joinToString("  ·  "),
                    Modifier.padding(top = 4.dp, bottom = 6.dp), MaterialTheme.typography.labelSmall,
                )
                // ---- Pengingat: yang sudah disetel, atau tawaran dari waktu yang disebut di catatan. Satu baris teks biasa. ----
                if (d.hasReminder && !d.inTrash) Row(
                    Modifier.clip(SmallShape).clickable(role = Role.Button) { vm.openReminder() }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (d.remindFired) MaterialTheme.colorScheme.error else timeColor
                    Icon(AppIcons.Bell, null, Modifier.size(14.dp), tint)
                    Text(reminderText(d), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = tint)
                } else hint?.takeIf { !hintDismissed && !d.hasReminder }?.let { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Bell, null, Modifier.size(14.dp), timeColor)
                        Text("Pengingat: ${whenLabel(h.at.toMillis())}", Modifier.weight(1f, fill = false).padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = timeColor)
                        TextAction("Tambahkan") { vm.openReminder(seed = h.at.toMillis()) }
                        IconAction(AppIcons.Close, "Abaikan saran pengingat", muted, 16.dp) { hintDismissed = true }
                    }
                }
                BasicTextField(
                    bodyField, ::setBody, Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = 8.dp),
                    textStyle = NoteType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner -> Box { if (bodyField.text.isEmpty()) Text("Mulai menulis…", style = NoteType.body, color = muted); inner() } },
                )
                if (vm.canUndoAi) TextAction("Urungkan perubahan AI", Modifier.padding(top = 4.dp), onClick = vm::undoAiChange)
                if (d.body.length > AiText.maxInput && hasModel) Muted(
                    "Catatan panjang: Ringkas, Ambil daftar tugas, dan Perbaiki ejaan memproses seluruh isi bagian demi bagian. " +
                        "Aksi AI lain hanya membaca ±${AiText.maxInput} huruf pertama.",
                    Modifier.padding(top = 8.dp),
                )

                // ---- Tag ----
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
                        PlainField(tagInput, { tagInput = it.take(28) }, "Tambah tag", style = MaterialTheme.typography.labelLarge, ime = ImeAction.Done) { vm.addTag(tagInput); tagInput = "" }
                    }
                    if (!d.isBlank && vm.suggestedTags.isEmpty()) TextAction(if (vm.aiBusy) "Mencari tag…" else "Sarankan tag", enabled = !vm.aiBusy, onClick = vm::suggestTags)
                }
                if (vm.suggestedTags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.suggestedTags.forEach { t -> Choice("+ $t") { vm.addTag(t) } }
                }
                if (aiState is AiState.Loading) Muted("Memuat model ke memori…", Modifier.padding(top = 8.dp))

                if (related.isNotEmpty()) {
                    SectionLabel("Catatan terkait", Modifier.padding(top = 20.dp, bottom = 2.dp))
                    related.forEach { n -> SourceRow(n) { vm.openRelated(n) } }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (vm.aiSheet || vm.aiJob != null) AiSheet(vm, hasModel, aiState is AiState.Loading) { copyText(ctx, it); vm.say("Teks disalin.") }
    if (confirmForever) Confirm("Hapus permanen?", "Catatan ini tidak bisa dipulihkan lagi.", "Hapus permanen", destructive = true,
        onDismiss = { confirmForever = false }) { vm.deleteDraftForever() }
    if (folderDialog) FolderDialog(d.folder, vm.folders, { folderDialog = false }) { f -> vm.edit { it.copy(folder = f) } }
    vm.versions?.let { VersionsSheet(vm, it) }
    ReminderSheet(vm)
}

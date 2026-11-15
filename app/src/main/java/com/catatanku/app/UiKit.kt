package com.catatanku.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Bagian antarmuka yang dipakai bersama semua layar. Aturan desainnya ada di docs/DESAIN.md.

internal val ID: Locale = Locale.forLanguageTag("id-ID")
internal val Gutter = 20.dp

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH.mm", ID)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", ID)
private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", ID)

/** Tanggal sependek mungkin: jam bila hari ini, "15 Nov" bila tahun ini, lengkap bila lebih lama. */
internal fun shortDate(t: Long, today: LocalDate = LocalDate.now()): String {
    val at = t.toLocal()
    return when {
        at.year != today.year -> FULL.format(at)
        at.toLocalDate() != today -> DAY.format(at)
        else -> TIME.format(at)
    }
}

internal val muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
/** Amber: selalu berarti waktu (pengingat, tenggat). */
internal val timeColor @Composable get() = MaterialTheme.colorScheme.tertiary

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall) =
    Text(text, modifier, color = muted, style = style)

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = muted, style = MaterialTheme.typography.labelMedium)

/** Garis pemisah tipis; pengganti kartu dan bayangan. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

/** Tombol ikon dengan sasaran sentuh 48dp. [label] dibacakan pembaca layar. */
@Composable
internal fun IconAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 22.dp, enabled: Boolean = true, onClick: () -> Unit) =
    IconButton(onClick, enabled = enabled) { Icon(icon, label, Modifier.size(size), if (enabled) tint else tint.copy(alpha = 0.38f)) }

/** Aksi teks: tombol tanpa bingkai untuk aksi sekunder. */
@Composable
internal fun TextAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) =
    TextButton(onClick, modifier, enabled = enabled, shape = SmallShape, colors = ButtonDefaults.textButtonColors(contentColor = color), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

/** Tombol utama: satu per layar atau lembar. Labelnya kata kerja + objek ("Simpan pengingat"). */
@Composable
internal fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = SmallShape) { Text(text) }

@Composable
internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = SmallShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text(text) }

/** Tombol ringkas di dalam baris pengaturan. */
@Composable
internal fun SmallButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    if (primary) Button(onClick, Modifier.height(40.dp), enabled = enabled, shape = SmallShape, contentPadding = pad) { Text(text) }
    else OutlinedButton(onClick, Modifier.height(40.dp), enabled = enabled, shape = SmallShape, contentPadding = pad, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text(text) }
}

/** Chip pilihan: terisi tinta bila dipilih. */
@Composable
internal fun Choice(text: String, selected: Boolean = false, onClick: () -> Unit) = Box(
    Modifier.heightIn(min = 40.dp).clip(SmallShape)
        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
    Alignment.Center,
) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
}

/** Label kecil berbingkai untuk status ("Aktif", "Utama"). */
@Composable
internal fun Badge(text: String, strong: Boolean = false) = Text(
    text,
    Modifier.clip(RoundedShape4).then(
        if (strong) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedShape4)
    ).padding(horizontal = 6.dp, vertical = 2.dp),
    style = MaterialTheme.typography.labelSmall,
    color = if (strong) MaterialTheme.colorScheme.onPrimary else muted,
)

private val RoundedShape4 = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)

/** Kolom teks tanpa bingkai dengan placeholder; dasar untuk kolom cari, judul, dan tag. */
@Composable
internal fun PlainField(
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
    decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = muted, maxLines = 1); inner() } },
)

@Composable
internal fun Confirm(title: String, text: String, ok: String, destructive: Boolean = false, onDismiss: () -> Unit, onOk: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, style = MaterialTheme.typography.titleLarge) }, text = { Text(text) },
    confirmButton = { TextAction(ok, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) { onDismiss(); onOk() } },
    dismissButton = { TextAction("Batal", onClick = onDismiss) },
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

/** Menu datar: garis tipis sebagai tepi, tanpa bayangan. */
@Composable
internal fun Menu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = DropdownMenu(
    expanded, onDismiss,
    shape = SmallShape, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 0.dp, shadowElevation = 0.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    content = content,
)

/** Lembar bawah dengan judul; dasar semua lembar (pengingat, bantu tulis, rencana, riwayat). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(title: String, onDismiss: () -> Unit, icon: ImageVector? = null, iconTint: Color = Color.Unspecified, content: @Composable ColumnScope.() -> Unit) = ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 0.dp,
) {
    Column(Modifier.padding(horizontal = Gutter).padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.padding(end = 8.dp).size(18.dp), if (iconTint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else iconTint)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}

/** Pesan singkat di dasar layar, bergaya sama di semua layar. */
@Composable
internal fun Notices(state: SnackbarHostState) = SnackbarHost(state) { data ->
    Snackbar(data, shape = SmallShape, actionColor = MaterialTheme.colorScheme.inversePrimary)
}

internal fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Catatan", text))
}

/** Keterangan pengingat sebuah catatan: waktu, pengulangan, dan gaya alarm. */
internal fun reminderText(n: Note): String = listOfNotNull(
    (if (n.remindFired) "Terlewat · " else "") + whenLabel(n.remindAt),
    n.remindRepeat.takeIf { it != Repeat.None }?.label?.lowercase(),
    if (n.remindAlarm) "alarm" else null,
).joinToString("  ·  ")

/** Satu catatan sebagai rujukan (catatan terkait, sumber jawaban). */
@Composable
internal fun SourceRow(n: Note, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clip(SmallShape).clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 8.dp)) {
        val first = n.body.take(240).trim().lineSequence().firstOrNull().orEmpty()
        Text(n.title.ifBlank { first }, style = NoteType.rowSnippet, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (n.title.isNotBlank() && first.isNotBlank()) Text(first.take(120), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

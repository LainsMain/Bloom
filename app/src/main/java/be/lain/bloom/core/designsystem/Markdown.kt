package be.lain.bloom.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Parsed Markdown blocks (the safe subset the coach uses). No links, images or HTML. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, val align: List<TextAlign>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private val bulletRe = Regex("^(\\s*)([-*•+]|\\d+[.)])\\s+(.*)")
private val separatorCell = Regex("^\\s*:?-{2,}:?\\s*$")

private fun cells(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    return t.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
}

private fun isTableLine(l: String) = l.trimStart().startsWith("|") && l.count { it == '|' } >= 2
private fun isSeparator(l: String) = isTableLine(l) && cells(l).all { separatorCell.matches(it) }

/** Splits text into blocks. Soft-wrapped paragraph lines are joined; tables need a header and a ---| row. */
fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r", "").trim().lines()
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.isEmpty() -> flush()
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                out += MdBlock.Code(code.toString().trimEnd())
            }
            isTableLine(line) && i + 1 < lines.size && isSeparator(lines[i + 1]) -> {
                flush()
                val header = cells(line)
                val align = cells(lines[i + 1]).map {
                    val c = it.trim()
                    when {
                        c.startsWith(":") && c.endsWith(":") -> TextAlign.Center
                        c.endsWith(":") -> TextAlign.End
                        else -> TextAlign.Start
                    }
                }
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && isTableLine(lines[i])) { rows += cells(lines[i]); i++ }
                out += MdBlock.Table(header, rows, align)
                continue
            }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out += MdBlock.Rule }
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 4)
                out += MdBlock.Heading(level, t.drop(level).trim())
            }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.removePrefix(">").trim()) }
            bulletRe.matches(line) -> {
                flush()
                val m = bulletRe.find(line)!!
                val marker = m.groupValues[2].let { if (it.first().isDigit()) it else "•" }
                out += MdBlock.Bullet(marker, m.groupValues[3], (m.groupValues[1].length / 2).coerceAtMost(3))
            }
            // A continuation line of the previous bullet.
            line.startsWith("  ") && out.lastOrNull() is MdBlock.Bullet && para.isEmpty() -> {
                val b = out.removeAt(out.lastIndex) as MdBlock.Bullet
                out += b.copy(text = b.text + " " + t)
            }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
        i++
    }
    flush()
    return out
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val blocks = remember(text) { parseMarkdown(text) }
    val body = MaterialTheme.typography.bodyLarge
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    inline(b.text, codeBg),
                    style = when (b.level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall },
                    color = color, modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = 4.dp + 16.dp * b.indent)) {
                    Text(b.marker, style = body, color = color, modifier = Modifier.width(if (b.marker.length > 2) 30.dp else 22.dp))
                    Text(inline(b.text, codeBg), style = body, color = color)
                }
                is MdBlock.Table -> MarkdownTable(b, color, codeBg)
                is MdBlock.Quote -> {
                    val bar = MaterialTheme.colorScheme.outlineVariant
                    Text(
                        inline(b.text, codeBg), style = body.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                            .drawBehind { drawRect(bar, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                            .padding(start = 12.dp),
                    )
                }
                is MdBlock.Code -> Surface(color = codeBg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text(b.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = color,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
                }
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                is MdBlock.Paragraph -> Text(inline(b.text, codeBg), style = body, color = color)
            }
        }
    }
}

/**
 * A real grid: columns size to their content (capped so long cells wrap), every cell in a row
 * gets the row's height, the header is shaded, and wide tables scroll sideways.
 */
@Composable
private fun MarkdownTable(t: MdBlock.Table, color: Color, codeBg: Color) {
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    val all = listOf(t.header) + t.rows
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val line = MaterialTheme.colorScheme.outlineVariant
    val shape = MaterialTheme.shapes.medium
    val cell = MaterialTheme.typography.bodyMedium
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Layout(
            modifier = Modifier.clip(shape).border(1.dp, line, shape),
            content = {
                all.forEachIndexed { r, row ->
                    for (c in 0 until cols) {
                        val last = r == all.lastIndex
                        Box(
                            Modifier
                                .then(if (r == 0) Modifier.background(headerBg) else Modifier)
                                .drawBehind {
                                    if (!last) drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                                    if (c < cols - 1) drawLine(line.copy(alpha = 0.5f), Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            Text(
                                inline(row.getOrElse(c) { "" }, codeBg),
                                style = if (r == 0) cell.copy(fontWeight = FontWeight.SemiBold) else cell,
                                color = color,
                                textAlign = t.align.getOrElse(c) { TextAlign.Start },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val cap = 200.dp.roundToPx()
            val minCol = 48.dp.roundToPx()
            val widths = IntArray(cols) { c ->
                all.indices.maxOf { r -> measurables[r * cols + c].maxIntrinsicWidth(Constraints.Infinity) }.coerceIn(minCol, cap)
            }
            val heights = IntArray(all.size) { r -> (0 until cols).maxOf { c -> measurables[r * cols + c].minIntrinsicHeight(widths[c]) } }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % cols], heights[i / cols])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in all.indices) {
                    var x = 0
                    for (c in 0 until cols) { placeables[r * cols + c].place(x, y); x += widths[c] }
                    y += heights[r]
                }
            }
        }
    }
}

private fun inline(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                val end = s.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> {
                val end = s.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            // Italic only when the marker starts a word, so snake_case and 2*3 stay as typed.
            (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' && (i == 0 || !s[i - 1].isLetterOrDigit()) &&
                s.indexOf(s[i], i + 1).let { e -> e > 0 && (e + 1 >= s.length || !s[e + 1].isLetterOrDigit()) } -> {
                val end = s.indexOf(s[i], i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}

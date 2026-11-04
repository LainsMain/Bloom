package be.lain.bloom.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Small, safe Markdown subset: headings, bullet/numbered lists, bold, italic, inline code. No links or HTML. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.trim().lines().fold(mutableListOf<String>()) { acc, line ->
            // Merge soft-wrapped paragraph lines.
            if (acc.isNotEmpty() && line.isNotBlank() && acc.last().isNotBlank() && !isBlock(line) && !isBlock(acc.last())) {
                acc[acc.lastIndex] = acc.last() + " " + line.trim()
            } else acc += line
            acc
        }.filter { it.isNotBlank() }.forEach { raw ->
            val line = raw.trimEnd()
            val bullet = Regex("^\\s*([-*•]|\\d+[.)])\\s+(.*)").find(line)
            when {
                line.startsWith("### ") -> Text(inline(line.removePrefix("### "), codeBg), style = MaterialTheme.typography.titleSmall, color = color)
                line.startsWith("## ") -> Text(inline(line.removePrefix("## "), codeBg), style = MaterialTheme.typography.titleMedium, color = color)
                line.startsWith("# ") -> Text(inline(line.removePrefix("# "), codeBg), style = MaterialTheme.typography.titleLarge, color = color)
                bullet != null -> Row(Modifier.padding(start = 4.dp)) {
                    val marker = bullet.groupValues[1].let { if (it.first().isDigit()) it else "•" }
                    Text(marker, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.width(22.dp))
                    Text(inline(bullet.groupValues[2], codeBg), style = MaterialTheme.typography.bodyLarge, color = color)
                }
                else -> Text(inline(line, codeBg), style = MaterialTheme.typography.bodyLarge, color = color)
            }
        }
    }
}

private fun isBlock(l: String) = l.trimStart().let { it.startsWith("#") || Regex("^([-*•]|\\d+[.)])\\s").containsMatchIn(it) }

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
            (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' && s.indexOf(s[i], i + 1) > 0 -> {
                val end = s.indexOf(s[i], i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}

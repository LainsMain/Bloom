package be.lain.bloom

import androidx.compose.ui.text.style.TextAlign
import be.lain.bloom.core.designsystem.MdBlock
import be.lain.bloom.core.designsystem.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun parsesTablesWithHeaderAndAlignment() {
        val md = """
            Your week:

            | Day | kcal | Protein |
            |-----|-----:|:-------:|
            | Mon | 1,650 | 110 g |
            | Tue | 1,720 | 95 g |

            Nice work.
        """.trimIndent()
        val b = parseMarkdown(md)
        assertEquals(3, b.size)
        val t = b[1] as MdBlock.Table
        assertEquals(listOf("Day", "kcal", "Protein"), t.header)
        assertEquals(listOf(listOf("Mon", "1,650", "110 g"), listOf("Tue", "1,720", "95 g")), t.rows)
        assertEquals(listOf(TextAlign.Start, TextAlign.End, TextAlign.Center), t.align)
        assertEquals(MdBlock.Paragraph("Nice work."), b[2])
    }

    @Test fun pipesWithoutSeparatorStayText() {
        val b = parseMarkdown("a | b | c\n| just text |")
        assertTrue(b.none { it is MdBlock.Table })
    }

    @Test fun headingsBulletsParagraphsAndCode() {
        val b = parseMarkdown("## Focus\n- Eat protein\n  at every meal\n1. Walk\n\nSoft\nwrapped\n```\ncode | here\n```\n---")
        assertEquals(MdBlock.Heading(2, "Focus"), b[0])
        assertEquals(MdBlock.Bullet("•", "Eat protein at every meal", 0), b[1])
        assertEquals(MdBlock.Bullet("1.", "Walk", 0), b[2])
        assertEquals(MdBlock.Paragraph("Soft wrapped"), b[3])
        assertEquals(MdBlock.Code("code | here"), b[4])
        assertEquals(MdBlock.Rule, b[5])
    }
}

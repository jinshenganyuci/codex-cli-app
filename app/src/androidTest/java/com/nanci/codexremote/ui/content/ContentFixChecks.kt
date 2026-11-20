package com.nanci.codexremote.ui.content

import com.nanci.codexremote.core.approvalDecisionSmokeChecks
import com.nanci.codexremote.core.UserInputs
import com.nanci.codexremote.ui.theme.nativeColors
import androidx.compose.ui.text.font.FontFamily

/** Calls the production parsers; safe for instrumentation without network, files or user chats. */
object ContentFixChecks {
    fun run(): List<String> = buildList {
        addAll(markdownInlineSmokeChecks())
        addAll(approvalDecisionSmokeChecks())
        val imagePath = "/home/codex/attachments/chart %20#?.SVG"
        val imageMention = UserInputs.remoteFile("图表.SVG", imagePath)
        val attachmentContent = UserInputs.prepareForSend(listOf(imageMention))
        if (userAttachmentPath(imageMention, attachmentContent) != imagePath || !hasImageFileExtension(imagePath))
            add("uploaded image attachment preserves literal server path")
        val otherMention = UserInputs.remoteFile("另一份附件", imagePath)
        if (userAttachmentPath(otherMention, attachmentContent) != null ||
            userAttachmentPath(imageMention, listOf(imageMention)) != null)
            add("only explicitly paired attachment metadata opens a server file")
        val patch = "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n---counter\n+++counter\n"
        val inline = parseChangedFilePatch("counter.txt", patch)
        val sheet = parseChangedFiles(patch)?.files?.singleOrNull()
        if (inline.additions != 1 || inline.deletions != 1 || !inline.countsKnown) add("inline diff prefix counts")
        if (inline.rows.none { it.kind == ChangedLineKind.Addition && it.text == "++counter" } ||
            inline.rows.none { it.kind == ChangedLineKind.Deletion && it.text == "--counter" }) add("inline diff prefix row styles")
        if (inline != sheet) add("inline and sheet diff models diverged")
        val hunk = parseChangedFilePatch("目录/counter one.txt", "@@ -1 +1 @@\n---counter\n+++counter\n")
        if (hunk.path != "目录/counter one.txt" || hunk.additions != 1 || hunk.deletions != 1) add("hunk-only item path/counts")
        if (parseChangedFilePatch("binary.png", "Binary files a/binary.png and b/binary.png differ").countsKnown) add("binary unknown counts")
        if (parseChangedFilePatch("broken", "@@ -999999999999 +1 @@\n-x\n+y").countsKnown) add("malformed hunk fallback")
        if (parseChangedFilePatch("broken", "@@ -1,999999999999 +1 @@\n-x\n+y").countsKnown) add("overflow hunk count fallback")

        if (splitTableRow("| A\\|B | C |") != listOf("A|B", "C")) add("escaped table pipe")
        if (splitTableRow("| `A|B` | C |") != listOf("`A|B`", "C")) add("table inline-code pipe")
        if (splitTableRow("| ``A`|B`` | C |") != listOf("``A`|B``", "C")) add("table variable code delimiter")
        if (splitTableRow("A | B") != listOf("A", "B")) add("table no outer borders")
        if (splitTableRow("| A | |") != listOf("A", "")) add("table empty last column")
        if (splitTableRow("| A\\\\| B |") != listOf("A\\\\", "B")) add("table escaped-backslash boundary")
        if (inlineMarkdown("``a`|b``").text != "a`|b") add("variable-backtick code text")
        // ANDROID_THEME_SPANS_BEGIN
        for (dark in listOf(false, true)) for (highContrast in listOf(false, true)) {
            val colors = nativeColors(dark, highContrast)
            val annotated = inlineMarkdown("`inline code` [link](https://example.invalid)", colors.accent, colors.field)
            val codeStyle = annotated.spanStyles.firstOrNull { it.item.fontFamily == FontFamily.Monospace }?.item
            if (codeStyle?.background != colors.field) add("inline code theme background dark=$dark highContrast=$highContrast")
            val linkRange = annotated.getStringAnnotations("url", 0, annotated.length).singleOrNull()
            val linkStyle = linkRange?.let { range -> annotated.spanStyles.firstOrNull { it.start == range.start && it.end == range.end }?.item }
            if (linkStyle?.color != colors.accent) add("inline link theme color dark=$dark highContrast=$highContrast")
        }
        // ANDROID_THEME_SPANS_END

        val document = markdownReferenceDocument("![猫][Cat] and ![CAT][] and ![cat]\n\n[cat]: </home/codex/cat one.svg> \"title\"\n[CAT]: /later.png")
        val spans = markdownImageSpans(document.body, document.references)
        if (spans.size != 3 || spans.any { it.destination != "/home/codex/cat one.svg" }) add("full collapsed shortcut image references")
        if (document.body.contains("[cat]:") || document.body.contains("[CAT]:")) add("reference definition visibility")
        if (markdownImageSpans("`![cat]` and ``![cat][cat]``", document.references).isNotEmpty()) add("reference code span guard")
        if (markdownImageSpans("`unfinished ![cat]", document.references).isNotEmpty()) add("reference streaming code guard")
        if (markdownImageSpans("\\![cat]", document.references).isNotEmpty()) add("reference escaped image guard")
        if (markdownImageSpans("![missing]", document.references).isNotEmpty()) add("undefined image reference")
        val fence = markdownReferenceDocument("````md\n[bad]: /bad.png\n```\n[also bad]: /also.png\n````\n[good]: /good.png\n    [indented]: /hidden.png")
        if (fence.references != mapOf("good" to "/good.png")) add("reference fenced/indented definition guard")
        val normalized = markdownReferenceDocument("[ Cat   Picture ]: /cat.png 'title'")
        if (markdownImageSpans("![alt][cat picture]", normalized.references).singleOrNull()?.destination != "/cat.png") add("reference label normalization")
        val parenthesized = markdownReferenceDocument("[cat]: /cat(2).png (title)")
        if (markdownImageSpans("![cat]", parenthesized.references).singleOrNull()?.destination != "/cat(2).png") add("reference balanced destination")
    }
}

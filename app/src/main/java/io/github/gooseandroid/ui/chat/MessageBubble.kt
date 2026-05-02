package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// User Bubble (right-aligned, primary color)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(
    message: ChatMessage,
    onRetry: (String) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 4.dp
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            MessageActionsPopup(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                messageContent = message.content,
                showRetry = true,
                onRetry = { onRetry(message.content) },
                onDelete = { onDelete(message) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Assistant Bubble (left-aligned, markdown rendered, inline images)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantBubble(
    message: ChatMessage,
    onDelete: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 16.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Render inline images if present
                    val imageUrls = extractImageUrls(message.content)
                    val textContent = removeImageMarkdown(message.content)

                    if (textContent.isNotBlank()) {
                        MarkdownContent(
                            content = textContent,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Display extracted images
                    imageUrls.forEach { url ->
                        Spacer(modifier = Modifier.height(8.dp))
                        InlineImage(url = url)
                    }
                }
            }

            MessageActionsPopup(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                messageContent = message.content,
                showRetry = false,
                onRetry = {},
                onDelete = { onDelete(message) }
            )
        }

        // Tool calls attached to this message
        if (message.toolCalls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            message.toolCalls.forEach { toolCall ->
                ToolCallCard(toolCall = toolCall)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// System Bubble (centered, muted)
// ---------------------------------------------------------------------------

@Composable
fun SystemBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Streaming Bubble — displays content directly (streaming from API provides
// the progressive effect; no character-by-character animation needed)
// ---------------------------------------------------------------------------

@Composable
fun StreamingBubble(
    content: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp
            ),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .animateContentSize()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                MarkdownContent(
                    content = content,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown Content Renderer
// ---------------------------------------------------------------------------

@Composable
fun MarkdownContent(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val blocks = parseMarkdownBlocks(content)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    CodeBlockView(
                        code = block.code,
                        language = block.language
                    )
                }
                is MarkdownBlock.Header -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = block.text,
                        style = style,
                        color = textColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = textColor.copy(alpha = 0.3f)
                    )
                }
                is MarkdownBlock.Blockquote -> {
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(IntrinsicSize.Min)
                                .background(textColor.copy(alpha = 0.4f))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        InlineMarkdownText(
                            text = block.text,
                            textColor = textColor.copy(alpha = 0.8f)
                        )
                    }
                }
                is MarkdownBlock.ListItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = block.bullet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.width(20.dp)
                        )
                        InlineMarkdownText(
                            text = block.text,
                            textColor = textColor
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    InlineMarkdownText(
                        text = block.text,
                        textColor = textColor
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Inline Markdown Text (bold, italic, code, links)
// ---------------------------------------------------------------------------

@Composable
private fun InlineMarkdownText(
    text: String,
    textColor: Color
) {
    val codeBackground = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary

    val annotated = remember(text, textColor) {
        buildInlineAnnotatedString(text, textColor, codeBackground, linkColor)
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor
    )
}

private fun buildInlineAnnotatedString(
    text: String,
    textColor: Color,
    codeBackground: Color,
    linkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold: **text**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bold: __text__
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Inline code: `code`
                text[i] == '`' && !text.startsWith("```", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                                fontSize = 13.sp
                            )
                        ) {
                            append(" ")
                            append(text.substring(i + 1, end))
                            append(" ")
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Link: [text](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            withStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                append(linkText)
                            }
                            i = closeParen + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: *text*
                text[i] == '*' && !text.startsWith("**", i) -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: _text_
                text[i] == '_' && !text.startsWith("__", i) -> {
                    val end = text.indexOf('_', i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Code Block View (with syntax highlighting and copy button)
// ---------------------------------------------------------------------------

@Composable
private fun CodeBlockView(
    code: String,
    language: String
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.05f)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp),
                        tint = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            SelectionContainer {
                Text(
                    text = highlightSyntax(code, language),
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                )
            }
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
}

@Composable
private fun highlightSyntax(code: String, language: String): AnnotatedString {
    val keywordColor = MaterialTheme.colorScheme.primary
    val stringColor = MaterialTheme.colorScheme.tertiary
    val commentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val numberColor = MaterialTheme.colorScheme.error
    val defaultColor = MaterialTheme.colorScheme.onSurface

    return remember(code, language) {
        buildAnnotatedString {
            val keywords = setOf(
                "fun", "val", "var", "class", "object", "interface", "if", "else",
                "when", "for", "while", "return", "import", "package", "private",
                "public", "protected", "internal", "override", "suspend", "data",
                "sealed", "enum", "companion", "const", "let", "function", "def",
                "self", "this", "true", "false", "null", "None", "True", "False",
                "async", "await", "try", "catch", "finally", "throw", "new",
                "static", "final", "abstract", "extends", "implements", "in", "is"
            )

            var i = 0
            while (i < code.length) {
                when {
                    code.startsWith("//", i) || code.startsWith("#", i) -> {
                        val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                        withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    code[i] == '"' -> {
                        val end = findStringEnd(code, i, '"')
                        withStyle(SpanStyle(color = stringColor)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    code[i] == '\'' -> {
                        val end = findStringEnd(code, i, '\'')
                        withStyle(SpanStyle(color = stringColor)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    code[i].isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit()) -> {
                        val end = (i until code.length).firstOrNull {
                            !code[it].isDigit() && code[it] != '.'
                        } ?: code.length
                        withStyle(SpanStyle(color = numberColor)) {
                            append(code.substring(i, end))
                        }
                        i = end
                    }
                    code[i].isLetter() || code[i] == '_' -> {
                        val end = (i until code.length).firstOrNull {
                            !code[it].isLetterOrDigit() && code[it] != '_'
                        } ?: code.length
                        val word = code.substring(i, end)
                        if (word in keywords) {
                            withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)) {
                                append(word)
                            }
                        } else {
                            withStyle(SpanStyle(color = defaultColor)) {
                                append(word)
                            }
                        }
                        i = end
                    }
                    else -> {
                        withStyle(SpanStyle(color = defaultColor)) {
                            append(code[i])
                        }
                        i++
                    }
                }
            }
        }
    }
}

private fun findStringEnd(code: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < code.length) {
        if (code[i] == '\\') {
            if (i + 1 < code.length) {
                i += 2
            } else {
                i++
            }
            continue
        }
        if (code[i] == quote) {
            return i + 1
        }
        i++
    }
    return code.length
}

// ---------------------------------------------------------------------------
// Markdown Block Parser
// ---------------------------------------------------------------------------

internal sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String) : MarkdownBlock()
    data class ListItem(val bullet: String, val text: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
}

internal fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = content.split("\n")
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        when {
            line.trimStart().startsWith("```") -> {
                val language = line.trimStart().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), language))
                i++
            }
            line.startsWith("# ") -> {
                blocks.add(MarkdownBlock.Header(1, line.removePrefix("# ")))
                i++
            }
            line.startsWith("## ") -> {
                blocks.add(MarkdownBlock.Header(2, line.removePrefix("## ")))
                i++
            }
            line.startsWith("### ") -> {
                blocks.add(MarkdownBlock.Header(3, line.removePrefix("### ")))
                i++
            }
            line.startsWith("#### ") -> {
                blocks.add(MarkdownBlock.Header(3, line.removePrefix("#### ")))
                i++
            }
            line.trimStart().startsWith("> ") -> {
                val quoteText = line.trimStart().removePrefix("> ").trim()
                blocks.add(MarkdownBlock.Blockquote(quoteText))
                i++
            }
            line.trim().matches(Regex("^-{3,}$")) || line.trim().matches(Regex("^\\*{3,}$")) -> {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val bullet = if (line.trimStart().startsWith("- ")) "-" else "*"
                val text = line.trimStart().removePrefix("$bullet ").trim()
                blocks.add(MarkdownBlock.ListItem("  -", text))
                i++
            }
            line.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                val match = Regex("^(\\d+)\\.\\s(.*)").find(line.trimStart())
                if (match != null) {
                    val num = match.groupValues[1]
                    val text = match.groupValues[2]
                    blocks.add(MarkdownBlock.ListItem("$num.", text))
                }
                i++
            }
            line.isBlank() -> {
                i++
            }
            else -> {
                val paragraphLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() &&
                    !lines[i].startsWith("#") &&
                    !lines[i].trimStart().startsWith("```") &&
                    !lines[i].trimStart().startsWith("- ") &&
                    !lines[i].trimStart().startsWith("* ") &&
                    !lines[i].trimStart().startsWith("> ") &&
                    !lines[i].trimStart().matches(Regex("^\\d+\\.\\s.*")) &&
                    !lines[i].trim().matches(Regex("^-{3,}$"))
                ) {
                    paragraphLines.add(lines[i])
                    i++
                }
                if (paragraphLines.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
                }
            }
        }
    }

    return blocks
}

package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Displays a chain of consecutive tool calls as a connected vertical group.
 * Each tool call is connected by a thin vertical line on the left side.
 * The chain has a header showing "N tool calls" with expand/collapse for the whole chain.
 * Individual tool calls within the chain can still be expanded independently.
 */
@Composable
fun ToolCallChain(
    toolCalls: List<ToolCall>,
    modifier: Modifier = Modifier
) {
    if (toolCalls.isEmpty()) return

    // If only one tool call, render it directly without chain UI
    if (toolCalls.size == 1) {
        ToolCallCard(toolCall = toolCalls.first(), modifier = modifier)
        return
    }

    var chainExpanded by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .drawBehind {
                    // Left border accent
                    drawLine(
                        color = primaryColor,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 4.dp.toPx()
                    )
                }
                .padding(start = 8.dp)
        ) {
            // Chain header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = primaryColor
                )
                Text(
                    text = "${toolCalls.size} tool calls",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                // Summary of statuses
                val completeCount = toolCalls.count { it.status == ToolCallStatus.COMPLETE }
                val runningCount = toolCalls.count { it.status == ToolCallStatus.RUNNING }
                val errorCount = toolCalls.count { it.status == ToolCallStatus.ERROR }

                if (runningCount > 0) {
                    StatusChip(status = ToolCallStatus.RUNNING)
                } else if (errorCount > 0) {
                    StatusChip(status = ToolCallStatus.ERROR)
                } else if (completeCount == toolCalls.size) {
                    StatusChip(status = ToolCallStatus.COMPLETE)
                }

                IconButton(
                    onClick = { chainExpanded = !chainExpanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (chainExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (chainExpanded) "Collapse chain" else "Expand chain",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded: show individual tool calls connected by vertical line
            if (chainExpanded) {
                val lineColor = primaryColor.copy(alpha = 0.4f)

                Column(
                    modifier = Modifier
                        .padding(start = 8.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    toolCalls.forEachIndexed { index, toolCall ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                        ) {
                            // Vertical connector line
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .fillMaxHeight()
                            ) {
                                // Draw connector dot and line
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 14.dp)
                                        .size(8.dp)
                                        .background(
                                            color = when (toolCall.status) {
                                                ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
                                                ToolCallStatus.COMPLETE -> MaterialTheme.colorScheme.primary
                                                ToolCallStatus.ERROR -> MaterialTheme.colorScheme.error
                                            },
                                            shape = RoundedCornerShape(50)
                                        )
                                )
                            }

                            // Tool call card (individual, expandable)
                            ToolCallCard(
                                toolCall = toolCall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(bottom = if (index < toolCalls.lastIndex) 4.dp else 0.dp)
                            )
                        }

                        // Vertical line between items
                        if (index < toolCalls.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 9.dp)
                                    .width(2.dp)
                                    .height(4.dp)
                                    .background(lineColor)
                            )
                        }
                    }
                }
            } else {
                // Collapsed: show brief summary of tool names
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val displayNames = toolCalls.take(3).map { it.name }
                    val remaining = toolCalls.size - 3

                    Text(
                        text = displayNames.joinToString(" → ") +
                            if (remaining > 0) " → +$remaining more" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Expandable card showing a tool call with its name, status, input, and output.
 */
@Composable
fun ToolCallCard(
    toolCall: ToolCall,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val toolIcon = when (toolCall.status) {
                    ToolCallStatus.RUNNING -> Icons.Default.Sync
                    ToolCallStatus.COMPLETE -> Icons.Default.CheckCircle
                    ToolCallStatus.ERROR -> Icons.Default.Error
                }
                val iconTint = when (toolCall.status) {
                    ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
                    ToolCallStatus.COMPLETE -> MaterialTheme.colorScheme.primary
                    ToolCallStatus.ERROR -> MaterialTheme.colorScheme.error
                }
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconTint
                )

                Text(
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )

                StatusChip(status = toolCall.status)

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                if (toolCall.input.isNotBlank()) {
                    Text(
                        text = "Input:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                text = toolCall.input,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .horizontalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (toolCall.output.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Output:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(toolCall.output))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy output",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = toolCall.output,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .horizontalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Colored chip indicating tool call status: RUNNING, COMPLETE, or ERROR.
 */
@Composable
fun StatusChip(
    status: ToolCallStatus,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (status) {
        ToolCallStatus.RUNNING -> "running" to MaterialTheme.colorScheme.tertiary
        ToolCallStatus.COMPLETE -> "done" to MaterialTheme.colorScheme.primary
        ToolCallStatus.ERROR -> "error" to MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

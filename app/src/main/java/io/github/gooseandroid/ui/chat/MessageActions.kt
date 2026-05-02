package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * Dropdown popup shown on long press of a message bubble.
 * Provides copy, retry (user messages only), and delete actions.
 */
@Composable
fun MessageActionsPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    messageContent: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = {
                clipboardManager.setText(AnnotatedString(messageContent))
                onDismiss()
            },
            leadingIcon = {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
            }
        )
        if (showRetry) {
            DropdownMenuItem(
                text = { Text("Retry") },
                onClick = {
                    onRetry()
                    onDismiss()
                },
                leadingIcon = {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = {
                onDelete()
                onDismiss()
            },
            leadingIcon = {
                Icon(Icons.Default.Delete, contentDescription = null)
            }
        )
    }
}

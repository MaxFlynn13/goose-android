package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Data class representing an attached file ready to be sent with a message.
 */
data class FileAttachment(
    val uri: Uri,
    val name: String,
    val content: String,
    val mimeType: String
)

/**
 * Chat input bar with text field, send/cancel button, file attachments, and voice input.
 * IME padding is applied here only.
 */
@Composable
fun ChatInputBar(
    onSend: (String) -> Unit,
    isGenerating: Boolean,
    onCancel: () -> Unit,
    prefillText: String? = null,
    onPrefillConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<FileAttachment>() }
    var isListening by remember { mutableStateOf(false) }

    // Handle recipe prefill
    LaunchedEffect(prefillText) {
        if (prefillText != null) {
            text = prefillText
            onPrefillConsumed()
        }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileContent = readFileContent(context, uri)
            val fileName = getFileName(context, uri)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            if (fileContent != null) {
                attachments.add(
                    FileAttachment(
                        uri = uri,
                        name = fileName,
                        content = fileContent,
                        mimeType = mimeType
                    )
                )
            }
        }
    }

    // Voice input (SpeechRecognizer)
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val voiceText = remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
            override fun onError(error: Int) {
                isListening = false
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    voiceText.value = matches[0]
                }
                isListening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    voiceText.value = matches[0]
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            speechRecognizer.destroy()
        }
    }

    // Insert voice transcription into text field
    LaunchedEffect(voiceText.value) {
        if (voiceText.value.isNotBlank()) {
            text = if (text.isBlank()) {
                voiceText.value
            } else {
                "$text ${voiceText.value}"
            }
            voiceText.value = ""
        }
    }

    // Microphone permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening(context, speechRecognizer)
            isListening = true
        }
    }

    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Attachment chips row
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEachIndexed { index, attachment ->
                        InputChip(
                            selected = false,
                            onClick = { attachments.removeAt(index) },
                            label = {
                                Text(
                                    text = attachment.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Input row
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Goose...") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank() && !isGenerating) {
                                sendWithAttachments(text.trim(), attachments, onSend)
                                text = ""
                                attachments.clear()
                            }
                        }
                    )
                )

                // Voice input button
                if (isListening) {
                    // Pulsing indicator while recording
                    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500, easing = EaseInOut),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse_alpha"
                    )
                    FilledIconButton(
                        onClick = {
                            speechRecognizer.stopListening()
                            isListening = false
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha),
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Stop recording"
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Attach file button
                IconButton(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "image/*",
                                "text/*"
                            )
                        )
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Send / Cancel button
                if (isGenerating) {
                    FilledIconButton(
                        onClick = onCancel,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Cancel generation"
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (text.isNotBlank() || attachments.isNotEmpty()) {
                                sendWithAttachments(text.trim(), attachments, onSend)
                                text = ""
                                attachments.clear()
                            }
                        },
                        enabled = text.isNotBlank() || attachments.isNotEmpty(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helper: Send message with file attachments included
// ---------------------------------------------------------------------------

private fun sendWithAttachments(
    text: String,
    attachments: List<FileAttachment>,
    onSend: (String) -> Unit
) {
    if (attachments.isEmpty()) {
        onSend(text)
    } else {
        val attachmentBlock = attachments.joinToString("\n\n") { attachment ->
            "[Attached file: ${attachment.name}]\n${attachment.content}"
        }
        val fullMessage = if (text.isNotBlank()) {
            "$text\n\n$attachmentBlock"
        } else {
            attachmentBlock
        }
        onSend(fullMessage)
    }
}

// ---------------------------------------------------------------------------
// Helper: Read file content from URI
// ---------------------------------------------------------------------------

private fun readFileContent(context: Context, uri: Uri): String? {
    return try {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        if (mimeType.startsWith("image/")) {
            // For images, return a placeholder indicating the image URI
            "[Image: $uri]"
        } else {
            // For text files, read content
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.use { it.readText() }
            // Limit content to prevent excessively large messages
            if (content.length > 50_000) {
                content.take(50_000) + "\n... [truncated, file too large]"
            } else {
                content
            }
        }
    } catch (e: Exception) {
        null
    }
}

// ---------------------------------------------------------------------------
// Helper: Get file name from URI
// ---------------------------------------------------------------------------

private fun getFileName(context: Context, uri: Uri): String {
    var name = "file"
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                name = it.getString(nameIndex) ?: "file"
            }
        }
    }
    return name
}

// ---------------------------------------------------------------------------
// Helper: Start speech recognition
// ---------------------------------------------------------------------------

private fun startListening(context: Context, speechRecognizer: SpeechRecognizer) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    speechRecognizer.startListening(intent)
}

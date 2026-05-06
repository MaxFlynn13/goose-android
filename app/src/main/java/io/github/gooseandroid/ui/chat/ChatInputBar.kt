package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import java.io.BufferedReader
import java.io.InputStreamReader

/** Maximum file size allowed for attachments: 1 MB */
private const val MAX_FILE_SIZE_BYTES = 1_048_576L // 1 MB

/**
 * Data class representing an attached file ready to be sent with a message.
 */
data class FileAttachment(
    val uri: Uri,
    val name: String,
    val content: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val isImage: Boolean = false
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

    // Fix #1: Check if speech recognition is available on this device
    val isSpeechAvailable = remember {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

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
            // Take persistable read permission so the URI stays accessible for thumbnail display
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Not all providers support persistable permissions; continue anyway
            }

            val fileSize = getFileSize(context, uri)
            // Check file size before reading (max 1MB)
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                Toast.makeText(
                    context,
                    "File too large (${formatFileSize(fileSize)}). Maximum allowed is 1 MB.",
                    Toast.LENGTH_LONG
                ).show()
                return@rememberLauncherForActivityResult
            }
            val fileContent = readFileContent(context, uri)
            val fileName = getFileName(context, uri)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val isImage = mimeType.startsWith("image/")
            if (fileContent != null) {
                attachments.add(
                    FileAttachment(
                        uri = uri,
                        name = fileName,
                        content = fileContent,
                        mimeType = mimeType,
                        sizeBytes = fileSize,
                        isImage = isImage
                    )
                )
            } else {
                Toast.makeText(
                    context,
                    "Could not read file: $fileName",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Fix #4: SpeechRecognizer created inside DisposableEffect with proper cleanup
    val speechRecognizer = remember { mutableStateOf<SpeechRecognizer?>(null) }
    val voiceText = remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        // Only create recognizer if speech is available
        val recognizer = if (isSpeechAvailable) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }

        if (recognizer != null) {
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
            recognizer.setRecognitionListener(listener)
        }

        speechRecognizer.value = recognizer

        onDispose {
            recognizer?.destroy()
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

    // Fix #5: Microphone permission launcher – only start listening after permission is granted
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            speechRecognizer.value?.let { recognizer ->
                startListening(context, recognizer)
                isListening = true
            }
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for voice input.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Surface(
        modifier = modifier,
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Attachment previews row
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEachIndexed { index, attachment ->
                        if (attachment.isImage) {
                            // Image thumbnail preview with remove button
                            Box(
                                modifier = Modifier.size(72.dp)
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        model = ImageRequest.Builder(context)
                                            .data(attachment.uri)
                                            .crossfade(true)
                                            .size(144) // 2x for density
                                            .build()
                                    ),
                                    contentDescription = attachment.name,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                // Remove button overlay
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(20.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = CircleShape
                                        )
                                        .clickable { attachments.removeAt(index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove attachment",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        } else {
                            // Text/other file chip
                            InputChip(
                                selected = false,
                                onClick = { attachments.removeAt(index) },
                                label = {
                                    Text(
                                        text = "${attachment.name} (${formatFileSize(attachment.sizeBytes)})",
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

                // Fix #1: Only show voice input button if speech recognition is available
                if (isSpeechAvailable) {
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
                                speechRecognizer.value?.stopListening()
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
                                // Fix #5: Request permission before starting recognition
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
                }

                // Attach file button
                IconButton(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "image/*",
                                "text/*",
                                "application/json",
                                "application/x-yaml",
                                "application/yaml",
                                "application/octet-stream", // fallback for .py, .kt, etc.
                                "application/csv",
                                "text/csv",
                                "text/x-python",
                                "text/x-kotlin",
                                "text/markdown"
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

/** Text-readable file extensions (used when MIME type is ambiguous) */
private val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "py", "kt", "kts", "java", "json", "yaml", "yml",
    "md", "csv", "xml", "html", "css", "js", "ts", "sh",
    "toml", "ini", "cfg", "conf", "log", "sql", "gradle",
    "properties", "swift", "rs", "go", "rb", "c", "cpp", "h"
)

/** MIME types that should be treated as readable text */
private val TEXT_MIME_TYPES = setOf(
    "application/json",
    "application/x-yaml",
    "application/yaml",
    "application/xml",
    "application/csv",
    "application/x-python",
    "application/javascript",
    "application/typescript",
    "application/toml"
)

private fun isTextFile(mimeType: String, fileName: String): Boolean {
    if (mimeType.startsWith("text/")) return true
    if (mimeType in TEXT_MIME_TYPES) return true
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in TEXT_FILE_EXTENSIONS
}

private fun readFileContent(context: Context, uri: Uri): String? {
    return try {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val fileName = getFileName(context, uri)

        // Check file size before reading
        val fileSize = getFileSize(context, uri)
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            return null
        }

        if (mimeType.startsWith("image/")) {
            // Read image bytes and encode to base64 for multimodal API calls
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.use { it.readBytes() }
            val base64String = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mimeType;base64,$base64String"
        } else if (isTextFile(mimeType, fileName)) {
            // For text-based files, read content as UTF-8
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val content = reader.use { it.readText() }
            // Limit content to prevent excessively large messages
            if (content.length > 50_000) {
                content.take(50_000) + "\n... [truncated, file too large]"
            } else {
                content
            }
        } else {
            // Unknown binary file — encode as base64 with a note
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.use { it.readBytes() }
            val base64String = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "[Binary file, base64 encoded]\ndata:$mimeType;base64,$base64String"
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
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                name = it.getString(nameIndex) ?: "file"
            }
        }
    }
    return name
}

// ---------------------------------------------------------------------------
// Helper: Get file size from URI
// ---------------------------------------------------------------------------

private fun getFileSize(context: Context, uri: Uri): Long {
    var size = 0L
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0) {
                size = it.getLong(sizeIndex)
            }
        }
    }
    return size
}

// ---------------------------------------------------------------------------
// Helper: Format file size for display
// ---------------------------------------------------------------------------

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1_048_576 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    }
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

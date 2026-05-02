package io.github.gooseandroid

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity

class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> {
                when {
                    intent.type?.startsWith("text/") == true -> {
                        intent.getStringExtra(Intent.EXTRA_TEXT)
                    }
                    intent.type?.startsWith("image/") == true -> {
                        val imageUri = getParcelableExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                        if (imageUri != null) {
                            readImageAsBase64(imageUri)
                        } else {
                            "Shared image: (could not read URI)"
                        }
                    }
                    else -> {
                        intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Shared content"
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getParcelableArrayListExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
                if (uris != null && uris.isNotEmpty()) {
                    val parts = uris.mapNotNull { uri ->
                        try {
                            val mimeType = contentResolver.getType(uri) ?: ""
                            if (mimeType.startsWith("image/")) {
                                readImageAsBase64(uri)
                            } else {
                                "Shared file: $uri"
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read shared URI: $uri", e)
                            null
                        }
                    }
                    parts.joinToString("\n\n")
                } else {
                    "Shared ${uris?.size ?: 0} files"
                }
            }
            else -> null
        }

        // Forward to main activity with the shared content
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("shared_text", sharedText)
        }
        startActivity(mainIntent)
        finish()
    }

    /**
     * Read an image URI's bytes and encode as a base64 data URI string
     * so the content is preserved when forwarded to the chat.
     */
    private fun readImageAsBase64(uri: Uri): String {
        return try {
            val mimeType = contentResolver.getType(uri) ?: "image/png"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                "data:$mimeType;base64,$b64"
            } else {
                "Shared image: (could not read content from $uri)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read image as base64", e)
            "Shared image: (error reading $uri: ${e.message})"
        }
    }

    /**
     * API 33+ compat wrapper for Intent.getParcelableExtra.
     */
    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableExtraCompat(
        intent: Intent,
        name: String
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, T::class.java)
        } else {
            intent.getParcelableExtra(name)
        }
    }

    /**
     * API 33+ compat wrapper for Intent.getParcelableArrayListExtra.
     */
    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableArrayListExtraCompat(
        intent: Intent,
        name: String
    ): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(name, T::class.java)
        } else {
            intent.getParcelableArrayListExtra(name)
        }
    }
}

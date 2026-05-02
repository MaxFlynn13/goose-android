package io.github.gooseandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> {
                when {
                    intent.type?.startsWith("text/") == true -> {
                        intent.getStringExtra(Intent.EXTRA_TEXT)
                    }
                    intent.type?.startsWith("image/") == true -> {
                        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        "Shared image: ${imageUri?.toString()}"
                    }
                    else -> {
                        intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Shared content"
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                "Shared ${uris?.size ?: 0} files"
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
}

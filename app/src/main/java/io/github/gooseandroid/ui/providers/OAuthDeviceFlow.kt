package io.github.gooseandroid.ui.providers

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private enum class FlowState {
    REQUESTING_CODE,
    WAITING_FOR_USER,
    SUCCESS,
    ERROR
}

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

@Composable
fun OAuthDeviceFlowDialog(
    providerName: String,
    deviceAuthUrl: String,
    tokenUrl: String,
    clientId: String,
    scope: String = "",
    onTokenReceived: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var state by remember { mutableStateOf(FlowState.REQUESTING_CODE) }
    var userCode by remember { mutableStateOf("") }
    var verificationUri by remember { mutableStateOf("") }
    var deviceCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var polling by remember { mutableStateOf(false) }

    suspend fun requestDeviceCode(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val bodyBuilder = FormBody.Builder()
                    .add("client_id", clientId)
                if (scope.isNotEmpty()) {
                    bodyBuilder.add("scope", scope)
                }
                val request = Request.Builder()
                    .url(deviceAuthUrl)
                    .post(bodyBuilder.build())
                    .build()
                val response = httpClient.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "")
                if (response.isSuccessful) {
                    deviceCode = json.getString("device_code")
                    userCode = json.getString("user_code")
                    verificationUri = json.optString("verification_uri",
                        json.optString("verification_url", ""))
                    true
                } else {
                    errorMessage = json.optString("error_description",
                        json.optString("error", "Failed to request device code"))
                    false
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Network error"
                false
            }
        }
    }

    suspend fun pollForToken(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("device_code", deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build()
                val request = Request.Builder()
                    .url(tokenUrl)
                    .post(body)
                    .build()
                val response = httpClient.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "")
                if (response.isSuccessful && json.has("access_token")) {
                    json.getString("access_token")
                } else {
                    val error = json.optString("error", "")
                    if (error != "authorization_pending" && error != "slow_down") {
                        errorMessage = json.optString("error_description", error)
                        throw Exception(errorMessage)
                    }
                    null
                }
            } catch (e: Exception) {
                if (errorMessage.isEmpty()) errorMessage = e.message ?: "Polling error"
                throw e
            }
        }
    }

    LaunchedEffect(state) {
        when (state) {
            FlowState.REQUESTING_CODE -> {
                val success = requestDeviceCode()
                state = if (success) FlowState.WAITING_FOR_USER else FlowState.ERROR
            }
            FlowState.WAITING_FOR_USER -> if (!polling) {
                polling = true
                try {
                    while (state == FlowState.WAITING_FOR_USER) {
                        delay(5000)
                        val token = pollForToken()
                        if (token != null) { state = FlowState.SUCCESS; onTokenReceived(token) }
                    }
                } catch (_: Exception) {
                    if (state == FlowState.WAITING_FOR_USER) state = FlowState.ERROR
                } finally { polling = false }
            }
            FlowState.SUCCESS -> { delay(1500); onDismiss() }
            FlowState.ERROR -> {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to $providerName") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state) {
                    FlowState.REQUESTING_CODE -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text("Requesting authorization code...")
                    }
                    FlowState.WAITING_FOR_USER -> {
                        Text(
                            "Visit this URL and enter the code:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        SelectionContainer {
                            Text(
                                text = verificationUri,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri))
                            )
                        }) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Browser")
                        }
                        Surface(
                            tonalElevation = 4.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = userCode,
                                    fontSize = 24.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    clipboardManager.setText(AnnotatedString(userCode))
                                }) {
                                    Icon(Icons.Default.ContentCopy,
                                        contentDescription = "Copy code")
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp)
                            Text("Waiting for authorization...",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    FlowState.SUCCESS -> {
                        Text("Authorization successful.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowState.ERROR -> {
                        Text(
                            text = errorMessage.ifEmpty { "An error occurred." },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(onClick = {
                            errorMessage = ""
                            state = FlowState.REQUESTING_CODE
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

package io.github.gooseandroid.engine.extensions

import io.github.gooseandroid.engine.tools.ToolResult
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class FetchExtension : BuiltInExtension {

    override val id = "fetch"
    override val name = "HTTP Fetch"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val DEFAULT_MAX_LENGTH = 50000
        private const val MAX_REDIRECTS = 5
    }

    override val tools: List<JSONObject> = listOf(
        buildToolSchema(
            name = "fetch",
            description = "Fetch a URL and return its raw content (HTML, JSON, text, etc). Returns status code, response headers, and body."
        ),
        buildToolSchema(
            name = "fetch_json",
            description = "Fetch a URL, parse the response as JSON, and return it formatted with indentation. Returns status code, response headers, and parsed JSON body."
        ),
        buildToolSchema(
            name = "fetch_text",
            description = "Fetch a URL and extract readable text content by stripping HTML tags. Returns status code, response headers, and cleaned text body."
        )
    )

    private fun buildToolSchema(name: String, description: String): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("url"))
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "The URL to fetch")
                    })
                    put("method", JSONObject().apply {
                        put("type", "string")
                        put("description", "HTTP method to use")
                        put("enum", JSONArray().apply {
                            put("GET")
                            put("POST")
                            put("PUT")
                            put("DELETE")
                        })
                        put("default", "GET")
                    })
                    put("headers", JSONObject().apply {
                        put("type", "object")
                        put("description", "Optional HTTP headers as key-value pairs")
                        put("additionalProperties", JSONObject().apply {
                            put("type", "string")
                        })
                    })
                    put("body", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional request body for POST/PUT requests")
                    })
                    put("max_length", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of characters to return in the response body (default 50000)")
                        put("default", DEFAULT_MAX_LENGTH)
                    })
                })
            })
        }
    }

    override suspend fun executeTool(name: String, input: JSONObject): ToolResult {
        return when (name) {
            "fetch" -> executeRequest(input, ResponseMode.RAW)
            "fetch_json" -> executeRequest(input, ResponseMode.JSON)
            "fetch_text" -> executeRequest(input, ResponseMode.TEXT)
            else -> ToolResult(
                isError = true,
                output = "Unknown tool: $name"
            )
        }
    }

    private enum class ResponseMode {
        RAW, JSON, TEXT
    }

    private fun executeRequest(input: JSONObject, mode: ResponseMode): ToolResult {
        val url = input.optString("url", "").ifEmpty {
            return ToolResult(isError = true, output = "Missing required parameter: url")
        }

        val method = input.optString("method", "GET").uppercase()
        val maxLength = input.optInt("max_length", DEFAULT_MAX_LENGTH)
        val headersObj = input.optJSONObject("headers")
        val body = input.optString("body", "")

        try {
            val requestBuilder = Request.Builder().url(url)

            // Add custom headers
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    requestBuilder.addHeader(key, headersObj.getString(key))
                }
            }

            // Add default User-Agent if not specified
            if (headersObj == null || !headersObj.has("User-Agent")) {
                requestBuilder.addHeader("User-Agent", "GooseAndroid-Fetch/1.0")
            }

            // Set method and body
            val requestBody = when (method) {
                "POST", "PUT" -> {
                    val contentType = headersObj?.optString("Content-Type", "application/json")
                        ?: "application/json"
                    body.toRequestBody(contentType.toMediaTypeOrNull())
                }
                else -> null
            }

            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
                "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
                "DELETE" -> {
                    if (body.isNotEmpty()) {
                        val contentType = headersObj?.optString("Content-Type", "application/json")
                            ?: "application/json"
                        requestBuilder.delete(body.toRequestBody(contentType.toMediaTypeOrNull()))
                    } else {
                        requestBuilder.delete()
                    }
                }
                else -> return ToolResult(
                    isError = true,
                    output = "Unsupported HTTP method: $method"
                )
            }

            val request = requestBuilder.build()

            // Execute with redirect tracking
            var redirectCount = 0
            var currentClient = client
            val response = currentClient.newCall(request).execute()

            // OkHttp handles redirects automatically, but we check the prior response chain
            var priorResponse = response.priorResponse
            while (priorResponse != null) {
                redirectCount++
                if (redirectCount > MAX_REDIRECTS) {
                    response.close()
                    return ToolResult(
                        isError = true,
                        output = "Too many redirects (exceeded $MAX_REDIRECTS)"
                    )
                }
                priorResponse = priorResponse.priorResponse
            }

            val statusCode = response.code
            val responseHeaders = JSONObject()
            response.headers.forEach { (name, value) ->
                responseHeaders.put(name, value)
            }

            val rawBody = response.body?.string() ?: ""
            response.close()

            val processedBody = when (mode) {
                ResponseMode.RAW -> rawBody
                ResponseMode.JSON -> {
                    try {
                        val trimmed = rawBody.trim()
                        if (trimmed.startsWith("[")) {
                            JSONArray(trimmed).toString(2)
                        } else {
                            JSONObject(trimmed).toString(2)
                        }
                    } catch (e: Exception) {
                        return ToolResult(
                            isError = true,
                            output = "Failed to parse response as JSON: ${e.message}\n\nRaw response (first 1000 chars):\n${rawBody.take(1000)}"
                        )
                    }
                }
                ResponseMode.TEXT -> stripHtmlTags(rawBody)
            }

            // Truncate to max_length
            val truncated = if (processedBody.length > maxLength) {
                processedBody.take(maxLength) + "\n\n[... truncated at $maxLength characters, total length: ${processedBody.length}]"
            } else {
                processedBody
            }

            val isError = statusCode >= 400

            val result = buildString {
                appendLine("HTTP $statusCode")
                appendLine()
                appendLine("Response Headers:")
                val headerKeys = responseHeaders.keys()
                while (headerKeys.hasNext()) {
                    val key = headerKeys.next()
                    appendLine("  $key: ${responseHeaders.getString(key)}")
                }
                appendLine()
                appendLine("Body:")
                append(truncated)
            }

            return ToolResult(
                isError = isError,
                content = result
            )

        } catch (e: SocketTimeoutException) {
            return ToolResult(
                isError = true,
                output = "Request timed out after 30 seconds: ${e.message}"
            )
        } catch (e: UnknownHostException) {
            return ToolResult(
                isError = true,
                output = "DNS resolution failed for URL '$url': ${e.message}"
            )
        } catch (e: SSLException) {
            return ToolResult(
                isError = true,
                output = "SSL/TLS error for URL '$url': ${e.message}"
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult(
                isError = true,
                output = "Invalid URL '$url': ${e.message}"
            )
        } catch (e: Exception) {
            return ToolResult(
                isError = true,
                output = "Request failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun stripHtmlTags(html: String): String {
        // Remove script and style blocks entirely
        var text = html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")

        // Remove HTML comments
        text = text.replace(Regex("<!--[\\s\\S]*?-->"), "")

        // Replace <br>, <p>, <div>, <li>, <h*> with newlines for readability
        text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</(p|div|li|h[1-6]|tr|blockquote)>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<(p|div|li|h[1-6]|tr|blockquote)[^>]*>", RegexOption.IGNORE_CASE), "\n")

        // Remove all remaining HTML tags
        text = text.replace(Regex("<[^>]+>"), "")

        // Decode common HTML entities
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        text = text.replace("&#39;", "'")
        text = text.replace("&apos;", "'")
        text = text.replace("&nbsp;", " ")
        text = text.replace("&#x27;", "'")
        text = text.replace("&#x2F;", "/")
        text = text.replace(Regex("&#(\\d+);")) { matchResult ->
            val code = matchResult.groupValues[1].toIntOrNull()
            if (code != null && code in 0..0xFFFF) {
                code.toChar().toString()
            } else {
                matchResult.value
            }
        }
        text = text.replace(Regex("&#x([0-9a-fA-F]+);")) { matchResult ->
            val code = matchResult.groupValues[1].toIntOrNull(16)
            if (code != null && code in 0..0xFFFF) {
                code.toChar().toString()
            } else {
                matchResult.value
            }
        }

        // Collapse multiple blank lines into at most two newlines
        text = text.replace(Regex("\\n{3,}"), "\n\n")

        // Trim leading/trailing whitespace on each line
        text = text.lines().joinToString("\n") { it.trim() }

        // Remove leading/trailing blank lines
        text = text.trim()

        return text
    }
}

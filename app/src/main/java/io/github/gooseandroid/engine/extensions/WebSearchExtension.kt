package io.github.gooseandroid.engine.extensions

import io.github.gooseandroid.engine.tools.ToolResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebSearchExtension(
    private val braveApiKey: String? = null
) : BuiltInExtension {

    override val id = "web_search"
    override val name = "Web Search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override val tools: List<JSONObject> = listOf(
        buildWebSearchTool(),
        buildSearchNewsTool(),
        buildGetSearchResultsTool()
    )

    override suspend fun executeTool(name: String, input: JSONObject): ToolResult {
        return try {
            when (name) {
                "web_search" -> executeWebSearch(input)
                "search_news" -> executeSearchNews(input)
                "get_search_results" -> executeGetSearchResults(input)
                else -> ToolResult(output = "Unknown tool: $name", isError = true)
            }
        } catch (e: Exception) {
            ToolResult(output = "Error executing $name: ${e.message ?: "Unknown error"}", isError = true)
        }
    }

    // ─── Tool Definitions ────────────────────────────────────────────────────────

    private fun buildWebSearchTool(): JSONObject {
        return JSONObject().apply {
            put("name", "web_search")
            put("description", "Search the web using DuckDuckGo. Returns a list of relevant results with titles, URLs, and snippets. No API key required.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "The search query string")
                    })
                    put("max_results", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of results to return (default: 10, max: 20)")
                        put("default", 10)
                    })
                })
                put("required", JSONArray().apply { put("query") })
            })
        }
    }

    private fun buildSearchNewsTool(): JSONObject {
        return JSONObject().apply {
            put("name", "search_news")
            put("description", "Search for recent news articles using DuckDuckGo. Returns news results with titles, URLs, snippets, and sources.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "The news search query string")
                    })
                    put("max_results", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of results to return (default: 10, max: 20)")
                        put("default", 10)
                    })
                })
                put("required", JSONArray().apply { put("query") })
            })
        }
    }

    private fun buildGetSearchResultsTool(): JSONObject {
        return JSONObject().apply {
            put("name", "get_search_results")
            put("description", "Get detailed search results with full snippets and metadata. Supports DuckDuckGo (default) or Brave Search (if API key is configured).")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "The search query string")
                    })
                    put("max_results", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of results to return (default: 10, max: 20)")
                        put("default", 10)
                    })
                    put("engine", JSONObject().apply {
                        put("type", "string")
                        put("description", "Search engine to use: 'duckduckgo' (default) or 'brave' (requires API key)")
                        put("enum", JSONArray().apply {
                            put("duckduckgo")
                            put("brave")
                        })
                        put("default", "duckduckgo")
                    })
                })
                put("required", JSONArray().apply { put("query") })
            })
        }
    }

    // ─── Tool Implementations ────────────────────────────────────────────────────

    private suspend fun executeWebSearch(input: JSONObject): ToolResult {
        val query = input.getString("query")
        val maxResults = input.optInt("max_results", 10).coerceIn(1, 20)

        val results = searchDuckDuckGo(query, maxResults)
        if (results.isEmpty()) {
            return ToolResult(output = "No results found for: $query")
        }

        val formatted = formatResults(results, query)
        return ToolResult(output = formatted)
    }

    private suspend fun executeSearchNews(input: JSONObject): ToolResult {
        val query = input.getString("query")
        val maxResults = input.optInt("max_results", 10).coerceIn(1, 20)

        // DuckDuckGo news search uses the same HTML endpoint with "news" appended
        val newsQuery = "$query news recent"
        val results = searchDuckDuckGo(newsQuery, maxResults)
        if (results.isEmpty()) {
            return ToolResult(output = "No news results found for: $query")
        }

        val formatted = buildString {
            appendLine("## News Results for: $query")
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine("### ${index + 1}. ${result.title}")
                appendLine("**URL:** ${result.url}")
                if (result.snippet.isNotBlank()) {
                    appendLine("**Summary:** ${result.snippet}")
                }
                appendLine()
            }
            appendLine("---")
            appendLine("_${results.size} news results found via DuckDuckGo_")
        }
        return ToolResult(output = formatted)
    }

    private suspend fun executeGetSearchResults(input: JSONObject): ToolResult {
        val query = input.getString("query")
        val maxResults = input.optInt("max_results", 10).coerceIn(1, 20)
        val engine = input.optString("engine", "duckduckgo")

        val results = if (engine == "brave" && !braveApiKey.isNullOrBlank()) {
            searchBrave(query, maxResults)
        } else {
            searchDuckDuckGo(query, maxResults)
        }

        if (results.isEmpty()) {
            return ToolResult(output = "No results found for: $query")
        }

        val engineLabel = if (engine == "brave" && !braveApiKey.isNullOrBlank()) "Brave Search" else "DuckDuckGo"
        val formatted = buildString {
            appendLine("## Detailed Search Results for: $query")
            appendLine("_Engine: ${engineLabel}_")
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine("### ${index + 1}. ${result.title}")
                appendLine("- **URL:** ${result.url}")
                if (result.snippet.isNotBlank()) {
                    appendLine("- **Snippet:** ${result.snippet}")
                }
                if (result.source.isNotBlank()) {
                    appendLine("- **Source:** ${result.source}")
                }
                appendLine()
            }
            appendLine("---")
            appendLine("_${results.size} results returned from ${engineLabel}_")
        }
        return ToolResult(output = formatted)
    }

    // ─── Search Engines ──────────────────────────────────────────────────────────

    private fun searchDuckDuckGo(query: String, maxResults: Int): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()

        if (!response.isSuccessful) {
            return emptyList()
        }

        return parseDuckDuckGoHtml(body, maxResults)
    }

    private fun parseDuckDuckGoHtml(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // DuckDuckGo HTML results are in <div class="result"> or similar blocks
        // Each result has a link in <a class="result__a"> and snippet in <a class="result__snippet">
        val resultBlocks = html.split("<div class=\"links_main links_deep result__body\">")
            .drop(1) // Drop content before first result

        for (block in resultBlocks) {
            if (results.size >= maxResults) break

            val title = extractTitle(block)
            val url = extractUrl(block)
            val snippet = extractSnippet(block)

            if (title.isNotBlank() && url.isNotBlank()) {
                results.add(SearchResult(title = title, url = url, snippet = snippet))
            }
        }

        // Fallback parsing if the above didn't work
        if (results.isEmpty()) {
            results.addAll(parseDuckDuckGoFallback(html, maxResults))
        }

        return results
    }

    private fun extractTitle(block: String): String {
        // Try <a class="result__a" ...>TITLE</a>
        val titleRegex = Regex("""<a[^>]*class="result__a"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val match = titleRegex.find(block)
        return match?.groupValues?.get(1)?.stripHtmlTags()?.trim() ?: ""
    }

    private fun extractUrl(block: String): String {
        // Try href from <a class="result__a" href="...">
        val urlRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>""")
        val match = urlRegex.find(block)
        var url = match?.groupValues?.get(1) ?: ""

        // Also try the other attribute order
        if (url.isBlank()) {
            val altRegex = Regex("""<a[^>]*href="([^"]*)"[^>]*class="result__a"[^>]*>""")
            val altMatch = altRegex.find(block)
            url = altMatch?.groupValues?.get(1) ?: ""
        }

        // DuckDuckGo sometimes wraps URLs in a redirect; extract the actual URL
        if (url.contains("uddg=")) {
            val uddgRegex = Regex("""uddg=([^&]+)""")
            val uddgMatch = uddgRegex.find(url)
            if (uddgMatch != null) {
                url = java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
            }
        }

        return url
    }

    private fun extractSnippet(block: String): String {
        // Try <a class="result__snippet" ...>SNIPPET</a>
        val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val match = snippetRegex.find(block)
        if (match != null) {
            return match.groupValues[1].stripHtmlTags().trim()
        }

        // Try <span class="result__snippet">
        val spanRegex = Regex("""<span[^>]*class="result__snippet"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        val spanMatch = spanRegex.find(block)
        return spanMatch?.groupValues?.get(1)?.stripHtmlTags()?.trim() ?: ""
    }

    private fun parseDuckDuckGoFallback(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Broader fallback: look for result__a links anywhere
        val linkRegex = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val snippetRegex = Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()

        for (i in links.indices) {
            if (results.size >= maxResults) break

            val linkMatch = links[i]
            var url = linkMatch.groupValues[1]
            val title = linkMatch.groupValues[2].stripHtmlTags().trim()

            // Decode redirect URLs
            if (url.contains("uddg=")) {
                val uddgRegex = Regex("""uddg=([^&]+)""")
                val uddgMatch = uddgRegex.find(url)
                if (uddgMatch != null) {
                    url = java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
                }
            }

            val snippet = if (i < snippets.size) {
                snippets[i].groupValues[1].stripHtmlTags().trim()
            } else ""

            if (title.isNotBlank() && url.isNotBlank()) {
                results.add(SearchResult(title = title, url = url, snippet = snippet))
            }
        }

        // Last resort: generic link extraction
        if (results.isEmpty()) {
            val genericRegex = Regex(
                """<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in genericRegex.findAll(html)) {
                if (results.size >= maxResults) break
                val url = match.groupValues[1]
                val title = match.groupValues[2].stripHtmlTags().trim()
                // Skip DuckDuckGo internal links
                if (title.isNotBlank() && !url.contains("duckduckgo.com") && url.startsWith("http")) {
                    results.add(SearchResult(title = title, url = url, snippet = ""))
                }
            }
        }

        return results
    }

    private fun searchBrave(query: String, maxResults: Int): List<SearchResult> {
        val apiKey = braveApiKey ?: return emptyList()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=$maxResults"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .header("X-Subscription-Token", apiKey)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()

        if (!response.isSuccessful) {
            return emptyList()
        }

        return parseBraveJson(body, maxResults)
    }

    private fun parseBraveJson(json: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val root = JSONObject(json)
            val webResults = root.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()

            for (i in 0 until minOf(webResults.length(), maxResults)) {
                val item = webResults.getJSONObject(i)
                val title = item.optString("title", "")
                val url = item.optString("url", "")
                val snippet = item.optString("description", "")
                val source = item.optJSONObject("meta_url")?.optString("hostname", "") ?: ""

                if (title.isNotBlank() && url.isNotBlank()) {
                    results.add(SearchResult(title = title, url = url, snippet = snippet, source = source))
                }
            }
        } catch (_: Exception) {
            // JSON parsing failed
        }

        return results
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private fun formatResults(results: List<SearchResult>, query: String): String {
        return buildString {
            appendLine("## Search Results for: $query")
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. **${result.title}**")
                appendLine("   ${result.url}")
                if (result.snippet.isNotBlank()) {
                    appendLine("   _${result.snippet}_")
                }
                appendLine()
            }
            appendLine("---")
            appendLine("_${results.size} results found_")
        }
    }

    private fun String.stripHtmlTags(): String {
        return this
            .replace(Regex("<b>|</b>"), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
    }

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String = "",
        val source: String = ""
    )
}

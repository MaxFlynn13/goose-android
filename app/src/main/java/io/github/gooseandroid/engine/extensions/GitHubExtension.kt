package io.github.gooseandroid.engine.extensions

import io.github.gooseandroid.engine.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class GitHubExtension(private val token: String) : BuiltInExtension {

    override val id = "github"
    override val name = "GitHub"
    override fun isConfigured() = token.isNotBlank()

    private val baseUrl = "https://api.github.com"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override val tools: List<JSONObject> = listOf(
        buildToolSchema(
            name = "search_repositories",
            description = "Search for GitHub repositories",
            properties = mapOf(
                "query" to propString("Search query (GitHub search syntax)"),
                "page" to propInt("Page number for pagination (default: 1)"),
                "per_page" to propInt("Results per page, max 100 (default: 30)")
            ),
            required = listOf("query")
        ),
        buildToolSchema(
            name = "get_file_contents",
            description = "Get the contents of a file or directory from a GitHub repository",
            properties = mapOf(
                "owner" to propString("Repository owner (user or organization)"),
                "repo" to propString("Repository name"),
                "path" to propString("Path to the file or directory"),
                "branch" to propString("Branch to get contents from (optional, defaults to repo default branch)")
            ),
            required = listOf("owner", "repo", "path")
        ),
        buildToolSchema(
            name = "create_or_update_file",
            description = "Create or update a single file in a GitHub repository",
            properties = mapOf(
                "owner" to propString("Repository owner (user or organization)"),
                "repo" to propString("Repository name"),
                "path" to propString("Path where to create/update the file"),
                "content" to propString("Content of the file"),
                "message" to propString("Commit message"),
                "branch" to propString("Branch to create/update the file in (optional)"),
                "sha" to propString("SHA of the file being replaced (required for updates)")
            ),
            required = listOf("owner", "repo", "path", "content", "message")
        ),
        buildToolSchema(
            name = "create_issue",
            description = "Create a new issue in a GitHub repository",
            properties = mapOf(
                "owner" to propString("Repository owner"),
                "repo" to propString("Repository name"),
                "title" to propString("Issue title"),
                "body" to propString("Issue body/description (optional)"),
                "labels" to propArray("Labels to apply to the issue", "string"),
                "assignees" to propArray("Usernames to assign to the issue", "string")
            ),
            required = listOf("owner", "repo", "title")
        ),
        buildToolSchema(
            name = "list_issues",
            description = "List issues in a GitHub repository with optional filters",
            properties = mapOf(
                "owner" to propString("Repository owner"),
                "repo" to propString("Repository name"),
                "state" to propString("Filter by state: open, closed, or all (default: open)"),
                "labels" to propString("Comma-separated list of label names to filter by"),
                "assignee" to propString("Filter by assignee username, 'none' for unassigned, '*' for any"),
                "sort" to propString("Sort by: created, updated, comments (default: created)"),
                "direction" to propString("Sort direction: asc or desc (default: desc)"),
                "page" to propInt("Page number (default: 1)"),
                "per_page" to propInt("Results per page, max 100 (default: 30)")
            ),
            required = listOf("owner", "repo")
        ),
        buildToolSchema(
            name = "create_pull_request",
            description = "Create a new pull request in a GitHub repository",
            properties = mapOf(
                "owner" to propString("Repository owner"),
                "repo" to propString("Repository name"),
                "title" to propString("Pull request title"),
                "body" to propString("Pull request description (optional)"),
                "head" to propString("The branch that contains your changes (e.g., 'feature-branch' or 'username:feature-branch')"),
                "base" to propString("The branch you want to merge into (e.g., 'main')"),
                "draft" to propBool("Whether to create as a draft PR (default: false)")
            ),
            required = listOf("owner", "repo", "title", "head", "base")
        ),
        buildToolSchema(
            name = "search_code",
            description = "Search for code across GitHub repositories",
            properties = mapOf(
                "query" to propString("Search query (GitHub code search syntax)"),
                "page" to propInt("Page number for pagination (default: 1)"),
                "per_page" to propInt("Results per page, max 100 (default: 30)")
            ),
            required = listOf("query")
        ),
        buildToolSchema(
            name = "get_pull_request",
            description = "Get details of a specific pull request",
            properties = mapOf(
                "owner" to propString("Repository owner"),
                "repo" to propString("Repository name"),
                "pull_number" to propInt("Pull request number")
            ),
            required = listOf("owner", "repo", "pull_number")
        ),
        buildToolSchema(
            name = "list_commits",
            description = "List recent commits in a GitHub repository",
            properties = mapOf(
                "owner" to propString("Repository owner"),
                "repo" to propString("Repository name"),
                "sha" to propString("Branch name or commit SHA to list commits from (optional)"),
                "author" to propString("Filter by commit author username (optional)"),
                "since" to propString("Only commits after this date (ISO 8601 format, optional)"),
                "until" to propString("Only commits before this date (ISO 8601 format, optional)"),
                "page" to propInt("Page number (default: 1)"),
                "per_page" to propInt("Results per page, max 100 (default: 30)")
            ),
            required = listOf("owner", "repo")
        )
    )

    override suspend fun executeTool(name: String, input: JSONObject): ToolResult {
        return try {
            when (name) {
                "search_repositories" -> searchRepositories(input)
                "get_file_contents" -> getFileContents(input)
                "create_or_update_file" -> createOrUpdateFile(input)
                "create_issue" -> createIssue(input)
                "list_issues" -> listIssues(input)
                "create_pull_request" -> createPullRequest(input)
                "search_code" -> searchCode(input)
                "get_pull_request" -> getPullRequest(input)
                "list_commits" -> listCommits(input)
                else -> ToolResult(isError = true, output = "Unknown tool: $name")
            }
        } catch (e: GitHubApiException) {
            ToolResult(isError = true, content = e.message ?: "GitHub API error")
        } catch (e: IOException) {
            ToolResult(isError = true, output = "Network error: ${e.message}")
        } catch (e: Exception) {
            ToolResult(isError = true, output = "Unexpected error: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    // --- Tool Implementations ---

    private suspend fun searchRepositories(input: JSONObject): ToolResult {
        val query = input.getString("query")
        val page = input.optInt("page", 1)
        val perPage = input.optInt("per_page", 30).coerceIn(1, 100)

        val url = "$baseUrl/search/repositories?q=${urlEncode(query)}&page=$page&per_page=$perPage"
        val response = get(url)

        val totalCount = response.optInt("total_count", 0)
        val items = response.optJSONArray("items") ?: JSONArray()

        val sb = StringBuilder()
        sb.appendLine("Found $totalCount repositories (showing ${items.length()} on page $page):")
        sb.appendLine()

        for (i in 0 until items.length()) {
            val repo = items.getJSONObject(i)
            sb.appendLine("**${repo.getString("full_name")}**")
            val description = repo.optString("description", "").takeIf { it.isNotEmpty() && it != "null" }
            if (description != null) {
                sb.appendLine("  $description")
            }
            sb.appendLine("  ⭐ ${repo.optInt("stargazers_count", 0)} | " +
                    "🍴 ${repo.optInt("forks_count", 0)} | " +
                    "Language: ${repo.optString("language", "N/A")} | " +
                    "Updated: ${repo.optString("updated_at", "N/A")}")
            sb.appendLine("  URL: ${repo.optString("html_url", "")}")
            sb.appendLine()
        }

        return ToolResult(content = sb.toString().trimEnd())
    }

    private suspend fun getFileContents(input: JSONObject): ToolResult {
        val owner = input.getString("owner")
        val repo = input.getString("repo")
        val path = input.getString("path")
        val branch = input.optString("branch", "").takeIf { it.isNotEmpty() && it != "null" }

        var url = "$baseUrl/repos/$owner/$repo/contents/${urlEncode(path)}"
        if (branch != null) {
            url += "?ref=${urlEncode(branch)}"
        }

        val response = getRaw(url)

        // Could be a file or directory
        return if (response.trimStart().startsWith("[")) {
            // Directory listing
            val items = JSONArray(response)
            val sb = StringBuilder()
            sb.appendLine("Directory: $path")
            sb.appendLine()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val type = item.optString("type", "file")
                val name = item.optString("name", "")
                val size = item.optInt("size", 0)
                val icon = when (type) {
                    "dir" -> "📁"
                    "file" -> "📄"
                    "symlink" -> "🔗"
                    else -> "❓"
                }
                sb.appendLine("$icon $name${if (type == "file") " ($size bytes)" else ""}")
            }
            ToolResult(content = sb.toString().trimEnd())
        } else {
            // Single file
            val json = JSONObject(response)
            val type = json.optString("type", "file")

            if (type == "file") {
                val encoding = json.optString("encoding", "")
                val content = if (encoding == "base64") {
                    val raw = json.optString("content", "").replace("\n", "").replace("\r", "")
                    try {
                        String(Base64.getDecoder().decode(raw))
                    } catch (e: Exception) {
                        "[Binary file - cannot display content]"
                    }
                } else {
                    json.optString("content", "[No content]")
                }

                val sb = StringBuilder()
                sb.appendLine("File: ${json.optString("name", path)}")
                sb.appendLine("Path: ${json.optString("path", path)}")
                sb.appendLine("Size: ${json.optInt("size", 0)} bytes")
                sb.appendLine("SHA: ${json.optString("sha", "N/A")}")
                sb.appendLine()
                sb.appendLine("---")
                sb.appendLine(content)

                ToolResult(content = sb.toString().trimEnd())
            } else {
                ToolResult(output = "Entry is of type '$type': ${json.optString("name", path)}")
            }
        }
    }

    private suspend fun createOrUpdateFile(input: JSONObject): ToolResult {
        val owner = input.getString("owner")
        val repo = input.getString("repo")
        val path = input.getString("path")
        val content = input.getString("content")
        val message = input.getString("message")
        val branch = input.optString("branch", "").takeIf { it.isNotEmpty() && it != "null" }
        val sha = input.optString("sha", "").takeIf { it.isNotEmpty() && it != "null" }

        val url = "$baseUrl/repos/$owner/$repo/contents/${urlEncode(path)}"

        val body = JSONObject().apply {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
            if (branch != null) put("branch", branch)
            if (sha != null) put("sha", sha)
        }

        val response = put(url, body)

        val fileInfo = response.optJSONObject("content")
        val commit = response.optJSONObject("commit")

        val sb = StringBuilder()
        val action = if (sha != null) "Updated" else "Created"
        sb.appendLine("$action file successfully!")
        sb.appendLine()
        if (fileInfo != null) {
            sb.appendLine("Path: ${fileInfo.optString("path", path)}")
            sb.appendLine("SHA: ${fileInfo.optString("sha", "N/A")}")
            sb.appendLine("Size: ${fileInfo.optInt("size", 0)} bytes")
            sb.appendLine("URL: ${fileInfo.optString("html_url", "N/A")}")
        }
        if (commit != null) {
            sb.appendLine()
            sb.appendLine("Commit: ${commit.optString("sha", "N/A")}")
            sb.appendLine("Message: ${commit.optString("message", message)}")
            sb.appendLine("URL: ${commit.optString("html_url", "N/A")}")
        }

        return ToolResult(content = sb.toString().trimEnd())
    }

    private suspend fun createIssue(input: JSONObject): ToolResult {
        val owner = input.getString("owner")
        val repo = input.getString("repo")
        val title = input.getString("title")
        val body = input.optString("body", "").takeIf { it.isNotEmpty() && it != "null" }
        val labels = input.optJSONArray("labels")
        val assignees = input.optJSONArray("assignees")

        val url = "$baseUrl/repos/$owner/$repo/issues"

        val requestBody = JSONObject().apply {
            put("title", title)
            if (body != null) put("body", body)
            if (labels != null && labels.length() > 0) put("labels", labels)
            if (assignees != null && assignees.length() > 0) put("assignees", assignees)
        }

        val response = post(url, requestBody)

        val sb = StringBuilder()
        sb.appendLine("Issue created successfully!")
        sb.appendLine()
        sb.appendLine("Number: #${response.optInt("number", 0)}")
        sb.appendLine("Title: ${response.optString("title", title)}")
        sb.appendLine("State: ${response.optString("state", "open")}")
        sb.appendLine("URL: ${response.optString("html_url", "N/A")}")

        val user = response.optJSONObject("user")
        if (user != null) {
            sb.appendLine("Created by: ${user.optString("login", "N/A")}")
        }

        val responseLabels = response.optJSONArray("labels")
        if (responseLabels != null && responseLabels.length() > 0) {
            val labelNames = (0 until responseLabels.length()).map {
                responseLabels.getJSONObject(it).optString("name", "")
            }.filter { it.isNotEmpty() }
            if (labelNames.isNotEmpty()) {
                sb.appendLine("Labels: ${labelNames.joinToString(", ")}")
            }
        }

        return ToolResult(content = sb.toString().trimEnd())
    }

    private suspend fun listIssues(input: JSONObject): ToolResult {
        val owner = input.getString("owner")
        val repo = input.getString("repo")
        val state = input.optString("state", "open").takeIf { it.isNotEmpty() && it != "null" } ?: "open"
        val labels = input.optString("labels", "").takeIf { it.isNotEmpty() && it != "null" }
        val assignee = input.optString("assignee", "").takeIf { it.isNotEmpty() && it != "null" }
        val sort = input.optString("sort", "created").takeIf { it.isNotEmpty() && it != "null" } ?: "created"
        val direction = input.optString("direction", "desc").takeIf { it.isNotEmpty() && it != "null" } ?: "desc"
        val page = input.optInt("page", 1)
        val perPage = input.optInt("per_page", 30).coerceIn(1, 100)

        val params = mutableListOf(
            "state=$state",
            "sort=$sort",
            "direction=$direction",
            "page=$page",
            "per_page=$perPage"
        )
        if (labels != null) params.add("labels=${urlEncode(labels)}")
        if (assignee != null) params.add("assignee=${urlEncode(assignee)}")

        val url = "$baseUrl/repos/$owner/$repo/issues?${params.joinToString("&")}"
        val response = getArray(url)

        val sb = StringBuilder()
        sb.appendLine("Issues for $owner/$repo (state: $state, page $page):")
        sb.appendLine()

        if (response.length() == 0) {
            sb.appendLine("No issues found matching the criteria.")
        } else {
            for (i in 0 until response.length()) {
                val issue = response.getJSONObject(i)
                // Skip pull requests (GitHub API includes PRs in issues endpoint)
                if (issue.has("pull_request")) continue

                val number = issue.optInt("number", 0)
                val issueTitle = issue.optString("title", "")
                val issueState = issue.optString("state", "")
                val user = issue.optJSONObject("user")?.optString("login", "N/A") ?: "N/A"
                val createdAt = issue.optString("created_at", "N/A")
                val comments = issue.optInt("comments", 0)

                val stateIcon = if (issueState == "open") "🟢" else "🔴"
                sb.appendLine("$stateIcon #$number: $issueTitle")
                sb.appendLine("   Author: $user | Created: $createdAt | Comments: $comments")

                val issueLabels = issue.optJSONArray("labels")
                if (issueLabels != null && issueLabels.length() > 0) {
                    val labelNames = (0 until issueLabels.length()).map {
                        issueLabels.getJSONObject(it).optString("name", "")
                    }.filter { it.isNotEmpty() }
                    if (labelNames.isNotEmpty()) {
                        sb.appendLine("   Labels: ${labelNames.joinToString(", ")}")
                    }
                }
                sb.appendLine()
            }
        }

        return ToolResult(content = sb.toString().trimEnd())
    }

    private suspend fun createPullRequest(input: JSONObject): ToolResult {
        val owner = input.getString("owner")
        val repo = input.getString("repo")
        val title = input.getString("title")
        val body = input.optString("body", "").takeIf { it.isNotEmpty() && it != "null" }
        val head = input.getString("head")
        val base = input.getString("base")
        val draft = input.optBoolean("draft", false)

        val url = "$baseUrl/repos/$owner/$repo/pulls"

        val requestBody = JSONObject().apply {
            put("title", title)
            put("head", head)
            put("base", base)
            put("draft", draft)
            if (body != null) put("body", body)
        }

        val response = post(url, requestBody)

        val sb = StringBuilder()
        sb.appendLine("Pull request created successfully!")
        sb.appendLine()
        sb.appendLine("Number: #${response.optInt("number", 0)}")
        sb.appendLine("Title: ${response.optString("title", title)}")
        sb.appendLine("State: ${response.optString("state", "open")}")
        sb.appendLine("Draft: ${response.optBoolean("draft", false)}")
        sb.appendLine("URL: ${response.optString("html_url", "N/A")}")
        sb.appendLine()
        sb.appendLine("Head: $head → Base: $base")
        sb.appendLine("Mergeable: ${response.optString("mergeable", "unknown")}")

        val user = response.optJSONObject("user")
        if (user != null) {
            sb.appendLine("Created by: ${user.optString("login", "N/A")}")
        }

        return ToolResult(content = sb.toString().trimEnd())
    }

    private suspend fun searchCode(input: JSONObject): ToolResult {
        val query = input.getString("query")
        val page = input.optInt("page", 1)
        val perPage = input.optInt("per_page", 30).coerceIn(1, 100)

        val url = "$baseUrl/search/code?q=${urlEncode(query)}&page=$page&per_page=$perPage"
        val response = get(url)

        val totalCount = response.optInt("total_count", 0)
        val items = response.optJSONArray("items") ?: JSONArray()

        val sb = StringBuilder()
        sb.appendLine("Found $totalCount code results (showing ${items.length()} on page $page):")
        sb.appendLine()

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val name = item.optString("name", "")
            val filePath = item.optString("path", "")
            val repoObj = item.optJSONObject("repository")
            val repoName = repoObj?.optString("full_name", "N/A") ?: "N/A"
            val htmlUrl = item.optString("html_url", "")

            sb.appendLine("📄 **$name** in $repoName")
            sb.appendLine("   Path: $filePath")
            sb.appendLine("   URL: $htmlUrl")
            sb.appendLine()
        }

        return ToolResult(content = sb.toString().trimEnd())
    }

    private suspend fun getPullRequest(input: JSONObject): ToolResult {
        val owner = input.getString("owner")
        val repo = input.getString("repo")
        val pullNumber = input.getInt("pull_number")

        val url = "$baseUrl/repos/$owner/$repo/pulls/$pullNumber"
        val response = get(url)

        val sb = StringBuilder()
        sb.appendLine("Pull Request #$pullNumber")
        sb.appendLine()
        sb.appendLine("Title: ${response.optString("title", "N/A")}")
        sb.appendLine("State: ${response.optString("state", "N/A")}")
        sb.appendLine("Draft: ${response.optBoolean("draft", false)}")
        sb.appendLine("Merged: ${response.optBoolean("merged", false)}")
        sb.appendLine("URL: ${response.optString("html_url", "N/A")}")
        sb.appendLine()

        val user = response.optJSONObject("user")
        if (user != null) {
            sb.appendLine("Author: ${user.optString("login", "N/A")}")
        }

        val head = response.optJSONObject("head")
        val base = response.optJSONObject("base")
        if (head != null && base != null) {
            sb.appendLine("Head: ${head.optString("label", "N/A")} (${head.optString("sha", "N/A").take(7)})")
            sb.appendLine("Base: ${base.optString("label", "N/A")} (${base.optString("sha", "N/A").take(7)})")
        }

        sb.appendLine()
        sb.appendLine("Commits: ${response.optInt("commits", 0)}")
        sb.appendLine("Additions: +${response.optInt("additions", 0)}")
        sb.appendLine("Deletions: -${response.optInt("deletions", 0)}")
        sb.appendLine("Changed files: ${response.optInt("changed_files", 0)}")
        sb.appendLine("Mergeable: ${response.optString("mergeable", "unknown")}")
        sb.appendLine("Mergeable state: ${response.optString("mergeable_state", "unknown")}")

        val prBody = response.optString("body", "").takeIf { it.isNotEmpty() && it != "null" }
        if (prBody != null) {
            sb.appendLine()
            sb.appendLine("--- Description ---")
            sb.appendLine(prBody)
        }

        sb.appendLine()
        sb.appendLine("Created: ${response.optString("created_at", "N/A")}")
        sb.appendLine("Updated: ${response.optString("updated_at", "N/A")}")

        val mergedAt = response.optString("merged_at", "").takeIf { it.isNotEmpty() && it != "null" }
        if (mergedAt != null) {
            sb.appendLine("Merged at: $mergedAt")
            val mergedBy = response.optJSONObject("merged_by")
            if (mergedBy != null) {
                sb.appendLine("Merged by: ${mergedBy.optString("login", "N/A")}")
            }
        }

        return ToolResult(content = sb.toString().trimEnd())
    }

    private suspend fun listCommits(input: JSONObject): ToolResult {
        val owner = input.getString("owner")
        val repo = input.getString("repo")
        val sha = input.optString("sha", "").takeIf { it.isNotEmpty() && it != "null" }
        val author = input.optString("author", "").takeIf { it.isNotEmpty() && it != "null" }
        val since = input.optString("since", "").takeIf { it.isNotEmpty() && it != "null" }
        val until = input.optString("until", "").takeIf { it.isNotEmpty() && it != "null" }
        val page = input.optInt("page", 1)
        val perPage = input.optInt("per_page", 30).coerceIn(1, 100)

        val params = mutableListOf(
            "page=$page",
            "per_page=$perPage"
        )
        if (sha != null) params.add("sha=${urlEncode(sha)}")
        if (author != null) params.add("author=${urlEncode(author)}")
        if (since != null) params.add("since=${urlEncode(since)}")
        if (until != null) params.add("until=${urlEncode(until)}")

        val url = "$baseUrl/repos/$owner/$repo/commits?${params.joinToString("&")}"
        val response = getArray(url)

        val sb = StringBuilder()
        sb.appendLine("Commits for $owner/$repo (page $page):")
        sb.appendLine()

        if (response.length() == 0) {
            sb.appendLine("No commits found matching the criteria.")
        } else {
            for (i in 0 until response.length()) {
                val commitObj = response.getJSONObject(i)
                val commitSha = commitObj.optString("sha", "N/A")
                val commit = commitObj.optJSONObject("commit")
                val message = commit?.optString("message", "N/A")?.lines()?.firstOrNull() ?: "N/A"
                val authorObj = commit?.optJSONObject("author")
                val authorName = authorObj?.optString("name", "N/A") ?: "N/A"
                val date = authorObj?.optString("date", "N/A") ?: "N/A"

                sb.appendLine("• ${commitSha.take(7)} $message")
                sb.appendLine("  Author: $authorName | Date: $date")
                sb.appendLine()
            }
        }

        return ToolResult(content = sb.toString().trimEnd())
    }

    // --- HTTP Helpers ---

    private suspend fun get(url: String): JSONObject {
        val responseBody = executeRequest(buildGetRequest(url))
        return JSONObject(responseBody)
    }

    private suspend fun getArray(url: String): JSONArray {
        val responseBody = executeRequest(buildGetRequest(url))
        return JSONArray(responseBody)
    }

    private suspend fun getRaw(url: String): String {
        return executeRequest(buildGetRequest(url))
    }

    private suspend fun post(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "GooseAndroid-GitHubExtension/1.0")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val responseBody = executeRequest(request)
        return JSONObject(responseBody)
    }

    private suspend fun put(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "GooseAndroid-GitHubExtension/1.0")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .put(body.toString().toRequestBody(jsonMediaType))
            .build()

        val responseBody = executeRequest(request)
        return JSONObject(responseBody)
    }

    private fun buildGetRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "GooseAndroid-GitHubExtension/1.0")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
    }

    private suspend fun executeRequest(request: Request): String {
        return withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMessage = parseErrorMessage(responseBody, response.code)
                throw GitHubApiException(errorMessage)
            }

            responseBody
        }
    }

    private fun parseErrorMessage(body: String, statusCode: Int): String {
        val prefix = when (statusCode) {
            401 -> "Authentication failed"
            403 -> {
                if (body.contains("rate limit", ignoreCase = true)) {
                    "Rate limit exceeded"
                } else {
                    "Access forbidden"
                }
            }
            404 -> "Resource not found"
            409 -> "Conflict"
            422 -> "Validation failed"
            else -> "GitHub API error (HTTP $statusCode)"
        }

        return try {
            val json = JSONObject(body)
            val message = json.optString("message", "")
            val errors = json.optJSONArray("errors")

            val details = buildString {
                append("$prefix: $message")
                if (errors != null && errors.length() > 0) {
                    append("\nDetails:")
                    for (i in 0 until errors.length()) {
                        val error = errors.get(i)
                        if (error is JSONObject) {
                            val field = error.optString("field", "")
                            val code = error.optString("code", "")
                            val errorMsg = error.optString("message", "")
                            append("\n  - ${field.ifEmpty { "error" }}: ${errorMsg.ifEmpty { code }}")
                        } else {
                            append("\n  - $error")
                        }
                    }
                }
                if (statusCode == 403 && body.contains("rate limit", ignoreCase = true)) {
                    append("\nPlease wait before making more requests.")
                }
            }
            details
        } catch (e: Exception) {
            "$prefix: $body".take(500)
        }
    }

    // --- Schema Helpers ---

    private fun buildToolSchema(
        name: String,
        description: String,
        properties: Map<String, JSONObject>,
        required: List<String>
    ): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    properties.forEach { (key, value) -> put(key, value) }
                })
                put("required", JSONArray(required))
            })
        }
    }

    private fun propString(description: String): JSONObject {
        return JSONObject().apply {
            put("type", "string")
            put("description", description)
        }
    }

    private fun propInt(description: String): JSONObject {
        return JSONObject().apply {
            put("type", "integer")
            put("description", description)
        }
    }

    private fun propBool(description: String): JSONObject {
        return JSONObject().apply {
            put("type", "boolean")
            put("description", description)
        }
    }

    private fun propArray(description: String, itemType: String): JSONObject {
        return JSONObject().apply {
            put("type", "array")
            put("description", description)
            put("items", JSONObject().apply {
                put("type", itemType)
            })
        }
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }

    // --- Exceptions ---

    private class GitHubApiException(message: String) : Exception(message)
}

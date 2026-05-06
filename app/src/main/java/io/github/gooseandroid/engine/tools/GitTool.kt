package io.github.gooseandroid.engine.tools

import android.util.Log
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Native Git tool using JGit — pure Java/Kotlin, no binary needed.
 *
 * Supports all core git operations:
 * - clone, init, status, add, commit, push, pull
 * - log, diff, branch, checkout, merge
 * - remote management
 *
 * This gives the AI full git capabilities on Android without
 * needing a native git binary or Termux.
 */
class GitTool(private val workingDir: File) {

    companion object {
        private const val TAG = "GitTool"

        val DEFINITION = JSONObject().apply {
            put("name", "git")
            put("description", "Execute git operations. Supports: clone, init, status, add, commit, " +
                "push, pull, log, diff, branch, checkout, merge, remote. " +
                "All operations run natively via JGit — no external binary needed.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "Git command: clone, init, status, add, commit, push, pull, " +
                            "log, diff, branch, checkout, merge, remote")
                    })
                    put("args", JSONObject().apply {
                        put("type", "object")
                        put("description", "Command-specific arguments as key-value pairs. " +
                            "Examples: {\"url\": \"...\"} for clone, {\"message\": \"...\"} for commit, " +
                            "{\"files\": [\"file.txt\"]} for add, {\"branch\": \"main\"} for checkout")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Repository path (relative to workspace). Defaults to current directory.")
                    })
                })
                put("required", org.json.JSONArray().apply { put("command") })
            })
        }
    }

    fun execute(input: JSONObject): ToolResult {
        val command = input.optString("command", "")
        val args = input.optJSONObject("args") ?: JSONObject()
        val path = input.optString("path", "")
        val repoDir = if (path.isNotBlank()) File(workingDir, path) else workingDir

        return try {
            when (command.lowercase()) {
                "clone" -> clone(args, repoDir)
                "init" -> init(repoDir)
                "status" -> status(repoDir)
                "add" -> add(args, repoDir)
                "commit" -> commit(args, repoDir)
                "push" -> push(args, repoDir)
                "pull" -> pull(args, repoDir)
                "log" -> log(args, repoDir)
                "diff" -> diff(args, repoDir)
                "branch" -> branch(args, repoDir)
                "checkout" -> checkout(args, repoDir)
                "merge" -> merge(args, repoDir)
                "remote" -> remote(args, repoDir)
                else -> ToolResult("Unknown git command: $command. " +
                    "Supported: clone, init, status, add, commit, push, pull, log, diff, branch, checkout, merge, remote",
                    isError = true)
            }
        } catch (e: GitAPIException) {
            Log.e(TAG, "Git error: ${e.message}", e)
            ToolResult("Git error: ${e.message}", isError = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            ToolResult("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    private fun clone(args: JSONObject, targetDir: File): ToolResult {
        val url = args.optString("url", "")
        if (url.isBlank()) return ToolResult("Missing 'url' argument for clone", isError = true)

        val branch = args.optString("branch", "")
        val depth = args.optInt("depth", 0)

        val cloneCommand = Git.cloneRepository()
            .setURI(url)
            .setDirectory(targetDir)

        if (branch.isNotBlank()) cloneCommand.setBranch(branch)
        if (depth > 0) cloneCommand.setDepth(depth)

        // Add credentials — JGit requires a CredentialsProvider even for public repos
        val token = args.optString("token", "")
        if (token.isNotBlank()) {
            cloneCommand.setCredentialsProvider(
                UsernamePasswordCredentialsProvider(token, "")
            )
        } else {
            // Anonymous access: provide empty credentials for public repos
            cloneCommand.setCredentialsProvider(
                UsernamePasswordCredentialsProvider("", "")
            )
        }

        val git = cloneCommand.call()
        val head = git.repository.resolve("HEAD")
        git.close()

        return ToolResult("Cloned $url to ${targetDir.name}\nHEAD: ${head?.name?.take(8) ?: "unknown"}")
    }

    private fun init(repoDir: File): ToolResult {
        repoDir.mkdirs()
        val git = Git.init().setDirectory(repoDir).call()
        git.close()
        return ToolResult("Initialized empty git repository in ${repoDir.absolutePath}")
    }

    private fun status(repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository: ${repoDir.path}", isError = true)
        val status = git.status().call()
        val sb = StringBuilder()

        sb.appendLine("On branch: ${git.repository.branch}")

        if (status.added.isNotEmpty()) sb.appendLine("Added: ${status.added.joinToString(", ")}")
        if (status.changed.isNotEmpty()) sb.appendLine("Changed: ${status.changed.joinToString(", ")}")
        if (status.removed.isNotEmpty()) sb.appendLine("Removed: ${status.removed.joinToString(", ")}")
        if (status.modified.isNotEmpty()) sb.appendLine("Modified: ${status.modified.joinToString(", ")}")
        if (status.untracked.isNotEmpty()) sb.appendLine("Untracked: ${status.untracked.joinToString(", ")}")
        if (status.conflicting.isNotEmpty()) sb.appendLine("Conflicting: ${status.conflicting.joinToString(", ")}")

        if (status.isClean) sb.appendLine("Working tree clean")

        git.close()
        return ToolResult(sb.toString().trim())
    }

    private fun add(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val files = args.optJSONArray("files")
        val all = args.optBoolean("all", false)

        if (all) {
            git.add().addFilepattern(".").call()
            git.close()
            return ToolResult("Added all files")
        }

        if (files == null || files.length() == 0) {
            git.close()
            return ToolResult("No files specified. Use {\"files\": [\"file.txt\"]} or {\"all\": true}", isError = true)
        }

        val addCommand = git.add()
        for (i in 0 until files.length()) {
            addCommand.addFilepattern(files.getString(i))
        }
        addCommand.call()
        git.close()

        return ToolResult("Added ${files.length()} file(s)")
    }

    private fun commit(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val message = args.optString("message", "")
        if (message.isBlank()) {
            git.close()
            return ToolResult("Missing 'message' argument for commit", isError = true)
        }

        val author = args.optString("author", "Goose")
        val email = args.optString("email", "goose@local")

        val commitResult = git.commit()
            .setMessage(message)
            .setAuthor(PersonIdent(author, email))
            .call()

        git.close()
        return ToolResult("Committed: ${commitResult.id.name.take(8)} - $message")
    }

    private fun push(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val remote = args.optString("remote", "origin")
        val branch = args.optString("branch", "")
        val token = args.optString("token", "")

        val pushCommand = git.push().setRemote(remote)
        if (branch.isNotBlank()) pushCommand.add(branch)
        if (token.isNotBlank()) {
            pushCommand.setCredentialsProvider(UsernamePasswordCredentialsProvider(token, ""))
        }

        val results = pushCommand.call()
        val sb = StringBuilder("Push to $remote:\n")
        for (result in results) {
            for (update in result.remoteUpdates) {
                sb.appendLine("  ${update.remoteName}: ${update.status}")
            }
        }

        git.close()
        return ToolResult(sb.toString().trim())
    }

    private fun pull(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val remote = args.optString("remote", "origin")
        val token = args.optString("token", "")

        val pullCommand = git.pull().setRemote(remote)
        if (token.isNotBlank()) {
            pullCommand.setCredentialsProvider(UsernamePasswordCredentialsProvider(token, ""))
        } else {
            pullCommand.setCredentialsProvider(UsernamePasswordCredentialsProvider("", ""))
        }

        val result = pullCommand.call()
        git.close()

        return if (result.isSuccessful) {
            ToolResult("Pull successful. ${result.mergeResult?.mergeStatus ?: ""}")
        } else {
            ToolResult("Pull failed: ${result.mergeResult?.mergeStatus}", isError = true)
        }
    }

    private fun log(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val maxCount = args.optInt("count", 10)

        val logCommand = git.log().setMaxCount(maxCount)
        val commits = logCommand.call()

        val sb = StringBuilder()
        for (commit in commits) {
            val shortId = commit.id.name.take(8)
            val author = commit.authorIdent.name
            val date = commit.authorIdent.`when`
            val msg = commit.shortMessage
            sb.appendLine("$shortId $msg ($author, $date)")
        }

        git.close()
        return ToolResult(sb.toString().trimEnd().ifBlank { "No commits yet" })
    }

    private fun diff(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val cached = args.optBoolean("cached", false)

        val outputStream = ByteArrayOutputStream()
        val formatter = DiffFormatter(outputStream)
        formatter.setRepository(git.repository)

        val reader = git.repository.newObjectReader()
        val headTree = if (git.repository.resolve("HEAD") != null) {
            val revWalk = RevWalk(git.repository)
            val headCommit = revWalk.parseCommit(git.repository.resolve("HEAD"))
            headCommit.tree
        } else null

        if (cached && headTree != null) {
            val indexTreeParser = org.eclipse.jgit.treewalk.CanonicalTreeParser()
            indexTreeParser.reset(reader, headTree)
            formatter.scan(indexTreeParser, org.eclipse.jgit.dircache.DirCacheIterator(git.repository.readDirCache()))
        } else {
            val diffs = git.diff().setCached(cached).call()
            for (entry in diffs) {
                formatter.format(entry)
            }
        }

        formatter.flush()
        git.close()

        val output = outputStream.toString()
        return ToolResult(output.ifBlank { "No changes" })
    }

    private fun branch(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val create = args.optString("create", "")
        val delete = args.optString("delete", "")
        val list = args.optBoolean("list", create.isBlank() && delete.isBlank())

        if (create.isNotBlank()) {
            git.branchCreate().setName(create).call()
            git.close()
            return ToolResult("Created branch: $create")
        }

        if (delete.isNotBlank()) {
            git.branchDelete().setBranchNames(delete).setForce(true).call()
            git.close()
            return ToolResult("Deleted branch: $delete")
        }

        if (list) {
            val branches = git.branchList().call()
            val current = git.repository.branch
            val sb = StringBuilder()
            for (ref in branches) {
                val name = ref.name.removePrefix("refs/heads/")
                val marker = if (name == current) "* " else "  "
                sb.appendLine("$marker$name")
            }
            git.close()
            return ToolResult(sb.toString().trimEnd().ifBlank { "No branches" })
        }

        git.close()
        return ToolResult("Use {\"create\": \"name\"}, {\"delete\": \"name\"}, or {\"list\": true}")
    }

    private fun checkout(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val branch = args.optString("branch", "")
        val create = args.optBoolean("create", false)

        if (branch.isBlank()) {
            git.close()
            return ToolResult("Missing 'branch' argument", isError = true)
        }

        val checkoutCommand = git.checkout().setName(branch)
        if (create) checkoutCommand.setCreateBranch(true)
        checkoutCommand.call()

        git.close()
        return ToolResult("Switched to branch: $branch${if (create) " (new)" else ""}")
    }

    private fun merge(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val branch = args.optString("branch", "")
        if (branch.isBlank()) {
            git.close()
            return ToolResult("Missing 'branch' argument for merge", isError = true)
        }

        val ref = git.repository.findRef(branch)
        if (ref == null) {
            git.close()
            return ToolResult("Branch not found: $branch", isError = true)
        }

        val result = git.merge().include(ref).call()
        git.close()
        return ToolResult("Merge ${result.mergeStatus}: $branch into ${git.repository.branch}")
    }

    private fun remote(args: JSONObject, repoDir: File): ToolResult {
        val git = openRepo(repoDir) ?: return ToolResult("Not a git repository", isError = true)
        val action = args.optString("action", "list")

        when (action) {
            "add" -> {
                val name = args.optString("name", "")
                val url = args.optString("url", "")
                if (name.isBlank() || url.isBlank()) {
                    git.close()
                    return ToolResult("Missing 'name' or 'url' for remote add", isError = true)
                }
                git.remoteAdd().setName(name).setUri(org.eclipse.jgit.transport.URIish(url)).call()
                git.close()
                return ToolResult("Added remote: $name → $url")
            }
            "list" -> {
                val remotes = git.remoteList().call()
                val sb = StringBuilder()
                for (remote in remotes) {
                    sb.appendLine("${remote.name}: ${remote.urIs.firstOrNull() ?: "no url"}")
                }
                git.close()
                return ToolResult(sb.toString().trimEnd().ifBlank { "No remotes configured" })
            }
            else -> {
                git.close()
                return ToolResult("Unknown remote action: $action. Use 'add' or 'list'")
            }
        }
    }

    /**
     * Wrapper that implements the Tool interface for registration in ToolRouter.
     */
    inner class AsRegisteredTool : Tool {
        override val name = "git"
        override val description = "Execute git operations (clone, init, status, add, commit, push, pull, log, diff, branch, checkout, merge, remote)"
        override fun getSchema(): JSONObject = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "git")
                put("description", description)
                put("parameters", DEFINITION.getJSONObject("input_schema"))
            })
        }
        override suspend fun execute(input: JSONObject): ToolResult = this@GitTool.execute(input)
    }

    private fun openRepo(dir: File): Git? {
        return try {
            val repo = FileRepositoryBuilder()
                .setGitDir(File(dir, ".git"))
                .readEnvironment()
                .findGitDir()
                .build()
            Git(repo)
        } catch (e: Exception) {
            Log.w(TAG, "Not a git repo: ${dir.path}: ${e.message}")
            null
        }
    }
}

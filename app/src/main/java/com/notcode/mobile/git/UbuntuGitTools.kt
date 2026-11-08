package com.notcode.mobile.git

import com.notcode.mobile.agent.ToolExecutionResult
import com.notcode.mobile.filesystem.ProjectPath
import com.notcode.mobile.llm.LlmTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.net.URI

/** The executor owns command approval and must start in the canonical Ubuntu workspace root.
 * Authentication is configured by the user in the Ubuntu PTY, never through tool arguments.
 */
class UbuntuGitTools(
    private val execute: suspend (command: String) -> String,
    private val approveRemote: suspend (description: String) -> Boolean,
) {
    suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        return try {
            require(arguments.keys.all { it in FIELDS }) { "Unsupported argument; credentials and raw commands are not accepted" }
            val action = arguments.string("action") ?: error("action is required")
            require(action in ACTIONS) { "Unsupported Git action" }
            val path = safePath(arguments.string("path").orEmpty())
            val branch = arguments.string("branch")?.also { validateBranch(it) }
            val repo = arguments.string("repoUrl")?.also { validateUrl(it) }
            val name = arguments.string("name")
            val message = arguments.string("message")
            val privateRepo = arguments["private"]?.let {
                require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "private must be a boolean" }
                it.boolean
            } ?: true
            require(repo == null || action == "clone") { "repoUrl is only supported for clone" }
            require(name == null || action == "create_github_repo") { "name is only supported for create_github_repo" }
            require(message == null || action == "commit") { "message is only supported for commit" }
            require(branch == null || action in setOf("init", "branch", "checkout", "clone", "pull", "push")) { "branch is not supported for this action" }
            val args = when (action) {
                "init" -> listOf("init") + (branch?.let { listOf("--initial-branch", it) } ?: emptyList())
                "status" -> listOf("status", "--short", "--untracked-files=normal")
                "diff" -> listOf("diff", "--no-ext-diff", "--no-textconv", "--stat", "--", ".")
                "log" -> listOf("log", "-20", "--format=%h %cs", "--no-decorate")
                "add" -> listOf("add", "--all", "--", ".")
                "commit" -> {
                    require(!message.isNullOrBlank() && message.length <= 2000 && message.none { it == '\u0000' || it == '\r' }) { "A commit message of at most 2000 characters is required" }
                    listOf("commit", "-m", message)
                }
                "branch" -> listOf("branch") + (branch?.let { listOf("--", it) } ?: listOf("--list", "--no-color"))
                "checkout" -> listOf("checkout", "--no-recurse-submodules", branch ?: error("branch is required"), "--")
                "clone" -> {
                    require(repo != null) { "repoUrl is required" }
                    require(path.isNotEmpty()) { "clone requires a new project-relative destination path" }
                    listOf("clone", "--no-recurse-submodules") +
                        (branch?.let { listOf("--branch", it) } ?: emptyList()) + listOf("--", repo, path)
                }
                "fetch" -> listOf("fetch", "--no-recurse-submodules", "origin")
                "pull" -> listOf("pull", "--ff-only", "--no-recurse-submodules", "origin") + listOfNotNull(branch)
                "push" -> listOf("push", "--no-follow-tags", "origin", branch ?: "HEAD")
                else -> {
                    require(name != null && Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}").matches(name)) { "name must be owner/repository" }
                    listOf("repo", "create", name, if (privateRepo) "--private" else "--public", "--source", ".", "--remote", "origin")
                }
            }
            if (action in setOf("push", "create_github_repo") &&
                !approveRemote(if (action == "push") "Push ${branch ?: "HEAD"} from ${path.ifEmpty { "workspace root" }} to origin" else "Create ${if (privateRepo) "private" else "public"} GitHub repository $name from ${path.ifEmpty { "workspace root" }} (without pushing)")) {
                return failure("USER_DENIED", "Remote operation was not approved")
            }
            val output = execute(command(action, path, args))
            // Never expose raw process errors: Git/SSH/gh may echo credentials from local config.
            val code = Regex("NOTCODE_GIT_EXIT=([0-9]+)").findAll(output).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            when (code) {
                127 -> failure("MISSING_TOOL", "Install ${if (action == "create_github_repo") "Git and GitHub CLI (gh)" else "Git"} yourself in the Ubuntu terminal, then retry. Nothing was installed automatically.")
                125 -> failure("INVALID_PATH", "Path resolves outside the Ubuntu workspace")
                0 -> ToolExecutionResult(buildJsonObject {
                    put("ok", true)
                    // Only bounded metadata is returned. Diff is a stat, log excludes messages/emails.
                    put("output", if (action in setOf("status", "diff", "log", "branch")) redact(output.substringBeforeLast("NOTCODE_GIT_EXIT=", output)).take(16384) else "Git operation completed")
                }.toString(), "Ubuntu Git: $action")
                else -> failure("GIT_FAILED", "Git operation failed. Check repository state and installed tools in the Ubuntu interactive terminal; authenticate there using Git HTTPS, SSH or gh auth login, then retry. Agent prompts are disabled; never send keys or tokens to the model.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            failure("INVALID_ARGUMENTS", "Invalid Git arguments. Use project-relative paths, valid branches and credential-free public-host HTTPS or ssh://git@host repository URLs; credentials and raw options are prohibited.")
        } catch (_: Exception) {
            failure("GIT_FAILED", "Git operation could not complete. Check the Ubuntu interactive terminal; install Git/gh or authenticate there if needed. Do not send credentials to the model.")
        }
    }

    private fun command(action: String, path: String, args: List<String>): String {
        val gh = action == "create_github_repo"
        val cwd = if (action == "clone") "." else path.ifEmpty { "." }
        val invocation = if (gh) {
            "(cd ${quote(cwd)} && gh ${args.joinToString(" ", transform = ::quote)})"
        } else {
            (listOf("git", "--no-pager", "-c", "protocol.file.allow=never", "-c", "protocol.ext.allow=never", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "submodule.recurse=false", "-C", cwd) + args).joinToString(" ", transform = ::quote)
        }
        // realpath runs inside Ubuntu, not against Android/host paths. -m also handles a new clone target.
        return "(root=\$(pwd -P); target=\$(realpath -m -- ${quote(path.ifEmpty { "." })}) || exit 125; " +
            "case \"\$target/\" in \"\$root/\"*) ;; *) exit 125 ;; esac; " +
            "case \"\$target/\" in */.git/*|*/.ssh/*) exit 125 ;; esac; " +
            "command -v git >/dev/null 2>&1 || exit 127; " +
            (if (gh) "command -v gh >/dev/null 2>&1 || exit 127; " else "") +
            "export GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/bin/false SSH_ASKPASS=/bin/false GH_PROMPT_DISABLED=1 GIT_SSH_COMMAND='ssh -o BatchMode=yes'; " +
            "$invocation); rc=\$?; printf '\\nNOTCODE_GIT_EXIT=%s\\n' \"\$rc\""
    }

    private fun safePath(raw: String): String {
        require(raw.length <= 1024 && raw.none { it.isISOControl() })
        val path = ProjectPath.parse(raw)
        require(path.value.split('/').none { it.startsWith('-') || it.equals(".git", true) || it.equals(".ssh", true) })
        return path.value
    }

    private fun validateBranch(branch: String) {
        require(branch.length in 1..200 && !branch.startsWith('-') && !branch.startsWith('/') && !branch.endsWith('/') && !branch.endsWith('.') && !branch.endsWith(".lock") &&
            branch != "@" && branch.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock") } &&
            branch.none { it.isWhitespace() || it.isISOControl() || it in "~^:?*[\\" } && ".." !in branch && "@{" !in branch)
    }

    private fun validateUrl(value: String) {
        require(value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Invalid repository URL") }
        require(uri.scheme in setOf("https", "ssh") && uri.rawQuery == null && uri.rawFragment == null)
        require(if (uri.scheme == "https") uri.rawUserInfo == null else uri.rawUserInfo == "git")
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException()
        // Reject all IP literals, including alternative numeric forms, and local-only names.
        require(!host.contains(':') && !host.startsWith('[') && !Regex("[0-9.]+").matches(host))
        require(host.contains('.') && host.split('.').all { Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(it) })
        require(!Regex("(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*").matches(host))
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal"))
        require(uri.port == -1 || uri.port in 1..65535)
        require(!uri.path.isNullOrBlank() && uri.path != "/" && uri.path.split('/').none { it == "." || it == ".." || it.equals(".git", true) })
        require('%' !in uri.rawPath && uri.rawPath.none { it in "'\";`$<>|" })
    }

    private fun JsonObject.string(key: String): String? = get(key)?.let {
        require(it is JsonPrimitive && it.isString)
        it.content
    }

    private fun failure(code: String, message: String) = ToolExecutionResult(buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.toString(), message)

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(https?://)[^\\s/@]+(?::[^\\s/@]*)?@"), "$1[redacted]@")
        .replace(Regex("(?i)(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+)"), "[redacted]")

    companion object {
        const val NAME = "git_ubuntu"
        private val ACTIONS = listOf("init", "status", "diff", "log", "add", "commit", "branch", "checkout", "clone", "fetch", "pull", "push", "create_github_repo")
        private val FIELDS = setOf("action", "path", "repoUrl", "branch", "message", "name", "private")
        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val definitions: List<LlmTool> = listOf(LlmTool(
            NAME,
            "Git in Ubuntu. path selects a repository (clone: new destination); empty means workspace root. add stages that repository, branch creates a branch or lists branches, checkout selects an existing branch. diff returns stats, log returns 20 hashes/dates. Push and GitHub creation always need separate approval. Authenticate only in the interactive Ubuntu terminal; never supply credentials. Git/gh must already be installed.",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    FIELDS.filter { it != "private" }.forEach { field ->
                        put(field, buildJsonObject {
                            put("type", "string")
                            if (field == "action") put("enum", JsonArray(ACTIONS.map(::JsonPrimitive)))
                            if (field == "name") put("description", "GitHub owner/repository")
                            if (field == "repoUrl") put("description", "Credential-free HTTPS or ssh://git@host/path URL; clone only")
                        })
                    }
                    put("private", buildJsonObject { put("type", "boolean"); put("default", true) })
                })
                put("required", JsonArray(listOf(JsonPrimitive("action"))))
                put("additionalProperties", false)
            },
        ))
    }
}

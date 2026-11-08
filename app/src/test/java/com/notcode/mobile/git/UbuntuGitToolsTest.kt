package com.notcode.mobile.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UbuntuGitToolsTest {
    private class Harness(var approved: Boolean = true) {
        val commands = mutableListOf<String>()
        val approvals = mutableListOf<String>()
        var output = "NOTCODE_GIT_EXIT=0"
        val tools = UbuntuGitTools({ commands += it; output }, { approvals += it; approved })
        suspend fun run(action: String, vararg fields: Pair<String, String>) = tools.execute(buildJsonObject {
            put("action", action)
            fields.forEach { (key, value) -> put(key, value) }
        })
    }

    @Test fun schemaIsOptInAndClosed() {
        val definition = UbuntuGitTools.definitions.single()
        assertEquals("git_ubuntu", definition.name)
        assertEquals(false, definition.parameters["additionalProperties"]!!.jsonPrimitive.boolean)
        val properties = definition.parameters["properties"]!!.jsonObject
        assertEquals(true, properties["private"]!!.jsonObject["default"]!!.jsonPrimitive.boolean)
        assertEquals(13, properties["action"]!!.jsonObject["enum"]!!.jsonArray.size)
        assertFalse(properties.keys.any { it.contains("token") || it.contains("password") || it == "command" })
    }

    @Test fun remoteDenialNeverExecutesIncludingPreflight() = runBlocking {
        val h = Harness(false)
        assertTrue(h.run("push").json.contains("USER_DENIED"))
        assertTrue(h.run("create_github_repo", "name" to "owner/repo").json.contains("USER_DENIED"))
        assertEquals(2, h.approvals.size)
        assertTrue(h.commands.isEmpty())
    }

    @Test fun approvedPushAndCreateAlwaysAskAndCreationNeverPushes() = runBlocking {
        val h = Harness()
        h.run("push", "branch" to "main")
        assertTrue(h.commands.last().contains("'push' '--no-follow-tags' 'origin' 'main'"))
        h.run("create_github_repo", "name" to "owner/repo", "path" to "app")
        val command = h.commands.last()
        assertTrue(command.contains("gh 'repo' 'create' 'owner/repo' '--private' '--source' '.' '--remote' 'origin'"))
        assertFalse(command.contains("--push"))
        assertEquals(2, h.approvals.size)
        h.tools.execute(buildJsonObject { put("action", "create_github_repo"); put("name", "owner/public"); put("private", false) })
        assertTrue(h.commands.last().contains("'--public'"))
        assertEquals(3, h.approvals.size)
    }

    @Test fun allLocalAndReadRemoteActionsUseGuardedCommands() = runBlocking {
        val h = Harness()
        listOf("init", "status", "diff", "log", "add", "branch", "fetch", "pull").forEach {
            assertTrue(h.run(it).json.contains("\"ok\":true"))
        }
        h.run("commit", "message" to "Useful change")
        h.run("checkout", "branch" to "feature/new")
        h.run("clone", "path" to "repos/new", "repoUrl" to "https://github.com/owner/repo.git")
        assertTrue(h.approvals.isEmpty())
        h.commands.forEach {
            assertTrue(it.contains("GIT_TERMINAL_PROMPT=0"))
            assertTrue(it.contains("BatchMode=yes"))
            assertTrue(it.contains("'protocol.file.allow=never'"))
            assertTrue(it.contains("'protocol.ext.allow=never'"))
            assertTrue(it.contains("'core.hooksPath=/dev/null'"))
            assertTrue(it.contains("realpath -m --"))
            assertTrue(it.contains("command -v git"))
            assertFalse(it.contains("apt "))
            assertFalse(it.contains("credential.helper"))
        }
        assertTrue(h.commands.any { it.contains("'log' '-20' '--format=%h %cs'") })
        assertTrue(h.commands.any { it.contains("'diff' '--no-ext-diff' '--no-textconv' '--stat'") })
        assertTrue(h.commands.any { it.contains("'pull' '--ff-only'") })
    }

    @Test fun shellMetacharactersAreQuotedAsSingleArguments() = runBlocking {
        val h = Harness()
        h.run("commit", "message" to "don't; \$(touch /tmp/injected) `id`")
        assertTrue(h.commands.single().contains("'-m' 'don'\\''t; \$(touch /tmp/injected) `id`'"))
        h.run("status", "path" to "my repo's;\$(id)")
        assertTrue(h.commands.last().contains("'-C' 'my repo'\\''s;\$(id)'"))
        assertTrue(h.commands.last().contains("realpath -m -- 'my repo'\\''s;\$(id)'"))
        h.run("checkout", "branch" to "feature/it's;\$(id)")
        assertTrue(h.commands.last().contains("'feature/it'\\''s;\$(id)' '--'"))
    }

    @Test fun invalidPathsNeverExecute() = runBlocking {
        val h = Harness()
        listOf("../outside", "nested/../../x", "/tmp", "C:/tmp", "\\tmp", "a/.git", ".GIT/config", "-oops", "a/-oops", ".ssh", "a\nnext", "a\u0000b").forEach {
            assertTrue(it, h.run("status", "path" to it).json.contains("INVALID_ARGUMENTS"))
        }
        assertTrue(h.commands.isEmpty())
        h.run("status", "path" to "")
        assertTrue(h.commands.single().contains("'-C' '.'"))
    }

    @Test fun cloneRejectsDangerousUrlsAndRequiresDestination() = runBlocking {
        val h = Harness()
        listOf(
            "file:///tmp/repo", "ext::sh -c id", "ssh://git:password@github.com/a/b",
            "https://token@github.com/a/b", "https://user:pass@github.com/a/b",
            "git@github.com:owner/repo", "--upload-pack=id", "http://github.com/a/b",
            "https://127.0.0.1/a", "https://10.0.0.1/a", "https://[::1]/a",
            "https://2130706433/a", "https://0x7f.0.0.1/a", "https://localhost/a",
            "https://machine.local/a", "https://github.com/a/../b", "https://github.com/a/%2e%2e/b",
            "https://github.com/a/b?token=secret", "https://github.com/a/b#secret",
            "ssh://root@github.com/a/b", "https://github.com/a/\$(id)",
        ).forEach {
            assertTrue(it, h.run("clone", "repoUrl" to it, "path" to "new").json.contains("INVALID_ARGUMENTS"))
        }
        assertTrue(h.run("clone", "repoUrl" to "https://github.com/a/b").json.contains("INVALID_ARGUMENTS"))
        assertTrue(h.commands.isEmpty())
        h.run("clone", "repoUrl" to "ssh://git@gitlab.com/owner/repo.git", "path" to "new", "branch" to "main")
        assertTrue(h.commands.single().contains("'--branch' 'main' '--' 'ssh://git@gitlab.com/owner/repo.git' 'new'"))
        assertTrue(h.commands.single().contains("'-C' '.'"))
    }

    @Test fun credentialsRawOptionsAndMalformedArgumentsNeverExecute() = runBlocking {
        val h = Harness()
        listOf("token", "password", "privateKey", "command", "env", "config").forEach {
            assertTrue(h.run("status", it to "SENSITIVE_VALUE").json.contains("INVALID_ARGUMENTS"))
        }
        listOf("--orphan", "a..b", "a@{b", "a b", "a.lock", "a/.hidden").forEach {
            assertTrue(h.run("checkout", "branch" to it).json.contains("INVALID_ARGUMENTS"))
        }
        assertTrue(h.run("create_github_repo", "name" to "--public").json.contains("INVALID_ARGUMENTS"))
        assertTrue(h.run("create_github_repo", "name" to "owner/repo", "private" to "false").json.contains("INVALID_ARGUMENTS"))
        assertTrue(h.run("status", "repoUrl" to "https://github.com/a/b").json.contains("INVALID_ARGUMENTS"))
        assertTrue(h.commands.isEmpty())
        assertTrue(h.approvals.isEmpty())
    }

    @Test fun missingToolsFailuresAndSymlinkEscapesAreActionableWithoutRawErrors() = runBlocking {
        val h = Harness()
        h.output = "credential=SENSITIVE_VALUE\nNOTCODE_GIT_EXIT=127"
        val missing = h.run("status")
        assertTrue(missing.json.contains("MISSING_TOOL"))
        assertTrue(missing.json.contains("Ubuntu terminal"))
        assertFalse(missing.json.contains("SENSITIVE_VALUE"))
        h.output = "SENSITIVE_VALUE\nNOTCODE_GIT_EXIT=128"
        val failed = h.run("fetch")
        assertTrue(failed.json.contains("interactive terminal"))
        assertFalse(failed.json.contains("SENSITIVE_VALUE"))
        h.output = "NOTCODE_GIT_EXIT=125"
        assertTrue(h.run("status").json.contains("INVALID_PATH"))
        h.output = "no exit marker"
        assertTrue(h.run("status").json.contains("GIT_FAILED"))
    }

    @Test fun outputIsBoundedRedactedAndNotReturnedForMutations() = runBlocking {
        val h = Harness()
        h.output = "https://user:password@example.com/repo ghp_testSecret\n" + "x".repeat(20000) + "\nNOTCODE_GIT_EXIT=0"
        val output = Json.parseToJsonElement(h.run("status").json).jsonObject["output"]!!.jsonPrimitive.content
        assertTrue(output.length <= 16384)
        assertFalse(output.contains("password"))
        assertFalse(output.contains("ghp_testSecret"))
        assertFalse(output.contains("NOTCODE_GIT_EXIT"))
        assertFalse(h.run("push").json.contains("example.com"))
    }

    @Test fun callbackErrorsDoNotLeakSecretsAndCancellationPropagates() = runBlocking {
        val tools = UbuntuGitTools({ error("PRIVATE_SECRET") }, { true })
        assertFalse(tools.execute(buildJsonObject { put("action", "status") }).json.contains("PRIVATE_SECRET"))
        val cancelled = UbuntuGitTools({ throw CancellationException("stop") }, { true })
        try {
            cancelled.execute(buildJsonObject { put("action", "status") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}

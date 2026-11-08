package com.notcode.mobile.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LinuxRuntimeManager(context: Context) {
    private val base = File(context.filesDir.canonicalFile, "runtime")
    private val root = File(base, "ubuntu")
    private val engine = File(base, "engine")
    private val powershell = File(root, "opt/microsoft/powershell/7")
    private val abi = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "x86_64") }
    private val unsupported = when {
        Os.sysconf(OsConstants._SC_PAGESIZE) != 4096L -> "Ubuntu недоступна на устройствах со страницами памяти не 4 КБ"
        abi == null -> "Поддерживаются только ARM64 и x86_64"
        else -> null
    }
    private val mutable = MutableStateFlow(snapshot())
    val state: StateFlow<RuntimeState> = mutable
    private val installMutex = Mutex()
    private val downloader = VerifiedDownload(File(context.cacheDir, "runtime-downloads"))

    private fun snapshot(progress: String = "", installing: Boolean = false) = RuntimeState(
        installing, File(root, ".notcode-ready").isFile && File(engine, ".notcode-ready").isFile,
        File(powershell, ".notcode-ready").isFile, progress, unsupported,
    )
    private fun progress(text: String) { mutable.value = snapshot(text, true) }

    suspend fun installUbuntu() = installMutex.withLock {
        require(unsupported == null) { unsupported.orEmpty() }
        if (state.value.ubuntuReady) return@withLock
        withContext(Dispatchers.IO) {
            base.mkdirs()
            check(base.usableSpace >= 1536L * 1024 * 1024) { "Нужно не менее 1,5 ГБ свободного места" }
            val staging = File(base, "ubuntu-install")
            check(!staging.exists() || staging.deleteRecursively()) { "Не удалось очистить незавершённую установку" }
            staging.mkdirs()
            try {
                progress("Загрузка Ubuntu")
                val archive = File(staging, "ubuntu.tar.gz")
                downloader.fetch(RuntimeCatalog.ubuntu(requireNotNull(abi)), archive, ::progress)
                progress("Распаковка Ubuntu")
                val extracted = File(staging, "root").apply { mkdirs() }
                SafeTarExtractor.extract(archive, extracted)
                progress("Установка PRoot")
                installEngine()
                File(extracted, "etc/resolv.conf").let { file ->
                    java.nio.file.Files.deleteIfExists(file.toPath())
                    file.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
                }
                File(extracted, "root").mkdirs()
                File(extracted, "tmp").mkdirs()
                File(extracted, "workspace").mkdirs()
                check(!root.exists()) { "Каталог Ubuntu уже существует без маркера готовности" }
                check(extracted.renameTo(root)) { "Не удалось завершить установку Ubuntu" }
                smoke(commandBuilderInternal("ubuntu", "printf NOTCODE_UBUNTU_OK", File(base, "smoke-workspace").apply { mkdirs() }, false), "NOTCODE_UBUNTU_OK")
                File(root, ".notcode-ready").writeText("1")
                mutable.value = snapshot("Ubuntu установлена")
            } catch (error: Exception) {
                if (!File(root, ".notcode-ready").exists()) root.deleteRecursively()
                mutable.value = snapshot(error.message.orEmpty())
                throw error
            } finally { staging.deleteRecursively(); mutable.value = snapshot(mutable.value.progress) }
        }
    }

    private suspend fun installEngine() {
        if (File(engine, ".notcode-ready").isFile) return
        val stage = File(base, "engine-install")
        stage.deleteRecursively(); stage.mkdirs()
        try {
            for (artifact in RuntimeCatalog.engine(requireNotNull(abi))) {
                val archive = File(stage, "package-${System.nanoTime()}.deb")
                downloader.fetch(artifact, archive, ::progress)
                extractDeb(archive, stage)
                archive.delete()
            }
            check(!engine.exists()) { "Каталог PRoot уже существует без маркера готовности" }
            check(stage.renameTo(engine)) { "Не удалось установить PRoot" }
            check(File(engine, "usr/bin/proot").isFile) { "Бинарник PRoot отсутствует" }
            File(engine, ".notcode-ready").writeText("1")
        } finally { stage.deleteRecursively() }
    }

    private suspend fun extractDeb(archive: File, destination: File) {
        org.apache.commons.compress.archivers.ar.ArArchiveInputStream(archive.inputStream()).use { ar ->
            var entry = ar.nextArEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                if (entry.name.startsWith("data.tar")) {
                    val tar = File(base, "engine-data-${System.nanoTime()}.tar.gz")
                    val payload = File(base, "engine-payload-${System.nanoTime()}").apply { mkdirs() }
                    try {
                        val decompressed = org.apache.commons.compress.compressors.CompressorStreamFactory().createCompressorInputStream(ar.buffered())
                        java.util.zip.GZIPOutputStream(tar.outputStream()).use { output -> decompressed.copyTo(output) }
                        SafeTarExtractor.extract(tar, payload)
                        val prefix = File(payload, "data/data/com.termux/files/usr")
                        check(prefix.isDirectory) { "Неподдерживаемая структура пакета PRoot" }
                        val installed = File(destination, "usr")
                        prefix.walkTopDown().forEach { original ->
                            val target = File(installed, original.relativeTo(prefix).path)
                            when {
                                java.nio.file.Files.isSymbolicLink(original.toPath()) -> {
                                    target.parentFile!!.mkdirs()
                                    java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Files.readSymbolicLink(original.toPath()))
                                }
                                original.isDirectory -> target.mkdirs()
                                else -> {
                                    original.copyTo(target, overwrite = false)
                                    if (original.canExecute()) check(target.setExecutable(true, true)) { "Не удалось выставить права PRoot" }
                                }
                            }
                        }
                    } finally { tar.delete(); payload.deleteRecursively() }
                    return
                }
                entry = ar.nextArEntry
            }
            error("В пакете отсутствует data.tar")
        }
    }

    suspend fun installPowerShell() = installMutex.withLock {
        check(state.value.ubuntuReady) { "Сначала установите Ubuntu" }
        if (state.value.powershellReady) return@withLock
        withContext(Dispatchers.IO) {
            val stage = File(base, "powershell-install")
            stage.deleteRecursively(); stage.mkdirs()
            try {
                progress("Загрузка PowerShell для Linux")
                val archive = File(stage, "powershell.tar.gz")
                downloader.fetch(RuntimeCatalog.powershell(requireNotNull(abi)), archive, ::progress)
                progress("Установка зависимостей PowerShell из репозитория Ubuntu")
                smoke(commandBuilderInternal("ubuntu", "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a && apt-get update && apt-get install -y --no-install-recommends libicu74 libssl3t64 libstdc++6 libgcc-s1 libunwind8 zlib1g ca-certificates && printf NOTCODE_DEPS_OK", File(base, "smoke-workspace").apply { mkdirs() }, true), "NOTCODE_DEPS_OK", 600_000)
                val extracted = File(stage, "payload").apply { mkdirs() }
                SafeTarExtractor.extract(archive, extracted)
                File(extracted, "pwsh").setExecutable(true, true)
                powershell.parentFile!!.mkdirs()
                check(!powershell.exists()) { "Каталог PowerShell уже существует без маркера готовности" }
                check(extracted.renameTo(powershell)) { "Не удалось установить PowerShell" }
                smoke(commandBuilderInternal("powershell", "Write-Output NOTCODE_PWSH_OK", File(base, "smoke-workspace").apply { mkdirs() }, false), "NOTCODE_PWSH_OK")
                File(powershell, ".notcode-ready").writeText("1")
                mutable.value = snapshot("PowerShell установлена")
            } catch (error: Exception) {
                if (!File(powershell, ".notcode-ready").exists()) powershell.deleteRecursively()
                mutable.value = snapshot(error.message.orEmpty()); throw error
            } finally { stage.deleteRecursively(); mutable.value = snapshot(mutable.value.progress) }
        }
    }

    fun commandBuilder(mode: String, command: String, workspace: File): ProcessBuilder = commandBuilderInternal(mode, command, workspace, true)

    fun interactiveBuilder(mode: String, workspace: File): ProcessBuilder {
        if (mode == "cmd") ensureCmdScript()
        val builder = commandBuilderInternal(mode, "", workspace, true)
        val args = builder.command().drop(1).toMutableList()
        val envIndex = args.indexOf("TERM=dumb")
        if (envIndex >= 0) args[envIndex] = "TERM=xterm-256color"
        val shellIndex = args.indexOf(if (mode == "powershell") "/opt/microsoft/powershell/7/pwsh" else "/bin/bash")
        check(shellIndex >= 0)
        while (args.size > shellIndex + 1) args.removeAt(args.lastIndex)
        args += when (mode) {
            "ubuntu" -> listOf("--noprofile", "--norc", "-i")
            "cmd" -> listOf("--noprofile", "--rcfile", "/usr/local/bin/notcode-cmd", "-i")
            else -> listOf("-NoLogo", "-NoProfile")
        }
        builder.command(args)
        builder.environment()["TERM"] = "xterm-256color"
        return builder
    }

    /** CMD-style commands (dir/copy/del/cls/...) implemented over Ubuntu bash; not real cmd.exe. */
    private fun ensureCmdScript() {
        val script = File(root, "usr/local/bin/notcode-cmd")
        if (script.exists()) return
        script.parentFile!!.mkdirs()
        script.writeText(
            """
            # NotCode CMD emulation
            export PS1='NotCode CMD> '
            dir() { ls -la --color=auto "${'$'}@"; }
            copy() { cp "${'$'}@"; }
            move() { mv "${'$'}@"; }
            del() { rm "${'$'}@"; }
            erase() { rm "${'$'}@"; }
            ren() { mv "${'$'}@"; }
            md() { mkdir "${'$'}@"; }
            rd() { rmdir "${'$'}@"; }
            cls() { printf '\033[2J\033[H'; }
            type() { cat "${'$'}@"; }
            ver() { echo "NotCode CMD [Version 1.0]"; }
            set() { if [ "${'$'}#" -eq 0 ]; then env; else export "${'$'}@"; fi; }
            help() { echo "Команды: dir cd copy move del erase ren md rd cls echo type set ver exit help"; }
            echo "NotCode CMD [Version 1.0]"
            echo "Эмуляция команд cmd.exe поверх Ubuntu. help — список команд."
            """.trimIndent() + "\n"
        )
    }

    private fun commandBuilderInternal(mode: String, command: String, workspace: File, requireReady: Boolean): ProcessBuilder {
        require(mode in setOf("ubuntu", "powershell", "cmd"))
        require(unsupported == null) { unsupported.orEmpty() }
        if (requireReady) check(if (mode == "powershell") state.value.powershellReady else state.value.ubuntuReady) { "Окружение не установлено" }
        val executable = File(engine, "usr/bin/proot")
        val arguments = mutableListOf("/system/bin/setsid", executable.absolutePath, "--kill-on-exit", "--link2symlink", "-0", "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "${workspace.canonicalPath}:/workspace", "-w", "/workspace")
        arguments += listOf("/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "LANG=C.UTF-8", "TERM=dumb", "TMPDIR=/tmp", "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1", "POWERSHELL_TELEMETRY_OPTOUT=1", "DOTNET_CLI_TELEMETRY_OPTOUT=1")
        arguments += if (mode == "powershell") listOf("/opt/microsoft/powershell/7/pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command)
            else listOf("/bin/bash", "--noprofile", "--norc", "-c", command)
        return ProcessBuilder(arguments).directory(base).apply {
            environment().clear()
            environment().putAll(mapOf("HOME" to "/root", "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "LANG" to "C.UTF-8", "TERM" to "dumb",
                "TMPDIR" to File(base, "tmp").apply { mkdirs() }.absolutePath,
                "PROOT_TMP_DIR" to File(base, "tmp").absolutePath,
                "PROOT_LOADER" to File(engine, "usr/libexec/proot/loader").absolutePath,
                "PROOT_LOADER_32" to File(engine, "usr/libexec/proot/loader32").absolutePath,
                "LD_LIBRARY_PATH" to File(engine, "usr/lib").absolutePath,
                "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT" to "1", "POWERSHELL_TELEMETRY_OPTOUT" to "1", "DOTNET_CLI_TELEMETRY_OPTOUT" to "1"))
        }
    }

    private suspend fun smoke(builder: ProcessBuilder, expected: String, timeoutMs: Long = 30_000) {
        val result = ManagedCommand.run(builder, timeoutMs)
        check(result.exitCode == 0 && expected in result.output) {
            "Проверка окружения не пройдена: ${result.output.takeLast(2000)}"
        }
    }
}

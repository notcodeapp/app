package com.notcode.mobile.runtime

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Stops the owned process, giving it a chance to clean up before forcing exit.
 * This is not process-tree termination: Termux PRoot ignores SIGTERM, and
 * --kill-on-exit applies when the initial guest exits, not when PRoot is SIGKILLed.
 * Reliable tracee cleanup needs a native controller that can signal PRoot with
 * SIGQUIT (or terminate the owned process group); no PID discovery is attempted here.
 */
suspend fun terminateProcess(process: Process) {
    withContext(NonCancellable + Dispatchers.IO) {
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        } finally {
            // Close before the caller joins its reader: descendants may retain pipes.
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}

/** Non-interactive, merged-output runner. Callbacks run on IO and must not block. */
object ManagedCommand {
    data class Result(val exitCode: Int, val output: String)

    suspend fun run(
        builder: ProcessBuilder,
        timeoutMs: Long = 30_000,
        maxOutputChars: Int = 65_536,
        onOutput: (String) -> Unit = {},
    ): Result {
        require(timeoutMs > 0)
        require(maxOutputChars > 0)
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            // Start and enter the cleanup region without a cancellable suspension.
            val child = builder.redirectErrorStream(true).start()
            try {
                coroutineScope {
                    val reader = async(Dispatchers.IO) {
                        child.inputStream.bufferedReader().use { input ->
                            val output = StringBuilder()
                            val buffer = CharArray(4096)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                val chunk = String(buffer, 0, count)
                                output.append(chunk)
                                if (output.length > maxOutputChars) {
                                    output.delete(0, output.length - maxOutputChars)
                                }
                                onOutput(chunk)
                            }
                            output.toString()
                        }
                    }
                    try {
                        // These commands have no input; do not leave a guest waiting on stdin.
                        child.outputStream.close()
                        withTimeout(timeoutMs) {
                            while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                                currentCoroutineContext().ensureActive()
                            }
                            Result(child.exitValue(), reader.await())
                        }
                    } finally {
                        // Must run inside the scope, before it waits for a blocked reader.
                        withContext(NonCancellable) {
                            terminateProcess(child)
                            reader.cancelAndJoin()
                        }
                    }
                }
            } finally {
                // Also covers failures setting up the reader/scope.
                terminateProcess(child)
            }
        }
    }
}

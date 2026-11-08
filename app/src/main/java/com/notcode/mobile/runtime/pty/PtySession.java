// SPDX-License-Identifier: GPL-3.0-only
package com.notcode.mobile.runtime.pty;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Main-thread-owned, single-use PTY. Retain this object outside the Android View lifecycle.
 * Input/output are handled by Termux worker threads; emulator changes and listeners use the main looper.
 * ProcessBuilder is a launch specification only: start() is never called and redirects are ignored.
 */
public final class PtySession implements TerminalSessionClient, AutoCloseable {
    public enum State { RUNNING, STOPPING, EXITED }

    public interface Listener {
        default void onScreenChanged(PtySession session) {}
        default void onTitleChanged(PtySession session) {}
        default void onExit(PtySession session, int exitStatus) {}
        default void onBell(PtySession session) {}
        default void onCopyRequested(PtySession session, String text) {}
        default void onPasteRequested(PtySession session) {}
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final TerminalSession terminal;
    private State state = State.RUNNING;
    private long processIdentity;
    private Integer exitStatus;

    private PtySession(String executable, String cwd, String[] argv, String[] env, int scrollbackRows) {
        terminal = new TerminalSession(executable, cwd, argv, env, scrollbackRows, this);
    }

    /**
     * Start the exact argv/env/cwd of a runtime builder using Termux JNI (which performs setsid).
     * A leading setsid executable without options is stripped for compatibility with command builders.
     * No shell string wrapping, quoting, Android shell fallback, or ProcessBuilder.start() is used.
     */
    public static PtySession start(ProcessBuilder builder, int columns, int rows) {
        return start(builder, columns, rows, 10_000);
    }

    public static PtySession start(ProcessBuilder builder, int columns, int rows, int scrollbackRows) {
        requireMainThread();
        validateSize(columns, rows);
        if (scrollbackRows < 0 || scrollbackRows > 50_000) throw new IllegalArgumentException("Invalid scrollback size");
        List<String> argv = new ArrayList<>(builder.command());
        if (!argv.isEmpty() && new File(argv.get(0)).getName().equals("setsid")) {
            argv.remove(0);
            if (argv.isEmpty() || argv.get(0).startsWith("-")) {
                throw new IllegalArgumentException("Pass executable argv without setsid options");
            }
        }
        if (argv.isEmpty()) throw new IllegalArgumentException("Empty argv");
        for (String arg : argv) requireCString(arg);
        File executable = new File(argv.get(0));
        if (!executable.isAbsolute()) throw new IllegalArgumentException("Executable must be an absolute runtime path");
        if (!executable.isFile() || !executable.canExecute()) throw new IllegalArgumentException("Runtime executable is not executable: " + executable);
        File cwd = builder.directory();
        if (cwd == null) cwd = new File(System.getProperty("user.dir", "/"));
        if (!cwd.isDirectory()) throw new IllegalArgumentException("Working directory does not exist: " + cwd);
        requireCString(cwd.getAbsolutePath());
        List<String> env = new ArrayList<>();
        for (Map.Entry<String, String> entry : builder.environment().entrySet()) {
            requireCString(entry.getKey());
            requireCString(entry.getValue());
            if (entry.getKey().isEmpty() || entry.getKey().contains("=")) throw new IllegalArgumentException("Invalid environment key");
            env.add(entry.getKey() + "=" + entry.getValue());
        }
        PtySession result = new PtySession(executable.getAbsolutePath(), cwd.getAbsolutePath(),
                argv.toArray(new String[0]), env.toArray(new String[0]), scrollbackRows);
        try {
            // Start immediately, even when no View has been measured or attached yet.
            result.terminal.updateSize(columns, rows, 0, 0);
            result.processIdentity = NotCodeProcessTree.identity(result.terminal.getPid());
            return result;
        } catch (RuntimeException | Error failure) {
            if (result.terminal.getPid() > 0) result.terminal.finishIfRunning();
            throw failure;
        }
    }

    public TerminalSession getTerminalSession() { requireMainThread(); return terminal; }
    public State getState() { requireMainThread(); return state; }
    public Integer getExitStatus() { requireMainThread(); return exitStatus; }
    public int getPid() { requireMainThread(); return terminal.getPid(); }
    public String getTitle() { requireMainThread(); return terminal.getTitle(); }

    public void addListener(Listener listener) { requireMainThread(); if (!listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(Listener listener) { requireMainThread(); listeners.remove(listener); }

    public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        requireMainThread();
        validateSize(columns, rows);
        if (cellWidthPixels < 0 || cellHeightPixels < 0) throw new IllegalArgumentException("Negative cell dimensions");
        if (state == State.RUNNING) terminal.updateSize(columns, rows, cellWidthPixels, cellHeightPixels);
    }

    /** Raw UTF-8 input; use paste() for clipboard text (bracketed-paste aware). */
    public void write(String input) {
        requireMainThread();
        if (state == State.RUNNING) terminal.write(input);
    }

    public void paste(String text) {
        requireMainThread();
        if (state == State.RUNNING) terminal.getEmulator().paste(text);
    }

    /** Send terminal VINTR, not SIGINT to the shell: the PTY interrupts its foreground job. */
    public void sendCtrlC() { write("\u0003"); }

    /**
     * Force-stop all visible same-UID descendants/session jobs, not just the proot leader.
     * Freezing the tree before killing prevents an apt worker from forking during teardown.
     * /proc visibility is OS-controlled; proot should also be launched with --kill-on-exit.
     * onExit is delivered after the Termux waiter has reaped the leader and closed PTY resources.
     */
    public void finish() {
        requireMainThread();
        if (state != State.RUNNING) return;
        state = State.STOPPING;
        int pid = terminal.getPid();
        long identity = processIdentity;
        Thread stopper = new Thread(() -> {
            boolean terminated;
            try {
                terminated = NotCodeProcessTree.terminate(pid, identity);
            } catch (RuntimeException failure) {
                Log.w("NotCodePTY", "Process tree teardown failed", failure);
                terminated = false;
            }
            final boolean killed = terminated;
            main.post(() -> {
                if (!killed && state == State.STOPPING && terminal.getPid() == pid) {
                    Log.w("NotCodePTY", "Process tree unavailable; killing leader (use proot --kill-on-exit)");
                    terminal.finishIfRunning();
                }
            });
        }, "NotCode-PTY-stop");
        stopper.start();
    }

    @Override public void close() { finish(); }

    public static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("PTY API must run on Android main thread");
    }
    private static void requireCString(String value) {
        if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in process specification");
    }
    private static void validateSize(int columns, int rows) {
        if (columns < 2 || rows < 2 || columns > 1000 || rows > 1000) throw new IllegalArgumentException("PTY size must be 2..1000");
    }

    @Override public void onTextChanged(TerminalSession session) { for (Listener l : listeners) l.onScreenChanged(this); }
    @Override public void onTitleChanged(TerminalSession session) { for (Listener l : listeners) l.onTitleChanged(this); }
    @Override public void onSessionFinished(TerminalSession session) {
        state = State.EXITED;
        exitStatus = session.getExitStatus();
        for (Listener l : listeners) l.onExit(this, exitStatus);
    }
    @Override public void onCopyTextToClipboard(TerminalSession session, String text) { for (Listener l : listeners) l.onCopyRequested(this, text); }
    @Override public void onPasteTextFromClipboard(TerminalSession session) { for (Listener l : listeners) l.onPasteRequested(this); }
    @Override public void onBell(TerminalSession session) { for (Listener l : listeners) l.onBell(this); }
    @Override public void onColorsChanged(TerminalSession session) { onTextChanged(session); }
    @Override public void onTerminalCursorStateChange(boolean state) { onTextChanged(terminal); }
    @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
    @Override public Integer getTerminalCursorStyle() { return null; }
    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Terminal failure", e); }
}

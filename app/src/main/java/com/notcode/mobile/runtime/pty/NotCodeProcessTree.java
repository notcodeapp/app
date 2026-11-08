// SPDX-License-Identifier: GPL-3.0-only
package com.notcode.mobile.runtime.pty;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Best-effort Linux /proc teardown, scoped to the original PTY process identity and app UID. */
final class NotCodeProcessTree {
    private static final class ProcessInfo {
        final int pid, parent, session;
        final long start;
        ProcessInfo(int pid, int parent, int session, long start) {
            this.pid = pid; this.parent = parent; this.session = session; this.start = start;
        }
    }

    static long identity(int pid) {
        ProcessInfo info = read(pid);
        return info == null ? 0 : info.start;
    }

    static boolean terminate(int pid, long start) {
        ProcessInfo leader = read(pid);
        if (leader == null) return true; // Already gone: do not signal a possibly recycled PID.
        if (start == 0 || leader.start != start || leader.session != pid) return false;
        Map<Integer, ProcessInfo> targets = new HashMap<>();
        targets.put(pid, leader);
        try {
            signal(leader, OsConstants.SIGSTOP);
            // Freeze parent before discovery; descendants can no longer keep spawning indefinitely.
            for (int pass = 0; pass < 64; pass++) {
                File[] entries = new File("/proc").listFiles();
                if (entries == null) break;
                boolean discovered = false;
                for (File entry : entries) {
                    int child;
                    try { child = Integer.parseInt(entry.getName()); }
                    catch (NumberFormatException ignored) { continue; }
                    if (targets.containsKey(child)) continue;
                    ProcessInfo info = read(child);
                    if (info == null) continue;
                    ProcessInfo parent = targets.get(info.parent);
                    boolean hasParent = parent != null && identity(parent.pid) == parent.start;
                    if (info.session == pid || hasParent) {
                        targets.put(child, info);
                        signal(info, OsConstants.SIGSTOP);
                        discovered = true;
                    }
                }
                if (!discovered) break;
            }
        } finally {
            // Children first. SIGKILL works on stopped tasks; never leave one SIGSTOP'ed.
            for (ProcessInfo info : targets.values()) if (info.pid != pid) signal(info, OsConstants.SIGKILL);
            signal(leader, OsConstants.SIGKILL);
        }
        return true;
    }

    private static ProcessInfo read(int pid) {
        if (pid <= 1 || pid == Os.getpid()) return null;
        try {
            String path = "/proc/" + pid;
            if (Os.stat(path).st_uid != Os.getuid()) return null;
            String stat = new String(Files.readAllBytes(new File(path, "stat").toPath()), StandardCharsets.US_ASCII);
            // comm may contain spaces and ')' characters. Fields after the LAST ')' start at state (3).
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+");
            return new ProcessInfo(pid, Integer.parseInt(fields[1]), Integer.parseInt(fields[3]), Long.parseLong(fields[19]));
        } catch (Exception ignored) { return null; }
    }

    private static void signal(ProcessInfo expected, int signal) {
        ProcessInfo current = read(expected.pid);
        if (current == null || current.start != expected.start) return;
        try { Os.kill(expected.pid, signal); }
        catch (ErrnoException e) {
            if (e.errno != OsConstants.ESRCH) Log.w("NotCodePTY", "Cannot signal PTY process " + expected.pid, e);
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.notcode.mobile.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.notcode.mobile.runtime.pty.PtySession;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/**
 * Embeddable official Termux TerminalView; suitable for XML, a View hierarchy, or Compose AndroidView.
 * bind() never launches a process. detach/unbind never stops it. The owner must call session.finish().
 * A session should have one visible terminal at a time; multiple views would compete over PTY size.
 */
public final class PtyTerminalView extends FrameLayout implements PtySession.Listener {
    private TerminalView terminalView;
    private PtySession session;
    private boolean observing;
    private int fontSize;
    private boolean ctrlActive;
    private boolean altActive;
    private Runnable modifierListener;

    public PtyTerminalView(Context context) { this(context, null); }
    public PtyTerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fontSize = Math.round(14 * getResources().getDisplayMetrics().scaledDensity);
        setBackgroundColor(0xff000000);
    }

    public void bind(PtySession session) {
        PtySession.requireMainThread();
        if (this.session == session) return;
        unbind();
        if (session == null) return;
        this.session = session;
        terminalView = new TerminalView(getContext(), null);
        terminalView.setTerminalViewClient(new ViewClient());
        terminalView.setTextSize(fontSize);
        terminalView.setTypeface(Typeface.MONOSPACE);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        addView(terminalView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        terminalView.attachSession(session.getTerminalSession());
        if (isAttachedToWindow()) observe();
    }

    public void unbind() {
        PtySession.requireMainThread();
        stopObserving();
        if (terminalView != null) {
            terminalView.clearFocus();
            terminalView.stopTextSelectionMode();
            terminalView.setTerminalCursorBlinkerState(false, true);
            removeView(terminalView);
        }
        terminalView = null;
        session = null;
    }

    public TerminalView getTerminalView() { return terminalView; }

    /** Sticky modifiers for the next key, Termux extra-keys style. Consumed on first use. */
    public void toggleControl() { ctrlActive = !ctrlActive; notifyModifiers(); }
    public void toggleAlt() { altActive = !altActive; notifyModifiers(); }
    public boolean isControlActive() { return ctrlActive; }
    public boolean isAltActive() { return altActive; }
    public void setOnModifierChangedListener(Runnable listener) { modifierListener = listener; }
    private void notifyModifiers() { if (modifierListener != null) modifierListener.run(); }
    private boolean consumeControl() {
        if (!ctrlActive) return false;
        ctrlActive = false;
        notifyModifiers();
        return true;
    }
    private boolean consumeAlt() {
        if (!altActive) return false;
        altActive = false;
        notifyModifiers();
        return true;
    }

    /** Physical pixel size, matching official TerminalView.setTextSize(). */
    public void setTextSizePixels(int pixels) {
        PtySession.requireMainThread();
        fontSize = Math.max(8, Math.min(96, pixels));
        if (terminalView != null) terminalView.setTextSize(fontSize);
    }

    public void showKeyboard() {
        PtySession.requireMainThread();
        if (terminalView == null) return;
        terminalView.requestFocus();
        InputMethodManager ime = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    /** Explicit user action, not automatically accepting clipboard requests from terminal output. */
    public void pasteFromClipboard() {
        PtySession.requireMainThread();
        if (session == null) return;
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(getContext());
            if (text != null) session.paste(text.toString());
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observe();
    }
    @Override protected void onDetachedFromWindow() {
        stopObserving();
        if (terminalView != null) {
            terminalView.clearFocus();
            terminalView.stopTextSelectionMode();
            terminalView.setTerminalCursorBlinkerState(false, true);
        }
        super.onDetachedFromWindow();
    }
    private void observe() {
        if (session == null || observing) return;
        session.addListener(this);
        observing = true;
        terminalView.onScreenUpdated();
    }
    private void stopObserving() {
        if (session != null && observing) session.removeListener(this);
        observing = false;
    }
    @Override public void onScreenChanged(PtySession changed) {
        if (terminalView != null) {
            terminalView.setBackgroundColor(changed.getTerminalSession().getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
            terminalView.onScreenUpdated();
        }
    }
    @Override public void onExit(PtySession changed, int status) { onScreenChanged(changed); }
    @Override public void onCopyRequested(PtySession changed, String text) {
        // Selection action only. OSC 52 output must not silently overwrite the user's clipboard.
        if (terminalView == null || !terminalView.isSelectingText()) return;
        String selected = terminalView.getSelectedText();
        if (selected == null || !selected.equals(text)) return;
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text));
    }
    @Override public void onPasteRequested(PtySession changed) {
        if (terminalView != null && terminalView.isSelectingText()) pasteFromClipboard();
    }

    private final class ViewClient implements TerminalViewClient {
        @Override public float onScale(float scale) {
            if (scale < 0.9f || scale > 1.1f) {
                setTextSizePixels(Math.round(fontSize * scale));
                return 1f;
            }
            return scale;
        }
        @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return true; }
        @Override public boolean isTerminalViewSelected() { return isShown() && hasWindowFocus(); }
        @Override public void copyModeChanged(boolean copyMode) {}
        @Override public boolean onKeyDown(int code, KeyEvent e, TerminalSession terminal) {
            if (e.isCtrlPressed() && e.isShiftPressed() && code == KeyEvent.KEYCODE_V) {
                pasteFromClipboard();
                return true;
            }
            return session == null || session.getState() != PtySession.State.RUNNING;
        }
        @Override public boolean onKeyUp(int code, KeyEvent e) { return false; }
        @Override public boolean onLongPress(MotionEvent e) { return false; }
        @Override public boolean readControlKey() { return consumeControl(); }
        @Override public boolean readAltKey() { return consumeAlt(); }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }
        @Override public boolean onCodePoint(int codePoint, boolean ctrl, TerminalSession terminal) {
            return session == null || session.getState() != PtySession.State.RUNNING;
        }
        @Override public void onEmulatorSet() {}
        @Override public void logError(String tag, String message) { Log.e(tag, message); }
        @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
        @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
        @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
        @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
        @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Terminal view failure", e); }
    }
}

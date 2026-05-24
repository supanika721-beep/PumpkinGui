package com.termux.terminal;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    /**
     * When the terminal text changes.
     *
     * @param session The {@link TerminalSession} for which the text changed.
     */
    void onTextChanged(TerminalSession session);

    /**
     * When the terminal title changes.
     *
     * @param session The {@link TerminalSession} for which the title changed.
     */
    void onTitleChanged(TerminalSession session);

    /**
     * When the terminal session finishes.
     *
     * @param session The {@link TerminalSession} that finished.
     */
    void onSessionFinished(TerminalSession session);

    /**
     * When the terminal session wants to copy text to clipboard.
     *
     * @param session The {@link TerminalSession} that wants to copy.
     * @param text The text to copy.
     */
    void onCopyTextToClipboard(TerminalSession session, String text);

    /**
     * When the terminal session wants to paste text from clipboard.
     *
     * @param session The {@link TerminalSession} that wants to paste.
     */
    void onPasteTextFromClipboard(TerminalSession session);

    /**
     * When the terminal session rings the bell.
     *
     * @param session The {@link TerminalSession} that rang the bell.
     */
    void onBell(TerminalSession session);

    /**
     * When the terminal session colors change.
     *
     * @param session The {@link TerminalSession} for which the colors changed.
     */
    void onColorsChanged(TerminalSession session);

    /**
     * When the terminal cursor state changes.
     *
     * @param state The new cursor state.
     */
    void onTerminalCursorStateChange(boolean state);

    /**
     * Get the terminal cursor style.
     *
     * @return The terminal cursor style.
     */
    int getTerminalCursorStyle();

    void logError(String tag, String message);
    void logWarn(String tag, String message);
    void logInfo(String tag, String message);
    void logDebug(String tag, String message);
    void logVerbose(String tag, String message);
    void logStackTraceWithMessage(String tag, String message, Exception e);
    void logStackTrace(String tag, Exception e);

}

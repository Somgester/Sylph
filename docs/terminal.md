# Integrated terminal

[Documentation index](README.md) · [Editor guide](editor-guide.md) · [Architecture](architecture.md)

## Open and switch

Use Ctrl on Linux/Windows and Cmd on macOS for `Mod` below.

| Action | Shortcut or control |
| --- | --- |
| Show/hide the bottom terminal panel | Mod+Shift+Backtick |
| Create a terminal in the editor area | Mod+Backtick |
| Return to the current file | Select its tab above the editor, or open a sidebar file |
| Duplicate a terminal | The duplicate button beside its tab name |
| Close a terminal | Its tab's close button |

Both opening commands also appear under View and in the command palette. The panel divider can be dragged. Hiding the panel keeps its shells alive; reopening it restores the same tabs and output. Closing its last tab removes the panel. Terminal tabs in the editor share a tab row with the current document. Sylph still edits one document at a time; adding terminal tabs does not add multiple document sessions.

A new terminal starts in the opened workspace folder. Without a workspace, it uses the active file's parent, or Sylph's working directory when no file is open. Changing the opened workspace affects future terminals, not existing shells. Duplicate starts an independent shell in the original terminal's **starting folder**, even after `cd`; it does not copy environment changes, history, or running commands.

Switching tabs preserves unsaved document text, selection, and undo history. If you return to the editor while a terminal is starting, renderer completion respects that focus choice. Shell input and output never become document edits or trigger autosave. Opening an existing sidebar file selects the document tab.

## Shell behavior

The terminal uses a real pseudo-terminal through [pty4j](https://github.com/JetBrains/pty4j) and renders ANSI sequences with [xterm.js](https://github.com/xtermjs/xterm.js). Interactive command input, arrow keys, control characters, colors, alternate screens, and resize events use the terminal protocol. Terminal scrollback is capped at 5,000 lines.

On Unix, Sylph starts the executable named by `SHELL` with `-i`, falling back to `/bin/sh -i` if that path is unusable. On Windows it starts `ComSpec`, falling back to `cmd.exe`. The executable and arguments are passed directly, and the working directory is set through the process API. No directory name is interpolated into a shell command. Unix shell startup files may run according to the selected shell's normal behavior.

Ctrl+C interrupts a foreground command. Ctrl+Shift+C copies selected terminal text and Ctrl+Shift+V pastes; Cmd+C/Cmd+V also work on macOS. Right-click provides Copy/Paste. The Edit menu also routes Copy/Paste/Select All to the active terminal; terminal Undo/Redo/Cut are disabled. Opening the menu retains its editing target, and returning to the document restores document actions. Markdown preview's shortcut is bypassed while terminal has focus so terminal paste works. Other application shortcuts, including Save and the palette, remain available. Paste goes through xterm.js so bracketed-paste mode is preserved.

Close stops the shell and its observed child processes and releases PTY streams. Reload Window and application shutdown close all terminal sessions. A naturally exited shell retains its output and exit status until its tab is closed; duplicate starts a new shell. Shell sessions are not persisted across restarts or reloads.

The terminal runs with the user's normal permissions. Its commands can change files outside the opened workspace, just like an external terminal. Sylph's guarded sidebar file actions do not sandbox shell commands.

## Implementation and verification

- `TerminalWorkspace` owns file/terminal tabs, the resizable panel, and duplicate/close lifecycle.
- `TerminalPage` embeds bundled scripts/styles into a page with a fresh CSP nonce, so development and packaged JAR launches use the same renderer without resource-origin assumptions.
- `TerminalView` owns a local WebView, theme changes, clipboard routing, and output acknowledgements.
- `TerminalBridge` exposes only terminal input, binary input, resize, and output acknowledgements to the application-owned page.
- `TerminalSession` owns shell startup, ordered input, UTF-8 decoding, native resize, and asynchronous cleanup. Process startup, reads, writes, and shutdown stay off the JavaFX thread.

Each output chunk waits for xterm.js to consume it before the next chunk is delivered, bounding pending renderer work. Java strings cross the JavaScript bridge as values, never interpolated code. Output is parsed as terminal data, not HTML. Renderer assets are bundled locally, require no CDN at runtime, and use a restrictive content policy. The terminal WebView is separate from Markdown's JavaScript-disabled preview.

Tests run real Unix shells and the real renderer in headless JavaFX. See [testing](testing.md). Windows and macOS desktop behavior still requires verification on those platforms; successful Linux checks are not cross-platform runtime evidence. Startup failures appear inside the tab instead of opening an external terminal.

## Dependency maintenance

`gradle/libs.versions.toml` pins pty4j. Browser assets are pinned and attributed in [terminal/THIRD-PARTY.md](../app/src/main/resources/terminal/THIRD-PARTY.md), with upstream MIT notices retained alongside them. When upgrading, verify npm package integrity, copy only the required browser assets/licenses, then rerun shell, clipboard, renderer, resize, and lifecycle checks. Application startup does not download terminal assets.

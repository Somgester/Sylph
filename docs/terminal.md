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
| Fill the Sylph window with a terminal | **Expand** in its tab header, then **Restore** to return |

Both opening commands also appear under View and in the command palette. The panel divider can be dragged. Hiding the panel keeps its shells alive; reopening it restores the same tabs and output. Closing its last tab removes the panel. Terminal tabs in the editor share a tab row with the current document. Sylph still edits one document at a time; adding terminal tabs does not add multiple document sessions.

Run `./init.sh` once to install Herdr CLI if needed, then `./run.sh`.
When Herdr CLI is on Sylph’s launch `PATH`, a shortcut that creates a terminal offers
**Shell** and **Herdr CLI**. Both open in the location picked by the shortcut. Use Tab
and Enter to choose or Esc to cancel. The bottom-panel shortcut hides or restores an
existing panel without asking again.

A new terminal starts in the opened workspace folder. Without a workspace, it uses the active file's parent, or Sylph's working directory when no file is open. Changing the opened workspace affects future terminals, not existing shells. Duplicate starts an independent shell in the original terminal's **starting folder**, even after `cd`; it does not copy environment changes, history, or running commands.

Every new Herdr CLI tab uses a unique named Herdr session (`sylph-…`). Duplicate
starts an independent named session in the same starting folder, with the same
theme choice. It does not attach another client to the original tab's server.
New session names encode the full random UUID in lowercase base 36 instead of
using its longer dashed representation. This reduces the generated name from
42 to at most 31 characters, leaving more room for Herdr's Unix socket paths.

Under **Settings → Herdr Themes**, pick a built-in theme for newly
opened Herdr CLI clients. All 18 themes bundled with Herdr CLI 0.9.5 are available,
including light variants such as Catppuccin Latte, Tokyo Night Day, and One Light.
Selecting a built-in theme uses a separate config and session profile
under `~/.sylph/herdr-cli`. **Reset** uses your regular Herdr configuration and sessions
again. Open a new CLI tab after changing the setting.

### Import a CLI theme

In **Settings → Herdr Themes**, use **Browse…** to pick a local TOML file, or enter
a file path or raw HTTPS URL. Picking a file fills the input; canceling the picker
keeps its previous value. Click **Import and select** to validate and install it.
Imports run in the background; on success Sylph selects
**Imported theme** and saves that choice across restarts. Open a new Herdr CLI tab
to use it. A failure keeps the previous theme and selection. Reset returns to your
regular Herdr configuration without deleting the imported file.

If an import fails, its status distinguishes invalid theme/URL data, missing Python
or curl, download failure, and file-access problems. Messages suggest the next step
without displaying subprocess output, private file paths, or URL credentials.

Imports require Bash and Python 3.11 or newer; HTTPS downloads also require `curl`.
The UI invokes the same bundled shell workflow as the command below, including
when launching a packaged JAR from another working directory. No AI service,
Git checkout, or downloaded install script is used.

```bash
./scripts/import-herdr-theme.sh ./my-theme.toml
./scripts/import-herdr-theme.sh https://example.org/raw/theme.toml
```

Use a direct URL to the raw TOML file, not a GitHub repository or HTML page.
The script installs validated data to `~/.sylph/herdr-cli/imported-theme.toml`.
An optional second argument chooses another destination, useful for testing.
When importing from the command line, select **Imported theme** in Settings
afterward. Running terminals are not restarted. Selecting Imported theme before
importing a file reports an error when opening a Herdr terminal.

Files must contain only a nonempty `[theme]` table and optional `[theme.custom]`,
`[theme.custom.light]`, and `[theme.custom.dark]` color tables. For example:

```toml
[theme]
name = "nord"

[theme.custom]
accent = "#88c0d0"
text = "#eceff4"
```

The validator accepts the 18 built-in names and color tokens documented in
[Herdr CLI 0.9.5's theme schema](https://github.com/herdrdev/herdr/blob/v0.9.5/src/config/theme.rs).
Colors support hex, `rgb(r, g, b)` with components from 0 to 255, and Herdr's named
colors. Full application configs, unknown fields, invalid UTF-8, malformed TOML,
and files larger than 64 KiB are rejected. Imports write a private file atomically;
validation or download failure preserves the previous imported theme. Downloads
use HTTPS-only redirects, a size limit, and timeouts. The script never modifies
your regular Herdr config or executes downloaded content.

For shell integrations, the importer exits with `0` on success, `2` for incorrect
arguments, `3` for invalid theme/URL data, `4` for missing/unsupported Python, `5`
for missing curl, `6` for download failure, and `7` for theme-file access failures.
Other shell/tool failures can return other nonzero codes.

Herdr Desktop layouts such as Traveler and T3 cannot be imported as CLI color
themes. Desktop installation and its layout workflow are outside this integration.

Expand works for Shell and Herdr CLI. The terminal fills the Sylph
window with its tab strip still available. Restore brings back the editor layout
and keeps the same terminal session and output.

Switching tabs preserves unsaved document text, selection, and undo history. If you return to the editor while a terminal is starting, renderer completion respects that focus choice. Shell input and output never become document edits or trigger autosave. Opening an existing sidebar file selects the document tab.

## Kiro session restore

Choose **Herdr CLI** in the terminal chooser to reopen the saved Herdr tabs
for the current workspace or terminal starting folder. Sylph focuses the last used
tab and reuses any tabs already open. With no saved session for the folder, Sylph creates
one. Starting Sylph and **Reload Window** leave Herdr closed until requested.

In a normal Sylph shell started through `./run.sh`, type `herdr` to apply the
same lookup using the shell’s current directory, including after `cd`. The shell
entry point requires Python 3. Commands with explicit arguments, such as
`herdr --session work` or `herdr --help`, go straight to Herdr. If a matching
Herdr session is already associated with another open tab, Sylph focuses that tab.

The checkout-local state file is `.sylph/last-session.json` when launched through
`run.sh`. Direct launches without `SYLPH_UPDATE_ROOT` use
`~/.sylph/last-session.json` instead. The checkout’s `.sylph/` directory is ignored
by Git. Explicitly closing a terminal removes its saved entry. Exiting Sylph
retains open-tab entries. Duplicate on a dedicated Herdr tab opens a fresh
session. Kiro stores conversation history separately.

When `kiro-cli` starts in a new Sylph-managed Herdr pane, the launcher observes
Kiro's successful session creation and load replies through a private FIFO. It
writes the exact ID to the tab record immediately, even before the first prompt.
Protocol traffic passes through memory. Only the session reference goes into the
state file. The global session-start hook remains available for compatible engines.
Sylph reads the previous `herdr-sessions.json` if the new file is absent.
The hook also reports `kiro-cli --resume-id <id>` to Herdr through its supported
custom-agent resume API. Herdr then owns restarting that conversation in its saved
pane after a full server restart/reboot. Reattaching to a live Herdr server keeps
its running agent instead of launching a duplicate.
For earlier JSON records that have a Kiro ID but no native resume report, Sylph
checks Herdr's live snapshot and asks its ready-shell agent API in the saved folder to resume that
exact ID once. A live/restoring agent or native session reference blocks this
fallback, so it cannot type a resume command into an existing chat.
Records with `kiroSessionId: null` can reattach to a live Herdr session, but cannot
reconstruct an unrecorded conversation after a full server stop. Sylph does not
guess the newest conversation in the directory. Capture a session-start event
before testing reboot restore. Run `bash scripts/setup-kiro-capture.sh` to upgrade the
old Sylph hook in place, or use `./init.sh` during setup. If the ID is empty,
open the intended chat with `kiro-cli --resume-id <id>` inside Herdr. Keep the tab
open when quitting Sylph.
Each outer Herdr session stores a `paneSessions` map in `.sylph/last-session.json`.
Each entry records the internal pane ID, Kiro conversation ID and working directory.
Opening another chat in a pane replaces only that pane's entry. On restore, an active
agent in one tab leaves the other tabs eligible to resume. A missing pane is never
recreated by this fallback. Older records retain their single known conversation ID.

Setup requires Kiro CLI 2.13+ with global lifecycle hooks and Python 3.8+. If Kiro
is not installed, this optional setup is skipped. The installed files are
`~/.kiro/hooks/sylph-herdr-session.json` and
`~/.sylph/kiro-hooks/kiro-session-hook.py`. Other hooks and agent settings are kept.
A conflicting file using Sylph's hook filename is preserved and setup fails rather
than replacing it. The action is a local command; it does not ask an AI agent to
generate or process the capture.

Kiro's [global-hook release confirmation](https://github.com/kirodotdev/Kiro/issues/9687)
and [hook trigger reference](https://kiro.dev/docs/hooks/types/) describe the
session-start metadata this bridge consumes.

Only Kiro processes launched inside a Sylph-owned Herdr session inherit the capture
scope. The hook forwards only the exact session ID, tab identity, and directory;
prompts, conversation contents, and credentials are not forwarded. A random local
token protects an IPv4-loopback listener. Its current connection details live in
private `.sylph/kiro-capture.json` (or the user-home fallback directory), so existing
Herdr shells can reconnect when Sylph creates a replacement listener. This file is
also ignored by Git; it is separate from the saved tab/session references.
Unknown or closed tabs cannot be added by hook events. Unrelated and nested-agent
events are ignored. Closing the app stops its listener but retains saved session
IDs; explicit tab close still removes the tab's saved entry.

The installer writes a versioned JSON document with a `hooks` array. Kiro CLI
2.21.4 logs a schema warning and skips older, unwrapped definitions.
Automated capture verification uses synthetic lifecycle payloads, real loopback
requests, and temporary stores. A real interactive Kiro conversation must still be
checked manually for that agent/version. Separately, the opt-in native restart
test stops/restarts real Herdr with a fake Kiro and verifies exactly one launch
with the original resume ID. It makes no AI calls and is not a real laptop reboot.

`HerdrSessionStore` saves ordered Herdr tab/session identifiers, optional exact Kiro
session IDs, absolute working directories, editor/panel placement, selected themes,
and the last active Herdr tab. A missing Kiro ID stays missing rather than selecting
the newest conversation from that folder. Conversation contents and credentials are
not copied into this file. Missing working directories remain recorded so a later
restore step can report the problem without losing the reference.

State files are limited to 100 tabs and 1 MiB, validate identifiers and references,
and use private `0600` file permissions on POSIX filesystems. Writes force file data
to disk before atomic replacement. Invalid, oversized, non-UTF-8, symbolic-link,
and newer-format files are rejected; automatic saves refuse to replace them with
empty defaults. These protections do not promise recovery from disk failure or
every forced power-loss scenario.

Kiro supports resuming a specific conversation by ID; see its
[session-management documentation](https://kiro.dev/docs/cli/chat/session-management/).
Herdr's named server sessions and Kiro's conversation IDs are separate identities.
Restoring a Herdr layout alone is not proof that its Kiro conversation resumed.

## Shell behavior

The terminal uses a real pseudo-terminal through [pty4j](https://github.com/JetBrains/pty4j) and renders ANSI sequences with [xterm.js](https://github.com/xtermjs/xterm.js). Interactive command input, arrow keys, control characters, colors, alternate screens, and resize events use the terminal protocol. Terminal scrollback is capped at 5,000 lines.

On Unix, Sylph starts the executable named by `SHELL` with `-i`, falling back to `/bin/sh -i` if that path is unusable. On Windows it starts `ComSpec`, falling back to `cmd.exe`. The executable and arguments are passed directly, and the working directory is set through the process API. No directory name is interpolated into a shell command. Unix shell startup files may run according to the selected shell's normal behavior.

Ctrl+C interrupts a foreground command. Ctrl+Shift+C copies selected terminal text and Ctrl+Shift+V pastes; Cmd+C/Cmd+V also work on macOS. Right-click provides Copy/Paste. The Edit menu also routes Copy/Paste/Select All to the active terminal; terminal Undo/Redo/Cut are disabled. Opening the menu retains its editing target, and returning to the document restores document actions. Markdown preview's shortcut is bypassed while terminal has focus so terminal paste works. Other application shortcuts, including Save and the palette, remain available. Paste goes through xterm.js so bracketed-paste mode is preserved.

Close stops the shell and its observed child processes and releases PTY streams.
Reload Window and application shutdown dispose Sylph's terminal clients. Saved
Herdr clients reopen when requested; a live Herdr server retains its pane
processes, while stopped servers use their saved layouts/resume references. Normal
shell tabs are not persisted. A naturally exited terminal retains its output and
exit status until closed; Duplicate starts a fresh session.

The terminal runs with the user's normal permissions. Its commands can change files outside the opened workspace, just like an external terminal. Sylph's guarded sidebar file actions do not sandbox shell commands.

## Implementation and verification

- `TerminalWorkspace` owns file/terminal tabs, the resizable panel, and duplicate/close lifecycle.
- `HerdrTabTracker` serializes open/select/explicit-close saves on a dedicated worker;
  normal application shutdown drains queued writes while retaining open-tab entries.
- `TerminalPage` embeds bundled scripts/styles into a page with a fresh CSP nonce, so development and packaged JAR launches use the same renderer without resource-origin assumptions.
- `TerminalView` owns a local WebView, theme changes, clipboard routing, and output acknowledgements.
- `TerminalBridge` exposes only terminal input, binary input, resize, and output acknowledgements to the application-owned page.
- `TerminalSession` owns shell startup, ordered input, UTF-8 decoding, native resize, and asynchronous cleanup. Process startup, reads, writes, and shutdown stay off the JavaFX thread.

Each output chunk waits for xterm.js to consume it before the next chunk is delivered, bounding pending renderer work. Java strings cross the JavaScript bridge as values, never interpolated code. Output is parsed as terminal data, not HTML. Renderer assets are bundled locally, require no CDN at runtime, and use a restrictive content policy. The terminal WebView is separate from Markdown's JavaScript-disabled preview.

Tests run real Unix shells and the real renderer in headless JavaFX. See [testing](testing.md). Windows and macOS desktop behavior still requires verification on those platforms; successful Linux checks are not cross-platform runtime evidence. Startup failures appear inside the tab instead of opening an external terminal.

## Herdr startup troubleshooting

On Unix, Sylph waits for the PTY to report a nonzero row and column count before
starting the terminal command. This prevents Herdr from rejecting a terminal
whose grid is still being initialized.

### Server readiness and socket paths

If Herdr reports `server did not become ready within 15s`, inspect the socket path
in the error. Unix-domain sockets have a small OS-dependent path-length limit.
The old dashed UUID names could produce a 111-byte client-socket path under the
Sylph theme profile, exceeding Linux's limit. Compact names fix this case without
changing the saved-state schema or deleting existing session entries.

Close the failed tab, restart Sylph with `./run.sh`, and open a fresh Herdr tab to
use the shorter name. Existing saved identifiers are retained unchanged. Exceptionally
long configuration/home paths can still exceed the OS limit; this change reduces
the name overhead rather than relocating Herdr's configuration or sockets.

## Dependency maintenance

`gradle/libs.versions.toml` pins pty4j. Browser assets are pinned and attributed in [terminal/THIRD-PARTY.md](../app/src/main/resources/terminal/THIRD-PARTY.md), with upstream MIT notices retained alongside them. When upgrading, verify npm package integrity, copy only the required browser assets/licenses, then rerun shell, clipboard, renderer, resize, and lifecycle checks. Application startup does not download terminal assets.

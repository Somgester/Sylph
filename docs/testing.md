# Testing and troubleshooting

[Documentation index](README.md) · [Scripts](getting-started.md) · [Test file map](file-reference.md)

## Commands

```bash
./lint.sh
./test.sh
./build.sh
```

For one test class, or a real rerun instead of Gradle's up-to-date result:

```bash
./test.sh --tests '*ShellSyntaxTest'
./test.sh --rerun-tasks
```

For the combined Gradle verification lifecycle:

```bash
source ./init.sh
./gradlew :app:check
```

`check` includes tests and Checkstyle. `build.sh` runs assembly only. Compiler warnings are errors; Checkstyle also checks test sources. Its current rules include a 120-character line limit, no tabs, no wildcard imports, naming, spacing, and separation checks.

## Headless UI tests

Tests use JUnit Jupiter. The Gradle test task selects Monocle headless JavaFX and software rendering by default. `FxTestSupport` initializes JavaFX and coordinates work on its application thread. UI tests still construct real scenes and controls, but do not need desktop popup windows.

To explicitly inspect visible UI tests on a desktop:

```bash
./test.sh -PshowUiTests=true --rerun-tasks
```

Keep that option out of normal automated workflows. On a Linux CI host, the usual test run can additionally remove display variables:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY ./test.sh
```

Headless testing still needs the platform libraries required by JavaFX and WebView. It does not replace a manual check on each supported desktop environment.

## Reports and useful coverage

On Linux, `python3 scripts/test-desktop.py -v` checks desktop registration in temporary directories.
It verifies the shared mark and application identity, unchanged-file preservation, invalid paths,
and exact launcher execution with reserved filename characters when GIO is available. It opens no
editor windows. `AppIconTest` covers logo scaling and window-icon bounds through headless JavaFX.

Generated HTML results are in `app/build/reports/tests/test/index.html`; XML results are under `app/build/test-results/test/`. Checkstyle reports are under `app/build/reports/checkstyle/`. These are generated output, not files to edit or commit.

Terminal checks exercise real Unix PTY input/output, working directories, Unicode/ANSI output, resizing, Ctrl+C, child cleanup, startup/close races, and headless terminal tabs, duplication, clipboard/menu routing without document edits or autosaves, focus changes during startup, theme changes, sustained output, and reload. Test shells bypass personal shell startup files. Linux evidence does not verify Windows/macOS desktop behavior.

The suite exercises workspace create/delete safety and lifecycle, palette commands/file search, exact match colors in both themes, recent history and guarded scene reload, filesystem validation and external-change detection, delayed/immediate autosave, unsaved-change dialog suspension, sidebar navigation, independent document tabs and undo/selection, inactive autosave, guarded tab closure, themes and popup controls, syntax state/cancellation/incremental updates, and sanitized Markdown preview with scroll restoration. Tab tests also cover empty startup/last-close states, double-click creation, cancelled/failed first saves, autosave only after a confirmed location, and Close All cancellation across multiple dirty documents. Terminal tests verify context-menu closure disposes shells in both layouts. The [file reference](file-reference.md) describes each test class.

Test totals are intentionally not frozen in these docs. An `UP-TO-DATE` task reuses earlier results; use `--rerun-tasks` when fresh execution is needed.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Setup prints nothing when another script starts | Existing JDK detection is quiet when `init.sh` is sourced. Run `./init.sh` directly for the success message. |
| No working JDK 21 | Both `java` and `javac` must be version 21. Check `JAVA_HOME` or install proto as instructed by setup. |
| Tests open desktop windows | Remove `-PshowUiTests=true`; normal Gradle test configuration is headless. |
| JavaFX run configuration-cache failure | Use `run.sh`, which passes `--no-configuration-cache`. |
| `build.sh` missing during `dev.sh` | Ensure the checkout includes all six helper scripts from the same revision. |
| README absent from sidebar | Confirm the opened folder is the project root rather than `app/`. Reopen the folder to refresh its listing after external changes. |
| Autosave stays off for a new document | Save once to provide a path, then enable autosave in Settings. |
| Autosave pauses after a disk error | Read the status, resolve the cause, then save manually or toggle autosave. |
| File changed outside Sylph | Reopen to read disk contents, or Save As elsewhere to preserve editor edits. |
| Shell grammar loop messages | Restart after updating the grammar. If reproduced, capture the file and smallest triggering edit; do not merely suppress the logs. |
| Terminal reports a startup failure | Read the tab status, verify the working folder and shell executable, and check native-library extraction permissions. |
| Terminal paste opens Markdown preview | Ensure terminal has focus; Ctrl+Shift+V belongs to terminal paste there. |
| Markdown links/images are inactive | This is current preview behavior: links are text and images are placeholders. |
| `bwrap: loopback: Failed RTM_NEWADDR` | The external sandbox failed before project inspection or checks ran. Restore sandbox access; this output is not a Sylph build/test verdict. |

When reporting an issue, include the command, current revision, file extension, autosave mode, theme, reproduction steps, and whether the problem occurs in the editor, sidebar, or preview. Avoid including private document contents when a small synthetic example reproduces it.

## Setup script checks

Run `python3 scripts/test-setup.py -v` for isolated setup regression checks. Fake Docker/JDK tools verify first-run credentials, permissions, repeated setup, readiness/startup failure, Java provisioning, Java-only/sourced behavior, and empty/complete/partial OAuth credential pairs without changing real credentials or starting containers.

`LocalUpdateMonitorTest` checks content changes with unchanged timestamps, source
addition/deletion, build/launcher changes, ignored docs/build/workspace files,
one notification per launch, and shutdown. These tests use temporary checkouts and
invoke checks directly, without opening update popups. `scripts/test-desktop.py`
also verifies `run.sh` exports its own checkout path and preserves Gradle arguments.
`LocalUpdateNoticeTest` opens the real notice headlessly to verify nonmodal behavior,
light/dark theme changes, dismissal cleanup, and preservation of editor text.

To check the update notice manually, start `./run.sh`, keep the app open, then make
a real content change under `app/src/main` (or pull a source change). Within about
10 seconds, expect one **Sylph update available** notice. Dismiss it and make another
change; expect no repeated notice in that launch. Close normally, relaunch, and
confirm the notice does not recur without another change. Repeat in light/dark
themes and with an unsaved document; the notice should preserve the document and
allow editing to continue. Documentation-only changes should not trigger a notice.

Run `python3 scripts/test-herdr-theme.py -v` with Python 3.11+ for offline theme-import
checks. Temporary profiles and a fake downloader cover valid custom colors, HTTPS
restrictions, argument handling, failed downloads, invalid/oversized content,
symlink rejection, atomic replacement, and preservation of previous imports. These
checks do not download anything or start Herdr or Sylph.

`HerdrThemeImporterTest` additionally runs the bundled scripts against temporary
local theme files and verifies that the selected profile uses imported colors.
Those script integration cases require Bash and Python 3.11+ and skip when those
tools are unavailable. Headless `HerdrThemeSettingsPaneTest` covers background
execution, successful selection/persistence, failed imports, reset, and cancellation
when the settings pane is disposed. No real user profile is modified.

Importer checks also verify structured exit codes and fixed UI diagnostics for
missing Python/curl, invalid theme data, download failure, and inaccessible source
files. Unexpected exception text is not displayed in the settings pane.

`HerdrSessionStoreTest` exercises local persistence without launching Herdr or Kiro.
It checks ordered round trips, IDs, placement/theme/active-tab references, missing
working directories, private permissions, atomic-write cleanup, immutable state,
invalid/oversized/non-UTF-8 files, unknown schema versions, and symlink rejection.
Malformed existing state is preserved on both failed loads and attempted saves.
Its Unix-socket regression check binds a real socket in a temporary profile with
enough path overhead to reject the former dashed UUID name. New compact names fit
the tested 103-byte budget. This runs on Linux/macOS when the temporary base path
leaves sufficient room; it does not start Herdr or open a desktop window.
`HerdrTabTrackerTest` covers ordered lifecycle saves, explicit-close removal,
normal-shutdown retention, preservation of an existing exact Kiro ID, and reporting
corrupt-state failures without overwriting the file. Headless `TerminalWorkspaceTest`
checks real PTY launch arguments, placement persistence, unique duplicate session
IDs, explicit-close removal, and retention when disposing the application. Fake
Herdr executables and temporary stores keep real Herdr/Kiro sessions untouched.
`KiroSessionCaptureTest` verifies token checks, input limits, exact IDs for two tabs
sharing a folder, rejection of closed/unknown tabs, and real bundled-hook delivery
after listener replacement. The Python integration fixture requires Python 3 and
skips when unavailable. `python3 scripts/test-kiro-capture.py -v` verifies scoped
events, ignored nested/unrelated events, closed-tab replies, endpoint restrictions,
private installer output, filename conflicts, and version-gated setup. Tests use
temporary hook folders, not user hooks or actual agent conversations.
Headless restoration tests verify that the chooser opens all saved
tabs for the current folder and that the launched command sees a nonzero PTY
grid. A shell test uses `cd` and bare `herdr` to resume in the existing terminal,
then closes that tab and checks its saved entry is removed. `HerdrKiroResumeTest` covers exact legacy resume arguments, no duplicate
launch over live/pending/native-owned agents, and no invented resume when IDs are absent.
The fallback also refuses to use a shell in a different directory.

The opt-in Unix command `python3 scripts/test-herdr-restore.py` starts real Herdr
with isolated config/state and a fake Kiro executable, sends a synthetic native
session-start event, stops the named server, then attaches again. It verifies the
same ID is resumed once with `kiro-cli --resume-id exact-resume-id`. It disables
update checks and cleans up only its own named server. No real AI chat, user Kiro
history, or laptop reboot is used. Verified with Herdr 0.9.5 on Linux.

Run `python3 scripts/test-herdr-restore.py --real-kiro` for the opt-in multi-pane test.
The related headless JavaFX test can be run with
`SYLPH_TEST_REAL_KIRO=1 ./test.sh --tests '*realKiroWritesDistinctIdsToDiskBeforeSylphExits'`.
It exercises the chooser, duplicate, on-disk state, and explicit close using real executables.
It then restarts Sylph with one native resume command removed from the surviving test
session and verifies that Kiro registers the original ID again in that internal tab.

The Python restart check starts two real Herdr sessions with two internal Kiro tabs each in the same temporary workspace, waits for their distinct
session IDs, and verifies that each reloads its own ID. On Linux it force-stops the
fixture Herdr servers. It submits no prompts. Kiro can retain the test sessions in
its own history.

A separate native Herdr 0.9.5 smoke check on Linux reproduced rejection of a
111-byte socket path and verified that a 100-byte path using the compact session
name starts the real server/client in a PTY with a Nord config. That check used
temporary config/state directories, disabled update checks, and stopped only its
isolated named server afterward. It supplements the fake-CLI launch tests; it is
not part of the default test suite or evidence for macOS runtime behavior.

To review tab persistence manually, launch `./run.sh`, open a Herdr tab using its
terminal chooser, then open `.sylph/last-session.json`. Verify a new named identity
and its editor/panel location. Duplicate and select tabs, then close one: its entry
should disappear while the others remain. Exit Sylph normally with a Herdr tab
still open: its entry should remain. Relaunch Sylph and verify no Herdr tabs open.
Open the same workspace and choose Herdr CLI, or type `herdr` in a normal Sylph shell
in that folder. All saved tabs for that folder should reopen, with the most recent selected.

For a real Kiro capture check, run `./init.sh`, start Sylph, open Herdr from its
chooser, then start `kiro-cli` there. Submit a prompt if the selected engine delays
its session-start event. Compare `/session-id` in Kiro with that open tab's
`kiroSessionId` in `.sylph/last-session.json`. Repeat with two Herdr tabs using the
same directory and verify each ID stays with its tab. Close one Sylph tab: its entry
must disappear and remain absent even if an old hook event arrives. This real-agent
check is manual and is not implied by successful fixture tests.

After updating the hook through `./init.sh`, test a real agent/server restart in a
separate test session with a confirmed recorded ID. Reopening a live Herdr server
should reattach without another Kiro launch. After stopping that test server,
relaunching Sylph and explicitly opening Herdr should reopen the same session
and resume the recorded ID. A
record with no Kiro ID cannot recover an unrecorded chat after server shutdown;
verify this condition is reported accurately rather than guessing another chat.

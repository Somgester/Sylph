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

Generated HTML results are in `app/build/reports/tests/test/index.html`; XML results are under `app/build/test-results/test/`. Checkstyle reports are under `app/build/reports/checkstyle/`. These are generated output, not files to edit or commit.

Terminal checks exercise real Unix PTY input/output, working directories, Unicode/ANSI output, resizing, Ctrl+C, child cleanup, startup/close races, and headless terminal tabs, duplication, clipboard/menu routing without document edits or autosaves, focus changes during startup, theme changes, sustained output, and reload. Test shells bypass personal shell startup files. Linux evidence does not verify Windows/macOS desktop behavior.

The suite exercises workspace create/delete safety and lifecycle, palette commands/file search, exact match colors in both themes, recent history and guarded scene reload, filesystem validation and external-change detection, delayed/immediate autosave, unsaved-change dialog suspension, sidebar navigation, independent document tabs and undo/selection, inactive autosave, guarded tab closure, themes and popup controls, syntax state/cancellation/incremental updates, and sanitized Markdown preview with scroll restoration. The [file reference](file-reference.md) describes each test class.

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

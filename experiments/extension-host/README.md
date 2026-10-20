# Open source extension host experiment

This opt-in experiment evaluates reusing Eclipse Theia's VS Code extension host. It starts a separate Theia browser workbench with its existing Monaco editor. It does **not** install extensions into the JavaFX Sylph application or replace its editor.

The experiment uses local processes and open-source components. No account, API key, subscription, AI service, or paid infrastructure is required. Initial setup downloads npm packages, pinned extension archives, and headless Chromium. This is a development experiment, not a small application distribution.

## Run

Tested on Linux x64 with Node.js 24.21.0. Setup requires Node.js 22+, npm, Python 3, curl, tar, make, and a C++ compiler. The native credential module also needs the Linux `libsecret-1.so.0` runtime. Chromium needs its usual Linux system libraries.

```bash
cd experiments/extension-host
npm run setup
npm test
```

Setup installs dependencies from the lockfile with lifecycle scripts disabled, explicitly builds `drivelist`, verifies and extracts a pinned upstream keytar binary, builds the browser workbench, and downloads headless Chromium. It does not install system packages. A missing prerequisite stops setup with an explanation. On the verification machine, a temporary extracted Ubuntu make package supplied the missing make executable through PATH; the host operating system was not modified.

Only `npm run setup` downloads dependencies. `npm test` uses the prepared local files. Sylph's `init.sh`, `run.sh`, `dev.sh`, Gradle build, and regular tests do not invoke this experiment.

Generated dependencies, downloaded extension binaries, browser files, projects, preferences, screenshots, and logs are ignored by Git. The workspace-trust prompt is disabled only in the generated test workspace. No user project is opened by the test.

## What is reused

| Component | Pinned version | Declared license |
| --- | --- | --- |
| Eclipse Theia workbench and extension host | 1.76.0 | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 |
| Pyright extension, `ms-pyright.pyright` | 1.1.402 | MIT |
| TypeScript/JavaScript language features, `vscode.typescript-language-features` | 1.95.3 | MIT in LICENSE-vscode.txt |
| TypeScript language declarations, `vscode.typescript` | 1.95.3 | MIT in LICENSE-vscode.txt |
| JavaScript language declarations, `vscode.javascript` | 1.95.3 | MIT in LICENSE-vscode.txt |
| Playwright Core | 1.64.1 | Apache-2.0 |

Extensions are unmodified Open VSX archives. Setup checks each archive's SHA-256 and manifest identity before execution. The declaration extensions supply language identifiers and file associations; the language-feature extensions supply the actual intelligence. Upstream licenses remain inside the downloaded packages. The table identifies the main components, not every transitive dependency's license.

The local `probe` extension only creates temporary fixture documents, checks editor state, and coordinates test acknowledgements. It does not register a completion provider or supply suggestion lists. No per-language server launch command is written into Sylph for these tests.

## Verification

On 2026-10-20, setup/build succeeded and all 12 editor cases plus four archive extraction checks passed in the saved experiment on Linux x64. Setup verification reused the already downloaded archives after a fresh GitHub download hit a DNS timeout; the same pinned hash checks were applied to the cached files. An interruption check also confirmed that SIGTERM exits the runner with status 143 and terminates its ten observed descendant processes.

The test first checks archive extraction and rejection of traversal paths and symlinks. It then opens headless Chromium, connects to a loopback-only Theia backend on a temporary port, and loads the real extensions. For each editor case it types the final character, waits for a visible automatic suggestion, accepts it, verifies the document edit through the extension API, and verifies that Undo restores the typed source. It does not press Ctrl+Space to make the automatic-suggestion checks pass.

| Language | Cases |
| --- | --- |
| Python | builtin, variable, function, module import, imported symbol, class member, dot trigger |
| JavaScript | string method |
| TypeScript | number method, class member |
| JSX | string method in a JSX document |
| TSX | string method in a TSX document |

Reports appear in `generated/reports/result.json`, `host.log`, and `case-*.png`. The runner exits nonzero on a failed case or timeout and terminates its host process group and browser on normal completion, errors, Ctrl+C, or SIGTERM. These checks establish editor-level completion behavior in the experimental Theia workbench. They do not establish full language, extension, operating-system, or production-packaging compatibility.

## Observed compatibility gap

An earlier programmatic probe called `vscode.executeCompletionItemProvider`. In Theia 1.76.0 it returned an object with `incomplete` and `suggestions` fields, while a VS Code caller expects a `CompletionList` with `items`. The unadapted probe failed to find `print`. This is an observed API compatibility gap, not evidence that Pyright failed to activate: the actual editor UI subsequently passed the completion checks above.

The committed smoke test exercises the visible editor instead of silently modifying Theia or the third-party extensions. Arbitrary VS Code extensions cannot be promised to work simply because installation succeeds. See Theia's [extension compatibility documentation](https://theia-ide.org/docs/user_install_vscode_extensions/).

## Architecture recommendation

Reuse Theia's workbench and extension APIs together for an extension-enabled Sylph client. Extracting only its Node host and recreating its document, editor, command, preference, and diagnostic APIs around JavaFX would retain much of the integration work this evaluation is intended to avoid. Theia's [architecture](https://theia-ide.org/docs/architecture/) and [extension model](https://theia-ide.org/docs/extensions/) describe that dependency.

Keep the current JavaFX application available during any migration. This experiment makes no production cutover. The next implementation chunk should establish a parallel Theia workbench and an extension installation flow, then verify file handling, autosave, tabs, terminal lifecycle, themes, and shortcuts before changing the production launcher.

This choice changes the PRD's expectation of a fully custom editor core: Theia's editor integration uses Monaco here. It does not satisfy the custom piece-table/rendering requirement. That requirement must be reconciled with extension reuse before planning a custom renderer. The current no-paid-services constraint also means the PRD's Claude-only AI plan cannot be assumed for future work; no AI provider is included in this experiment.

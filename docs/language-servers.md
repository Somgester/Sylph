# Language servers

[Documentation index](README.md) · [Editor guide](editor-guide.md) · [Testing](testing.md)

## Python setup

Python support uses [Microsoft Pyright](https://github.com/microsoft/pyright). Install Node.js and Pyright separately:

```bash
npm install --global pyright
```

Open **Settings → Language servers — Python**:

1. Select **Enable Python language server**.
2. Enter `pyright-langserver`, or the absolute path to that executable. Enter only the executable; Sylph supplies `--stdio`.
3. Click **Apply and restart**.
4. Open a Python file. Status changes from **Starting** to **Ready** after initialization.

Server support starts disabled. Executable and enabled state are saved in user preferences. Editing the form does not restart a server until Apply is clicked. Applying unchanged settings also retries a failed server. A failed preferences write is reported while the current configuration stays active for the session.

Executable paths containing spaces remain a single argument. Relative executable paths such as `./server` are rejected: opening a workspace must not select executable code from that workspace. Server configuration comes from user settings.

Sylph shares one Python server across tabs in the same workspace. Files outside the opened workspace use their containing directory as the server root. Closing the last associated tab, disabling support, applying settings, or closing Sylph releases the old server. Save As updates the document's language and server root. A startup failure is shown in Settings; Apply retries it.

## Features

| Action | Shortcut |
| --- | --- |
| Completion | Ctrl+Space |
| Hover | Move the pointer over text, or Ctrl+Shift+Space |
| Go to definition | F12 |
| Show reported problems | Ctrl/Cmd+Shift+M |

Right-click also offers Hover, Go to Definition, and Show Problems. Diagnostics appear in the gutter; clicking a marker shows its messages. Multiple definitions open a chooser. Completion and associated import edits form one undoable change. Local suggestions remain available when the server is disabled or unavailable.

Python is the first configured server profile. Other language-server profiles are added separately.

## Verification

Ordinary `test.sh` and `dev.sh` runs need no installed language server and perform no downloads. They verify settings and lifecycle behavior using controlled server processes. UI tests run headless by default.

An explicit integration task verifies a real Pyright installation, including the Settings-to-editor completion flow:

```bash
source ./init.sh
./gradlew :app:pythonServerIntegrationTest \
  -PpythonServerExecutable="/absolute/path/to/pyright-langserver"
```

The integration task fails when no executable is supplied. It uses temporary projects and in-memory settings. Reports are in `app/build/reports/tests/pythonServerIntegrationTest/`.

Verified on Linux with Pyright 1.1.414 and Node.js 24.21.0. Verification includes completion edits, hover, definition lookup, diagnostics, and applying and undoing a real server completion inside the headless editor.

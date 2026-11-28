# Getting started

[Documentation index](README.md) · [File reference](file-reference.md)

Sylph is a JavaFX desktop text editor built with JDK 21 and Gradle. The editing control is RichTextFX; syntax colors come from bundled TextMate grammars through TM4E.

## Setup and launch

Run from the project root in Bash:

```bash
./init.sh
./dev.sh
```

`dev.sh` runs lint, tests, build, then launches the app. A failed step stops the workflow. For app launch alone:

```bash
./run.sh
```

A desktop display is needed to run the application. Tests use headless JavaFX by default. Initial Gradle and dependency downloads require network access; grammars are bundled and need no runtime download.

## Script reference

| Script | Work performed | Example |
| --- | --- | --- |
| [init.sh](../init.sh) | Sets up JDK 21, Herdr CLI, optional Kiro capture and local Keycloak; `--java-only` sets up Java alone | `./init.sh` |
| [lint.sh](../lint.sh) | Runs `:app:checkstyleMain` and `:app:checkstyleTest` | `./lint.sh` |
| [test.sh](../test.sh) | Runs `:app:test` | `./test.sh --tests '*ShellSyntaxTest'` |
| [build.sh](../build.sh) | Runs `:app:assemble`; does not run tests or lint | `./build.sh` |
| [run.sh](../run.sh) | Runs `:app:run --no-configuration-cache` | `./run.sh --console=plain` |
| [dev.sh](../dev.sh) | Runs lint → tests → build → app | `./dev.sh --console=plain` |

Direct `./init.sh` checks for an installed Herdr CLI and skips its download when available.
Otherwise, it downloads Herdr 0.9.5 for Linux or macOS (x86_64/ARM64), verifies its
pinned SHA-256 checksum, and installs it under `~/.local/share/sylph/tools/bin`.
This needs curl and sha256sum (or shasum). A fresh `./run.sh` discovers the installed
binary automatically. `./init.sh --java-only` skips Herdr and Keycloak setup.

If Kiro CLI is installed, direct `./init.sh` also installs Sylph's local session-start
capture hook. This needs Kiro CLI 2.13+ and Python 3.8+. It preserves other hooks and
agent settings; an existing conflicting Sylph hook filename stops setup. If Kiro is
absent, optional capture setup is skipped. `--java-only` and sourced initialization
skip hook installation. See [Kiro capture and restoration](terminal.md#kiro-session-restore).

Direct `./init.sh` also requires a running Docker installation with Docker Compose v2; it starts Keycloak and imports the Sylph realm/client automatically. For automatic Google/GitHub configuration on a fresh machine, fill their client IDs/secrets in `auth/.env` before setup; see [authentication setup](authentication.md).

Each workflow script resolves its own directory and sources Java setup and local Herdr PATH discovery from `init.sh`, so invoking it from another working directory works. Arguments are forwarded as separate quoted arguments. `dev.sh` forwards its arguments only to `run.sh`, not to its check/build steps. Arguments reach Gradle, not directly the Java main method.

On Linux, an interactive `run.sh` launch asks whether to install a user-local Sylph desktop launcher
and scalable `S` icon. Answer `y` to create or refresh it; any other answer or end of input skips
installation and launches the app. Noninteractive launches skip installation, and the installed desktop
launcher skips the prompt. This registers a launcher for this checkout; keep the checkout available.
The launcher matches JavaFX's window class so the desktop can identify Sylph instead of displaying a
generic Java icon. It shares the same mark as the in-app logo and window icon, respects
`XDG_DATA_HOME`, and needs no administrator access. An unchanged registration is not rewritten; a
registration failure does not prevent app launch. Restart an already-running Sylph window to pick up
the desktop identity. For direct Gradle launches, register it once with
`bash scripts/install-desktop.sh`.

### Updates after pulling code

The desktop icon launches this checkout's `run.sh`; Gradle compiles changed sources
before opening the app. After `git pull`, closing and reopening Sylph through the
desktop icon or `./run.sh` uses the local changes. The launcher does not need to be
installed again for source updates.

While an app started through `run.sh` is open, a background check looks for changes
to its local application sources/resources, scripts, and build configuration about
every 10 seconds. One themed, nonmodal **Sylph update available** notice appears per
launch when those inputs change, including uncommitted edits. Save your work, close
Sylph normally, then relaunch to rebuild and use the update. **Reload Window** only
recreates the UI inside the current process; it does not reload changed Java classes.

The notice never closes the app, changes open files, saves documents, or runs Git.
It does not contact GitHub or discover unpulled remote commits. Build output, docs,
and files elsewhere in the opened workspace do not trigger it. Reopening establishes
a fresh baseline, so already-present changes do not cause another update prompt.
Direct Gradle or packaged distribution launches do not enable checkout monitoring
unless launched with `SYLPH_UPDATE_ROOT` pointing at that checkout.

## Canvas preview

Open or create a document, right-click inside its source editor, and choose **Show Canvas Preview**.
The optional pane renders the live buffer beside the editable source and follows the theme setting.
It is read-only; edit in the source pane. Scroll long lines with **Shift + mouse wheel** or a horizontal
trackpad gesture; line numbers stay fixed. Horizontal range grows as wider rows are displayed.
Click preview text to move the source caret, or drag to select. Hold **Shift** while clicking or
starting a drag to extend the existing source selection. Clicking
the gutter moves to the line start. The preview mirrors source selection and shows a steady caret;
selected newlines have a small end marker. Use **Ctrl+C** (or **Cmd+C**) to copy. Click back in the source pane to edit.
Use **Close** in its header or **Hide Canvas Preview** in
the context menu to remove it. This is the custom renderer's preview stage; see [editor core](editor-core.md).

## Java detection

`init.sh` checks these sources in order:

1. `JAVA_HOME`, requiring both `java` and `javac` version 21.
2. Java on `PATH`, inspecting its `java.home` and checking the complete JDK.
3. Proto binaries and shims under `PROTO_HOME`, or `$HOME/.proto` by default.
4. If proto is available but no usable JDK was found, `proto install jdk` using [.prototools](../.prototools).

If neither a complete JDK 21 nor proto is available, setup prints installation instructions and exits with failure. It does not fetch and execute a remote installer. When run directly, successful setup prints whether installation was skipped or completed and points to `dev.sh` and `run.sh`. When sourced by another script, an already-installed JDK is selected quietly.

Running `./init.sh` cannot change the parent shell's environment. The other scripts source it themselves. For direct Gradle commands in your current shell, use:

```bash
source ./init.sh
./gradlew :app:check
```

## Build and distribution

The wrapper selects Gradle 8.14.4. JavaFX is configured in [app/build.gradle](../app/build.gradle); dependency versions are in the [version catalog](../gradle/libs.versions.toml). The build enables all Java compiler warnings and treats them as errors. Checkstyle uses the shared [rules](../config/checkstyle/checkstyle.xml).

```bash
source ./init.sh
./gradlew :app:installDist
```

The installed distribution is under `app/build/install/app/`, with launcher scripts in `bin/` and libraries in `lib/`. Assembly also produces distribution archives under `app/build/distributions/`.

Configuration caching is enabled globally. `run.sh` disables it for the JavaFX run task because that task captures source sets that cannot be serialized by the cache.

The `.sh` helpers require Bash. Windows has `gradlew.bat` for direct Gradle tasks after configuring a JDK; the helpers are not native PowerShell scripts.

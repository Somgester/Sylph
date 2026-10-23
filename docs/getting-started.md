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
| [init.sh](../init.sh) | Sets up JDK 21 and local Keycloak; use `--java-only` for guest-only setup | `./init.sh` |
| [lint.sh](../lint.sh) | Runs `:app:checkstyleMain` and `:app:checkstyleTest` | `./lint.sh` |
| [test.sh](../test.sh) | Runs `:app:test` | `./test.sh --tests '*ShellSyntaxTest'` |
| [build.sh](../build.sh) | Runs `:app:assemble`; does not run tests or lint | `./build.sh` |
| [run.sh](../run.sh) | Runs `:app:run --no-configuration-cache` | `./run.sh --console=plain` |
| [dev.sh](../dev.sh) | Runs lint → tests → build → app | `./dev.sh --console=plain` |

Direct `./init.sh` also requires a running Docker installation with Docker Compose v2; it starts Keycloak and imports the Sylph realm/client automatically. For automatic Google/GitHub configuration on a fresh machine, fill their client IDs/secrets in `auth/.env` before setup; see [authentication setup](authentication.md).

Each workflow script resolves its own directory and sources only the Java setup from `init.sh`, so invoking it from another working directory works. Arguments are forwarded as separate quoted arguments. `dev.sh` forwards its arguments only to `run.sh`, not to its check/build steps. Arguments reach Gradle, not directly the Java main method.

On Linux, `run.sh` also registers a user-local Sylph launcher and scalable `S` icon. The launcher
matches JavaFX's window class so the desktop can identify Sylph instead of displaying a generic Java
icon. It shares the same mark as the in-app logo and window icon, respects `XDG_DATA_HOME`, and needs
no administrator access. An unchanged registration is not rewritten; a registration failure does
not prevent app launch. Restart an already-running Sylph window to pick up the desktop identity.
For direct Gradle launches, register it once with `bash scripts/install-desktop.sh`.

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

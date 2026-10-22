# Complete file reference

[Documentation index](README.md) · [Architecture](architecture.md) · [Testing](testing.md)

Every tracked implementation, resource, test, and configuration file has an entry below. Paths are relative to the repository root. Generated output (`build/`, `app/build/`), Gradle caches, and Git internals are excluded. When adding a file, update its group here and the relevant feature guide.

## Application source

| File | Responsibility |
| --- | --- |
| [app/src/main/java/dev/somgester/sylph/App.java](../app/src/main/java/dev/somgester/sylph/App.java) | Java entry point; launches the JavaFX application. |
| [app/src/main/java/dev/somgester/sylph/AppIcon.java](../app/src/main/java/dev/somgester/sylph/AppIcon.java) | Builds the S logo, action symbols, and window icon from JavaFX shapes. |
| [app/src/main/java/dev/somgester/sylph/AppSettings.java](../app/src/main/java/dev/somgester/sylph/AppSettings.java) | Observable theme/autosave settings, defaults, preference storage, and persistence status. |
| [app/src/main/java/dev/somgester/sylph/AutoSaveController.java](../app/src/main/java/dev/somgester/sylph/AutoSaveController.java) | Schedules immediate/delayed saves, invalidates obsolete callbacks, and owns nestable suspension handles. |
| [app/src/main/java/dev/somgester/sylph/EditorApp.java](../app/src/main/java/dev/somgester/sylph/EditorApp.java) | Composes the scene; coordinates commands, dialogs, file-action guards, themes, preview, and shutdown. |
| [app/src/main/java/dev/somgester/sylph/EditorChrome.java](../app/src/main/java/dev/somgester/sylph/EditorChrome.java) | Compact menus and icon toolbar; binds enabled states and routes shared commands. |
| [app/src/main/java/dev/somgester/sylph/EditorEditMenu.java](../app/src/main/java/dev/somgester/sylph/EditorEditMenu.java) | Focus-aware Edit actions for documents, terminals, and text fields; preserves menu targets and guards unsupported actions. |
| [app/src/main/java/dev/somgester/sylph/EditorFileService.java](../app/src/main/java/dev/somgester/sylph/EditorFileService.java) | Validates UTF-8 regular files; normalizes line endings and saves with conflict checks and temporary files. |
| [app/src/main/java/dev/somgester/sylph/EditorLanguage.java](../app/src/main/java/dev/somgester/sylph/EditorLanguage.java) | Language enum, display labels, extension detection, and shell startup filename recognition. |
| [app/src/main/java/dev/somgester/sylph/EditorDocument.java](../app/src/main/java/dev/somgester/sylph/EditorDocument.java) | One file tab and its session, view/history, autosave, preview, and disposal. |
| [app/src/main/java/dev/somgester/sylph/EditorSession.java](../app/src/main/java/dev/somgester/sylph/EditorSession.java) | Observable document state and asynchronous file operations; separates busy from editing-blocked state. |
| [app/src/main/java/dev/somgester/sylph/EditorShortcuts.java](../app/src/main/java/dev/somgester/sylph/EditorShortcuts.java) | Command names/key combinations and scene-level dispatch, including the folder chord. |
| [app/src/main/java/dev/somgester/sylph/EditorView.java](../app/src/main/java/dev/somgester/sylph/EditorView.java) | RichTextFX CodeArea, line numbers, session synchronization, editing actions, undo/redo, and highlighter ownership. |
| [app/src/main/java/dev/somgester/sylph/MarkdownHtml.java](../app/src/main/java/dev/somgester/sylph/MarkdownHtml.java) | Cleans rendered HTML with a safelist and replaces links/images with inert representations. |
| [app/src/main/java/dev/somgester/sylph/MarkdownPreview.java](../app/src/main/java/dev/somgester/sylph/MarkdownPreview.java) | Live WebView pane; schedules rendering, follows theme/document changes, preserves scroll, and cancels stale work. |
| [app/src/main/java/dev/somgester/sylph/MarkdownRenderer.java](../app/src/main/java/dev/somgester/sylph/MarkdownRenderer.java) | CommonMark parser with tables/strikethrough; sanitized HTML page and embedded preview stylesheet. |
| [app/src/main/java/dev/somgester/sylph/SettingsPage.java](../app/src/main/java/dev/somgester/sylph/SettingsPage.java) | Scrollable theme/autosave controls, shortcut help, persistence messages, and popup theme synchronization. |
| [app/src/main/java/dev/somgester/sylph/Sidebar.java](../app/src/main/java/dev/somgester/sylph/Sidebar.java) | Resizable/collapsible file tree, directory loading, sorted entries, and file activation callbacks. |
| [app/src/main/java/dev/somgester/sylph/SyntaxGrammars.java](../app/src/main/java/dev/somgester/sylph/SyntaxGrammars.java) | Worker-owned TM4E registry and packaged grammar scope/resource mapping. |
| [app/src/main/java/dev/somgester/sylph/SyntaxHighlighter.java](../app/src/main/java/dev/somgester/sylph/SyntaxHighlighter.java) | Debounced worker scheduling, cancellation/revision checks, and JavaFX style application. |
| [app/src/main/java/dev/somgester/sylph/SyntaxStyles.java](../app/src/main/java/dev/somgester/sylph/SyntaxStyles.java) | TextMate scope-to-CSS mapping and incremental style-span patches. |
| [app/src/main/java/dev/somgester/sylph/SyntaxTokenizer.java](../app/src/main/java/dev/somgester/sylph/SyntaxTokenizer.java) | Line tokenization, multiline state cache, UTF-16 offsets, reuse, interruption, and bounded per-line retries. |
| [app/src/main/java/dev/somgester/sylph/CommandPalette.java](../app/src/main/java/dev/somgester/sylph/CommandPalette.java) | In-scene command/file search panel, keyboard navigation, query cancellation, and guarded activation. |
| [app/src/main/java/dev/somgester/sylph/PaletteFileSearch.java](../app/src/main/java/dev/somgester/sylph/PaletteFileSearch.java) | Background relative-path matching with capped results, skipped cache/dependency trees, and no symlink traversal. |
| [app/src/main/java/dev/somgester/sylph/PaletteMatch.java](../app/src/main/java/dev/somgester/sylph/PaletteMatch.java) | Shared Unicode-aware literal query matching and merged UTF-16 highlight ranges. |
| [app/src/main/java/dev/somgester/sylph/RecentFiles.java](../app/src/main/java/dev/somgester/sylph/RecentFiles.java) | Bounded newest-first successful file history with production preferences and an injectable store. |
| [app/src/main/java/dev/somgester/sylph/WorkspaceFileService.java](../app/src/main/java/dev/somgester/sylph/WorkspaceFileService.java) | Scoped file/folder creation and recursive deletion, name validation, workspace boundaries, and symlink handling. |
| [app/src/main/java/dev/somgester/sylph/TerminalSession.java](../app/src/main/java/dev/somgester/sylph/TerminalSession.java) | PTY shell startup, ordered input, UTF-8 output, resize, and process cleanup. |
| [app/src/main/java/dev/somgester/sylph/TerminalView.java](../app/src/main/java/dev/somgester/sylph/TerminalView.java) | Local terminal WebView, theme and clipboard routing, and renderer backpressure. |
| [app/src/main/java/dev/somgester/sylph/TerminalPage.java](../app/src/main/java/dev/somgester/sylph/TerminalPage.java) | Self-contained renderer page with embedded resources and per-page CSP nonce. |
| [app/src/main/java/dev/somgester/sylph/TerminalBridge.java](../app/src/main/java/dev/somgester/sylph/TerminalBridge.java) | Public WebKit bridge for terminal input, binary input, resize, and output acknowledgement. |
| [app/src/main/java/dev/somgester/sylph/TerminalWorkspace.java](../app/src/main/java/dev/somgester/sylph/TerminalWorkspace.java) | Editor/file tabs, resizable terminal panel, duplication, and tab disposal. |

## Authentication files

| File | Responsibility |
| --- | --- |
| [AccountController.java](../app/src/main/java/dev/somgester/sylph/AccountController.java) | Optional in-memory account lifecycle, browser dispatch, cancellation, expiry, and logout. |
| [AccountMenu.java](../app/src/main/java/dev/somgester/sylph/AccountMenu.java) | Native initials avatar, verified identity summary, and sign-in/sign-out menu. |
| [AccountPage.java](../app/src/main/java/dev/somgester/sylph/AccountPage.java) | Native sign-in/account page with guest editing preserved. |
| [AuthConfiguration.java](../app/src/main/java/dev/somgester/sylph/AuthConfiguration.java) | Non-secret issuer/client/loopback-port configuration. |
| [AuthHttp.java](../app/src/main/java/dev/somgester/sylph/AuthHttp.java) | Bounded, timed OIDC requests without redirects or sensitive response logging. |
| [AuthFailure.java](../app/src/main/java/dev/somgester/sylph/AuthFailure.java) | Fixed, safe diagnostics for code-exchange and token-verification failures. |
| [OAuthCallback.java](../app/src/main/java/dev/somgester/sylph/OAuthCallback.java) | Short-lived loopback listener with exact paths and state/issuer validation. |
| [OidcClient.java](../app/src/main/java/dev/somgester/sylph/OidcClient.java) | Browser authorization code + PKCE, Nimbus token validation, and RP-initiated logout. |
| [auth/compose.yaml](../auth/compose.yaml) | Loopback-only local Keycloak server with realm readiness check, started by direct `init.sh`. |
| [auth/sylph-realm.json](../auth/sylph-realm.json) | Public desktop client, exact redirect URIs, and local registration policy. |
| [auth/start-keycloak.sh](../auth/start-keycloak.sh) | Enables complete Google/GitHub credential pairs before first realm import. |
| `auth/.env` (generated, ignored) | Private admin and optional OAuth credentials; created once by setup. |
| [scripts/setup-auth.sh](../scripts/setup-auth.sh) | Private credential generation, Docker startup, and realm readiness wait. |
| [scripts/test-setup.py](../scripts/test-setup.py) | Isolated setup regression checks with fake tools and temporary credentials. |
| [scripts/test-auth.py](../scripts/test-auth.py) | Disposable-user provisioning and real Keycloak integration runner. |
| [Authentication tests](../app/src/test/java/dev/somgester/sylph/) | Account UI/controller, callback, token-security fixture, and opt-in `KeycloakIntegrationTest` checks. |

## Stylesheets

| File | Responsibility |
| --- | --- |
| [app/src/main/resources/styles/dark.css](../app/src/main/resources/styles/dark.css) | Dark theme palette and themed JavaFX controls. |
| [app/src/main/resources/styles/editor.css](../app/src/main/resources/styles/editor.css) | CodeArea, gutter, syntax classes, scrolling, and JavaFX preview container styling. |
| [app/src/main/resources/styles/light.css](../app/src/main/resources/styles/light.css) | Light theme palette and themed JavaFX controls. |
| [app/src/main/resources/styles/markdown.css](../app/src/main/resources/styles/markdown.css) | Browser CSS embedded into Markdown preview HTML. |
| [app/src/main/resources/styles/settings.css](../app/src/main/resources/styles/settings.css) | Settings layout, cards, controls, and popup styling. |
| [app/src/main/resources/styles/shell.css](../app/src/main/resources/styles/shell.css) | Application menus, compact icon actions, and logo styling; unrelated to shell-language grammar. |

## Grammars and licenses

| File | Responsibility |
| --- | --- |
| [app/src/main/resources/syntax/grammars/JSON.tmLanguage.json](../app/src/main/resources/syntax/grammars/JSON.tmLanguage.json) | JSON grammar. |
| [app/src/main/resources/syntax/grammars/JavaScript.tmLanguage.json](../app/src/main/resources/syntax/grammars/JavaScript.tmLanguage.json) | JavaScript grammar. |
| [app/src/main/resources/syntax/grammars/JavaScriptReact.tmLanguage.json](../app/src/main/resources/syntax/grammars/JavaScriptReact.tmLanguage.json) | JSX grammar. |
| [app/src/main/resources/syntax/grammars/MagicPython.tmLanguage.json](../app/src/main/resources/syntax/grammars/MagicPython.tmLanguage.json) | Python grammar. |
| [app/src/main/resources/syntax/grammars/TypeScript.tmLanguage.json](../app/src/main/resources/syntax/grammars/TypeScript.tmLanguage.json) | TypeScript grammar. |
| [app/src/main/resources/syntax/grammars/TypeScriptReact.tmLanguage.json](../app/src/main/resources/syntax/grammars/TypeScriptReact.tmLanguage.json) | TSX grammar. |
| [app/src/main/resources/syntax/grammars/java.tmLanguage.json](../app/src/main/resources/syntax/grammars/java.tmLanguage.json) | Java grammar. |
| [app/src/main/resources/syntax/grammars/shell-unix-bash.tmLanguage.json](../app/src/main/resources/syntax/grammars/shell-unix-bash.tmLanguage.json) | Shell grammar with documented local TM4E compatibility fixes. |
| [app/src/main/resources/syntax/licenses/LICENSE-java-grammar.txt](../app/src/main/resources/syntax/licenses/LICENSE-java-grammar.txt) | Java grammar upstream license. |
| [app/src/main/resources/syntax/licenses/LICENSE-javascript-textmate.txt](../app/src/main/resources/syntax/licenses/LICENSE-javascript-textmate.txt) | JavaScript TextMate grammar upstream license. |
| [app/src/main/resources/syntax/licenses/LICENSE-json-grammar.txt](../app/src/main/resources/syntax/licenses/LICENSE-json-grammar.txt) | JSON grammar upstream license. |
| [app/src/main/resources/syntax/licenses/LICENSE-python-grammar.txt](../app/src/main/resources/syntax/licenses/LICENSE-python-grammar.txt) | MagicPython grammar upstream license. |
| [app/src/main/resources/syntax/licenses/LICENSE-shell-grammar.txt](../app/src/main/resources/syntax/licenses/LICENSE-shell-grammar.txt) | Shell provenance, pinned revisions, checksum, adaptations, and upstream license. |
| [app/src/main/resources/syntax/licenses/LICENSE-typescript-grammars.txt](../app/src/main/resources/syntax/licenses/LICENSE-typescript-grammars.txt) | TypeScript/TSX grammar upstream license. |
| [app/src/main/resources/syntax/licenses/LICENSE-vscode.txt](../app/src/main/resources/syntax/licenses/LICENSE-vscode.txt) | VS Code license notice for bundled resources derived from that project. |
| [app/src/main/resources/syntax/grammars/c.tmLanguage.json](../app/src/main/resources/syntax/grammars/c.tmLanguage.json) | C grammar with optional unbundled embeddings removed. |
| [app/src/main/resources/syntax/grammars/cpp.tmLanguage.json](../app/src/main/resources/syntax/grammars/cpp.tmLanguage.json) | C++ grammar, including templates, raw strings, and documented Joni compatibility adaptations. |
| [app/src/main/resources/syntax/grammars/cpp.embedded.macro.tmLanguage.json](../app/src/main/resources/syntax/grammars/cpp.embedded.macro.tmLanguage.json) | Required C++ macro grammar and references to the main C++ repository. |
| [app/src/main/resources/syntax/licenses/LICENSE-c-grammar.txt](../app/src/main/resources/syntax/licenses/LICENSE-c-grammar.txt) | C grammar source revisions, original checksum, local adaptations, and MIT license. |
| [app/src/main/resources/syntax/licenses/LICENSE-cpp-grammar.txt](../app/src/main/resources/syntax/licenses/LICENSE-cpp-grammar.txt) | C++/macro grammar provenance, original checksums, adaptations, and MIT license. |

## Tests and support

| File | Responsibility |
| --- | --- |
| [app/src/test/java/dev/somgester/sylph/AdditionalSyntaxTest.java](../app/src/test/java/dev/somgester/sylph/AdditionalSyntaxTest.java) | Python, JavaScript/JSX, and TypeScript/TSX scopes, multiline behavior, and shared language fixtures. |
| [app/src/test/java/dev/somgester/sylph/AppSettingsTest.java](../app/src/test/java/dev/somgester/sylph/AppSettingsTest.java) | Setting defaults, persistence, delay validation, and persistence failures. |
| [app/src/test/java/dev/somgester/sylph/AppTest.java](../app/src/test/java/dev/somgester/sylph/AppTest.java) | Application entry-class structure and constructor. |
| [app/src/test/java/dev/somgester/sylph/AutoSaveControllerTest.java](../app/src/test/java/dev/somgester/sylph/AutoSaveControllerTest.java) | Delay reset, immediate saves, edits during saves, failure recovery, suspension, and obsolete callbacks. |
| [app/src/test/java/dev/somgester/sylph/EditorTabsTest.java](../app/src/test/java/dev/somgester/sylph/EditorTabsTest.java) | Independent file tabs, selection/history, reopening, close choices, inactive autosave, and Save As conflicts. |
| [app/src/test/java/dev/somgester/sylph/EditorAppTest.java](../app/src/test/java/dev/somgester/sylph/EditorAppTest.java) | Application theme stylesheet selection. |
| [app/src/test/java/dev/somgester/sylph/EditorChromeTest.java](../app/src/test/java/dev/somgester/sylph/EditorChromeTest.java) | Compact accessible icon actions, command menus, settings binding, undo-manager rebinding, and focused text-field edits. |
| [app/src/test/java/dev/somgester/sylph/EditorFileServiceTest.java](../app/src/test/java/dev/somgester/sylph/EditorFileServiceTest.java) | BOM/line-ending preservation, external-change refusal, overwrite rules, invalid UTF-8, and controls including DEL. |
| [app/src/test/java/dev/somgester/sylph/EditorLanguageIntegrationTest.java](../app/src/test/java/dev/somgester/sylph/EditorLanguageIntegrationTest.java) | Successful/failed opens, same-text language changes, Save As, shell startup files, and language indicator. |
| [app/src/test/java/dev/somgester/sylph/EditorLanguageTest.java](../app/src/test/java/dev/somgester/sylph/EditorLanguageTest.java) | Filename/extension language detection and plain-text fallback. |
| [app/src/test/java/dev/somgester/sylph/EditorLayoutTest.java](../app/src/test/java/dev/somgester/sylph/EditorLayoutTest.java) | Line-number gutter, scrolling, theme rendering, selection, and undo preservation. |
| [app/src/test/java/dev/somgester/sylph/EditorShortcutsTest.java](../app/src/test/java/dev/somgester/sylph/EditorShortcutsTest.java) | Command dispatch, folder chord, and cancellation by unrelated typing. |
| [app/src/test/java/dev/somgester/sylph/EditorSyntaxTest.java](../app/src/test/java/dev/somgester/sylph/EditorSyntaxTest.java) | Actual CodeArea colors, edits/undo, language switches, Save As, themes, and document-state preservation. |
| [app/src/test/java/dev/somgester/sylph/EditorViewTest.java](../app/src/test/java/dev/somgester/sylph/EditorViewTest.java) | Session synchronization, undo, styling without dirty/autosave side effects, and disposal. |
| [app/src/test/java/dev/somgester/sylph/FxTestSupport.java](../app/src/test/java/dev/somgester/sylph/FxTestSupport.java) | Shared JavaFX toolkit initialization and application-thread test helpers; support code rather than a test class. |
| [app/src/test/java/dev/somgester/sylph/MarkdownPreviewTest.java](../app/src/test/java/dev/somgester/sylph/MarkdownPreviewTest.java) | Preview commands, live updates, themes, inert HTML, document changes, and scroll preservation/clamping. |
| [app/src/test/java/dev/somgester/sylph/MarkdownRendererTest.java](../app/src/test/java/dev/somgester/sylph/MarkdownRendererTest.java) | Markdown and embedded HTML output, literal code, sanitization, and self-contained themed pages. |
| [app/src/test/java/dev/somgester/sylph/SettingsPageTest.java](../app/src/test/java/dev/somgester/sylph/SettingsPageTest.java) | Setting controls, navigation, dropdown themes, small-window layout, and rendered snapshots. |
| [app/src/test/java/dev/somgester/sylph/ShellSyntaxTest.java](../app/src/test/java/dev/somgester/sylph/ShellSyntaxTest.java) | Shell token colors, arithmetic/substitutions, heredocs, multiline scopes, and non-advancing-rule regression. |
| [app/src/test/java/dev/somgester/sylph/SidebarFileOpenTest.java](../app/src/test/java/dev/somgester/sylph/SidebarFileOpenTest.java) | Click/Enter navigation, save destinations, document lifecycle, unsaved choices, errors, and no autosave dimming. |
| [app/src/test/java/dev/somgester/sylph/SidebarTest.java](../app/src/test/java/dev/somgester/sylph/SidebarTest.java) | Directory listings, tree state, sizing, collapse/restore, and drag-handle layout. |
| [app/src/test/java/dev/somgester/sylph/SyntaxGrammarsTest.java](../app/src/test/java/dev/somgester/sylph/SyntaxGrammarsTest.java) | Packaged grammar loading, scope availability, and registry reuse. |
| [app/src/test/java/dev/somgester/sylph/SyntaxHighlighterTest.java](../app/src/test/java/dev/somgester/sylph/SyntaxHighlighterTest.java) | Obsolete results, incomplete-result handling, failure recovery, language changes, and worker/listener shutdown. |
| [app/src/test/java/dev/somgester/sylph/SyntaxIncrementalTest.java](../app/src/test/java/dev/somgester/sylph/SyntaxIncrementalTest.java) | Cache reuse, multiline-state convergence, cancellation/timeouts, and mixed-edit equivalence with full parsing. |
| [app/src/test/java/dev/somgester/sylph/SyntaxStylesTest.java](../app/src/test/java/dev/somgester/sylph/SyntaxStylesTest.java) | Scope categories, span coverage, and changed-region patches. |
| [app/src/test/java/dev/somgester/sylph/SyntaxTokenizerTest.java](../app/src/test/java/dev/somgester/sylph/SyntaxTokenizerTest.java) | Multiline grammar states, Unicode offsets, blank lines, timeouts, cancellation, and immutable results. |
| [app/src/test/java/dev/somgester/sylph/UnsavedChangesTest.java](../app/src/test/java/dev/somgester/sylph/UnsavedChangesTest.java) | Save/Discard/Cancel flows and autosave suspension across confirmations and file choosers. |
| [app/src/test/java/dev/somgester/sylph/CommandPaletteTest.java](../app/src/test/java/dev/somgester/sylph/CommandPaletteTest.java) | Centered layout, search activation, command/focus behavior, and reload Save/Discard/Cancel with autosave protection. |
| [app/src/test/java/dev/somgester/sylph/PaletteFileSearchTest.java](../app/src/test/java/dev/somgester/sylph/PaletteFileSearchTest.java) | Relative-path matching, exclusions, symlinks, result limits, and interruption. |
| [app/src/test/java/dev/somgester/sylph/PaletteMatchTest.java](../app/src/test/java/dev/somgester/sylph/PaletteMatchTest.java) | Exact match ranges, multi-word/repeated/overlapping queries, Unicode offsets, and literal regex characters. |
| [app/src/test/java/dev/somgester/sylph/RecentFilesTest.java](../app/src/test/java/dev/somgester/sylph/RecentFilesTest.java) | Normalized deduplication, newest-first persistence, bounded history, and storage failure fallback. |
| [app/src/test/java/dev/somgester/sylph/CppSyntaxTest.java](../app/src/test/java/dev/somgester/sylph/CppSyntaxTest.java) | C/C++ preprocessor colors, multiline state, raw strings, templates/lambdas/macros, and incomplete-edit loop regressions. |
| [app/src/test/java/dev/somgester/sylph/WorkspaceFileServiceTest.java](../app/src/test/java/dev/somgester/sylph/WorkspaceFileServiceTest.java) | Creation/deletion on temporary trees, duplicates, invalid names, containment, symlinks, directory-swap protection, unsupported filesystems, and cancellation. |
| [app/src/test/java/dev/somgester/sylph/WorkspaceActionsTest.java](../app/src/test/java/dev/somgester/sylph/WorkspaceActionsTest.java) | Headless create buttons/context actions, nested refresh, unsaved delete choices through direct and aliased paths, autosave suspension, and failure preservation. |
| [app/src/test/java/dev/somgester/sylph/TerminalSessionTest.java](../app/src/test/java/dev/somgester/sylph/TerminalSessionTest.java) | Real Unix shell/PTY behavior, resize, interruption, startup failure, and process cleanup. |
| [app/src/test/java/dev/somgester/sylph/TerminalPageTest.java](../app/src/test/java/dev/somgester/sylph/TerminalPageTest.java) | Offline renderer assets and per-page script authorization. |
| [app/src/test/java/dev/somgester/sylph/TerminalWorkspaceTest.java](../app/src/test/java/dev/somgester/sylph/TerminalWorkspaceTest.java) | Headless terminal layouts, document preservation, duplication, clipboard/menu routing, autosave protection, startup focus, rendering, themes, output, and reload. |

## Scripts and build configuration

| File | Responsibility |
| --- | --- |
| [.gitattributes](../.gitattributes) | Git line-ending rules for wrapper scripts and binary treatment for JARs. |
| [.gitignore](../.gitignore) | Excludes Gradle caches, build output, and compiled application launch output. |
| [.prototools](../.prototools) | Proto JDK selection: temurin-21, used by init.sh when provisioning is needed. |
| [README.md](../README.md) | Root navigation only; links to the core documentation. |
| [app/build.gradle](../app/build.gradle) | Application/JavaFX/Checkstyle setup, repositories, dependencies, compiler settings, and headless tests. |
| [build.sh](../build.sh) | Assembles the app through Gradle after initializing Java. |
| [config/checkstyle/checkstyle.xml](../config/checkstyle/checkstyle.xml) | Java formatting and naming checks shared by main/test sources. |
| [dev.sh](../dev.sh) | Fail-fast lint, test, build, and launch workflow. |
| [gradle.properties](../gradle.properties) | Enables Gradle configuration caching globally. |
| [gradle/libs.versions.toml](../gradle/libs.versions.toml) | Dependency aliases, pinned versions, and the explicit TM4E runtime bundle. |
| [gradle/wrapper/gradle-wrapper.jar](../gradle/wrapper/gradle-wrapper.jar) | Committed Gradle bootstrap binary; maintain using the wrapper tooling, not manual binary edits. |
| [gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) | Gradle distribution URL/version, wrapper download paths, and timeout settings. |
| [gradlew](../gradlew) | Unix Gradle wrapper launcher. |
| [gradlew.bat](../gradlew.bat) | Windows Gradle wrapper launcher. |
| [init.sh](../init.sh) | JDK 21 detection/provisioning plus direct-run Keycloak setup; sourced usage and `--java-only` keep guest workflows independent. |
| [lint.sh](../lint.sh) | Runs Checkstyle for main and test sources. |
| [run.sh](../run.sh) | Launches JavaFX with configuration caching disabled for the run task. |
| [settings.gradle](../settings.gradle) | Names the root project, includes app, and configures the Foojay toolchain resolver. |
| [test.sh](../test.sh) | Runs the Gradle test task and forwards arguments. |

## Terminal resources

| File | Responsibility |
| --- | --- |
| [app/src/main/resources/terminal/index.html](../app/src/main/resources/terminal/index.html) | Local terminal page and content policy. |
| [app/src/main/resources/terminal/terminal.js](../app/src/main/resources/terminal/terminal.js) | Sylph xterm.js/bridge integration, theme, clipboard paste, and resize. |
| [app/src/main/resources/terminal/terminal.css](../app/src/main/resources/terminal/terminal.css) | Browser terminal container sizing. |
| [app/src/main/resources/terminal/xterm.js](../app/src/main/resources/terminal/xterm.js) | Pinned upstream terminal emulator. |
| [app/src/main/resources/terminal/xterm.css](../app/src/main/resources/terminal/xterm.css) | Upstream terminal styles. |
| [app/src/main/resources/terminal/addon-fit.js](../app/src/main/resources/terminal/addon-fit.js) | Pinned upstream terminal sizing addon. |
| [app/src/main/resources/terminal/LICENSE-xterm.txt](../app/src/main/resources/terminal/LICENSE-xterm.txt) | Upstream terminal MIT notice. |
| [app/src/main/resources/terminal/LICENSE-fit.txt](../app/src/main/resources/terminal/LICENSE-fit.txt) | Upstream fit addon MIT notice. |
| [app/src/main/resources/terminal/THIRD-PARTY.md](../app/src/main/resources/terminal/THIRD-PARTY.md) | Pinned versions, sources, licenses, and archive integrity. |

## Documentation files

| File | Responsibility |
| --- | --- |
| [docs/README.md](README.md) | Documentation navigation hub. |
| [docs/getting-started.md](getting-started.md) | Setup, script behavior, Java provisioning, and build/distribution commands. |
| [docs/editor-guide.md](editor-guide.md) | User-facing files, saves, settings, shortcuts, supported languages, and preview behavior. |
| [docs/architecture.md](architecture.md) | Component ownership, state flow, threading, lifecycle, and maintenance boundaries. |
| [docs/syntax-highlighting.md](syntax-highlighting.md) | TextMate pipeline, incremental cache, fallbacks, shell adaptations, and adding languages. |
| [docs/testing.md](testing.md) | Headless checks, reports, regression areas, and troubleshooting. |
| [docs/file-reference.md](file-reference.md) | This complete repository file map. |
| [docs/terminal.md](terminal.md) | Integrated terminal behavior, lifecycle, dependencies, and platform limits. |

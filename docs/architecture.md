# Architecture

[Documentation index](README.md) · [Complete file reference](file-reference.md)

Production classes share the package `dev.somgester.sylph` in the single Gradle `app` module. Most implementation classes are package-private. The application separates document state, disk operations, presentation, and background rendering.

## Main flow as of now

```mermaid
flowchart TB
    entry["App"] --> app["EditorApp: scene and lifecycle"]

    subgraph ui["JavaFX UI and document state"]
        commands["EditorChrome / EditorShortcuts"]
        sidebar["Sidebar"]
        session["EditorSession"]
        view["EditorView / CodeArea"]
        settings["AppSettings"]
        page["SettingsPage"]
        autosave["AutoSaveController"]
        preview["MarkdownPreview / WebView"]
    end

    commands -->|commands| app
    sidebar -->|selected file| app
    app -->|guarded open and save| session
    app -->|compose| view
    app -->|show or hide| preview
    app -->|suspend during file actions| autosave
    session <-->|document text| view
    page <-->|preferences| settings
    settings -->|mode and delay| autosave
    session -->|dirty and busy state| autosave
    autosave -->|background save| session
    session -->|document changes| preview
    settings -->|theme| preview

    subgraph io["File IO worker"]
        files["EditorFileService"]
    end
    session -->|read or save snapshot| files
    files <-->|validated UTF-8 data| disk[("Filesystem")]
    files -->|completion on JavaFX thread| session

    subgraph syntax["Syntax worker"]
        tokenizer["SyntaxTokenizer: incremental line cache"]
        grammars["SyntaxGrammars: bundled TextMate resources"]
        styles["SyntaxStyles: scope mapping and patches"]
        tokenizer -->|load grammar| grammars
        tokenizer -->|tokens| styles
    end
    view -->|plain-text edits| highlighter["SyntaxHighlighter: scheduling and revision checks"]
    session -->|language| highlighter
    highlighter -->|debounced snapshot| tokenizer
    styles -->|patch via JavaFX thread| highlighter
    highlighter -->|apply current styles| view

    subgraph markdown["Markdown worker"]
        renderer["MarkdownRenderer: CommonMark"]
        sanitizer["MarkdownHtml: sanitization"]
        renderer -->|generated HTML| sanitizer
        sanitizer -->|clean body| renderer
    end
    preview -->|text and theme| renderer
    renderer -->|page via JavaFX thread| preview
```

Arrows show commands and data flow, not every constructor dependency. Worker results return to the JavaFX thread before updating controls.

`EditorApp` owns the scene and connects these components. It coordinates dialogs, foreground file actions, theme changes, preview visibility, and shutdown. `EditorChrome` means the surrounding menus/toolbars, not the text document or a browser engine.

## Document lifecycle and disk work

`EditorSession` exposes observable text, path, language, dirty state, busy state, editing-blocked state, and status. Its executor performs file tasks away from the JavaFX thread. Completion handlers accept the current operation only, so late results cannot replace a newer state or resurrect a closed session.

`EditorView` synchronizes plain-text changes with the session and owns selection, line numbers, undo/redo, and the syntax highlighter. Style changes must never become document edits or trigger saves. A successful open marks a new document even when its text equals the previous document; Save As changes the path without resetting editing history.

`EditorFileService` returns snapshots containing path, normalized text, line-ending convention, BOM flag, and a SHA-256 digest. Save compares on-disk content with the expected digest, writes a temporary sibling, preserves existing POSIX permissions when supported, rechecks the digest, and atomically replaces an existing destination. New destinations use a move without replacement. Temporary files are cleaned up in `finally`.

These checks detect external modifications but are not a filesystem lock or a guarantee against every concurrent writer race. File bytes and editor text are held in memory; this is not a streaming large-file editor.

## Autosave and foreground actions

`AutoSaveController` observes the session and settings. Generation numbers invalidate queued timer callbacks. Nested suspension handles pause saving across a confirmation and its following chooser/save/load flow. Releasing a handle is idempotent; closing the controller removes listeners and invalidates callbacks.

`busy` covers any file operation. `editingBlocked` distinguishes foreground operations from background autosaves. The sidebar binds to `editingBlocked`, preventing autosave from repeatedly changing its disabled appearance. File-action coordination still prevents overlapping operations; it does not queue navigation clicks during a save.

## Thread boundaries and disposal

- Scene changes, properties used by controls, and application of rendered results stay on the JavaFX application thread.
- File IO belongs to the session executor; sidebar directory discovery also uses asynchronous tasks.
- Syntax tokenization uses one worker with its own grammar registry and line cache. Immutable results cross back to JavaFX.
- Markdown rendering uses background work and rejects obsolete results before applying them to WebView.
- Owners close views/controllers, detach listeners, cancel pending work, and shut down their workers. Preserve these paths when introducing new asynchronous features.

## Theme and preview boundaries

`dark.css` and `light.css` supply theme colors. `shell.css`, `editor.css`, and `settings.css` style JavaFX components using those values. `markdown.css` is browser CSS embedded into preview HTML; it is not a JavaFX stylesheet.

`MarkdownRenderer` parses Markdown with table and strikethrough extensions and cleans the complete generated body through `MarkdownHtml`. Cleaning the full body preserves paired inline HTML and block structure. Preview pages also include a restrictive content policy, and WebView JavaScript is disabled. Same-document scroll restoration belongs to `MarkdownPreview`, not the renderer.

## Maintenance boundaries

Keep filesystem validation in the file service, document state in the session, command wiring in the app/shortcuts, and visual rules in the relevant stylesheets. Test changes at the boundary they affect: disk contents for saves, actual style spans for highlighting, and headless JavaFX scenes for lifecycle and layout. See [testing](testing.md) and [grammar maintenance](syntax-highlighting.md).

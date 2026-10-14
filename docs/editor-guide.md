# Editor guide

[Documentation index](README.md) · [Architecture](architecture.md)

## Files and folders

Open a file through the File menu, toolbar, or shortcut. Open a folder to populate the sidebar, then single-click a file or press Enter on its selected row to load it. Directories are listed before files, with names sorted without case sensitivity. The sidebar can be resized or collapsed.

Sylph edits one active document. Opening another document resets its selection, caret, and undo history. Save As keeps the current document's history and selects its language from the successful destination path. Failed or cancelled opens keep the current document.

Files must be regular UTF-8 text files. Symbolic links, special files, invalid UTF-8, and unsupported control characters (including DEL) are rejected. Tabs and line breaks are accepted. There is currently no explicit 5 MiB limit: the complete file is read into memory, so large files still consume memory and processing time.

UTF-8 BOMs are preserved. Line endings are normalized to LF while editing; saves use the detected original convention. A file with mixed line endings is normalized to one convention on an edited save, rather than preserving each individual line ending.

## Save and autosave

Save writes to the current location; Save As chooses another location. Files changed externally are refused instead of silently overwritten: reopen the file, or use Save As to retain your edits in another file. Existing-file saves require atomic replacement support from the filesystem. Errors leave the document available in the editor.

Settings provides dark/light themes, autosave on/off, and delays of **Immediately, 1, 2, 5, or 10 seconds**. Defaults are dark theme, autosave off, and a 2-second delay. Preferences persist through Java's user preferences store. If persistence fails, settings still apply to the current session and a status message explains the failure.

An untitled document needs its first manual save before autosave has a destination. Immediate mode queues a save without an intentional typing delay; disk work is still asynchronous. Edits made during a save remain dirty and can trigger the next save. Background autosaves do not disable or dim the sidebar.

Autosave pauses throughout unsaved-change confirmations and associated file selection. Cancel releases the pause and restarts scheduling. Save failures pause autosave; save manually or toggle it off and on to retry. Discard is a decision about unsaved edits, not an undo of changes that were already saved before the prompt.

## Shortcuts

Use Ctrl on Linux/Windows and Cmd on macOS wherever `Mod` appears.

| Action | Shortcut |
| --- | --- |
| Open file | Mod+O |
| Open folder | Mod+Shift+O |
| Open folder, alternate chord | Mod+K, then Mod+O within 1.5 seconds |
| Save | Mod+S |
| Save As | Mod+Shift+S |
| Toggle sidebar | Mod+B |
| Settings | Mod+, |
| Toggle Markdown preview | Mod+Shift+V |
| Toggle autosave | Mod+Alt+S |

The editor also exposes undo, redo, cut, copy, paste, and select-all through its editing controls and menus. Application commands share the definitions in [EditorShortcuts.java](../app/src/main/java/dev/somgester/sylph/EditorShortcuts.java).

## Languages

| Language | Recognized extensions |
| --- | --- |
| Java | `.java` |
| JSON | `.json` |
| Python | `.py`, `.pyw`, `.pyi` |
| JavaScript | `.js`, `.mjs`, `.cjs` |
| JSX | `.jsx` |
| TypeScript | `.ts`, `.mts`, `.cts` |
| TSX | `.tsx` |
| Shell | `.sh`, `.bash`, `.zsh`, `.ksh`, `.zsh-theme` |
| Markdown preview | `.md`, `.markdown` |

Names are matched without case sensitivity. Shell also recognizes `.bashrc`, `.bash_aliases`, `.bash_profile`, `.bash_login`, `.bash_logout`, `.profile`, `.envrc`, and dotted or undotted `zshrc`, `zshenv`, `zprofile`, `zlogin`, `zlogout`.

Other files remain editable as plain text. Detection uses the filename, not the shebang. Markdown has preview support but no TextMate source highlighting. C and C++ grammars are not currently registered. Syntax colors do not run, compile, or type-check the source.

## Markdown preview

Open a Markdown file and use Mod+Shift+V, View → Preview, or right-click the editor and choose Preview. The live preview sits alongside the source and follows the theme. Same-document edits and theme changes preserve its scroll position; another Markdown file resets it, and shorter content clamps the position.

The renderer supports CommonMark, tables, strikethrough, and allowed embedded HTML, including common headings, lists, tables, inline formatting, and details/summary. It sanitizes HTML before loading the page. Scripts, event handlers, arbitrary styles, frames, and resource-loading attributes are removed. Links display as inactive text, and images become alt-text placeholders. Preview is therefore not a general-purpose browser or an exact GitHub rendering.

Closing preview returns to the source without replacing its text or undo history. Opening a non-Markdown file closes preview.

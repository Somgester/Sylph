# Editor guide

[Documentation index](README.md) · [Architecture](architecture.md)

## Files and folders

Open a file through the File menu, toolbar, or shortcut. Open a folder to populate the sidebar, then single-click a file or press Enter on its selected row to load it. Directories are listed before files, with names sorted without case sensitivity. The sidebar can be resized or collapsed. Selected entries use the active theme’s accent text and selection surface, including when focus returns to the editor.

Sylph edits one active document. Opening another document resets its selection, caret, and undo history. Save As keeps the current document's history and selects its language from the successful destination path. Failed or cancelled opens keep the current document.

Files must be regular UTF-8 text files. Symbolic links, special files, invalid UTF-8, and unsupported control characters (including DEL) are rejected. Tabs and line breaks are accepted. There is currently no explicit 5 MiB limit: the complete file is read into memory, so large files still consume memory and processing time.

UTF-8 BOMs are preserved. Line endings are normalized to LF while editing; saves use the detected original convention. A file with mixed line endings is normalized to one convention on an edited save, rather than preserving each individual line ending.

## Create and delete workspace entries

Open a folder to enable the sidebar's New File and New Folder buttons. Creation uses the selected directory, the parent of a selected file, or the workspace root when nothing is selected. Right-click a row for the same actions, or right-click empty sidebar space to create at the workspace root. Enter a single filename/folder name; nested paths, absolute paths, and existing names are refused.

New files are created empty and opened with the appropriate language and fresh undo history. New folders preserve the active document. Creating a file checks unsaved edits before switching; cancelling a prompt keeps the current document. Directory listings refresh asynchronously while preserving other loaded/expanded nodes.

Right-click an entry and choose Delete, or press Delete with a non-root sidebar entry selected. Deletion always asks for confirmation and permanently removes the selected entry; folders include their contents. The opened workspace root is protected. Symbolic links are unlinked rather than followed, and actions through symbolic-link parent directories are refused. Deletion uses secure directory handles to prevent concurrent folder swaps from redirecting it outside the workspace. It is refused on filesystems without that support, or when any ancestor of the workspace path is a symbolic link.

Deleting an active file or a folder containing it first offers Save/Discard/Cancel, including when the active file was opened through a symbolic-link folder alias. Autosave pauses through the decisions and operation. After successful deletion, the affected editor returns to an empty untitled document, its undo history is cleared, and deleted paths leave recent-file history. Cancelling or a failed operation retains editor text. Recursive deletion is not transactional: a filesystem failure can leave a partially deleted folder, and the refreshed sidebar shows the remaining entries.

## Save and autosave

Save writes to the current location; Save As chooses another location. Files changed externally are refused instead of silently overwritten: reopen the file, or use Save As to retain your edits in another file. Existing-file saves require atomic replacement support from the filesystem. Errors leave the document available in the editor.

Settings provides dark/light themes, autosave on/off, and delays of **Immediately, 1, 2, 5, or 10 seconds**. Defaults are dark theme, autosave off, and a 2-second delay. Preferences persist through Java's user preferences store. If persistence fails, settings still apply to the current session and a status message explains the failure.

An untitled document needs its first manual save before autosave has a destination. Immediate mode queues a save without an intentional typing delay; disk work is still asynchronous. Edits made during a save remain dirty and can trigger the next save. Background autosaves do not disable or dim the sidebar.

Autosave pauses throughout unsaved-change confirmations and associated file selection. Cancel releases the pause and restarts scheduling. Save failures pause autosave; save manually or toggle it off and on to retry. Discard is a decision about unsaved edits, not an undo of changes that were already saved before the prompt.

## Command palette and file search

The centered search entry shares the top row with the File/Edit/View menus and save actions. Click it or press Mod+P to search regular files beneath the opened folder. Queries match words in relative paths without case sensitivity; search skips `.git`, `.gradle`, and `node_modules`, does not follow symbolic links, and displays at most 200 matches. Narrow the query if results are capped. File search runs in the background and cancels superseded queries.

Opening file search with an empty query suggests up to 20 recently opened or manually saved files, newest first. Successful opens through the dialog, sidebar, or palette update this list; background autosaves do not reorder it. History persists in Java user preferences across app restarts and Reload Window. Missing files and symbolic-link entries are skipped in suggestions. Recent files can appear even without an opened folder; paths outside the current folder display in full. Typing searches matching recent files first, then the workspace, with duplicates removed and at most 200 total results.

Typing updates the existing result list without clearing it while a search is pending. A still-matching selection is retained when new results arrive. Enter and double-click wait for current-query results so an older suggestion cannot open the wrong file.

Matching query words are colored with the theme accent and bolded in both file and command results. Matching ignores case, supports multiple words and Unicode filenames, and keeps the original label text intact.

Press Mod+Shift+P, or start a query with `>`, to search application commands, including Reload Window. Use Up/Down to select, Enter or double-click to activate, and Esc or a click outside the panel to dismiss. Tab keeps focus within the panel. Commands unavailable in the current state are omitted.

Reload Window rebuilds the scene and reopens the current folder and saved file. Unsaved changes receive the same Save/Discard/Cancel decision as file switching, with autosave paused during the decision. Reload resets undo history, preview visibility, and sidebar presentation; theme and autosave preferences remain. Cancel retains the current scene and edits. The palette appears inside the existing window and follows both themes.

## Shortcuts

Use Ctrl on Linux/Windows and Cmd on macOS wherever `Mod` appears.

| Action | Shortcut |
| --- | --- |
| Search files | Mod+P |
| Command palette | Mod+Shift+P |
| Reload window | Mod+Alt+R |
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
| C | `.c`, `.i` |
| C++ | `.cpp`, `.cc`, `.cxx`, `.c++`, `.h`, `.hpp`, `.hh`, `.hxx`, `.h++`, `.ii`, `.inl`, `.ipp`, `.tpp`, `.txx`, `.cppm`, `.ccm`, `.cxxm`, `.c++m`, `.ixx`, `.ino`, uppercase `.C` |
| Java | `.java` |
| JSON | `.json` |
| Python | `.py`, `.pyw`, `.pyi` |
| JavaScript | `.js`, `.mjs`, `.cjs` |
| JSX | `.jsx` |
| TypeScript | `.ts`, `.mts`, `.cts` |
| TSX | `.tsx` |
| Shell | `.sh`, `.bash`, `.zsh`, `.ksh`, `.zsh-theme` |
| Markdown preview | `.md`, `.markdown` |

Names are matched without case sensitivity except the conventional C/C++ distinction: `.c` is C and uppercase `.C` is C++. Shared `.h` headers default to C++; detection does not inspect their contents. Shell also recognizes `.bashrc`, `.bash_aliases`, `.bash_profile`, `.bash_login`, `.bash_logout`, `.profile`, `.envrc`, and dotted or undotted `zshrc`, `zshenv`, `zprofile`, `zlogin`, `zlogout`.

Other files remain editable as plain text. Detection uses the filename, not the shebang. Markdown has preview support but no TextMate source highlighting. Syntax colors do not run, compile, or type-check the source. C/C++ includes keyword/builtin-type, function, number, string, comment, and preprocessor highlighting; C++ also supports templates, raw strings, and its bundled macro grammar. Autocomplete and compiler integration are not implemented by these grammars.

## Markdown preview

Open a Markdown file and use Mod+Shift+V, View → Preview, or right-click the editor and choose Preview. The live preview sits alongside the source and follows the theme. Same-document edits and theme changes preserve its scroll position; another Markdown file resets it, and shorter content clamps the position.

The renderer supports CommonMark, tables, strikethrough, and allowed embedded HTML, including common headings, lists, tables, inline formatting, and details/summary. It sanitizes HTML before loading the page. Scripts, event handlers, arbitrary styles, frames, and resource-loading attributes are removed. Links display as inactive text, and images become alt-text placeholders. Preview is therefore not a general-purpose browser or an exact GitHub rendering.

Closing preview returns to the source without replacing its text or undo history. Opening a non-Markdown file closes preview.

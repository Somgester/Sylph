# Editor guide

[Documentation index](README.md) · [Architecture](architecture.md)

## Files and folders

Open a file through the File menu, toolbar, or shortcut. Open a folder to populate the sidebar, then single-click a file or press Enter on its selected row to load it. Directories are listed before files, with names sorted without case sensitivity. The sidebar can be resized or collapsed. Selected entries use the active theme’s accent text and selection surface, including when focus returns to the editor.

Sylph starts with an empty editor and no document tabs. Double-click the empty editor or blank tab-bar space, choose File → New File, or press Mod+N to create an unsaved Untitled file. Double-clicking document text or an existing tab does not create a file. New files have no disk location until you confirm Save As.

Each different file opens in its own tab beside editor terminal tabs. Reopening a file selects its existing tab, preserving unsaved edits. Each tab keeps its text, selection, caret, undo/redo history, syntax state, and optional Markdown preview. A sole clean Untitled tab can be reused for the first file; a modified Untitled tab is retained. Failed or cancelled opens leave existing tabs intact.

Use a file tab's close button or right-click any tab for Close Tab and Close All Tabs. Close Tab targets the clicked tab, including inactive tabs. Close All Tabs checks every dirty document before removing any tabs, and also closes terminal tabs in both layouts. Dirty tabs offer Save, Discard, or Cancel. Cancelling a decision or save dialog, or a failed save, retains the tabs and unsaved content. Closing the last tab leaves the empty editor; no replacement Untitled tab is created. Save and Edit actions follow the selected file. Save As retains history and updates the tab name and language; overwriting a file already open in another tab is refused.

Files must be regular UTF-8 text files. Symbolic links, special files, invalid UTF-8, and unsupported control characters (including DEL) are rejected. Tabs and line breaks are accepted. There is currently no explicit 5 MiB limit: the complete file is read into memory, so large files still consume memory and processing time.

UTF-8 BOMs are preserved. Line endings are normalized to LF while editing; saves use the detected original convention. A file with mixed line endings is normalized to one convention on an edited save, rather than preserving each individual line ending.

## Create and delete workspace entries

Open a folder to enable the sidebar's New File and New Folder buttons. Creation uses the selected directory, the parent of a selected file, or the workspace root when nothing is selected. Right-click a row for the same actions, or right-click empty sidebar space to create at the workspace root. Enter a single filename/folder name; nested paths, absolute paths, and existing names are refused.

New files are created empty and opened with the appropriate language and fresh undo history. New folders preserve the active document. New files open in their own tab, preserving existing unsaved documents. Directory listings refresh asynchronously while preserving other loaded/expanded nodes.

Right-click an entry and choose Delete, or press Delete with a non-root sidebar entry selected. Deletion always asks for confirmation and permanently removes the selected entry; folders include their contents. The opened workspace root is protected. Symbolic links are unlinked rather than followed, and actions through symbolic-link parent directories are refused. Deletion uses secure directory handles to prevent concurrent folder swaps from redirecting it outside the workspace. It is refused on filesystems without that support, or when any ancestor of the workspace path is a symbolic link.

Deleting files or folders checks every affected open tab, including inactive tabs and files opened through symbolic-link folder aliases, and offers Save/Discard/Cancel for unsaved edits. Autosave pauses through the decisions and operation. After successful deletion, affected tabs close and deleted paths leave recent-file history. Other tabs remain open; no replacement document is created. Cancelling or a failed operation retains editor text. Recursive deletion is not transactional: a filesystem failure can leave a partially deleted folder, and the refreshed sidebar shows the remaining entries.

## Save and autosave

Save writes to the current location; an unsaved file always opens Save As so you can choose its folder and name. Cancelling writes nothing and leaves the file without a location. Save As chooses another location for an existing file. Save is disabled when no document is open. Files changed externally are refused instead of silently overwritten: close and reopen its tab, or use Save As to retain your edits in another file. Existing-file saves require atomic replacement support from the filesystem. Errors leave the document available in the editor.

Settings provides dark/light themes, autosave on/off, and delays of **Immediately, 1, 2, 5, or 10 seconds**. Defaults are dark theme, autosave off, and a 2-second delay. Preferences persist through Java's user preferences store. If persistence fails, settings still apply to the current session and a status message explains the failure.

An untitled document needs its first manual save before autosave has a destination. Immediate mode queues a save without an intentional typing delay; disk work is still asynchronous. Edits made during a save remain dirty and can trigger the next save. Autosave continues for inactive document tabs. Background autosaves do not disable or dim the sidebar.

Autosave pauses throughout unsaved-change confirmations and associated file selection. Cancel releases the pause and restarts scheduling. Save failures pause autosave; save manually or toggle it off and on to retry. Discard is a decision about unsaved edits, not an undo of changes that were already saved before the prompt.

## Command palette and file search

The centered search entry shares the top row with the File/Edit/View menus and save actions. Click it or press Mod+P to search regular files beneath the opened folder. Queries match words in relative paths without case sensitivity; search skips `.git`, `.gradle`, and `node_modules`, does not follow symbolic links, and displays at most 200 matches. Narrow the query if results are capped. File search runs in the background and cancels superseded queries.

Opening file search with an empty query suggests up to 20 recently opened or manually saved files, newest first. Successful opens through the dialog, sidebar, or palette update this list; background autosaves do not reorder it. History persists in Java user preferences across app restarts and Reload Window. Missing files and symbolic-link entries are skipped in suggestions. Recent files can appear even without an opened folder; paths outside the current folder display in full. Typing searches matching recent files first, then the workspace, with duplicates removed and at most 200 total results.

Typing updates the existing result list without clearing it while a search is pending. A still-matching selection is retained when new results arrive. Enter and double-click wait for current-query results so an older suggestion cannot open the wrong file.

Matching query words are colored with the theme accent and bolded in both file and command results. Matching ignores case, supports multiple words and Unicode filenames, and keeps the original label text intact.

Press Mod+Shift+P, or start a query with `>`, to search application commands, including Reload Window. Use Up/Down to select, Enter or double-click to activate, and Esc or a click outside the panel to dismiss. Tab keeps focus within the panel. Commands unavailable in the current state are omitted.

Reload Window rebuilds the scene and reopens the current folder and all open files with paths. Every dirty tab receives Save/Discard/Cancel protection, with autosave paused during the decision. Reload resets undo history, preview visibility, and sidebar presentation; theme and autosave preferences remain. Cancel retains the current scene and edits. The palette appears inside the existing window and follows both themes.

## Integrated terminal

Mod+Shift+Backtick toggles the bottom terminal panel; Mod+Backtick creates a terminal tab in the editor area. Select the document tab to return to the file. Tabs provide duplicate and close controls. New shells start in the opened workspace, falling back to the active file's parent or Sylph's working directory. Duplicate uses the same starting folder and starts an independent shell. See the [terminal guide](terminal.md) for clipboard keys, lifecycle, and platform details.

## Shortcuts

Use Ctrl on Linux/Windows and Cmd on macOS wherever `Mod` appears.

| Action | Shortcut |
| --- | --- |
| Search files | Mod+P |
| Command palette | Mod+Shift+P |
| Reload window | Mod+Alt+R |
| New unsaved file | Mod+N |
| Open file | Mod+O |
| Open folder | Mod+Shift+O |
| Open folder, alternate chord | Mod+K, then Mod+O within 1.5 seconds |
| Save | Mod+S |
| Save As | Mod+Shift+S |
| Toggle sidebar | Mod+B |
| Settings | Mod+, |
| Toggle Markdown preview | Mod+Shift+V |
| Toggle terminal panel | Mod+Shift+Backtick |
| New terminal in editor | Mod+Backtick |
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

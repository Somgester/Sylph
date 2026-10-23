# Editor core

[Documentation index](README.md) · [Architecture](architecture.md)

## Current stage

The PRD calls for a custom piece-table buffer, virtualized rendering, highlighting, and undo/redo.
The first review chunk added `PieceTable` and its tests. The second connects it to `EditorSession`:
the session owns the buffer, and all text changes use explicit range-edit methods. The observable
text property is read-only and publishes immutable strings for autosave, preview, and language-server
observers. RichTextFX still renders the editor and supplies undo/redo during this migration.

`EditorView` forwards native `multiPlainChanges` batches in their sequential coordinate system.
The session checks every range before changing the buffer and publishes one final snapshot per batch,
so completion edits and their associated imports do not expose partially applied text. Style-only
changes do not enter the buffer. Synchronization guards prevent model-driven view updates from
feeding the same changes back into the session.

Programmatic callers use `replaceText` or `applyEdits`, rather than writing the text property.
Session edits normalize CRLF/CR to LF to match the current view and file-loading policy; the
standalone buffer itself still preserves exact text. Edits require the JavaFX thread and an editable
session. They remain allowed during background autosaves, which use captured immutable snapshots.
Opening or clearing a document resets its buffer; Save As preserves its current editing state.

This adapter still materializes full string snapshots for existing observers, and programmatic
session changes still refresh the view through its existing text synchronization. It establishes
buffer ownership; it does not complete custom rendering or eliminate whole-document copies.

## Buffer contract

`PieceTable` is a Java-only `CharSequence`, with no JavaFX or external dependencies.
It keeps the original string unchanged, appends inserted text to a separate store, and
represents the current document as an ordered list of ranges into those two sources.

- `replace(start, end, text)` replaces a half-open range. Equal bounds insert text;
  an empty replacement deletes text.
- All offsets and lengths count UTF-16 code units, matching Java strings and current editor offsets.
  Callers are responsible for choosing character/grapheme boundaries for user actions.
- Text is preserved exactly, including original line endings and surrogate code units.
  File validation and newline policy remain the responsibility of the existing file service.
- `charAt` reads one code unit. `subSequence` returns an immutable string for the requested range;
  `toString` materializes an immutable snapshot of the whole document.
- The mutable buffer belongs to one thread. Workers must receive string snapshots, not a live buffer.
- Invalid ranges or null replacement text fail before changing document contents.

Adjacent pieces that reference contiguous ranges in the same source are merged. Repeated typing
at the end of an inserted range therefore does not create one permanent piece per keystroke.

This initial implementation scans a list: edits visit O(p) pieces and append the inserted text;
character lookup takes O(p), and range reads take O(p + k) for k returned code units.
It does not claim tree-based indexing or
constant-time editing. Deleted inserted text remains in the append-only store; compaction and
history retention limits need a separate design when undo/history is connected.

## Next review chunks

1. Add and connect custom undo/redo with defined edit grouping, retention limits, and document reset behavior.
2. Introduce custom virtualized rendering separately, preserving selection, keyboard/IME input,
   clipboard, accessibility, highlighting, and language-server offsets before replacing RichTextFX.

These remain follow-up changes.

## Verification

```bash
./test.sh --tests '*PieceTableTest'
./test.sh --tests '*EditorSessionTest' --tests '*EditorViewTest'
```

Tests compare seeded edits and reads against `StringBuilder`, cover edits across both text stores,
verify immutable snapshots and invalid-edit safety, preserve Unicode and line endings, and exercise
repeated typing on a large original document. They need no UI, model download, or network service.
Session and headless view tests cover range/batch publication, Unicode/newline synchronization,
invalid-edit safety, open/reset/Save As, immutable background saves, and completion undo/redo.

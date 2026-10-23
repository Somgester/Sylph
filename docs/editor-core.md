# Editor core

[Documentation index](README.md) · [Architecture](architecture.md)

## Current stage

The PRD calls for a custom piece-table buffer, virtualized rendering, highlighting, and undo/redo.
The first review chunk adds `PieceTable` and its tests. It does **not** replace the running
editor's RichTextFX model, change file saving, or add a second synchronized document model.
Existing editor behavior stays on the current implementation until a separate integration change.

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

1. Connect document editing to the buffer through explicit range changes, keeping open/save,
   autosave, completion edits, and dirty-state behavior covered by regression tests.
2. Add and connect custom undo/redo with defined edit grouping and document reset behavior.
3. Introduce custom virtualized rendering separately, preserving selection, keyboard/IME input,
   clipboard, accessibility, highlighting, and language-server offsets before replacing RichTextFX.

These are follow-up changes, not features already delivered by the buffer foundation.

## Verification

```bash
./test.sh --tests '*PieceTableTest'
```

Tests compare seeded edits and reads against `StringBuilder`, cover edits across both text stores,
verify immutable snapshots and invalid-edit safety, preserve Unicode and line endings, and exercise
repeated typing on a large original document. They need no UI, model download, or network service.

# Editor core

[Documentation index](README.md) · [Architecture](architecture.md)

## Current stage

The PRD calls for a custom piece-table buffer, virtualized rendering, highlighting, and undo/redo.
The first review chunk added `PieceTable` and its tests. The second connects it to `EditorSession`:
the session owns the buffer, and all text changes use explicit range-edit methods. The observable
text property is read-only and publishes immutable strings for autosave, preview, and language-server
observers. RichTextFX still renders the editor; Sylph now supplies its undo/redo history through
`EditorUndoManager`, an adapter for the existing editor commands.

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
constant-time editing. Deleted inserted text remains in the append-only store. Bounded history
does not reclaim that store; buffer compaction remains a separate follow-up.

## History contract

The third review chunk added the standalone `EditHistory` core. The fourth connects it through
`EditorUndoManager` to existing keyboard shortcuts, Edit/context menus, and completion boundaries.
The fifth adds original/final caret and selection snapshots to those history batches.
The view owns one history for its document; replay uses the same batched view-to-session edits as
normal input, keeping the piece table, dirty state, autosave, preview, and language features in sync.

`EditHistory` stores immutable batches of committed changes, including their removed and inserted text.
Each explicit batch is one undo step unless eligible adjacent typing/deletion changes are merged.
Undo reverses change order and swaps removed/inserted content;
redo replays the original order. Callers supply an atomic replay callback, which must either apply
the whole batch or throw without changing the document. Failed callbacks leave the history unchanged,
and recording or resetting history during replay is rejected.

Both stacks share caller-configured limits for batch count and retained UTF-16 content units
(removed plus inserted text, not exact heap bytes). New edits discard redo; no-op changes do not.
Oldest whole batches are evicted when necessary. An oversized edit clears history entirely to prevent
undoing older offsets across an unrecorded change. `clear()` drops both stacks for document resets.
The caller remains responsible for document ranges, thread confinement, and recording only edits
that actually committed. These limits do not compact the piece table's append-only store.

The editor retains at most 1,000 batches and 4,194,304 UTF-16 content units across both stacks.
Adjacent single-code-point insertions or deletions group while gaps stay at most 500 ms. Newlines,
multi-character inserts, replacements, compound edits, navigation keys, mouse presses, focus changes,
and explicit completion/mark boundaries prevent merging. Selected-text deletion starts a separate
step. Merging also requires the preceding final selection to match the next edit's initial selection.
Undo/redo starts a new typing group.
Replay validates every expected removal against a scratch snapshot before committing one batch.
Each batch now keeps its original and final selection as UTF-16 anchor/caret pairs, preserving
selection direction. Undo restores the original pair; redo restores the final pair, independently
of subsequent navigation. Merged typing/deletions keep the first initial pair and latest final pair.
This distinguishes Delete from Backspace and preserves reversed selections and completion placeholders.

`EditorUndoManager` captures selection before document updates. Native multi-change operations keep
their resulting selection when the update finishes. `EditorCodeArea` wraps normal styled/plain-text
replacements so their final caret is captured after RichTextFX returns from updating it.
`CompletionEdit` similarly wraps its text batch and explicit final selection. No deferred callback
can accidentally capture later navigation, and a no-op completion cannot rewrite an older edit's
selection. Snapshot metadata stays in the same bounded batch as its text and follows its lifecycle.

A successful open replaces the manager, dropping both stacks even for identical document text.
Save As leaves history intact. Closing the view detaches listeners and releases history. Native
`forgetHistory()` semantics retain redo; document resets deliberately replace the entire manager.
Position/mark tracking survives undo/redo and invalidates forgotten or abandoned positions.

## Next review chunks

1. Introduce custom virtualized rendering separately, preserving selection, keyboard/IME input,
   clipboard, accessibility, highlighting, and language-server offsets before replacing RichTextFX.

These remain follow-up changes.

## Verification

```bash
./test.sh --tests '*PieceTableTest'
./test.sh --tests '*EditHistoryTest'
./test.sh --tests '*EditorUndoManagerTest'
./test.sh --tests '*EditorSessionTest' --tests '*EditorViewTest'
```

Tests compare seeded edits and reads against `StringBuilder`, cover edits across both text stores,
verify immutable snapshots and invalid-edit safety, preserve Unicode and line endings, and exercise
repeated typing on a large original document. They need no UI, model download, or network service.
Session and headless view tests cover range/batch publication, Unicode/newline synchronization,
invalid-edit safety, open/reset/Save As, immutable background saves, and completion undo/redo.
History tests cover compound replay order, immutable batches, Unicode, failed replay, branching,
retention limits, reset behavior, and 500 seeded batches replayed against independent snapshots.
Adapter tests use an injected clock for typing boundaries and cover native shortcuts/context menus,
compound replay, caret updates, style-only edits, edit locks, disposal, and history marks.
Selection tests cover both directions, grouped typing and deletion, emoji offsets, distinct
Delete/Backspace carets, native single-change builders, no-op completions, and compound placeholders.

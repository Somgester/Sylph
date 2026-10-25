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
maintaining the line index described below can additionally shift O(L) line starts.
Character lookup takes O(p), and range reads take O(p + k) for k returned code units.
It does not claim tree-based indexing or
constant-time editing. Deleted inserted text remains in the append-only store. Bounded history
does not reclaim that store; buffer compaction remains a separate follow-up.

## Line lookup for rendering

The sixth review chunk adds `LineIndex` to `PieceTable`. The index updates with each range edit;
opening or clearing a session creates a fresh buffer/index. It stores line starts in a primitive
integer array, without keeping another copy of the document text.

- Line numbers and offsets are zero-based; offsets count UTF-16 code units.
- `lineCount()` includes the trailing empty line after LF. An empty document has one empty line.
- `lineStart(line)` is inclusive; `lineEnd(line)` excludes LF or equals document length on the last line.
- `lineText(line)` reads just that immutable slice from the pieces, without materializing the whole document.
- `lineAt(offset)` accepts caret offsets through document length. An offset on LF belongs to its
  preceding line; the offset immediately after LF belongs to the next line. Columns can be obtained
  by subtracting that line's start offset.
- Only LF creates a line boundary. Session text already normalizes newlines; standalone buffers
  preserve CR and other characters as ordinary line content, including CR in a literal CRLF pair.
- Invalid lines, offsets, ranges, and null replacements are rejected without changing the index.

Line count/start/end take O(1); offset-to-line lookup uses O(log L) binary search for L lines.
Index updates scan inserted text and may copy/shift O(L) line starts. Equal-length edits without
changed line breaks and edits confined to the final line without changed breaks avoid that copy.
Line reads still have the buffer's O(p + k) cost. This does not introduce a tree index or eliminate
the existing session snapshot bridge.

The index is connected to live buffer edits, but the displayed editor still uses RichTextFX.
The read-only Canvas prototype below now uses this line-read contract.

## Read-only Canvas prototype

The seventh review chunk added `LineViewport`, `TextLines`, and `EditorCanvas`. The eighth exposes
an optional live preview: right-click the source editor and choose **Show Canvas Preview**. It appears
beside the existing editor, labelled read-only; use its **Close** button or **Hide Canvas Preview**
to remove it. Each document owns its preview. RichTextFX continues handling editing and syntax styles.

`LineViewport` calculates a half-open rendered row range from fixed line height, viewport height,
scroll offset, and overscan. It includes partially visible rows, clamps scrolling at either end,
and handles zero-sized/empty views without requesting rows. Geometry must be finite and valid.

`TextLines` exposes only line count and individual immutable line reads. `PieceTable` and `EditorSession` implement it;
the Canvas never requests a full document string. Document contents must remain stable during each
JavaFX-thread render pass. Owners explicitly call `refresh()` after edits or `setDocument()` on
replacement; the latter resets scroll. `EditorCanvasPreview` handles these calls for a live session:
text changes refresh rows, while a dedicated document revision resets scroll on successful open/clear,
including identical text. Failed opens, saves, and Save As preserve scroll; edits preserve offsets
subject to the recalculated content bounds. Session line reads require the JavaFX thread and an open
session. Closing the
preview removes text/revision/theme listeners and replaces its line source with an empty buffer so
pending layouts cannot read a disposed session.

`EditorCanvas` draws line numbers and plain text for visible rows plus one overscan row on either
side, clipping to the viewport. It supports fractional vertical scroll, wheel input, manually supplied
palette colors, and four-space tab stops measured with the same font as the text. Tabs remain intact
in the document. The ninth chunk adds horizontal scrolling: use **Shift + mouse wheel** or a horizontal
trackpad gesture. Diagonal trackpad gestures can move both axes. Ctrl/Cmd-modified events and events
that cannot move at a boundary remain available to parent controls. Text is clipped behind the fixed
line-number gutter; tab stops remain anchored to document columns as the viewport moves.

Horizontal bounds are learned from visible/overscan rows rather than scanning the entire document.
Visiting a wider line grows the available extent. That extent survives vertical scrolling onto shorter
rows and theme changes; edits invalidate it, remeasure the current rows, and clamp as needed. Resizing
clamps offsets against the known width; replacing/opening/clearing a document resets both axes. This
lazy extent is not a globally precomputed longest-line width and can shrink after an edit until wider
rows are visited again. Rendering requests each row only once per pass; width measurement and drawing
reuse that immutable line slice.

The prototype has a fixed monospaced font/line height, with no wrapping or horizontal scrollbar yet.
It lays out each requested line in full, so a very long visible line still has proportional cost.
The live preview follows the document's light/dark theme setting. Syntax styles, Canvas caret/selection
painting, keyboard/IME editing, and accessible document semantics still need integration before this
can replace the current editor. Opening/closing the pane does not edit text, save files, or reset
undo/selection. The click-to-source bridge below deliberately moves source selection without editing.

## Click-to-source navigation

The tenth review chunk adds text hit testing. A primary single click in Canvas Preview focuses the
source editor and places its caret at the corresponding location; Shift-click extends its selection
from the existing source anchor. Gutter clicks move to that line's start, independently of horizontal
scroll. Blank space below the document resolves to the last line; positions beyond a line resolve
through the font layout to its nearest caret. Scroll offsets and pending layout updates are applied
before hit testing.

`CanvasTextMetrics` shares font measurement and four-space tab stops between painting and hit testing.
It uses JavaFX `Text.hitTest` for glyph placement and snaps any interior hit to an extended grapheme
boundary using the JDK character iterator and caret geometry. UTF-16 offsets therefore never split
surrogate pairs, combining sequences, or joined emoji. `EditorSession.offsetAt` validates the final
line/column against the live piece-table index before source navigation.

Navigation does not create an undo entry or change dirty state. It starts a new typing undo group,
even if the clicked caret equals the current one, and dismisses completion suggestions. Foreground
file operations block navigation; background autosaves do not. Modified Ctrl/Cmd/Alt clicks, secondary
clicks, drags, and multi-click gestures are not handled by this bridge. Closing the pane removes the
navigation callback along with its document subscriptions.

The Canvas still has no editing input engine or painted selection: typing after a preview click goes
to the focused source editor. Drag selection and direct Canvas keyboard/IME handling remain follow-ups.

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

1. Add Canvas caret/selection painting and drag-selection interaction.
2. Connect custom input and editing separately, preserving selection, keyboard/IME input, clipboard,
   accessibility, highlighting, and language-server offsets before replacing RichTextFX.

These remain follow-up changes.

## Verification

```bash
./test.sh --tests '*PieceTableTest'
./test.sh --tests '*LineIndexTest'
./test.sh --tests '*LineViewportTest' --tests '*EditorCanvasTest'
./test.sh --tests '*EditorCanvasPreviewTest'
./test.sh --tests '*CanvasTextMetricsTest'
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
Line tests compare every caret offset against independent string scans, cover LF-boundary insertions,
deletions, CRLF/Unicode, empty/trailing lines, invalid edits, and typing on a large document's final line.
Existing seeded piece-table edits now verify line slices and offsets after every edit as well.
Canvas tests use a synthetic million-line source to verify that only viewport rows are requested;
they also cover refresh/replacement, resize clamping, empty dimensions, wheel propagation, tab spacing,
and rendered pixels in two palettes. Geometry tests cover fractional/exact boundaries, overscan,
large counts, and invalid dimensions. Tests stay headless and save preview images under
`app/build/editor-canvas-preview/` for visual inspection.
Live preview tests cover menu/header controls, rendered edits and undo/redo, theme/header colors,
Save As and failed-open scroll preservation, same-text open/clear resets, disposal, and safe pending
layouts after document closure. Live split-pane snapshots are saved there as `live-dark.png` and
`live-light.png` without opening desktop windows.
Horizontal checks cover Shift-wheel/trackpad input, diagonal movement, event propagation at limits,
fixed gutter pixels, tab alignment after scrolling, learned width across vertical scrolling/themes,
resize/shrink/reset clamping, and both axes across Save As/failed/successful opens. The scrolled
render snapshot is saved as `horizontal.png` beside the other Canvas previews.
Hit-test checks cover glyph midpoint placement, tab edges, both scroll axes, trailing/empty lines,
Unicode grapheme boundaries, and mixed-direction glyphs. Navigation checks cover source caret/anchor
mapping, Shift-click, native focus transfer, undo group boundaries, foreground-save blocking, and
closed-pane callbacks. `live-navigation.png` shows source selection created from preview clicks.

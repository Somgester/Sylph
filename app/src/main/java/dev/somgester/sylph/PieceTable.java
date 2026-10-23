package dev.somgester.sylph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A mutable text buffer with UTF-16 offsets, confined to its owning thread.
 * Original text is immutable and inserted text is append-only; edits rearrange
 * references to those sources. String snapshots can be passed to workers.
 */
final class PieceTable implements CharSequence, TextLines {

    private enum Source { ORIGINAL, ADDED }

    private record Piece(Source source, int offset, int length) { }

    private final String original;

    private final StringBuilder additions = new StringBuilder();

    private final LineIndex lines;

    private List<Piece> pieces;

    private int length;

    PieceTable() {
        this("");
    }

    PieceTable(String text) {
        original = Objects.requireNonNull(text, "text");
        lines = new LineIndex(text);
        length = text.length();
        pieces = length == 0 ? List.of() : List.of(new Piece(Source.ORIGINAL, 0, length));
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public int lineCount() {
        return lines.lineCount();
    }

    int lineStart(int line) {
        return lines.lineStart(line);
    }

    int lineEnd(int line) {
        return lines.lineEnd(line);
    }

    int lineAt(int offset) {
        return lines.lineAt(offset);
    }

    @Override
    public String lineText(int line) {
        return subSequence(lineStart(line), lineEnd(line));
    }

    @Override
    public char charAt(int index) {
        Objects.checkIndex(index, length);
        int position = 0;
        for (Piece piece : pieces) {
            if (index < position + piece.length()) {
                return source(piece).charAt(piece.offset() + index - position);
            }
            position += piece.length();
        }
        throw new AssertionError("Piece lengths do not match the buffer length");
    }

    @Override
    public String subSequence(int start, int end) {
        Objects.checkFromToIndex(start, end, length);
        StringBuilder result = new StringBuilder(end - start);
        int position = 0;
        for (Piece piece : pieces) {
            if (position >= end) {
                break;
            }
            int pieceEnd = position + piece.length();
            if (pieceEnd > start) {
                int from = piece.offset() + Math.max(0, start - position);
                int to = piece.offset() + Math.min(piece.length(), end - position);
                result.append(source(piece), from, to);
            }
            position = pieceEnd;
        }
        return result.toString();
    }

    /** Replaces the half-open range [start, end), without normalizing its text. */
    void replace(int start, int end, String text) {
        Objects.checkFromToIndex(start, end, length);
        Objects.requireNonNull(text, "text");
        if (start == end && text.isEmpty()) {
            return;
        }
        int updatedLength = Math.addExact(length - (end - start), text.length());
        // Deleted inserted text stays in the append-only store for future history support.
        Math.addExact(additions.length(), text.length());
        Piece insertion = new Piece(Source.ADDED, additions.length(), text.length());
        List<Piece> updated = new ArrayList<>();
        int position = 0;
        boolean inserted = false;
        for (Piece piece : pieces) {
            int pieceEnd = position + piece.length();
            if (pieceEnd <= start) {
                append(updated, piece);
            } else if (position >= end) {
                if (!inserted) {
                    append(updated, insertion);
                    inserted = true;
                }
                append(updated, piece);
            } else {
                if (position < start) {
                    append(updated, new Piece(piece.source(), piece.offset(), start - position));
                }
                if (!inserted) {
                    append(updated, insertion);
                    inserted = true;
                }
                if (pieceEnd > end) {
                    append(updated, new Piece(piece.source(), piece.offset() + end - position, pieceEnd - end));
                }
            }
            position = pieceEnd;
        }
        if (!inserted) {
            append(updated, insertion);
        }
        lines.replace(start, end, text);
        additions.append(text);
        pieces = updated;
        length = updatedLength;
    }

    @Override
    public String toString() {
        return subSequence(0, length);
    }

    private CharSequence source(Piece piece) {
        return piece.source() == Source.ORIGINAL ? original : additions;
    }

    private static void append(List<Piece> target, Piece piece) {
        if (piece.length() == 0) {
            return;
        }
        if (!target.isEmpty()) {
            Piece previous = target.getLast();
            if (previous.source() == piece.source() && previous.offset() + previous.length() == piece.offset()) {
                target.set(target.size() - 1,
                        new Piece(previous.source(), previous.offset(), previous.length() + piece.length()));
                return;
            }
        }
        target.add(piece);
    }
}

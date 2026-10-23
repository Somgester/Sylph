package dev.somgester.sylph;

import java.util.Objects;

/** Thread-confined, zero-based logical lines with UTF-16 offsets; only LF starts a new line. */
final class LineIndex {

    private int[] starts;

    private int length;

    LineIndex(String text) {
        Objects.requireNonNull(text, "text");
        starts = new int[Math.addExact(countBreaks(text), 1)];
        int next = 1;
        for (int offset = 0; offset < text.length(); offset++) {
            if (text.charAt(offset) == '\n') {
                starts[next++] = offset + 1;
            }
        }
        length = text.length();
    }

    int lineCount() {
        return starts.length;
    }

    int lineStart(int line) {
        return starts[Objects.checkIndex(line, starts.length)];
    }

    int lineEnd(int line) {
        Objects.checkIndex(line, starts.length);
        return line + 1 < starts.length ? starts[line + 1] - 1 : length;
    }

    int lineAt(int offset) {
        if (offset < 0 || offset > length) {
            throw new IndexOutOfBoundsException("Offset outside document: " + offset);
        }
        return after(offset) - 1;
    }

    void replace(int start, int end, String text) {
        Objects.checkFromToIndex(start, end, length);
        Objects.requireNonNull(text, "text");
        int updatedLength = Math.addExact(length - (end - start), text.length());
        int first = after(start);
        int suffix = after(end);
        int added = countBreaks(text);
        int removed = suffix - first;
        int delta = text.length() - (end - start);
        // No line starts move for equal-length edits without LF, or typing on the final line.
        if (added == 0 && removed == 0 && (delta == 0 || suffix == starts.length)) {
            length = updatedLength;
            return;
        }
        int[] updated = new int[Math.addExact(starts.length - removed, added)];
        System.arraycopy(starts, 0, updated, 0, first);
        int next = first;
        for (int offset = 0; offset < text.length(); offset++) {
            if (text.charAt(offset) == '\n') {
                updated[next++] = start + offset + 1;
            }
        }
        for (int index = suffix; index < starts.length; index++) {
            updated[next++] = starts[index] + delta;
        }
        starts = updated;
        length = updatedLength;
    }

    // First line start strictly greater than offset (including the end-of-document caret).
    private int after(int offset) {
        int low = 0;
        int high = starts.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (starts[middle] <= offset) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int countBreaks(String text) {
        int count = 0;
        for (int offset = 0; offset < text.length(); offset++) {
            if (text.charAt(offset) == '\n') {
                count++;
            }
        }
        return count;
    }
}

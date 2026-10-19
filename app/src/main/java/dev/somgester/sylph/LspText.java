package dev.somgester.sylph;

import java.util.ArrayList;
import java.util.Arrays;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Strict UTF-16 coordinates for LF-normalized editor snapshots.
 * Invalid server ranges must never silently edit another location.
 */
final class LspText {

    private final String text;

    private final int[] lines;

    LspText(String text) {
        this.text = text;
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                starts.add(index + 1);
            }
        }
        lines = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    int offset(Position position) {
        if (position == null || position.getLine() < 0 || position.getLine() >= lines.length
                || position.getCharacter() < 0) {
            return -1;
        }
        int line = position.getLine();
        int end = line + 1 < lines.length ? lines[line + 1] - 1 : text.length();
        long offset = (long) lines[line] + position.getCharacter();
        return offset <= end && boundary((int) offset) ? (int) offset : -1;
    }

    Position position(int offset) {
        if (!boundary(offset)) {
            throw new IllegalArgumentException("Invalid UTF-16 offset");
        }
        int line = Arrays.binarySearch(lines, offset);
        if (line < 0) {
            line = -line - 2;
        }
        return new Position(line, offset - lines[line]);
    }

    boolean valid(Range range) {
        if (range == null) {
            return false;
        }
        int start = offset(range.getStart());
        int end = offset(range.getEnd());
        return start >= 0 && end >= start;
    }

    boolean boundary(int offset) {
        return offset >= 0 && offset <= text.length() && !(offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)));
    }
}

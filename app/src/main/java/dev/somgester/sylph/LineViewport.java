package dev.somgester.sylph;

/** Fixed-height logical rows. The rendered range is half-open and may include overscan rows. */
record LineViewport(int firstLine, int endLine, double scrollY, double maxScrollY, double lineHeight) {

    static LineViewport layout(int lineCount, double lineHeight, double height, double requestedY, int overscan) {
        if (lineCount < 0 || overscan < 0 || !Double.isFinite(lineHeight) || lineHeight <= 0
                || !Double.isFinite(height) || height < 0 || !Double.isFinite(requestedY)) {
            throw new IllegalArgumentException("Invalid line viewport dimensions");
        }
        double contentHeight = lineCount * lineHeight;
        if (!Double.isFinite(contentHeight)) {
            throw new IllegalArgumentException("Document height exceeds viewport range");
        }
        double maxScroll = Math.max(0, contentHeight - height);
        double scroll = Math.clamp(requestedY, 0, maxScroll);
        if (lineCount == 0 || height == 0) {
            return new LineViewport(0, 0, scroll, maxScroll, lineHeight);
        }
        int first = (int) Math.floor(scroll / lineHeight);
        int end = (int) Math.ceil((scroll + height) / lineHeight);
        return new LineViewport(Math.max(0, first - overscan),
                (int) Math.min(lineCount, (long) end + overscan), scroll, maxScroll, lineHeight);
    }

    double lineY(int line) {
        if (line < firstLine || line >= endLine) {
            throw new IndexOutOfBoundsException("Line outside rendered range: " + line);
        }
        return line * lineHeight - scrollY;
    }
}

package dev.somgester.sylph;

/** Nonnegative line count and LF-free line slices, stable on the owner's thread during a render pass. */
interface TextLines {

    int lineCount();

    String lineText(int line);
}

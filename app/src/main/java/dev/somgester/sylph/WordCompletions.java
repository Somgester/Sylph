package dev.somgester.sylph;

import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

// Document words only. Language keywords and language-server results belong to later providers.
final class WordCompletions {

    record Result(int start, int end, String prefix, List<String> words) {
        Result {
            words = List.copyOf(words);
        }
    }

    private static final int MAX_WORD_LENGTH = 128;

    private static final int MAX_RESULTS = 50;

    private WordCompletions() { }

    static Result suggest(String text, int caret, int minimumPrefix) {
        if (caret < 0 || caret > text.length()) {
            throw new IllegalArgumentException("Caret outside document");
        }
        if (caret > 0 && caret < text.length() && Character.isHighSurrogate(text.charAt(caret - 1))
                && Character.isLowSurrogate(text.charAt(caret))) {
            return new Result(caret, caret, "", List.of());
        }
        int start = caret;
        while (start > 0 && part(text.codePointBefore(start))) {
            checkCancelled();
            start -= Character.charCount(text.codePointBefore(start));
        }
        int end = caret;
        while (end < text.length() && part(text.codePointAt(end))) {
            checkCancelled();
            end += Character.charCount(text.codePointAt(end));
        }
        if (caret - start < minimumPrefix || end - start > MAX_WORD_LENGTH
                || start < end && !first(text.codePointAt(start))) {
            return new Result(start, end, "", List.of());
        }
        String prefix = text.substring(start, caret);
        String current = text.substring(start, end);
        var words = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (int index = 0; index < text.length();) {
            checkCancelled();
            int wordStart = index;
            int cp = text.codePointAt(index);
            if (!part(cp)) {
                index += Character.charCount(cp);
                continue;
            }
            do {
                index += Character.charCount(text.codePointAt(index));
                checkCancelled();
            } while (index < text.length() && part(text.codePointAt(index)));
            if (first(cp) && index - wordStart <= MAX_WORD_LENGTH) {
                String word = text.substring(wordStart, index);
                if (!word.equals(current) && word.length() > prefix.length()
                        && word.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    words.add(word);
                    if (words.size() > MAX_RESULTS) {
                        words.pollLast();
                    }
                }
            }
        }
        return new Result(start, end, prefix, List.copyOf(words));
    }

    private static boolean first(int cp) {
        return cp == '_' || cp == '$' || Character.isUnicodeIdentifierStart(cp);
    }

    private static boolean part(int cp) {
        return !Character.isISOControl(cp) && (cp == '$' || Character.isUnicodeIdentifierPart(cp));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Completion request superseded");
        }
    }
}

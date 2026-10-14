package dev.somgester.sylph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

// Match offsets refer to the original UTF-16 label, including non-ASCII filenames.
final class PaletteMatch {

    record Range(int start, int end) { }

    private PaletteMatch() { }

    static List<Pattern> words(String query) {
        return java.util.Arrays.stream(query.strip().replace('\\', '/').split("\\s+"))
                .filter(word -> !word.isEmpty())
                .map(word -> Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
    }

    static boolean matches(String label, List<Pattern> words) {
        String normalized = label.replace('\\', '/');
        return words.stream().allMatch(word -> word.matcher(normalized).find());
    }

    static Optional<List<Range>> ranges(String label, String query) {
        String normalized = label.replace('\\', '/');
        List<Range> matches = new ArrayList<>();
        for (Pattern word : words(query)) {
            var matcher = word.matcher(normalized);
            boolean found = false;
            int from = 0;
            while (matcher.find(from)) {
                found = true;
                matches.add(new Range(matcher.start(), matcher.end()));
                from = matcher.start() + 1;
            }
            if (!found) {
                return Optional.empty();
            }
        }
        matches.sort(Comparator.comparingInt(Range::start));
        List<Range> merged = new ArrayList<>();
        for (Range range : matches) {
            if (!merged.isEmpty() && range.start() <= merged.getLast().end()) {
                Range previous = merged.removeLast();
                merged.add(new Range(previous.start(), Math.max(previous.end(), range.end())));
            } else {
                merged.add(range);
            }
        }
        return Optional.of(List.copyOf(merged));
    }
}

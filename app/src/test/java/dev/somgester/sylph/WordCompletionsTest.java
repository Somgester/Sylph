package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WordCompletionsTest {

    @Test
    void matchesDeduplicatesAndSortsDocumentWordsWithoutInventingKeywords() {
        String text = "alphabet alpha alpha beta alphanumeric\nal";
        var result = WordCompletions.suggest(text, text.length(), 2);
        assertEquals(List.of("alpha", "alphabet", "alphanumeric"), result.words());
        assertEquals(text.length() - 2, result.start());
        assertEquals(text.length(), result.end());
        assertEquals("al", result.prefix());
        assertTrue(WordCompletions.suggest("cl", 2, 0).words().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.words().clear());
    }

    @Test
    void replacesWholeIdentifierAtMiddleOfWordWithoutDuplicatingSuffix() {
        String text = "camelCase camera camcorder\ncamel";
        int caret = text.lastIndexOf("camel") + 3;
        var result = WordCompletions.suggest(text, caret, 0);
        assertEquals("cam", result.prefix());
        assertEquals(text.lastIndexOf("camel"), result.start());
        assertEquals(text.length(), result.end());
        assertTrue(result.words().contains("camelCase"));
        assertFalse(result.words().contains("camel"));
    }

    @Test
    void unicodeIdentifiersDollarAndUnderscoreUseUtf16ReplacementOffsets() {
        String text = "café cafétéria _value $value 𐐀name\n𐐀n";
        var result = WordCompletions.suggest(text, text.length(), 0);
        assertEquals(List.of("𐐀name"), result.words());
        assertEquals(text.length() - 3, result.start());
        assertEquals(List.of("_value"), WordCompletions.suggest(text + "\n_v", text.length() + 3, 0).words());
        assertEquals(List.of("$value"), WordCompletions.suggest(text + "\n$v", text.length() + 3, 0).words());
        assertTrue(WordCompletions.suggest(text, text.length() - 2, 0).words().isEmpty());
    }

    @Test
    void manualRequestAllowsEmptyPrefixButAutomaticRequiresTwoCharacters() {
        assertTrue(WordCompletions.suggest("alpha\na", 7, 2).words().isEmpty());
        assertEquals(List.of("alpha"), WordCompletions.suggest("alpha\na", 7, 0).words());
        assertEquals(List.of("alpha", "beta"), WordCompletions.suggest("alpha beta\n", 11, 0).words());
        assertTrue(WordCompletions.suggest("123bad\n12", 9, 0).words().isEmpty());
    }

    @Test
    void limitsCandidatesAndIgnoresUnreasonablyLongIdentifiers() {
        String words = IntStream.range(0, 1000).mapToObj(index -> "word" + index).collect(Collectors.joining(" "));
        String text = words + " word" + "x".repeat(200) + "\nwo";
        var result = WordCompletions.suggest(text, text.length(), 2);
        assertEquals(50, result.words().size());
        assertEquals("word0", result.words().getFirst());
        assertTrue(result.words().stream().allMatch(word -> word.length() <= 128));
    }

    @Test
    void interruptedRequestsStopAndKeepInterruptStatus() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> WordCompletions.suggest("alpha al", 8, 0));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}

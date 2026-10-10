package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SyntaxHighlighterTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void oldWorkerResultsCannotPaintNewDocumentEvenWhenCancellationIsIgnored() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ReadOnlyObjectWrapper<EditorLanguage> language = new ReadOnlyObjectWrapper<>(EditorLanguage.JAVA);
        CodeArea area = FxTestSupport.onFxThread(() -> new CodeArea("old"));
        SyntaxHighlighter highlighter = FxTestSupport.onFxThread(() -> new SyntaxHighlighter(area, language,
                (text, requestedLanguage) -> {
                    assertFalse(Platform.isFxApplicationThread());
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        firstStarted.countDown();
                        awaitIgnoringInterrupts(releaseFirst);
                        return result(text, "string.quoted.double.java");
                    }
                    secondStarted.countDown();
                    awaitIgnoringInterrupts(releaseSecond);
                    assertEquals("new", text);
                    assertEquals(EditorLanguage.JSON, requestedLanguage);
                    return result(text, "constant.numeric.json");
                }));
        try {
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            FxTestSupport.onFxThread(() -> {
                area.replaceText("new");
                language.set(EditorLanguage.JSON);
                return null;
            });
            releaseFirst.countDown();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            FxTestSupport.onFxThread(() -> {
                assertEquals(List.of(), area.getStyleOfChar(0));
                return null;
            });
            releaseSecond.countDown();
            FxTestSupport.await(() -> area.getStyleOfChar(0).contains("syntax-number"));
            assertEquals(2, calls.get());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            FxTestSupport.onFxThread(() -> {
                highlighter.close();
                area.dispose();
                return null;
            });
        }
    }

    @Test
    void firstUseTimeoutRetriesOnceOnTheWorker() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ReadOnlyObjectWrapper<EditorLanguage> language = new ReadOnlyObjectWrapper<>(EditorLanguage.JSON);
        CodeArea area = FxTestSupport.onFxThread(() -> new CodeArea("42"));
        SyntaxHighlighter highlighter = FxTestSupport.onFxThread(() -> new SyntaxHighlighter(area, language,
                (text, requestedLanguage) -> {
                    assertFalse(Platform.isFxApplicationThread());
                    if (calls.incrementAndGet() == 1) {
                        var partial = result(text, "string.quoted.double.json");
                        return new SyntaxTokenizer.Result(partial.length(), partial.lines(), false);
                    }
                    return result(text, "constant.numeric.json");
                }));
        try {
            FxTestSupport.await(() -> area.getStyleOfChar(0).contains("syntax-number"));
            assertEquals(2, calls.get());
        } finally {
            FxTestSupport.onFxThread(() -> {
                highlighter.close();
                area.dispose();
                return null;
            });
        }
    }

    @Test
    void switchingToPlainTextClearsColorsAndClosingDetachesListeners() throws Exception {
        ReadOnlyObjectWrapper<EditorLanguage> language = new ReadOnlyObjectWrapper<>(EditorLanguage.JAVA);
        CodeArea area = FxTestSupport.onFxThread(() -> new CodeArea("class Example {}"));
        SyntaxHighlighter highlighter = FxTestSupport.onFxThread(() -> new SyntaxHighlighter(area, language));
        try {
            FxTestSupport.await(() -> area.getStyleOfChar(6).contains("syntax-type"));
            FxTestSupport.onFxThread(() -> {
                language.set(EditorLanguage.PLAIN_TEXT);
                assertEquals(List.of(), area.getStyleOfChar(6));
                highlighter.close();
                highlighter.close();
                area.replaceText("closed");
                area.setStyle(0, area.getLength(), List.of("kept"));
                language.set(EditorLanguage.JSON);
                assertEquals(List.of("kept"), area.getStyleOfChar(0));
                return null;
            });
        } finally {
            FxTestSupport.onFxThread(() -> {
                highlighter.close();
                area.dispose();
                return null;
            });
        }
    }

    private static SyntaxTokenizer.Result result(String text, String scope) {
        var token = new SyntaxTokenizer.Token(0, text.length(), List.of(scope));
        var line = new SyntaxTokenizer.Line(0, text.length(), List.of(token));
        return new SyntaxTokenizer.Result(text.length(), List.of(line), true);
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                if (latch.await(10, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException ignored) {
                // Simulate a tokenizer that does not cooperate with cancellation.
            }
        }
        throw new AssertionError("Worker release timed out");
    }
}

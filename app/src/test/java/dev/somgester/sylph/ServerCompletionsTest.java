package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

class ServerCompletionsTest {

    @Test
    void replacesWholeIdentifierAndAccountsForImportsBeforeCaret() {
        String text = "head\nfoSuffix";
        var item = new CompletionItem("foo");
        item.setTextEdit(Either.forRight(new InsertReplaceEdit("foo", range(1, 0, 2), range(1, 0, 8))));
        item.setAdditionalTextEdits(List.of(new TextEdit(range(0, 0, 0), "import foo;\r\n")));
        var plan = convert(text, 7, item).getFirst();
        String result = apply(text, plan);
        assertEquals("import foo;\nhead\nfoo", result);
        assertEquals(result.length(), plan.selectionStart());
        assertEquals(plan.selectionStart(), plan.selectionEnd());
    }

    @Test
    void filtersPlainItemsAndHonorsSortTextAndFilterText() {
        var first = new CompletionItem("fooSecond");
        first.setSortText("2");
        var second = new CompletionItem("display label");
        second.setSortText("1");
        second.setFilterText("foo");
        second.setInsertText("fooFirst");
        var ignored = new CompletionItem("unrelated");
        var local = LanguageCompletions.suggest("fo", 2, 0, EditorLanguage.PLAIN_TEXT);
        var result = ServerCompletions.convert("fo", 2, local, List.of(first, ignored, second));
        assertEquals(List.of("display label", "fooSecond"), result.stream().map(Object::toString).toList());
        assertEquals("fooFirst", apply("fo", result.getFirst()));
    }

    @Test
    void rejectsOverlappingEditsCommandsSnippetsAndUnpreservableContent() {
        var invalid = new ArrayList<CompletionItem>();
        var overlap = new CompletionItem("foo");
        overlap.setAdditionalTextEdits(List.of(new TextEdit(range(0, 0, 1), "import")));
        invalid.add(overlap);
        var snippet = new CompletionItem("foo");
        snippet.setInsertTextFormat(InsertTextFormat.Snippet);
        snippet.setInsertText("foo(${1:value})");
        invalid.add(snippet);
        var command = new CompletionItem("foo");
        command.setCommand(new Command("Run", "server.command"));
        invalid.add(command);
        var control = new CompletionItem("foo");
        control.setInsertText("foo\u007f");
        invalid.add(control);
        var surrogate = new CompletionItem("foo");
        surrogate.setInsertText("foo\uD800");
        invalid.add(surrogate);
        var unknownPosition = new CompletionItem("foo");
        unknownPosition.setTextEdit(Either.forLeft(new TextEdit(range(20, 0, 2), "foo")));
        invalid.add(unknownPosition);
        var wrongCursor = new CompletionItem("foo");
        wrongCursor.setTextEdit(Either.forLeft(new TextEdit(range(0, 0, 1), "foo")));
        invalid.add(wrongCursor);
        for (var item : invalid) {
            assertTrue(convert("fo", 2, item).isEmpty(), item.getLabel());
        }
    }

    @Test
    void rejectsBadInsertReplacePairsAndPartialUnicodeOffsets() {
        var item = new CompletionItem("foo");
        item.setTextEdit(Either.forRight(new InsertReplaceEdit("foo", range(0, 1, 2), range(0, 0, 2))));
        assertTrue(convert("fo", 2, item).isEmpty());
        item.setTextEdit(Either.forLeft(new TextEdit(range(0, 1, 4), "foo")));
        assertTrue(convert("🙂fo", 4, item).isEmpty());
    }

    private static List<CompletionEdit> convert(String text, int caret, CompletionItem item) {
        return ServerCompletions.convert(text, caret,
                LanguageCompletions.suggest(text, caret, 0, EditorLanguage.PLAIN_TEXT), List.of(item));
    }

    private static Range range(int line, int start, int end) {
        return new Range(new Position(line, start), new Position(line, end));
    }

    private static String apply(String text, CompletionEdit plan) {
        var result = new StringBuilder(text);
        for (var edit : plan.replacements().reversed()) {
            result.replace(edit.start(), edit.end(), edit.text());
        }
        return result.toString();
    }
}

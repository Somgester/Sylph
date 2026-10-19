package dev.somgester.sylph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.TextEdit;

final class ServerCompletions {

    private ServerCompletions() { }

    static List<CompletionEdit> convert(String text, int caret, LanguageCompletions.Result local,
            List<CompletionItem> items) {
        if (items == null) {
            return List.of();
        }
        var coordinates = new LspText(text);
        String prefix = text.substring(local.start(), caret);
        return items.stream().filter(item -> item != null && item.getLabel() != null && !item.getLabel().isBlank())
                .sorted(Comparator.comparing((CompletionItem item) -> item.getSortText() == null
                        ? item.getLabel() : item.getSortText()))
                .map(item -> convert(coordinates, caret, local, prefix, item)).filter(java.util.Objects::nonNull)
                .limit(50).toList();
    }

    private static CompletionEdit convert(LspText coordinates, int caret, LanguageCompletions.Result local,
            String prefix, CompletionItem item) {
        if (item.getInsertTextFormat() == InsertTextFormat.Snippet || item.getCommand() != null) {
            // Neither snippet expansion nor server commands are advertised by this client.
            return null;
        }
        int start = local.start();
        int end = local.end();
        String insertion = item.getInsertText() == null ? item.getLabel() : item.getInsertText();
        if (item.getTextEdit() != null) {
            var edit = item.getTextEdit();
            var range = edit.isLeft() ? edit.getLeft().getRange() : edit.getRight().getReplace();
            if (!coordinates.valid(range) || range.getStart().getLine() != range.getEnd().getLine()) {
                return null;
            }
            if (edit.isRight()) {
                var insert = edit.getRight().getInsert();
                if (!coordinates.valid(insert) || !insert.getStart().equals(range.getStart())
                        || coordinates.offset(insert.getEnd()) > coordinates.offset(range.getEnd())
                        || coordinates.offset(insert.getEnd()) < caret) {
                    return null;
                }
            }
            start = coordinates.offset(range.getStart());
            end = coordinates.offset(range.getEnd());
            if (start > caret || end < caret) {
                return null;
            }
            insertion = edit.isLeft() ? edit.getLeft().getNewText() : edit.getRight().getNewText();
        }
        String filter = item.getFilterText() == null ? item.getLabel() : item.getFilterText();
        if ((item.getFilterText() != null || item.getTextEdit() == null)
                && !filter.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        insertion = normalize(insertion);
        if (insertion == null) {
            return null;
        }
        var changes = new ArrayList<CompletionEdit.Replacement>();
        var main = new CompletionEdit.Replacement(start, end, insertion);
        changes.add(main);
        var additional = item.getAdditionalTextEdits() == null ? List.<TextEdit>of() : item.getAdditionalTextEdits();
        for (TextEdit extra : additional) {
            if (extra == null || !coordinates.valid(extra.getRange())) {
                return null;
            }
            String value = normalize(extra.getNewText());
            if (value == null) {
                return null;
            }
            changes.add(new CompletionEdit.Replacement(coordinates.offset(extra.getRange().getStart()),
                    coordinates.offset(extra.getRange().getEnd()), value));
        }
        changes.sort(Comparator.comparingInt(CompletionEdit.Replacement::start));
        int selection = start + insertion.length();
        for (int index = 0; index < changes.size(); index++) {
            var change = changes.get(index);
            if (index > 0) {
                var previous = changes.get(index - 1);
                if (previous.end() > change.start() || previous.start() == change.start()) {
                    return null;
                }
            }
            if (change != main && change.start() < start) {
                selection += change.text().length() - (change.end() - change.start());
            }
        }
        return new CompletionEdit(item.getLabel(), changes, selection, selection);
    }

    private static String normalize(String text) {
        if (text == null || text.chars().anyMatch(ch -> (ch < 32 || ch == 127)
                && ch != '\n' && ch != '\r' && ch != '\t')) {
            return null;
        }
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    return null;
                }
            } else if (Character.isLowSurrogate(ch)) {
                return null;
            }
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}

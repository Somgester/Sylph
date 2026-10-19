package dev.somgester.sylph;

import java.util.List;
import org.fxmisc.richtext.CodeArea;

/** All offsets refer to the original snapshot; the selection refers to the resulting document. */
record CompletionEdit(String label, List<Replacement> replacements, int selectionStart, int selectionEnd) {

    record Replacement(int start, int end, String text) { }

    CompletionEdit {
        replacements = List.copyOf(replacements);
    }

    static CompletionEdit local(LanguageCompletions.Result result, LanguageCompletions.Suggestion word) {
        return new CompletionEdit(word.label(), List.of(new Replacement(result.start(), result.end(), word.text())),
                result.start() + word.selectionStart(), result.start() + word.selectionEnd());
    }

    void apply(CodeArea area) {
        var change = area.createMultiChange(replacements.size());
        for (var replacement : replacements) {
            change.replaceText(replacement.start(), replacement.end(), replacement.text());
        }
        change.commit();
        area.selectRange(selectionStart, selectionEnd);
    }

    @Override
    public String toString() {
        return label;
    }
}

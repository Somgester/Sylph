package dev.somgester.sylph;

import java.util.Collection;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyledDocument;

/** Captures selection after RichTextFX finishes a normal replacement's caret update. */
final class EditorCodeArea extends CodeArea {

    @Override
    public void replace(int start, int end,
            StyledDocument<Collection<String>, String, Collection<String>> replacement) {
        if (getUndoManager() instanceof EditorUndoManager history) {
            history.captureSelection(() -> super.replace(start, end, replacement));
        } else {
            super.replace(start, end, replacement);
        }
    }
}

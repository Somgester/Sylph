package dev.somgester.sylph;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;

// Confined to one worker, including the grammar registry. Results can cross threads.
final class SyntaxTokenizer {

    record Token(int start, int end, List<String> scopes) {
        Token {
            scopes = List.copyOf(scopes);
        }
    }

    // Token offsets are relative to this line; start is the document's UTF-16 offset.
    // Length excludes the newline, which the renderer leaves unstyled.
    record Line(int start, int length, List<Token> tokens) {
        Line {
            tokens = List.copyOf(tokens);
        }
    }

    record Result(int length, List<Line> lines, boolean complete) {
        Result {
            lines = List.copyOf(lines);
        }
    }

    private static final Duration LINE_TIME_LIMIT = Duration.ofMillis(100);

    private final Function<EditorLanguage, Optional<IGrammar>> grammarForLanguage;

    SyntaxTokenizer() {
        this(new SyntaxGrammars()::forLanguage);
    }

    SyntaxTokenizer(Function<EditorLanguage, Optional<IGrammar>> grammarForLanguage) {
        this.grammarForLanguage = grammarForLanguage;
    }

    Result tokenize(String text, EditorLanguage language) {
        checkCancelled();
        var grammar = grammarForLanguage.apply(language);
        List<Line> lines = new ArrayList<>();
        IStateStack state = null;
        boolean complete = true;
        int start = 0;
        while (true) {
            checkCancelled();
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            String lineText = text.substring(start, end);
            List<Token> tokens;
            if (grammar.isPresent() && complete) {
                var lineResult = grammar.orElseThrow().tokenizeLine(lineText, state, LINE_TIME_LIMIT);
                checkCancelled();
                if (lineResult.isStoppedEarly()) {
                    // An incomplete state cannot safely seed the following lines.
                    complete = false;
                    tokens = unstyled(lineText.length());
                } else {
                    state = lineResult.getRuleStack();
                    tokens = copyTokens(lineResult.getTokens(), lineText.length());
                }
            } else {
                tokens = unstyled(lineText.length());
            }
            lines.add(new Line(start, lineText.length(), tokens));
            if (newline < 0) {
                return new Result(text.length(), lines, complete);
            }
            start = newline + 1;
        }
    }

    private static List<Token> copyTokens(IToken[] source, int length) {
        List<Token> tokens = new ArrayList<>();
        int cursor = 0;
        for (IToken token : source) {
            // TM4E may include its synthetic newline; never paint beyond the source line.
            int start = Math.clamp(token.getStartIndex(), cursor, length);
            int end = Math.clamp(token.getEndIndex(), start, length);
            if (start > cursor) {
                tokens.add(new Token(cursor, start, List.of()));
            }
            if (end > start) {
                tokens.add(new Token(start, end, token.getScopes()));
            }
            cursor = end;
        }
        if (cursor < length) {
            tokens.add(new Token(cursor, length, List.of()));
        }
        return tokens;
    }

    private static List<Token> unstyled(int length) {
        return length == 0 ? List.of() : List.of(new Token(0, length, List.of()));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Syntax tokenization interrupted");
        }
    }
}

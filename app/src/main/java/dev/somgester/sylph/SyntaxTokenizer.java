package dev.somgester.sylph;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    private record CachedLine(String text, IStateStack before, IStateStack after, List<Token> tokens) {
        CachedLine {
            tokens = List.copyOf(tokens);
        }
    }

    private EditorLanguage cachedLanguage;

    private List<CachedLine> cache = List.of();

    SyntaxTokenizer() {
        this(new SyntaxGrammars()::forLanguage);
    }

    SyntaxTokenizer(Function<EditorLanguage, Optional<IGrammar>> grammarForLanguage) {
        this.grammarForLanguage = grammarForLanguage;
    }

    Result tokenize(String text, EditorLanguage language) {
        checkCancelled();
        var grammar = grammarForLanguage.apply(language);
        String[] source = text.split("\n", -1);
        List<CachedLine> previous = language == cachedLanguage ? cache : List.of();
        int prefix = 0;
        while (prefix < source.length && prefix < previous.size()
                && source[prefix].equals(previous.get(prefix).text())) {
            checkCancelled();
            prefix++;
        }
        int suffix = 0;
        while (suffix < source.length - prefix && suffix < previous.size() - prefix
                && source[source.length - 1 - suffix].equals(previous.get(previous.size() - 1 - suffix).text())) {
            checkCancelled();
            suffix++;
        }
        List<Line> lines = new ArrayList<>();
        List<CachedLine> nextCache = new ArrayList<>();
        IStateStack state = null;
        boolean complete = true;
        int start = 0;
        for (int index = 0; index < source.length; index++) {
            checkCancelled();
            String lineText = source[index];
            IStateStack before = state;
            List<Token> tokens;
            CachedLine reusable = null;
            if (complete && index < prefix) {
                reusable = previous.get(index);
            } else if (complete && index >= source.length - suffix) {
                var candidate = previous.get(index + previous.size() - source.length);
                if (Objects.equals(state, candidate.before())) {
                    reusable = candidate;
                }
            }
            if (reusable != null) {
                state = reusable.after();
                tokens = reusable.tokens();
            } else if (grammar.isPresent() && complete) {
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
            var cached = reusable != null ? reusable : new CachedLine(lineText, before, state, tokens);
            nextCache.add(cached);
            lines.add(new Line(start, lineText.length(), cached.tokens()));
            start += lineText.length() + 1;
        }
        checkCancelled();
        // Never publish partial or cancelled state as a future reuse point.
        if (complete) {
            cachedLanguage = language;
            cache = List.copyOf(nextCache);
        }
        return new Result(text.length(), lines, complete);
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

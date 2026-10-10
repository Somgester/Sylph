package dev.somgester.sylph;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IRegistryOptions;
import org.eclipse.tm4e.core.registry.Registry;

// One instance belongs to one tokenizer worker; TM4E registries are not shared across threads.
final class SyntaxGrammars {

    private static final Map<String, String> RESOURCES = Map.of(
            "source.java", "/syntax/grammars/java.tmLanguage.json",
            "source.json", "/syntax/grammars/JSON.tmLanguage.json",
            "source.python", "/syntax/grammars/MagicPython.tmLanguage.json",
            "source.js", "/syntax/grammars/JavaScript.tmLanguage.json",
            "source.js.jsx", "/syntax/grammars/JavaScriptReact.tmLanguage.json",
            "source.ts", "/syntax/grammars/TypeScript.tmLanguage.json",
            "source.tsx", "/syntax/grammars/TypeScriptReact.tmLanguage.json");

    private final Registry registry = new Registry(new IRegistryOptions() {
        @Override
        public IGrammarSource getGrammarSource(String scopeName) {
            String resource = RESOURCES.get(scopeName);
            return resource == null ? null : IGrammarSource.fromResource(SyntaxGrammars.class, resource);
        }
    });

    Optional<IGrammar> forLanguage(EditorLanguage language) {
        String scope = switch (language) {
            case JAVA -> "source.java";
            case JSON -> "source.json";
            case PYTHON -> "source.python";
            case JAVASCRIPT -> "source.js";
            case JAVASCRIPT_JSX -> "source.js.jsx";
            case TYPESCRIPT -> "source.ts";
            case TYPESCRIPT_TSX -> "source.tsx";
            case PLAIN_TEXT -> null;
        };
        if (scope == null) {
            return Optional.empty();
        }
        return Optional.of(Objects.requireNonNull(registry.loadGrammar(scope), "Missing bundled grammar: " + scope));
    }
}

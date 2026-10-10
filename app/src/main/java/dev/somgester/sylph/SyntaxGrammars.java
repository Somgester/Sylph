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
            "source.json", "/syntax/grammars/JSON.tmLanguage.json");

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
            case PLAIN_TEXT -> null;
        };
        if (scope == null) {
            return Optional.empty();
        }
        return Optional.of(Objects.requireNonNull(registry.loadGrammar(scope), "Missing bundled grammar: " + scope));
    }
}

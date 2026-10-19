package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

enum EditorLanguage {
    PLAIN_TEXT("Plain Text"),
    JAVA("Java"),
    JSON("JSON"),
    PYTHON("Python"),
    JAVASCRIPT("JavaScript"),
    JAVASCRIPT_JSX("JavaScript (JSX)"),
    TYPESCRIPT("TypeScript"),
    TYPESCRIPT_TSX("TypeScript (TSX)"),
    MARKDOWN("Markdown"),
    SHELL("Shell Script"),
    C("C"),
    CPP("C++");

    private static final Set<String> SHELL_FILENAMES = Set.of(
            ".bashrc", ".bash_aliases", ".bash_profile", ".bash_login", ".bash_logout", ".profile",
            ".zshrc", ".zshenv", ".zprofile", ".zlogin", ".zlogout", ".envrc",
            "zshrc", "zshenv", "zprofile", "zlogin", "zlogout");

    private final String displayName;

    EditorLanguage(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    String languageServerId() {
        return switch (this) {
            case PLAIN_TEXT -> "plaintext";
            case JAVA -> "java";
            case JSON -> "json";
            case PYTHON -> "python";
            case JAVASCRIPT -> "javascript";
            case JAVASCRIPT_JSX -> "javascriptreact";
            case TYPESCRIPT -> "typescript";
            case TYPESCRIPT_TSX -> "typescriptreact";
            case MARKDOWN -> "markdown";
            case SHELL -> "shellscript";
            case C -> "c";
            case CPP -> "cpp";
        };
    }

    static EditorLanguage forPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return PLAIN_TEXT;
        }
        String originalName = path.getFileName().toString();
        String name = originalName.toLowerCase(Locale.ROOT);
        if (SHELL_FILENAMES.contains(name)) {
            return SHELL;
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return PLAIN_TEXT;
        }
        // Uppercase .C conventionally denotes C++, unlike lowercase .c.
        if (originalName.substring(dot + 1).equals("C")) {
            return CPP;
        }
        return switch (name.substring(dot + 1)) {
            case "c", "i" -> C;
            case "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "h", "ii", "inl", "ipp",
                    "tpp", "txx", "cppm", "ccm", "cxxm", "c++m", "ixx", "ino" -> CPP;
            case "java" -> JAVA;
            case "json" -> JSON;
            case "py", "pyw", "pyi" -> PYTHON;
            case "js", "mjs", "cjs" -> JAVASCRIPT;
            case "jsx" -> JAVASCRIPT_JSX;
            case "ts", "mts", "cts" -> TYPESCRIPT;
            case "tsx" -> TYPESCRIPT_TSX;
            case "md", "markdown" -> MARKDOWN;
            case "sh", "bash", "zsh", "ksh", "zsh-theme" -> SHELL;
            default -> PLAIN_TEXT;
        };
    }
}

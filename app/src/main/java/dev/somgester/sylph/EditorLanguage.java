package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.Locale;

enum EditorLanguage {
    PLAIN_TEXT("Plain Text"),
    JAVA("Java"),
    JSON("JSON"),
    PYTHON("Python"),
    JAVASCRIPT("JavaScript"),
    JAVASCRIPT_JSX("JavaScript (JSX)"),
    TYPESCRIPT("TypeScript"),
    TYPESCRIPT_TSX("TypeScript (TSX)");

    private final String displayName;

    EditorLanguage(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    static EditorLanguage forPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return PLAIN_TEXT;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return PLAIN_TEXT;
        }
        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "java" -> JAVA;
            case "json" -> JSON;
            case "py", "pyw", "pyi" -> PYTHON;
            case "js", "mjs", "cjs" -> JAVASCRIPT;
            case "jsx" -> JAVASCRIPT_JSX;
            case "ts", "mts", "cts" -> TYPESCRIPT;
            case "tsx" -> TYPESCRIPT_TSX;
            default -> PLAIN_TEXT;
        };
    }
}

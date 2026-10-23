package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

// Images are shared; each cell/tab needs its own scene-graph node.
final class FileIcon {

    private static final int SIZE = 16;

    private static final Map<EditorLanguage, Image> IMAGES = loadImages();

    private FileIcon() { }

    static Node forPath(Path path) {
        EditorLanguage language = EditorLanguage.forPath(path);
        Image image = IMAGES.get(language);
        if (image == null) {
            return symbol(AppIcon.Symbol.FILE, path == null ? "Untitled file" : "File");
        }
        ImageView icon = new ImageView(image);
        icon.setFitWidth(SIZE);
        icon.setFitHeight(SIZE);
        icon.setPreserveRatio(true);
        icon.setMouseTransparent(true);
        icon.setAccessibleText(language.displayName() + " file");
        icon.getStyleClass().add("file-icon");
        return icon;
    }

    static StackPane folder() {
        return symbol(AppIcon.Symbol.FOLDER, "Folder");
    }

    private static StackPane symbol(AppIcon.Symbol symbol, String description) {
        StackPane icon = AppIcon.symbol(symbol);
        icon.getStyleClass().add("file-icon");
        icon.setAccessibleText(description);
        return icon;
    }

    private static Map<EditorLanguage, Image> loadImages() {
        Map<EditorLanguage, Image> images = new EnumMap<>(EditorLanguage.class);
        for (EditorLanguage language : EditorLanguage.values()) {
            String name = switch (language) {
                case PLAIN_TEXT -> null;
                case JAVA -> "java";
                case JSON -> "json";
                case PYTHON -> "python";
                case JAVASCRIPT -> "javascript";
                case JAVASCRIPT_JSX -> "react";
                case TYPESCRIPT -> "typescript";
                case TYPESCRIPT_TSX -> "react_ts";
                case MARKDOWN -> "markdown";
                case SHELL -> "console";
                case C -> "c";
                case CPP -> "cpp";
            };
            if (name != null) {
                var resource = Objects.requireNonNull(FileIcon.class.getResource("/icons/files/" + name + ".png"));
                images.put(language, new Image(resource.toExternalForm()));
            }
        }
        return Map.copyOf(images);
    }
}

package dev.somgester.sylph;

import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

final class AppIcon {

    enum Symbol {
        FILE("M4 2 H10 L14 6 V14 H4 Z M10 2 V6 H14"),
        FOLDER("M2 4 V13 H14 V5 H8 L6 3 H2 Z"),
        SAVE("M3 2 H12 L14 4 V14 H2 V2 Z M5 2 V6 H11 V2 M5 14 V9 H11 V14"),
        SIDEBAR("M2 2 H14 V14 H2 Z M6 2 V14"),
        SETTINGS("M8 2 V4 M8 12 V14 M2 8 H4 M12 8 H14 M4 4 L5 5 M11 11 L12 12 "
                + "M4 12 L5 11 M11 5 L12 4 M11 8 A3 3 0 1 1 5 8 A3 3 0 1 1 11 8");

        private final String path;

        Symbol(String path) {
            this.path = path;
        }
    }

    private static final String MARK = "M20 7 C20 3 13 1 7 4 C3 6 4 10 8 11 H15 "
            + "C19 11 21 14 19 17 C16 22 8 22 4 18 L6 16 C9 19 15 19 17 16 "
            + "C18 14 16 13 14 13 H8 C1 13 0 6 5 2 C12 -2 22 1 22 7 Z";

    private AppIcon() { }

    static StackPane symbol(Symbol symbol) {
        SVGPath path = new SVGPath();
        path.setContent(symbol.path);
        path.setFill(Color.TRANSPARENT);
        path.setStrokeWidth(1.4);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        path.getStyleClass().add("action-icon");
        return container(new Group(path), 16);
    }

    static StackPane logo(double size) {
        SVGPath mark = new SVGPath();
        mark.setContent(MARK);
        mark.setFill(Color.web("#06b6d4"));
        mark.getStyleClass().add("sylph-mark");
        Group group = new Group(mark);
        group.getTransforms().add(new Scale(size / 24, size / 24));
        StackPane icon = container(group, size);
        icon.setAccessibleText("Sylph");
        return icon;
    }

    static Image windowIcon() {
        StackPane icon = logo(64);
        icon.resize(64, 64);
        icon.layout();
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return icon.snapshot(parameters, null);
    }

    private static StackPane container(Group graphic, double size) {
        StackPane icon = new StackPane(graphic);
        icon.setMinSize(size, size);
        icon.setPrefSize(size, size);
        icon.setMaxSize(size, size);
        icon.setMouseTransparent(true);
        return icon;
    }
}

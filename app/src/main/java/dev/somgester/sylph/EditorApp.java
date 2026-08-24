package dev.somgester.sylph;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class EditorApp extends Application {

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setId("root");

        Label brand = new Label("SYLPH");
        brand.setId("brand");

        ToggleGroup themeGroup = new ToggleGroup();
        ToggleButton darkTheme = createThemeButton("Dark", themeGroup, true);
        ToggleButton lightTheme = createThemeButton("Light", themeGroup, false);
        darkTheme.getStyleClass().add("theme-toggle");
        lightTheme.getStyleClass().add("theme-toggle");
        HBox themes = new HBox(4, darkTheme, lightTheme);
        themes.setAlignment(Pos.CENTER_RIGHT);

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, javafx.scene.layout.Priority.ALWAYS);
        HBox toolbar = new HBox(24, brand, toolbarSpacer, themes);
        toolbar.setId("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(14, 18, 14, 18));
        root.setTop(toolbar);

        TreeItem<String> project = new TreeItem<>("sylph");
        project.setExpanded(true);
        project.getChildren().add(new TreeItem<>("src"));
        project.getChildren().add(new TreeItem<>("README.md"));
        project.getChildren().add(new TreeItem<>("build.gradle"));
        TreeView<String> projectTree = new TreeView<>(project);
        projectTree.setShowRoot(true);
        projectTree.setPrefWidth(210);
        VBox sidebar = new VBox(projectTree);
        sidebar.setId("sidebar");
        sidebar.setPadding(new Insets(14, 0, 0, 0));
        root.setLeft(sidebar);

        TextArea editor = new TextArea("// Sylph Sample Text Area\n\n");
        editor.setWrapText(false);
        editor.setId("editor");
        root.setCenter(editor);

        Label status = new Label("Ready  |  sylph");
        status.setId("status");
        status.setPadding(new Insets(8, 14, 8, 14));
        root.setBottom(status);

        Scene scene = new Scene(root, 1100, 700);
        stage.setTitle("Sylph");
        stage.setScene(scene);
        darkTheme.setOnAction(event -> applyTheme(scene, true));
        lightTheme.setOnAction(event -> applyTheme(scene, false));
        applyTheme(scene, true);
        stage.show();
    }

        private ToggleButton createThemeButton(String label, ToggleGroup group, boolean selected) {
        ToggleButton button = new ToggleButton(label);
        button.setToggleGroup(group);
        button.setSelected(selected);
        button.setPadding(new Insets(7, 12, 7, 12));
        return button;
        }

    private void applyTheme(Scene scene, boolean dark) {
        String stylesheet = dark ? "dark.css" : "light.css";
        var resource = getClass().getResource("/styles/" + stylesheet);
        if (resource == null) {
            throw new IllegalStateException("Missing theme stylesheet: " + stylesheet);
        }
        scene.getStylesheets().setAll(resource.toExternalForm());
    }
}
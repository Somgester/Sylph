package dev.somgester.sylph;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
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

        Sidebar sidebar = new Sidebar();

        Label status = new Label("Ready  |  sylph");
        status.setId("status");
        status.setPadding(new Insets(8, 14, 8, 14));

        Runnable openAction = () -> openFolderDialog(stage, sidebar, status);
        Button openFolderButton = createOpenFolderButton(openAction);
        Button toggleSidebarButton = createSidebarToggleButton(sidebar::toggleCollapsed);

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(24, brand, toggleSidebarButton, openFolderButton, toolbarSpacer, themes);
        toolbar.setId("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(14, 18, 14, 18));
        root.setTop(toolbar);
        root.setLeft(sidebar);

        TextArea editor = new TextArea("// Sylph Sample Text Area\n\n");
        editor.setWrapText(false);
        editor.setId("editor");
        root.setCenter(editor);

        root.setBottom(status);

        Scene scene = new Scene(root, 1100, 700);
        stage.setTitle("Sylph");
        stage.setScene(scene);
        darkTheme.setOnAction(event -> applyTheme(scene, true));
        lightTheme.setOnAction(event -> applyTheme(scene, false));
        registerOpenFolderShortcuts(scene, openAction);
        registerSidebarToggleShortcut(scene, sidebar::toggleCollapsed);
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

    private Button createOpenFolderButton(Runnable onOpen) {
        Button button = new Button("Open Folder");
        button.setId("open-folder");
        button.getStyleClass().add("theme-toggle");
        button.setTooltip(new Tooltip("Open Folder (Ctrl+O or Ctrl+K Ctrl+O)"));
        button.setOnAction(event -> onOpen.run());
        return button;
    }

    private Button createSidebarToggleButton(Runnable onToggle) {
        Button button = new Button("Sidebar");
        button.setId("toggle-sidebar");
        button.getStyleClass().add("theme-toggle");
        button.setTooltip(new Tooltip("Toggle Sidebar (Ctrl+B)"));
        button.setOnAction(event -> onToggle.run());
        return button;
    }

    private void openFolderDialog(Stage stage, Sidebar sidebar, Label status) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Folder");
        try {
            Path current = sidebar.getRootDirectory();
            if (current != null && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                chooser.setInitialDirectory(current.toFile());
            }
        } catch (IllegalArgumentException | SecurityException ex) {
            // Fall back to the default directory.
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            openFolder(selected.toPath(), sidebar, status);
        }
    }

    void openFolder(Path directory, Sidebar sidebar, Label status) {
        sidebar.setRootDirectoryAsync(directory);
        Path normalized = directory.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        String name;
        if (fileName == null) {
            name = normalized.toString();
        } else {
            name = fileName.toString();
        }
        status.setText("Opened  |  " + name);
        status.setTooltip(new Tooltip(normalized.toString()));
    }

    private void registerOpenFolderShortcuts(Scene scene, Runnable openAction) {
        KeyCombination openCombo = new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (openCombo.match(event)) {
                openAction.run();
                event.consume();
            }
        });
    }

    private void registerSidebarToggleShortcut(Scene scene, Runnable toggleAction) {
        KeyCombination toggleCombo = new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (toggleCombo.match(event)) {
                toggleAction.run();
                event.consume();
            }
        });
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

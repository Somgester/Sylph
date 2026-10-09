package dev.somgester.sylph;

import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TitledPane;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

final class SettingsPage extends ScrollPane {

    SettingsPage(AppSettings settings, AutoSaveController autoSave, Runnable onBack) {
        setId("settings-page");
        setFitToWidth(true);
        setFitToHeight(true);
        VBox content = new VBox(12);
        content.setMaxWidth(760);
        content.setMinHeight(Region.USE_PREF_SIZE);

        Button back = new Button("← Back to editor");
        back.setId("settings-back");
        back.getStyleClass().add("settings-back");
        back.setOnAction(event -> onBack.run());
        Label title = label("Settings", "settings-title");
        Label subtitle = label("Personalize your editor and how your work is saved.", "settings-description");
        HBox heading = new HBox(16, title, back);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);
        VBox header = new VBox(10, heading, subtitle);

        ToggleGroup themes = new ToggleGroup();
        RadioButton dark = new RadioButton("Dark");
        dark.setId("theme-dark");
        dark.setUserData(true);
        dark.setToggleGroup(themes);
        RadioButton light = new RadioButton("Light");
        light.setId("theme-light");
        light.setUserData(false);
        light.setToggleGroup(themes);
        themes.selectToggle(settings.darkThemeProperty().get() ? dark : light);
        themes.selectedToggleProperty().addListener((observable, oldValue, selected) -> {
            if (selected != null) {
                settings.darkThemeProperty().set((Boolean) selected.getUserData());
            }
        });
        settings.darkThemeProperty().addListener((observable, oldValue, isDark) ->
                themes.selectToggle(isDark ? dark : light));
        HBox themeChoices = new HBox(16, themeCard(dark, true), themeCard(light, false));
        VBox appearance = section("Appearance", "Choose the theme that feels comfortable for you.", themeChoices);

        CheckBox enabled = new CheckBox("Save changes automatically");
        enabled.setId("autosave-toggle");
        enabled.selectedProperty().bindBidirectional(settings.autoSaveProperty());
        ComboBox<Integer> delays = new ComboBox<>();
        delays.setId("autosave-delay");
        followPopupTheme(delays);
        delays.getItems().setAll(0, 1, 2, 5, 10);
        delays.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer seconds) {
                return seconds == null ? "" : seconds == 0 ? "Immediately"
                        : seconds + (seconds == 1 ? " second" : " seconds");
            }

            @Override
            public Integer fromString(String value) {
                throw new UnsupportedOperationException("Delay options are not editable");
            }
        });
        delays.setValue(settings.delaySecondsProperty().get());
        delays.valueProperty().addListener((observable, oldValue, seconds) -> {
            if (seconds != null) {
                settings.delaySecondsProperty().set(seconds);
            }
        });
        settings.delaySecondsProperty().addListener((observable, oldValue, seconds) ->
                delays.setValue(seconds.intValue()));
        HBox delayRow = new HBox(16, label("Save after", "settings-field-label"), delays);
        delayRow.setAlignment(Pos.CENTER_LEFT);
        delayRow.disableProperty().bind(settings.autoSaveProperty().not());
        Label autoSaveStatus = label("", "settings-description");
        autoSaveStatus.setId("autosave-state");
        autoSaveStatus.textProperty().bind(autoSave.statusProperty());
        VBox saving = section("Autosave", "Save every change immediately, or wait until you pause typing.",
                new VBox(14, enabled, delayRow, autoSaveStatus));
        Label persistence = label("", "settings-description");
        persistence.setId("settings-persistence");
        persistence.textProperty().bind(settings.persistenceStatusProperty());
        persistence.visibleProperty().bind(persistence.textProperty().isNotEmpty());
        persistence.managedProperty().bind(persistence.visibleProperty());
        GridPane shortcuts = new GridPane();
        shortcuts.setHgap(28);
        shortcuts.setVgap(12);
        int row = 0;
        for (EditorShortcuts.Command command : EditorShortcuts.Command.values()) {
            shortcuts.addRow(row++, label(command.label(), "settings-field-label"),
                    label(command.shortcut().getDisplayText(), "shortcut-key"));
        }
        shortcuts.addRow(row, label("Back to editor", "settings-field-label"), label("Esc", "shortcut-key"));
        TitledPane keybindings = new TitledPane("Keyboard shortcuts", shortcuts);
        keybindings.setId("settings-keybindings");
        keybindings.setExpanded(false);
        keybindings.setAnimated(false);
        content.getChildren().addAll(header, appearance, saving, keybindings, persistence);
        StackPane canvas = new StackPane(content);
        canvas.setAlignment(Pos.TOP_CENTER);
        canvas.setPadding(new Insets(8, 28, 8, 28));
        canvas.setMinHeight(Region.USE_PREF_SIZE);
        canvas.getStyleClass().add("settings-canvas");
        setContent(canvas);
    }

    private static void followPopupTheme(ComboBox<Integer> combo) {
        // Popup content lives in its own scene and otherwise keeps cached owner stylesheets.
        combo.skinProperty().addListener((observable, oldSkin, newSkin) -> {
            if (combo.getScene() != null) {
                if (oldSkin instanceof ComboBoxListViewSkin<?> oldListSkin
                        && oldListSkin.getPopupContent() instanceof Parent oldPopup) {
                    Bindings.unbindContent(oldPopup.getStylesheets(), combo.getScene().getStylesheets());
                }
                if (newSkin instanceof ComboBoxListViewSkin<?> newListSkin
                        && newListSkin.getPopupContent() instanceof Parent newPopup) {
                    Bindings.bindContent(newPopup.getStylesheets(), combo.getScene().getStylesheets());
                }
            }
        });
        combo.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (combo.getSkin() instanceof ComboBoxListViewSkin<?> skin
                    && skin.getPopupContent() instanceof Parent popup) {
                if (oldScene != null) {
                    Bindings.unbindContent(popup.getStylesheets(), oldScene.getStylesheets());
                }
                if (newScene != null) {
                    Bindings.bindContent(popup.getStylesheets(), newScene.getStylesheets());
                } else {
                    popup.getStylesheets().clear();
                }
            }
        });
    }

    private static VBox section(String title, String description, Region controls) {
        VBox section = new VBox(9, label(title, "settings-section-title"),
                label(description, "settings-description"), controls);
        section.getStyleClass().add("settings-section");
        return section;
    }

    private static VBox themeCard(RadioButton option, boolean dark) {
        Region sidebar = new Region();
        sidebar.getStyleClass().add("preview-sidebar");
        sidebar.setPrefWidth(34);
        sidebar.setMinWidth(34);
        Text sample = new Text("Aa");
        sample.getStyleClass().add("preview-text");
        Region line = new Region();
        line.getStyleClass().add("preview-line");
        line.setPrefHeight(5);
        line.setMinHeight(5);
        line.setMaxWidth(78);
        VBox code = new VBox(8, sample, line);
        code.setPadding(new Insets(12));
        HBox.setHgrow(code, Priority.ALWAYS);
        HBox preview = new HBox(sidebar, code);
        preview.getStyleClass().add(dark ? "theme-preview-dark" : "theme-preview-light");
        preview.setPrefHeight(70);
        VBox card = new VBox(10, preview, option);
        card.getStyleClass().add("theme-card");
        card.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(card, Priority.ALWAYS);
        PseudoClass selected = PseudoClass.getPseudoClass("selected");
        card.pseudoClassStateChanged(selected, option.isSelected());
        option.selectedProperty().addListener((observable, oldValue, value) ->
                card.pseudoClassStateChanged(selected, value));
        card.setOnMouseClicked(event -> option.setSelected(true));
        return card;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }
}

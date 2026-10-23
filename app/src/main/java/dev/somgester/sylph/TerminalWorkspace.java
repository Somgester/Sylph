package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

final class TerminalWorkspace extends SplitPane implements AutoCloseable {

    private final TabPane editors = new TabPane();

    private final TabPane panel = new TabPane();

    private Tab document;

    private final Consumer<Tab> selectDocument;

    private final BooleanSupplier canSelectDocument;

    private final Consumer<List<Tab>> closeTabs;

    private boolean changingDocuments;

    private final Map<Tab, TerminalView> terminals = new IdentityHashMap<>();

    private final Function<Path, TerminalView> createTerminal;

    private final Runnable focusDocument;

    private int nextId = 1;

    private boolean panelVisible;

    private boolean closed;

    @SuppressWarnings("this-escape")
    TerminalWorkspace(Function<Path, TerminalView> createTerminal, Runnable focusDocument,
            Consumer<Tab> selectDocument, BooleanSupplier canSelectDocument,
            Consumer<List<Tab>> closeTabs, Runnable newFile) {
        this.createTerminal = createTerminal;
        this.focusDocument = focusDocument;
        this.selectDocument = selectDocument;
        this.canSelectDocument = canSelectDocument;
        this.closeTabs = closeTabs;
        setId("terminal-workspace");
        setOrientation(Orientation.VERTICAL);
        editors.setId("editor-tabs");
        panel.setId("terminal-panel");
        editors.setMinSize(100, 100);
        panel.setMinSize(100, 100);
        editors.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        panel.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        installTabLifecycle(editors);
        installTabLifecycle(panel);
        Label empty = new Label("Double-click to create a new file\n"
                + EditorShortcuts.Command.NEW_FILE.help() + "  •  " + EditorShortcuts.Command.OPEN_FILE.help());
        empty.setId("empty-editor");
        empty.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        empty.setAlignment(Pos.CENTER);
        empty.visibleProperty().bind(Bindings.isEmpty(editors.getTabs()));
        empty.managedProperty().bind(empty.visibleProperty());
        empty.setOnMouseClicked(event -> {
            if (isDoubleClick(event)) {
                newFile.run();
                event.consume();
            }
        });
        editors.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (isDoubleClick(event) && isBlankTabBar(event.getTarget())) {
                newFile.run();
                event.consume();
            }
        });
        StackPane area = new StackPane(editors, empty);
        area.setId("editor-area");
        getItems().add(area);
    }

    private static boolean isDoubleClick(MouseEvent event) {
        return event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2;
    }

    private boolean isBlankTabBar(Object target) {
        for (Node node = target instanceof Node clicked ? clicked : null;
                node != null && node != editors; node = node.getParent()) {
            if (node instanceof ButtonBase || node.getStyleClass().contains("tab")) {
                return false;
            }
            if (node.getStyleClass().contains("tab-header-area")) {
                return true;
            }
        }
        return false;
    }

    List<Tab> tabs() {
        List<Tab> tabs = new ArrayList<>(editors.getTabs());
        tabs.addAll(panel.getTabs());
        return List.copyOf(tabs);
    }

    void removeTabs(List<Tab> tabs) {
        for (Tab tab : tabs) {
            if (tab == document) {
                removeDocument(tab);
            } else {
                editors.getTabs().remove(tab);
                panel.getTabs().remove(tab);
            }
        }
    }

    private void installTabMenu(Tab tab) {
        MenuItem close = new MenuItem("Close Tab");
        close.setOnAction(event -> closeTabs.accept(List.of(tab)));
        MenuItem closeAll = new MenuItem("Close All Tabs");
        closeAll.setOnAction(event -> closeTabs.accept(tabs()));
        tab.setContextMenu(new ContextMenu(close, closeAll));
    }

    private void installTabLifecycle(TabPane pane) {
        pane.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(this::installTabMenu);
                for (Tab removed : change.getRemoved()) {
                    TerminalView terminal = terminals.remove(removed);
                    if (terminal != null) {
                        terminal.close();
                    }
                    removed.setContent(null);
                }
            }
            if (pane == panel && pane.getTabs().isEmpty()) {
                panelVisible = false;
                getItems().remove(panel);
                focusSelection(editors);
            }
        });
        pane.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (pane == editors && selected != null && !terminals.containsKey(selected)) {
                if (!changingDocuments && !canSelectDocument.getAsBoolean() && selected != document) {
                    pane.getSelectionModel().select(previous);
                    return;
                }
                document = selected;
                selectDocument.accept(selected);
            }
            focusSelection(pane);
        });
    }

    private void focusSelection(TabPane pane) {
        if (closed) {
            return;
        }
        TerminalView terminal = terminals.get(pane.getSelectionModel().getSelectedItem());
        if (terminal != null) {
            terminal.focusTerminal(getScene());
        } else if (pane == editors) {
            var scene = getScene();
            Node previous = scene == null ? null : scene.getFocusOwner();
            Platform.runLater(() -> {
                if (!closed && getScene() == scene && scene != null && scene.getFocusOwner() == previous
                        && document != null && editors.getSelectionModel().getSelectedItem() == document) {
                    focusDocument.run();
                }
            });
        }
    }

    void addDocument(Tab tab) {
        changingDocuments = true;
        try {
            editors.getTabs().add(tab);
            editors.getSelectionModel().select(tab);
        } finally {
            changingDocuments = false;
        }
    }

    void removeDocument(Tab tab) {
        changingDocuments = true;
        try {
            editors.getTabs().remove(tab);
        } finally {
            if (document == tab) {
                document = null;
            }
            changingDocuments = false;
        }
    }

    void selectDocument(Tab tab) {
        changingDocuments = true;
        try {
            editors.getSelectionModel().select(tab);
        } finally {
            changingDocuments = false;
        }
    }

    void selectDocument() {
        if (document != null && editors.getTabs().contains(document)) {
            selectDocument(document);
        }
    }

    void togglePanel(Path directory) {
        if (panelVisible) {
            panelVisible = false;
            getItems().remove(panel);
            focusSelection(editors);
        } else if (panel.getTabs().isEmpty()) {
            open(directory, false);
        } else {
            showPanel();
            TerminalView selected = terminals.get(panel.getSelectionModel().getSelectedItem());
            if (selected != null) {
                selected.focusTerminal(getScene());
            }
        }
    }

    TerminalView open(Path directory, boolean inEditor) {
        if (closed) {
            return null;
        }
        TabPane pane = inEditor ? editors : panel;
        TerminalView view = createTerminal.apply(directory);
        Tab tab = new Tab("Terminal " + nextId++, view);
        tab.setTooltip(new Tooltip(directory.toString()));
        Button duplicate = new Button("⧉");
        duplicate.getStyleClass().add("terminal-duplicate");
        duplicate.setAccessibleText("Duplicate " + tab.getText());
        duplicate.setTooltip(new Tooltip("Duplicate terminal: fresh shell in the same starting folder"));
        duplicate.setOnAction(event -> open(view.session().directory(), inEditor));
        tab.setGraphic(duplicate);
        terminals.put(tab, view);
        pane.getTabs().add(tab);
        pane.getSelectionModel().select(tab);
        if (!inEditor) {
            showPanel();
        }
        // Attach new tab contents before the explicit focus request, rather than stealing focus later.
        if (getScene() != null) {
            getScene().getRoot().applyCss();
            getScene().getRoot().layout();
        }
        view.focusTerminal(getScene());
        return view;
    }

    private void showPanel() {
        panelVisible = true;
        if (!getItems().contains(panel)) {
            getItems().add(panel);
            Platform.runLater(() -> {
                if (!closed && panelVisible) {
                    setDividerPositions(0.65);
                }
            });
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            terminals.values().forEach(TerminalView::close);
            terminals.clear();
            editors.getTabs().clear();
            panel.getTabs().clear();
            getItems().clear();
        }
    }
}

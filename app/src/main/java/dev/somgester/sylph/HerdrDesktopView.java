package dev.somgester.sylph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

/** Displays and controls a separately started Herdr Desktop session. */
public final class HerdrDesktopView extends BorderPane implements AutoCloseable {

    public interface Observer {
        default void frameShown(Image image, boolean resized, boolean resizeUnconfirmed, boolean inputIdle) { }

        default void clipboardCompleted(boolean copy) { }

        default void fixedKeysCompleted() { }

        default void failed(IOException error) { }
    }

    private static final int CLIPBOARD_LIMIT = 65_536;

    private sealed interface NativeInput permits Click, Drag, NativeKey, NativeClipboard { }

    private record Click(double x, double y) implements NativeInput { }

    private record Drag(Click start, Click end) implements NativeInput { }

    private record Viewport(int width, int height) {
        boolean matches(Image image) {
            // Captured frames use physical pixels. Compare aspect ratios across display scales.
            return Math.abs(image.getWidth() * height / image.getHeight() - width) <= 2;
        }
    }

    private record NativeKey(String token) implements NativeInput { }

    // Null text requests a copy. Non-null text is a snapshot of the JavaFX clipboard at keypress.
    private record NativeClipboard(String text) implements NativeInput { }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("herdr-desktop-frame").factory());

    private final StackPane surface = new StackPane();

    private final ImageView view = new ImageView();

    private final Label status = new Label();

    private final ArrayDeque<String> desktopKeys = new ArrayDeque<>();

    private final ArrayDeque<NativeInput> nativeInputs = new ArrayDeque<>();

    private final String desktopCore;

    private final String desktopTerminal;

    private final boolean desktopClicks;

    private final boolean desktopResize;

    private Viewport requestedViewport;

    private Viewport confirmedViewport;

    private Image confirmedFrame;

    private long resizeDeadline;

    private boolean resizeUnconfirmed;

    private Click dragStart;

    private boolean desktopFailed;

    private boolean unsupportedDesktopKey;

    private int desktopScrollLines;

    private AnimationTimer timer;

    private boolean pending;

    private boolean closed;

    private String clipboardStatus;

    private final Observer observer;

    private boolean active;

    public HerdrDesktopView(String executable) {
        this(executable, null, null, true, true, new Observer() { });
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            desktopKeys.clear();
            nativeInputs.clear();
            desktopScrollLines = 0;
            dragStart = null;
        }
    }

    void focusPreview() {
        surface.requestFocus();
    }

    static boolean ownsFocus(Scene scene) {
        for (var node = scene.getFocusOwner(); node != null; node = node.getParent()) {
            if (node instanceof HerdrDesktopView) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            setActive(false);
            timer.stop();
            worker.shutdownNow();
        }
    }

    // The observer is used by the opt-in transport smoke tests.
    @SuppressWarnings("this-escape")
    public HerdrDesktopView(String executable, String core, String terminal, boolean interactive, boolean resize,
            Observer observer) {
        this.observer = Objects.requireNonNull(observer);
        desktopCore = core;
        desktopTerminal = terminal;
        desktopClicks = interactive;
        desktopResize = resize;
        setId("herdr-desktop-preview");
        getStyleClass().add("terminal-view");
        surface.setId("herdr-desktop-surface");
        view.setId("herdr-desktop-frame");
        if (desktopCore != null && (desktopTerminal == null || !desktopTerminal.matches("t[0-9a-z]+"))) {
            throw new IllegalArgumentException("Use an explicit local terminal ID, such as t1.");
        }
        if (desktopCore != null || desktopClicks) {
            surface.setFocusTraversable(true);
            surface.setOnMousePressed(event -> surface.requestFocus());
            surface.setOnScroll(event -> {
                double delta = event.getDeltaY();
                if (!desktopFailed && Double.isFinite(delta) && delta != 0
                        && !event.isControlDown() && !event.isMetaDown() && !event.isShiftDown()) {
                    // Positive JavaFX wheel deltas move toward history, which Herdr counts as negative.
                    int lines = Math.max(1, (int) Math.round(Math.min(Math.abs(delta) / 12, 12)));
                    desktopScrollLines = Math.clamp(desktopScrollLines + (delta > 0 ? -lines : lines), -96, 96);
                    event.consume();
                }
            });
            surface.setOnKeyTyped(event -> {
                if (!event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                    event.getCharacter().codePoints().forEach(this::queueDesktopCharacter);
                    event.consume();
                }
            });
            surface.setOnKeyPressed(event -> {
                if (desktopClicks && handleClipboardShortcut(event)) {
                    event.consume();
                    return;
                }
                if (event.isAltDown() || event.isMetaDown() || event.isShiftDown()) {
                    return;
                }
                String keyName = event.isControlDown()
                        ? (event.getCode() == KeyCode.C ? "ctrl+c" : null)
                        : switch (event.getCode()) {
                            case ENTER -> "enter";
                            case BACK_SPACE -> "backspace";
                            case TAB -> "tab";
                            case ESCAPE -> "esc";
                            case UP -> "up";
                            case DOWN -> "down";
                            case LEFT -> "left";
                            case RIGHT -> "right";
                            default -> null;
                        };
                if (keyName != null) {
                    if ("ctrl+c".equals(keyName)) {
                        unsupportedDesktopKey = false;
                    }
                    if (!"enter".equals(keyName) || !unsupportedDesktopKey) {
                        queueDesktopKey(keyName);
                    }
                    event.consume();
                }
            });
        }
        view.setPreserveRatio(true);
        if (desktopClicks) {
            view.setPickOnBounds(true);
            view.setOnMousePressed(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    dragStart = resizeDeadline == 0 ? imagePoint(event, false) : null;
                }
            });
            view.setOnMouseReleased(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    Click start = dragStart;
                    dragStart = null;
                    if (!event.isStillSincePress() && start != null) {
                        Click end = imagePoint(event, true);
                        if (end != null && !start.equals(end)) {
                            queueNativeInput(new Drag(start, end));
                            event.consume();
                        }
                    }
                }
            });
            view.setOnMouseClicked(event -> {
                if (resizeDeadline == 0 && event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                    Click point = imagePoint(event, false);
                    if (point != null) {
                        queueNativeInput(point);
                        event.consume();
                    }
                }
            });
        }
        view.fitWidthProperty().bind(surface.widthProperty());
        view.fitHeightProperty().bind(surface.heightProperty());
        surface.getChildren().add(view);
        status.setText("Connecting to Herdr Desktop…");
        setCenter(surface);
        setBottom(status);
        timer = new AnimationTimer() {
            private long nextCapture;
            private Viewport observedViewport;
            private long viewportChangedAt;

            @Override
            public void handle(long now) {
                if (!active || pending || desktopFailed) {
                    return;
                }
                Viewport viewport = desktopResize ? viewport() : null;
                if (!Objects.equals(viewport, observedViewport)) {
                    observedViewport = viewport;
                    viewportChangedAt = now;
                }
                if (!nativeInputs.isEmpty()) {
                    pending = true;
                    if (nativeInputs.peekFirst() instanceof Click click) {
                        nativeInputs.removeFirst();
                        worker.execute(() -> sendDesktopPointer(executable, click, null));
                    } else if (nativeInputs.peekFirst() instanceof Drag dragEvent) {
                        nativeInputs.removeFirst();
                        worker.execute(() -> sendDesktopPointer(executable, dragEvent.start(), dragEvent.end()));
                    } else if (nativeInputs.peekFirst() instanceof NativeClipboard clipboard) {
                        nativeInputs.removeFirst();
                        worker.execute(() -> exchangeClipboard(executable, clipboard));
                    } else {
                        List<String> keys = new ArrayList<>();
                        while (nativeInputs.peekFirst() instanceof NativeKey key) {
                            keys.add(key.token());
                            nativeInputs.removeFirst();
                        }
                        worker.execute(() -> sendNativeKeys(executable, keys));
                    }
                } else if (!desktopKeys.isEmpty()) {
                    List<String> keys = new ArrayList<>(desktopKeys);
                    desktopKeys.clear();
                    pending = true;
                    worker.execute(() -> sendDesktopKeys(keys));
                } else if (desktopScrollLines != 0) {
                    int lines = desktopScrollLines;
                    desktopScrollLines = 0;
                    pending = true;
                    worker.execute(() -> scrollDesktop(executable, lines));
                } else if (viewport != null && dragStart == null && !viewport.equals(requestedViewport)
                        && now - viewportChangedAt >= TimeUnit.MILLISECONDS.toNanos(150)) {
                    requestedViewport = viewport;
                    resizeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    resizeUnconfirmed = false;
                    pending = true;
                    worker.execute(() -> resizeDesktop(executable, viewport));
                } else if (now >= nextCapture) {
                    pending = true;
                    nextCapture = now + TimeUnit.MILLISECONDS.toNanos(500);
                    worker.execute(() -> captureDesktop(executable));
                }
            }
        };
        timer.start();
    }

    private Viewport viewport() {
        return new Viewport(Math.clamp((int) surface.getWidth(), 320, 1920),
                Math.clamp((int) surface.getHeight(), 200, 1080));
    }

    private void resizeDesktop(String executable, Viewport viewport) {
        try {
            runDesktopCommand(List.of(executable, "sylph-resize", Integer.toString(viewport.width()),
                    Integer.toString(viewport.height())), "Herdr Desktop resize request failed.");
            Platform.runLater(() -> pending = false);
        } catch (IOException failure) {
            Platform.runLater(() -> showFailure(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Click imagePoint(MouseEvent event, boolean clamp) {
        var bounds = view.getBoundsInLocal();
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            return null;
        }
        double x = (event.getX() - bounds.getMinX()) / bounds.getWidth();
        double y = (event.getY() - bounds.getMinY()) / bounds.getHeight();
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return null;
        }
        if (clamp) {
            return new Click(Math.clamp(x, 0, Math.nextDown(1.0)), Math.clamp(y, 0, Math.nextDown(1.0)));
        }
        return x >= 0 && x < 1 && y >= 0 && y < 1 ? new Click(x, y) : null;
    }

    private void sendDesktopPointer(String executable, Click start, Click end) {
        try {
            List<String> command = new ArrayList<>(List.of(executable, end == null ? "sylph-click" : "sylph-drag",
                    Double.toString(start.x()), Double.toString(start.y())));
            if (end != null) {
                command.add(Double.toString(end.x()));
                command.add(Double.toString(end.y()));
            }
            runDesktopCommand(command, "Herdr Desktop pointer request failed. Check the patched app and opt-in.");
            captureDesktop(executable);
        } catch (IOException failure) {
            Platform.runLater(() -> showFailure(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void scrollDesktop(String executable, int lines) {
        try {
            String arguments = desktopClicks ? "{\"lines\":" + lines + "}"
                    : String.format(Locale.ROOT, "{\"terminal\":\"%s\",\"lines\":%d}", desktopTerminal, lines);
            runDesktopCommand(List.of(executable, "do", "terminal.scroll", arguments),
                    "Herdr could not scroll the terminal.");
            captureDesktop(executable);
        } catch (IOException failure) {
            Platform.runLater(() -> showFailure(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void queueDesktopCharacter(int character) {
        if (Character.isISOControl(character) || desktopFailed) {
            return;
        }
        if (desktopClicks) {
            queueDesktopKey("u:" + Integer.toHexString(character));
        } else if (character == ' ') {
            queueDesktopKey("space");
        } else if (character >= 'A' && character <= 'Z') {
            queueDesktopKey("shift+" + Character.toString(Character.toLowerCase(character)));
        } else if ((character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9') || "',-./;=[\\]`".indexOf(character) >= 0) {
            queueDesktopKey(Character.toString(character));
        } else {
            unsupportedDesktopKey = true;
        }
    }

    private void queueDesktopKey(String keyName) {
        if (desktopClicks) {
            queueNativeInput(new NativeKey(keyName));
        } else if (!desktopFailed) {
            if (desktopKeys.size() == 128) {
                showFailure(new IOException("Terminal input queue is full. Reopen the preview."));
                return;
            }
            desktopKeys.addLast(keyName);
        }
    }

    private void queueNativeInput(NativeInput input) {
        if (desktopFailed) {
            return;
        }
        if (nativeInputs.size() >= 128) {
            showFailure(new IOException("Desktop input queue is full. Reopen the preview."));
        } else {
            nativeInputs.addLast(input);
        }
    }

    private boolean handleClipboardShortcut(KeyEvent event) {
        boolean chord = event.isControlDown() && event.isShiftDown() && !event.isMetaDown()
                || event.isMetaDown() && !event.isControlDown() && !event.isShiftDown();
        if (!chord || event.isAltDown() || (event.getCode() != KeyCode.C && event.getCode() != KeyCode.V)) {
            return false;
        }
        if (event.getCode() == KeyCode.C) {
            queueNativeInput(new NativeClipboard(null));
        } else {
            String text = Clipboard.getSystemClipboard().getString();
            if (text == null || text.isEmpty()) {
                clipboardStatus = "Clipboard has no text";
            } else if (text.length() > CLIPBOARD_LIMIT || text.codePoints().anyMatch(Character::isISOControl)
                    || text.getBytes(StandardCharsets.UTF_8).length > CLIPBOARD_LIMIT) {
                clipboardStatus = "Paste accepts up to 64 KiB of single-line text";
            } else {
                queueNativeInput(new NativeClipboard(text));
            }
        }
        return true;
    }

    private void exchangeClipboard(String executable, NativeClipboard clipboard) {
        boolean copy = clipboard.text() == null;
        try {
            byte[] reply = runClipboardCommand(executable, clipboard.text());
            Platform.runLater(() -> {
                pending = false;
                if (closed || desktopFailed || !active) {
                    return;
                }
                if (copy) {
                    String text = new String(reply, StandardCharsets.UTF_8);
                    clipboardStatus = text.isEmpty() ? "No terminal selection"
                            : writeClipboard(text) ? "Copied terminal selection" : "Could not write to clipboard";
                } else {
                    clipboardStatus = "Pasted text into Herdr";
                }
                status.setText(clipboardStatus);
                observer.clipboardCompleted(copy);
            });
        } catch (IOException failure) {
            Platform.runLater(() -> {
                if (!closed && !desktopFailed) {
                    pending = false;
                    clipboardStatus = failure.getMessage();
                    observer.failed(failure);
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] runClipboardCommand(String executable, String text)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable, text == null ? "sylph-copy" : "sylph-paste")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        FutureTask<byte[]> transfer = new FutureTask<>(() -> {
            try (var input = process.getOutputStream()) {
                if (text != null) {
                    input.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
            try (var output = process.getInputStream()) {
                return output.readNBytes(CLIPBOARD_LIMIT + 1);
            }
        });
        Thread.startVirtualThread(transfer);
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Herdr clipboard request failed. Focus a terminal in the preview.");
            }
            byte[] reply = transfer.get(1, TimeUnit.SECONDS);
            if (reply.length > CLIPBOARD_LIMIT) {
                throw new IOException("Herdr clipboard reply exceeds 64 KiB");
            }
            return reply;
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("Could not exchange clipboard text with Herdr", failure);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            transfer.cancel(true);
        }
    }

    private static boolean writeClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        return Clipboard.getSystemClipboard().setContent(content);
    }

    private void sendNativeKeys(String executable, List<String> keys) {
        try {
            List<String> command = new ArrayList<>(List.of(executable, "sylph-keys"));
            command.addAll(keys);
            runDesktopCommand(command, "Herdr Desktop rejected the keyboard input.");
            captureDesktop(executable);
        } catch (IOException failure) {
            Platform.runLater(() -> showFailure(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendDesktopKeys(List<String> keys) {
        try {
            List<String> command = new ArrayList<>(List.of(desktopCore, "keys", "--", desktopTerminal));
            command.addAll(keys);
            runDesktopCommand(command, "Herdr terminal input failed for " + desktopTerminal);
            Platform.runLater(() -> {
                pending = false;
                if (!closed && !desktopFailed && active) {
                    observer.fixedKeysCompleted();
                }
            });
        } catch (IOException failure) {
            Platform.runLater(() -> showFailure(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void captureDesktop(String executable) {
        try {
            Path png = Files.createTempFile("sylph-herdr-frame-", ".png");
            try {
                capturePng(executable, png);
                try (var input = Files.newInputStream(png)) {
                    Image image = new Image(input);
                    if (image.isError() || image.getPixelReader() == null) {
                        throw new IOException("Herdr Desktop returned an unreadable PNG");
                    }
                    Platform.runLater(() -> showDesktopFrame(image));
                }
            } finally {
                Files.deleteIfExists(png);
            }
        } catch (IOException failure) {
            Platform.runLater(() -> showFailure(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void capturePng(String executable, Path png) throws IOException, InterruptedException {
        runDesktopCommand(List.of(executable, "shot", png.toString()),
                "Herdr Desktop capture failed. Check that its window is open.");
    }

    private static void runDesktopCommand(List<String> command, String failure)
            throws IOException, InterruptedException {
        Process capture = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            if (!capture.waitFor(8, TimeUnit.SECONDS) || capture.exitValue() != 0) {
                throw new IOException(failure);
            }
        } finally {
            if (capture.isAlive()) {
                capture.destroyForcibly();
            }
        }
    }

    private void showDesktopFrame(Image image) {
        if (!closed && !desktopFailed) {
            view.setImage(image);
            if (resizeDeadline != 0) {
                boolean changedSize = confirmedFrame == null || requestedViewport.equals(confirmedViewport)
                        || image.getWidth() != confirmedFrame.getWidth()
                        || image.getHeight() != confirmedFrame.getHeight();
                if (changedSize && requestedViewport.matches(image)) {
                    confirmedViewport = requestedViewport;
                    confirmedFrame = image;
                    resizeDeadline = 0;
                } else if (System.nanoTime() >= resizeDeadline) {
                    resizeUnconfirmed = true;
                    resizeDeadline = 0;
                }
            }
            status.setText(resizeDeadline != 0 ? "Waiting for resized Herdr frame…"
                    : resizeUnconfirmed ? "Herdr did not confirm the new size"
                    : desktopClicks ? (clipboardStatus == null
                    ? "Live Herdr Desktop frames · Click and type" : clipboardStatus)
                    : desktopCore == null ? "Live Herdr Desktop frames · Read-only"
                    : "Keyboard target: " + desktopTerminal + (unsupportedDesktopKey
                            ? " · Unsupported character. Press Ctrl+C before submitting."
                            : " · Click preview to type"));
            pending = false;
            if (active) {
                observer.frameShown(image, requestedViewport != null && requestedViewport.equals(confirmedViewport)
                        && viewport().equals(requestedViewport), resizeUnconfirmed, nativeInputs.isEmpty());
            }
        }
    }

    private void showFailure(IOException failure) {
        if (!closed) {
            desktopFailed = true;
            desktopKeys.clear();
            nativeInputs.clear();
            desktopScrollLines = 0;
            timer.stop();
            status.setText(failure.getMessage());
            observer.failed(failure);
        }
    }

}

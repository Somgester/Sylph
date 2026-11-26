import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Point3D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class FrameBridgeDemo extends Application {
    private static final String SMOKE_KEY = "A";
    private static final int CLIPBOARD_LIMIT = 65_536;
    private static final String COPY_MARKER = "SYLPH_COPY_CHECK";
    private static final String PASTE_MARKER = "Sylph paste é";
    private static boolean smokePassed;

    private sealed interface NativeInput permits Click, NativeKey, NativeClipboard { }

    private record Click(double x, double y) implements NativeInput { }

    private record NativeKey(String token) implements NativeInput { }

    // Null text requests a copy. Non-null text is a snapshot of the JavaFX clipboard at keypress.
    private record NativeClipboard(String text) implements NativeInput { }

    private record Request(int width, int height, int x, int y, boolean focused, int key, Click click) { }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("frame-fixture").factory());
    private final StackPane surface = new StackPane();
    private final ImageView view = new ImageView();
    private final Label status = new Label();
    private final ArrayDeque<String> desktopKeys = new ArrayDeque<>();
    private final ArrayDeque<Click> probeClicks = new ArrayDeque<>();
    private final ArrayDeque<NativeInput> nativeInputs = new ArrayDeque<>();
    private String desktopCore;
    private String desktopTerminal;
    private boolean desktopClicks;
    private Click desktopSmokeClick;
    private boolean desktopClickSent;
    private boolean desktopFailed;
    private boolean desktopSmokeStarted;
    private boolean unsupportedDesktopKey;
    private int desktopScrollLines;
    private Image scrollSmokeInitial;
    private Process helper;
    private AnimationTimer timer;
    private PauseTransition smokeTimeout;
    private WritableImage frame;
    private Request shown;
    private boolean pending;
    private boolean closed;
    private int pointerX = -10;
    private int pointerY = -10;
    private int key;
    private int clickSmokeStep;
    private int clipboardSmokeStep;
    private String clipboardStatus;

    @Override
    public void start(Stage stage) throws IOException {
        String desktop = System.getProperty("sylph.herdr.desktop");
        if (desktop != null) {
            startDesktopPreview(stage, desktop);
            return;
        }
        String executable = System.getProperty("sylph.herdr.frameHelper");
        ProcessBuilder command = executable == null
                ? new ProcessBuilder("python3", "render_fixture.py") : new ProcessBuilder(executable);
        helper = command.redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var requests = new BufferedWriter(new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.US_ASCII));
        var frames = new BufferedInputStream(helper.getInputStream());
        TextField source = new TextField();
        source.setPromptText("Click here to type outside the preview");
        surface.setFocusTraversable(true);
        view.setMouseTransparent(true);
        view.fitWidthProperty().bind(surface.widthProperty());
        view.fitHeightProperty().bind(surface.heightProperty());
        surface.getChildren().add(view);
        surface.setOnMousePressed(event -> surface.requestFocus());
        if (executable != null) {
            surface.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()
                        && surface.contains(event.getX(), event.getY())) {
                    if (probeClicks.size() == 32) {
                        showFailure(new IOException("GPUI click queue is full. Reopen the probe."));
                    } else {
                        probeClicks.addLast(new Click(event.getX() / surface.getWidth(),
                                event.getY() / surface.getHeight()));
                    }
                }
            });
        }
        surface.setOnMouseMoved(event -> {
            pointerX = (int) event.getX();
            pointerY = (int) event.getY();
        });
        surface.setOnKeyTyped(event -> {
            if (!event.getCharacter().isEmpty()) {
                key = event.getCharacter().codePointAt(0);
            }
            event.consume();
        });
        BorderPane root = new BorderPane(surface, source, null, status, null);
        stage.setTitle("Sylph / Herdr frame transport experiment");
        stage.setScene(new Scene(root, 720, 440));
        stage.show();
        if (smokeMode()) {
            smokeTimeout = new PauseTransition(Duration.seconds(5));
            smokeTimeout.setOnFinished(event -> Platform.exit());
            smokeTimeout.play();
        }

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (pending) {
                    return;
                }
                Request request = snapshot();
                if (request.click() != null || !request.equals(shown)) {
                    pending = true;
                    worker.execute(() -> exchange(requests, frames, request));
                }
            }
        };
        timer.start();
    }

    private void startDesktopPreview(Stage stage, String executable) {
        desktopCore = System.getProperty("sylph.herdr.core");
        desktopTerminal = System.getProperty("sylph.herdr.terminal");
        desktopClicks = Boolean.getBoolean("sylph.herdr.desktopClicks");
        String smokeCoordinates = System.getProperty("sylph.herdr.desktopSmokeClick");
        if (smokeCoordinates != null) {
            String[] parts = smokeCoordinates.split(",", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Specify smoke click coordinates as normalized x,y.");
            }
            desktopSmokeClick = new Click(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
            if (!(desktopSmokeClick.x() >= 0 && desktopSmokeClick.x() < 1
                    && desktopSmokeClick.y() >= 0 && desktopSmokeClick.y() < 1)) {
                throw new IllegalArgumentException("Smoke click coordinates must be in [0, 1).");
            }
        }
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
            view.setOnMouseClicked(event -> {
                if (!desktopFailed && event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                    var bounds = view.getBoundsInLocal();
                    double x = (event.getX() - bounds.getMinX()) / bounds.getWidth();
                    double y = (event.getY() - bounds.getMinY()) / bounds.getHeight();
                    if (x >= 0 && x < 1 && y >= 0 && y < 1) {
                        queueNativeInput(new Click(x, y));
                        event.consume();
                    }
                }
            });
        }
        view.fitWidthProperty().bind(surface.widthProperty());
        view.fitHeightProperty().bind(surface.heightProperty());
        surface.getChildren().add(view);
        status.setText("Connecting to Herdr Desktop…");
        stage.setTitle("Sylph / Herdr Desktop frame preview");
        stage.setScene(new Scene(new BorderPane(surface, null, null, status, null), 800, 540));
        stage.show();
        timer = new AnimationTimer() {
            private long nextCapture;

            @Override
            public void handle(long now) {
                if (pending || desktopFailed) {
                    return;
                }
                if (!nativeInputs.isEmpty()) {
                    pending = true;
                    if (nativeInputs.peekFirst() instanceof Click click) {
                        nativeInputs.removeFirst();
                        worker.execute(() -> clickDesktop(executable, click));
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
                } else if (now >= nextCapture) {
                    pending = true;
                    nextCapture = now + TimeUnit.MILLISECONDS.toNanos(500);
                    worker.execute(() -> captureDesktop(executable));
                }
            }
        };
        timer.start();
    }

    private void clickDesktop(String executable, Click click) {
        try {
            runDesktopCommand(List.of(executable, "sylph-click", Double.toString(click.x()),
                    Double.toString(click.y())), "Herdr Desktop click failed. Check the patched app and opt-in.");
            Platform.runLater(() -> desktopClickSent = true);
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
                if (closed || desktopFailed) {
                    return;
                }
                pending = false;
                if (copy) {
                    String text = new String(reply, StandardCharsets.UTF_8);
                    clipboardStatus = text.isEmpty() ? "No terminal selection"
                            : writeClipboard(text) ? "Copied terminal selection" : "Could not write to clipboard";
                } else {
                    clipboardStatus = "Pasted text into Herdr";
                }
                status.setText(clipboardStatus);
                if (getParameters().getRaw().contains("--clipboard-smoke")) {
                    finishClipboardSmoke(copy);
                }
            });
        } catch (IOException failure) {
            Platform.runLater(() -> {
                if (!closed && !desktopFailed) {
                    pending = false;
                    clipboardStatus = failure.getMessage();
                    if (smokeMode()) {
                        showFailure(failure);
                    }
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

    private void finishClipboardSmoke(boolean copy) {
        if (copy && clipboardSmokeStep == 1) {
            String copied = Clipboard.getSystemClipboard().getString();
            if (copied != null && copied.contains(COPY_MARKER) && writeClipboard(PASTE_MARKER)) {
                clipboardSmokeStep = 2;
                fireClipboardShortcut(KeyCode.V);
                return;
            }
        } else if (!copy && clipboardSmokeStep == 2) {
            smokePassed = true;
        }
        Platform.exit();
    }

    private void fireClipboardShortcut(KeyCode code) {
        Event.fireEvent(surface, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                true, true, false, false));
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
                if (!closed && !desktopFailed && smokeMode()) {
                    smokePassed = true;
                    Platform.exit();
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
            status.setText(desktopClicks ? (clipboardStatus == null
                    ? "Live Herdr Desktop frames · Click and type" : clipboardStatus)
                    : desktopCore == null ? "Live Herdr Desktop frames · Read-only"
                    : "Keyboard target: " + desktopTerminal + (unsupportedDesktopKey
                            ? " · Unsupported character. Press Ctrl+C before submitting."
                            : " · Click preview to type"));
            pending = false;
            if (getParameters().getRaw().contains("--clipboard-smoke")) {
                if (clipboardSmokeStep == 0) {
                    clipboardSmokeStep = 1;
                    fireClipboardShortcut(KeyCode.C);
                }
            } else if (getParameters().getRaw().contains("--scroll-smoke")) {
                checkScrollFrame(image);
            } else if (desktopSmokeClick != null && !desktopClickSent) {
                var bounds = view.getBoundsInLocal();
                double x = bounds.getMinX() + bounds.getWidth() * desktopSmokeClick.x();
                double y = bounds.getMinY() + bounds.getHeight() * desktopSmokeClick.y();
                firePrimaryClick(view, x, y);
                if (nativeInputs.isEmpty()) {
                    showFailure(new IOException("Smoke click did not reach the preview"));
                } else if (getParameters().getRaw().contains("--native-input-smoke")) {
                    // Queue the draft immediately after the click to catch input reordering.
                    for (String text : List.of("x", "x", "Y_é")) {
                        Event.fireEvent(surface, new KeyEvent(KeyEvent.KEY_TYPED, text, "",
                                KeyCode.UNDEFINED, false, false, false, false));
                    }
                }
            } else if (smokeMode() && desktopCore == null && nativeInputs.isEmpty()) {
                smokePassed = true;
                Platform.exit();
            } else if (smokeMode() && desktopCore != null && !desktopSmokeStarted) {
                desktopSmokeStarted = true;
                // Leave a harmless draft in the test shell. No Enter is sent.
                for (String letter : List.of("x", "x", "Y")) {
                    Event.fireEvent(surface, new KeyEvent(KeyEvent.KEY_TYPED, letter, "",
                            KeyCode.UNDEFINED, false, false, false, false));
                }
            }
        }
    }

    private void checkScrollFrame(Image image) {
        if (scrollSmokeInitial == null) {
            scrollSmokeInitial = image;
            Event.fireEvent(surface, new ScrollEvent(ScrollEvent.SCROLL, 0, 0, 0, 0,
                    false, false, false, false, false, false, 0, 144, 0, 144,
                    ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            return;
        }
        // Ignore tiny redraws such as a blinking caret. The test shell must have filled its scrollback.
        int changed = 0;
        var before = scrollSmokeInitial.getPixelReader();
        var after = image.getPixelReader();
        int height = (int) Math.min(image.getHeight(), scrollSmokeInitial.getHeight());
        int width = (int) Math.min(image.getWidth(), scrollSmokeInitial.getWidth());
        for (int y = 60; y < height; y += 2) {
            for (int x = width / 5; x < width; x += 2) {
                if (before.getArgb(x, y) != after.getArgb(x, y) && ++changed >= 256) {
                    smokePassed = true;
                    Platform.exit();
                    return;
                }
            }
        }
        Platform.exit();
    }

    private void showFailure(IOException failure) {
        if (!closed) {
            desktopFailed = true;
            desktopKeys.clear();
            probeClicks.clear();
            nativeInputs.clear();
            desktopScrollLines = 0;
            timer.stop();
            status.setText(failure.getMessage());
            if (smokeMode()) {
                Platform.exit();
            }
        }
    }

    private Request snapshot() {
        double width = Math.max(1, surface.getWidth());
        double height = Math.max(1, surface.getHeight());
        int frameWidth = Math.min(960, (int) width);
        int frameHeight = Math.min(540, (int) height);
        return new Request(frameWidth, frameHeight, (int) (pointerX * frameWidth / width),
                (int) (pointerY * frameHeight / height), surface.isFocused(), key, probeClicks.pollFirst());
    }

    private void exchange(BufferedWriter requests, BufferedInputStream frames, Request request) {
        try {
            requests.write(String.format(Locale.ROOT, "%d %d %d %d %d %d", request.width(), request.height(),
                    request.x(), request.y(), request.focused() ? 1 : 0, request.key()));
            if (request.click() != null) {
                int x = Math.clamp((int) (request.click().x() * request.width()), 0, request.width() - 1);
                int y = Math.clamp((int) (request.click().y() * request.height()), 0, request.height() - 1);
                requests.write(" " + x + " " + y);
            }
            requests.newLine();
            requests.flush();
            int size = request.width() * request.height() * 4;
            byte[] pixels = frames.readNBytes(size);
            if (pixels.length != size) {
                throw new IOException("Helper ended before sending a complete frame");
            }
            Platform.runLater(() -> present(request, pixels));
        } catch (IOException failure) {
            Platform.runLater(() -> showFailure(failure));
        }
    }

    private void present(Request request, byte[] pixels) {
        if (closed) {
            return;
        }
        if (frame == null || frame.getWidth() != request.width() || frame.getHeight() != request.height()) {
            frame = new WritableImage(request.width(), request.height());
            view.setImage(frame);
        }
        frame.getPixelWriter().setPixels(0, 0, request.width(), request.height(),
                PixelFormat.getByteBgraPreInstance(), pixels, 0, request.width() * 4);
        shown = request;
        pending = false;
        status.setText("%d × %d  |  Focus: %s  |  Last key: %d".formatted(
                request.width(), request.height(), request.focused() ? "preview" : "outside", request.key()));
        if (getParameters().getRaw().contains("--click-smoke")) {
            checkProbeClick();
        } else if (smokeMode()) {
            if (request.key() == 0) {
                Event.fireEvent(surface, new KeyEvent(KeyEvent.KEY_TYPED, SMOKE_KEY, "",
                        KeyCode.UNDEFINED, false, false, false, false));
            } else {
                int expectedBlue = 35 + SMOKE_KEY.codePointAt(0);
                smokePassed = (frame.getPixelReader().getArgb(0, 8) & 0xff) == expectedBlue;
                Platform.exit();
            }
        }
    }

    private void checkProbeClick() {
        // Check a stable pixel in the button, clear of the text and click coordinates.
        int expectedBlue = Math.max(0, clickSmokeStep - 1) * 96;
        if ((frame.getPixelReader().getArgb(120, 50) & 0xff) != expectedBlue) {
            Platform.exit();
            return;
        }
        if (clickSmokeStep == 3) {
            smokePassed = true;
            Platform.exit();
            return;
        }
        double x = clickSmokeStep == 0 ? 160 : 68;
        double y = clickSmokeStep == 0 ? 80 : 40;
        clickSmokeStep++;
        firePrimaryClick(surface, x, y);
    }

    private static void firePrimaryClick(javafx.scene.Node target, double x, double y) {
        Event.fireEvent(target, new MouseEvent(MouseEvent.MOUSE_CLICKED, x, y, x, y,
                MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true,
                new PickResult(target, new Point3D(x, y, 0), 0)));
    }

    private boolean smokeMode() {
        return getParameters().getRaw().contains("--smoke");
    }

    @Override
    public void stop() {
        closed = true;
        if (timer != null) {
            timer.stop();
        }
        if (smokeTimeout != null) {
            smokeTimeout.stop();
        }
        if (helper != null) {
            helper.destroyForcibly();
        }
        worker.shutdownNow();
    }

    public static void main(String[] args) {
        launch(FrameBridgeDemo.class, args);
        if (java.util.List.of(args).contains("--smoke")) {
            String check = List.of(args).contains("--clipboard-smoke")
                    ? "JavaFX Herdr text clipboard round trip"
                    : List.of(args).contains("--native-input-smoke")
                    ? "JavaFX Desktop click and keyboard delivery"
                    : System.getProperty("sylph.herdr.desktopSmokeClick") != null
                    ? "JavaFX Desktop click delivery"
                    : List.of(args).contains("--click-smoke")
                    ? "JavaFX click hit testing in GPUI"
                    : List.of(args).contains("--scroll-smoke")
                    ? "Herdr Desktop scroll and frame refresh in JavaFX"
                    : System.getProperty("sylph.herdr.desktop") == null
                    ? "JavaFX frame and keyboard round trip"
                    : System.getProperty("sylph.herdr.core") == null ? "Herdr Desktop frame in JavaFX"
                    : "Herdr Desktop frame and keyboard delivery in JavaFX";
            if (!smokePassed) {
                throw new IllegalStateException(check + " did not complete");
            }
            System.out.println(check + " passed");
        }
    }
}

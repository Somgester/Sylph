import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

import dev.somgester.sylph.HerdrDesktopView;

public final class FrameBridgeDemo extends Application {
    private static final String SMOKE_KEY = "A";
    private static final String COPY_MARKER = "SYLPH_COPY_CHECK";
    private static final String PASTE_MARKER = "Sylph paste é";
    private static boolean smokePassed;

    private record Click(double x, double y) { }

    private record Request(int width, int height, int x, int y, boolean focused, int key, Click click) { }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("frame-fixture").factory());
    private StackPane surface = new StackPane();
    private ImageView view = new ImageView();
    private final Label status = new Label();
    private final ArrayDeque<Click> probeClicks = new ArrayDeque<>();
    private HerdrDesktopView desktopHost;
    private boolean desktopInputStarted;
    private double initialSmokeWidth = -1;
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
        boolean nativeInput = Boolean.getBoolean("sylph.herdr.desktopClicks");
        String core = System.getProperty("sylph.herdr.core");
        String[] click = smokeCoordinates("sylph.herdr.desktopSmokeClick", 2);
        String[] drag = smokeCoordinates("sylph.herdr.desktopSmokeDrag", 4);
        desktopHost = new HerdrDesktopView(executable, core, System.getProperty("sylph.herdr.terminal"),
                nativeInput, Boolean.getBoolean("sylph.herdr.desktopResize"), new HerdrDesktopView.Observer() {
                    @Override
                    public void frameShown(Image image, boolean resized, boolean resizeUnconfirmed, boolean idle) {
                        observeDesktopFrame(stage, image, core != null, click, drag, resized, resizeUnconfirmed, idle);
                    }

                    @Override
                    public void clipboardCompleted(boolean copy) {
                        if (getParameters().getRaw().contains("--clipboard-smoke")) {
                            finishClipboardSmoke(copy);
                        }
                    }

                    @Override
                    public void fixedKeysCompleted() {
                        if (smokeMode()) {
                            smokePassed = true;
                            Platform.exit();
                        }
                    }

                    @Override
                    public void failed(IOException error) {
                        if (smokeMode()) {
                            System.err.println(error.getMessage());
                            Platform.exit();
                        }
                    }
                });
        surface = (StackPane) desktopHost.lookup("#herdr-desktop-surface");
        view = (ImageView) desktopHost.lookup("#herdr-desktop-frame");
        stage.setTitle("Sylph / Herdr Desktop frame preview");
        stage.setScene(new Scene(desktopHost, 800, 540));
        stage.show();
        if (smokeMode()) {
            smokeTimeout = new PauseTransition(Duration.seconds(12));
            smokeTimeout.setOnFinished(event -> Platform.exit());
            smokeTimeout.play();
        }
        desktopHost.setActive(true);
    }

    private void observeDesktopFrame(Stage stage, Image image, boolean fixedTarget, String[] click, String[] drag,
            boolean resized, boolean resizeUnconfirmed, boolean idle) {
        var arguments = getParameters().getRaw();
        if (arguments.contains("--resize-smoke")) {
            if (resizeUnconfirmed) {
                Platform.exit();
            } else if (resized && initialSmokeWidth < 0) {
                initialSmokeWidth = image.getWidth();
                stage.setWidth(stage.getWidth() + 210);
                stage.setHeight(stage.getHeight() + 60);
            } else if (resized && image.getWidth() != initialSmokeWidth) {
                smokePassed = true;
                Platform.exit();
            }
        } else if (arguments.contains("--clipboard-smoke")) {
            if (clipboardSmokeStep == 0) {
                clipboardSmokeStep = 1;
                fireClipboardShortcut(KeyCode.C);
            }
        } else if (arguments.contains("--scroll-smoke")) {
            checkScrollFrame(image);
        } else if (drag != null && !desktopInputStarted) {
            desktopInputStarted = true;
            fireImagePointer(MouseEvent.MOUSE_PRESSED, parsePoint(drag, 0), true, true);
            fireImagePointer(MouseEvent.MOUSE_RELEASED, parsePoint(drag, 2), false, false);
        } else if (click != null && !desktopInputStarted) {
            desktopInputStarted = true;
            var point = parsePoint(click, 0);
            var bounds = view.getBoundsInLocal();
            firePrimaryClick(view, bounds.getMinX() + bounds.getWidth() * point.x(),
                    bounds.getMinY() + bounds.getHeight() * point.y());
            if (arguments.contains("--native-input-smoke")) {
                typeDraft(List.of("x", "x", "Y_é"));
            }
        } else if (smokeMode() && !fixedTarget && idle) {
            smokePassed = true;
            Platform.exit();
        } else if (smokeMode() && fixedTarget && !desktopInputStarted) {
            desktopInputStarted = true;
            typeDraft(List.of("x", "x", "Y"));
        }
    }

    private void typeDraft(List<String> letters) {
        for (String text : letters) {
            Event.fireEvent(surface, new KeyEvent(KeyEvent.KEY_TYPED, text, "", KeyCode.UNDEFINED,
                    false, false, false, false));
        }
    }

    private static String[] smokeCoordinates(String property, int count) {
        String configured = System.getProperty(property);
        if (configured == null) {
            return null;
        }
        String[] parts = configured.split(",", -1);
        if (parts.length != count) {
            throw new IllegalArgumentException("Incorrect number of normalized pointer coordinates.");
        }
        return parts;
    }

    private static Click parsePoint(String[] fields, int offset) {
        double x = Double.parseDouble(fields[offset]);
        double y = Double.parseDouble(fields[offset + 1]);
        if (!(x >= 0 && x < 1 && y >= 0 && y < 1)) {
            throw new IllegalArgumentException("Pointer coordinates must be in [0, 1).");
        }
        return new Click(x, y);
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

    private void fireImagePointer(javafx.event.EventType<MouseEvent> type, Click point,
            boolean primaryDown, boolean stillSincePress) {
        var bounds = view.getBoundsInLocal();
        double x = bounds.getMinX() + bounds.getWidth() * point.x();
        double y = bounds.getMinY() + bounds.getHeight() * point.y();
        Event.fireEvent(view, new MouseEvent(type, x, y, x, y,
                MouseButton.PRIMARY, 1, false, false, false, false, primaryDown, false, false,
                false, false, stillSincePress, new PickResult(view, new Point3D(x, y, 0), 0)));
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
        if (desktopHost != null) {
            desktopHost.close();
        }
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
            String check = List.of(args).contains("--resize-smoke")
                    ? "JavaFX Herdr viewport reflow"
                    : System.getProperty("sylph.herdr.desktopSmokeDrag") != null
                    ? "JavaFX Herdr drag delivery"
                    : List.of(args).contains("--clipboard-smoke")
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

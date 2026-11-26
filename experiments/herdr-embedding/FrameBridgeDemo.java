import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class FrameBridgeDemo extends Application {
    private static final String SMOKE_KEY = "A";
    private static boolean smokePassed;

    private record Request(int width, int height, int x, int y, boolean focused, int key) { }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("frame-fixture").factory());
    private final StackPane surface = new StackPane();
    private final ImageView view = new ImageView();
    private final Label status = new Label();
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

    @Override
    public void start(Stage stage) throws IOException {
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
                Request request = snapshot();
                if (!pending && !request.equals(shown)) {
                    pending = true;
                    worker.execute(() -> exchange(requests, frames, request));
                }
            }
        };
        timer.start();
    }

    private Request snapshot() {
        double width = Math.max(1, surface.getWidth());
        double height = Math.max(1, surface.getHeight());
        int frameWidth = Math.min(960, (int) width);
        int frameHeight = Math.min(540, (int) height);
        return new Request(frameWidth, frameHeight, (int) (pointerX * frameWidth / width),
                (int) (pointerY * frameHeight / height), surface.isFocused(), key);
    }

    private void exchange(BufferedWriter requests, BufferedInputStream frames, Request request) {
        try {
            requests.write(String.format(Locale.ROOT, "%d %d %d %d %d %d%n", request.width(), request.height(),
                    request.x(), request.y(), request.focused() ? 1 : 0, request.key()));
            requests.flush();
            int size = request.width() * request.height() * 4;
            byte[] pixels = frames.readNBytes(size);
            if (pixels.length != size) {
                throw new IOException("Helper ended before sending a complete frame");
            }
            Platform.runLater(() -> present(request, pixels));
        } catch (IOException failure) {
            Platform.runLater(() -> {
                if (!closed) {
                    timer.stop();
                    status.setText(failure.getMessage());
                    if (smokeMode()) {
                        Platform.exit();
                    }
                }
            });
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
        if (smokeMode()) {
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
            if (!smokePassed) {
                throw new IllegalStateException("Frame and keyboard round trip did not complete");
            }
            System.out.println("JavaFX frame and keyboard round trip passed");
        }
    }
}

package dev.somgester.ide;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class EditorApp extends Application {

    @Override
    public void start(Stage stage) {
        Canvas canvas = new Canvas(900, 600);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        StackPane root = new StackPane(canvas);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());

        Scene scene = new Scene(root, 900, 600);
        stage.setTitle("Somgester");
        stage.setScene(scene);
        stage.show();

        gc.setFill(Color.web("#1e1e1e"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setFill(Color.web("#d4d4d4"));
        gc.setFont(Font.font("Monospaced", 14));
        gc.fillText("Initialized Canvas 0-0", 20, 30);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
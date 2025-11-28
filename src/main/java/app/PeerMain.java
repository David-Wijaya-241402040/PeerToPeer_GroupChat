package main.java.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import main.java.app.peer.PeerController;

import java.awt.*;

public class PeerMain extends Application {
    private PeerController controller;

    @Override
    public void start(Stage stage) throws Exception{
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/main/resources/fxml/LokalpediaChatView.fxml"));
        Parent root = loader.load();

        controller = loader.getController();

        Scene scene = new Scene(root);
        stage.setTitle("Lokalpedia Group Chat");
        stage.setResizable(false);

        Image icon = new Image(getClass().getResourceAsStream("/main/resources/images/lokalicon.png"));
        stage.getIcons().add(icon);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        if(controller != null){
            controller.safeShutdown();
        }
        super.stop();
    }
    public static void main(String[] args) {
        launch();
    }
}
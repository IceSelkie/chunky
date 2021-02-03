/* Copyright (c) 2016-2019 Jesper Öqvist <jesper@llbit.se>
 *
 * This file is part of Chunky.
 *
 * Chunky is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Chunky is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Chunky.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.llbit.chunky.ui;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.stage.PopupWindow;
import se.llbit.chunky.renderer.RenderMode;
import se.llbit.chunky.renderer.RenderStatusListener;
import se.llbit.chunky.renderer.Renderer;
import se.llbit.chunky.renderer.Repaintable;
import se.llbit.chunky.renderer.SceneStatusListener;
import se.llbit.chunky.renderer.scene.Camera;
import se.llbit.math.Vector2;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the current render preview.
 */
public class RenderCanvasFx extends ScrollPane implements Repaintable, SceneStatusListener {
  private static final WritablePixelFormat<IntBuffer> PIXEL_FORMAT =
      PixelFormat.getIntArgbInstance();

  private final se.llbit.chunky.renderer.scene.Scene renderScene;

  private WritableImage image;

  private final AtomicBoolean painting = new AtomicBoolean(false);
  private final Canvas canvas;
  private final Group guideGroup;
  private final StackPane canvasPane;
  private final Renderer renderer;
  private int lastX;
  private int lastY;
  private Vector2 target = new Vector2(0, 0);
  private Tooltip tooltip = new Tooltip();

  private boolean fitToScreen = false;

  private RenderStatusListener renderListener;

  public RenderCanvasFx(se.llbit.chunky.renderer.scene.Scene scene, Renderer renderer) {
    this.renderScene = scene;
    this.renderer = renderer;
    renderer.addSceneStatusListener(this);

    tooltip.setAutoHide(true);
    tooltip.setConsumeAutoHidingEvents(false);
    tooltip.setAnchorLocation(PopupWindow.AnchorLocation.WINDOW_BOTTOM_LEFT);

    canvas = new Canvas();
    synchronized (scene) {
      canvas.setWidth(scene.width);
      canvas.setHeight(scene.height);
      image = new WritableImage(scene.width, scene.height);
    }

    canvasPane = new StackPane(canvas);
    setContent(canvasPane);

    guideGroup = new Group();
    Line hGuide1 = new Line();
    Line hGuide2 = new Line();
    Line vGuide1 = new Line();
    Line vGuide2 = new Line();
    guideGroup.getChildren().addAll(hGuide1, hGuide2, vGuide1, vGuide2);
    canvasPane.getChildren().add(guideGroup);

    hGuide1.setVisible(false);
    hGuide1.setStroke(Color.rgb(0, 0, 0, 0.5));
    hGuide1.setStartX(0);
    hGuide1.endXProperty().bind(canvas.widthProperty());
    hGuide1.startYProperty().bind(canvas.heightProperty().divide(3));
    hGuide1.endYProperty().bind(hGuide1.startYProperty());

    hGuide2.setVisible(false);
    hGuide2.setStroke(Color.rgb(0, 0, 0, 0.5));
    hGuide2.setStartX(0);
    hGuide2.endXProperty().bind(canvas.widthProperty());
    hGuide2.startYProperty().bind(canvas.heightProperty().multiply(2 / 3.0));
    hGuide2.endYProperty().bind(hGuide2.startYProperty());

    vGuide1.setVisible(false);
    vGuide1.setStroke(Color.rgb(0, 0, 0, 0.5));
    vGuide1.setStartY(0);
    vGuide1.endYProperty().bind(canvas.heightProperty());
    vGuide1.startXProperty().bind(canvas.widthProperty().divide(3));
    vGuide1.endXProperty().bind(vGuide1.startXProperty());

    vGuide2.setVisible(false);
    vGuide2.setStroke(Color.rgb(0, 0, 0, 0.5));
    vGuide2.setStartY(0);
    vGuide2.endYProperty().bind(canvas.heightProperty());
    vGuide2.startXProperty().bind(canvas.widthProperty().multiply(2 / 3.0));
    vGuide2.endXProperty().bind(vGuide2.startXProperty());

    canvas.setOnMousePressed(e -> {
      lastX = (int) e.getX();
      lastY = (int) e.getY();
    });

    canvas.setOnMouseDragged(e -> {
      int dx = lastX - (int) e.getX();
      int dy = lastY - (int) e.getY();
      lastX = (int) e.getX();
      lastY = (int) e.getY();
      scene.camera().rotateView((Math.PI / 250) * dx, -(Math.PI / 250) * dy);
    });

    canvas.setOnMouseExited(event -> {
      // Hide the tooltip so that it won't prevent clicking outside the render canvas.
      // This is to work around an OpenJDK bug on Linux.
      tooltip.hide();
    });

    ContextMenu contextMenu = new ContextMenu();
    MenuItem setTarget = new MenuItem("Set target");
    setTarget.setOnAction(e -> {
      scene.camera().setTarget(target.x, target.y);
      if (scene.getMode() == RenderMode.PREVIEW) {
        scene.forceReset();
      }
    });
    CheckMenuItem showGuides = new CheckMenuItem("Show guides");
    showGuides.setSelected(false);
    showGuides.selectedProperty().addListener((observable, oldValue, newValue) -> {
      hGuide1.setVisible(newValue);
      hGuide2.setVisible(newValue);
      vGuide1.setVisible(newValue);
      vGuide2.setVisible(newValue);
    });
    Menu canvasScale = new Menu("Canvas scale");
    ToggleGroup scaleGroup = new ToggleGroup();
    for (int percent : new int[] { 25, 50, 75, 100, 150, 200, 300, 400 }) {
      RadioMenuItem item = new RadioMenuItem(String.format("%d%%", percent));
      item.setToggleGroup(scaleGroup);
      if (percent == 100) {
        item.setSelected(true);
      }
      item.setOnAction(e -> {
        updateCanvasScale(percent / 100.0);
        fitToScreen = false;
      });
      canvasScale.getItems().add(item);
    }

    RadioMenuItem item = new RadioMenuItem("Fit to Screen");
    item.setToggleGroup(scaleGroup);
    item.setOnAction(e -> {
      fitToScreen = true;
      updateCanvasFit();
    });
    canvasScale.getItems().add(item);

    contextMenu.getItems().addAll(setTarget, showGuides, canvasScale);

    canvas.setOnMouseClicked(event -> {
      if (event.getButton() == MouseButton.SECONDARY) {
        double invHeight = 1.0 / canvas.getHeight();
        double halfWidth = canvas.getWidth() / (2.0 * canvas.getHeight());
        target.set(-halfWidth + event.getX() * invHeight,
            -0.5 + event.getY() * invHeight);
        contextMenu.show(getScene().getWindow(), event.getScreenX(), event.getScreenY());
      }
    });
    canvas.setOnScroll(e -> {
      double diff = -e.getDeltaY() / e.getMultiplierY();
      Camera camera = scene.camera();
      double value = camera.getFov();
      double scale = camera.getMaxFoV() - camera.getMinFoV();
      double offset = value / scale;
      double newValue = scale * Math.exp(Math.log(offset) + 0.1 * diff);
      if (!Double.isNaN(newValue) && !Double.isInfinite(newValue)) {
        camera.setFoV(newValue);
      }
    });
    renderer.setCanvas(this);

    viewportBoundsProperty().addListener((observable, oldValue, newValue) -> updateCanvasPane());

    // Register key event listener for keyboard navigation.
    canvasPane.setFocusTraversable(true);

    // TODO(llbit): find better way of preventing the tab header from stealing focus?
    // The canvas pane needs focus or else it will not receive keyboard events.
    canvasPane.setOnMouseDragged(event -> {
      if (!canvasPane.isFocused()) {
        canvasPane.requestFocus();
      }
    });
    canvasPane.setOnMouseClicked(event -> canvasPane.requestFocus());

    canvasPane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (!isVisible()) {
        return;
      }
      double modifier = 1;
      if (e.isControlDown()) {
        modifier *= 100;
      }
      if (e.isShiftDown()) {
        modifier *= 0.1;
      }
      switch (e.getCode()) {
        case ESCAPE:
          // TODO
          //hide();
          e.consume();
          break;
        case W:
          renderScene.camera().moveForward(modifier);
          e.consume();
          break;
        case S:
          renderScene.camera().moveBackward(modifier);
          e.consume();
          break;
        case A:
          renderScene.camera().strafeLeft(modifier);
          e.consume();
          break;
        case D:
          renderScene.camera().strafeRight(modifier);
          e.consume();
          break;
        case R:
          renderScene.camera().moveUp(modifier);
          e.consume();
          break;
        case F:
          renderScene.camera().moveDown(modifier);
          e.consume();
          break;
        case J:
          renderScene.camera().moveBackward(modifier);
          e.consume();
          break;
        case K:
          renderScene.camera().moveForward(modifier);
          e.consume();
          break;
        case SPACE:
          synchronized (renderScene) {
            if (renderScene.getMode() == RenderMode.RENDERING) {
              renderScene.pauseRender();
            } else {
              renderScene.startRender();
            }
            renderListener.renderStateChanged(renderScene.getMode());
          }
          e.consume();
          break;
      }
    });
  }

  private void updateCanvasPane() {
    Bounds bounds = getViewportBounds();
    canvasPane.setMinWidth(Math.max(canvas.getWidth() * canvas.getScaleX(), bounds.getWidth()));
    canvasPane.setMinHeight(Math.max(canvas.getHeight() * canvas.getScaleY(), bounds.getHeight()));
  }

  private void updateCanvasScale(double scale) {
    canvas.setScaleX(scale);
    canvas.setScaleY(scale);
    updateCanvasPane();
    guideGroup.setScaleX(scale);
    guideGroup.setScaleY(scale);
    updateCanvasScroll();
  }

  private void updateCanvasScroll() {
    // Force layout to occur or else scroll might not work
    layout();

    if (fitToScreen) {
      setVvalue((getVmin() + getVmax())/2);
      setHvalue((getHmin() + getHmax())/2);
    } else {
      double scaleX = canvas.getScaleX();
      double scrollX = getHvalue();

      if (scrollX > scaleX/2 + 0.5) {
        setHvalue(scaleX/2 + 0.5);
      } else if (scrollX < -scaleX/2 + 0.5) {
        setHvalue(-scaleX/2 + 0.5);
      }

      double scaleY = canvas.getScaleY();
      double scrollY = getVvalue();

      if (scrollY > scaleY/2 + 0.5) {
        setVvalue(scaleY/2 + 0.5);
      } else if (scrollY < -scaleY/2 + 0.5) {
        setVvalue(-scaleY/2 + 0.5);
      }
    }
  }

  private void updateCanvasFit() {
    if (!fitToScreen) {
      return;
    }

    double width = canvas.getWidth();
    double height = canvas.getHeight();
    double fitWidth = canvasPane.getMinWidth();
    double fitHeight = canvasPane.getMinHeight();

    double scaleX = fitWidth / width;
    double scaleY = fitHeight / height;

    updateCanvasScale(Math.min(scaleX, scaleY) * 0.95);
  }

  @Override public void repaint() {
    if (painting.compareAndSet(false, true)) {
      forceRepaint();
    }
  }

  public void forceRepaint() {
    painting.set(true);
    renderer.withBufferedImage(bitmap -> {
      if (bitmap.width == (int) image.getWidth()
          && bitmap.height == (int) image.getHeight()) {
        image.getPixelWriter().setPixels(0, 0, bitmap.width, bitmap.height, PIXEL_FORMAT,
            bitmap.data, 0, bitmap.width);
      }
    });
    Platform.runLater(() -> {
      GraphicsContext gc = canvas.getGraphicsContext2D();
      gc.drawImage(image, 0, 0);
      painting.set(false);
    });
  }

  public void setRenderListener(RenderStatusListener renderListener) {
    this.renderListener = renderListener;
  }

  @Override public void sceneStatus(String status) {
    Platform.runLater(() -> {
      Point2D offset = localToScene(0, 0);
      tooltip.setText(status);
      tooltip.show(this,
          offset.getX() + getScene().getX() + getScene().getWindow().getX(),
          offset.getY() + getScene().getY() + getScene().getWindow().getY() + getHeight());
    });
  }

  /**
   * Should only be called on the JavaFX application thread.
   */
  public void setCanvasSize(int width, int height) {
    canvas.setWidth(width);
    canvas.setHeight(height);
    if (image == null || width != image.getWidth() || height != image.getHeight()) {
      image = new WritableImage(width, height);
    }

    if (fitToScreen) {
      updateCanvasFit();
    } else {
      updateCanvasScale(canvas.getScaleX());
    }
  }
}

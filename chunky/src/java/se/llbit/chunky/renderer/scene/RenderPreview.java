/* Copyright (c) 2012-2021 Jesper Öqvist <jesper@llbit.se>
 * Copyright (c) 2012-2021 Chunky contributors
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
package se.llbit.chunky.renderer.scene;

import org.apache.commons.math3.util.FastMath;
import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.renderer.RenderState;
import se.llbit.chunky.renderer.WorkerState;
import se.llbit.log.Log;
import se.llbit.math.ColorUtil;
import se.llbit.math.QuickMath;
import se.llbit.math.Ray;
import se.llbit.util.BitmapImage;
import se.llbit.util.TaskTracker;
import se.llbit.util.file.OutputMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class RenderPreview {

  protected final Scene scene;
  /** Preview frame interlacing counter. */
  public int previewCount;
  /** This is the 8-bit channel frame buffer. */
  protected BitmapImage previewDoubleBufferFrontShow;
  protected BitmapImage previewDoubleBufferBackStaging;
  protected boolean shouldFinalizeBuffer = false;
  protected boolean finalized = false;

  //Rendered Stuff
  /**
   * HDR sample buffer for the render output.
   *
   * <p>Note: the sample buffer is initially null, it is only
   * initialized if the scene will be used for rendering. This avoids allocating new sample buffers each time we want to
   * copy the scene state to a temporary scene.
   *
   * <p>TODO: render buffers (sample buffer, alpha channel, etc.)
   * should really be moved somewhere else and not be so tightly coupled to the scene settings.
   */
  protected double[] sampleBuffer;

  public RenderPreview(Scene scene) {
    this.scene = scene;
  }

  public void initBuffers() {
    previewDoubleBufferFrontShow = new BitmapImage(scene.width, scene.height);
    previewDoubleBufferBackStaging = new BitmapImage(scene.width, scene.height);
    scene.sceneSaver.alphaChannelPng = new byte[scene.width * scene.height];
  }

  /**
   * Prepare the front buffer for rendering by flipping the back and front buffer.
   */
  public synchronized void swapBuffers() {
    finalized = false;
    BitmapImage tmp = previewDoubleBufferFrontShow;
    previewDoubleBufferFrontShow = previewDoubleBufferBackStaging;
    previewDoubleBufferBackStaging = tmp;
  }





  /**
   * Compute the alpha channel.
   */
  protected void computeAlpha(TaskTracker progress, int threadCount) {
    if (scene.transparentSky) {
      if (scene.sceneSaver.outputMode == OutputMode.TIFF_32 || scene.sceneSaver.outputMode == OutputMode.PFM) {
        Log.warn("Can not use transparent sky with TIFF or PFM output modes. Use PNG instead.");
      }
      else {
        try (TaskTracker.Task task = progress.task("Computing alpha channel")) {
          ExecutorService executor = Executors.newFixedThreadPool(threadCount);
          AtomicInteger done = new AtomicInteger(0);
          int colWidth = scene.width / threadCount;
          for (int x = 0; x < scene.width; x += colWidth) {
            final int currentX = x;
            executor.submit(() -> {
              WorkerState state = new WorkerState();
              state.ray = new Ray();
              for (int xc = currentX; xc < currentX + colWidth && xc < scene.width; xc++) {
                for (int y = 0; y < scene.height; ++y) {
                  computeAlpha(xc, y, state);
                }
                task.update(scene.width, done.incrementAndGet());
              }
            });
          }
          executor.shutdown();
          executor.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
          Log.warn("Failed to compute alpha channel", e);
        }
      }
    }
  }

  /**
   * Post-process all pixels in the current frame.
   * <p>
   * This is normally done by the render workers during rendering, but in some cases an separate post processing pass is
   * needed.
   */
  public void postProcessFrame(TaskTracker progress) {
    try (TaskTracker.Task task = progress.task("Finalizing frame")) {
      int threadCount = PersistentSettings.getNumThreads();
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      AtomicInteger done = new AtomicInteger(0);
      int colWidth = scene.width / threadCount;
      for (int x = 0; x < scene.width; x += colWidth) {
        final int currentX = x;
        executor.submit(() -> {
          for (int xc = currentX; xc < currentX + colWidth && xc < scene.width; xc++) {
            for (int y = 0; y < scene.height; ++y) {
              finalizePixel(xc, y);
            }
            task.update(scene.width, done.incrementAndGet());
          }
        });
      }
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      Log.error("Finalizing frame failed", e);
    }
  }

  /**
   * Finalize a pixel. Calculates the resulting RGB color values for the pixel and sets these in the bitmap image.
   */
  public void finalizePixel(int x, int y) {
    finalized = true;
    double[] result = new double[3];
    postProcessPixel(x, y, result);
    previewDoubleBufferBackStaging.data[y * scene.width + x] = ColorUtil
          .getRGB(QuickMath.min(1, result[0]), QuickMath.min(1, result[1]),
                QuickMath.min(1, result[2]));
  }

  /**
   * Postprocess a pixel. This applies gamma correction and clamps the color value to [0,1].
   *
   * @param result the resulting color values are written to this array
   */
  public void postProcessPixel(int x, int y, double[] result) {
    double r = sampleBuffer[(y * scene.width + x) * 3 + 0];
    double g = sampleBuffer[(y * scene.width + x) * 3 + 1];
    double b = sampleBuffer[(y * scene.width + x) * 3 + 2];

    r *= scene.exposure;
    g *= scene.exposure;
    b *= scene.exposure;

    if (scene.mode != RenderState.PREVIEW) {
      switch (scene.postprocess) {
        case NONE:
          break;
        case TONEMAP1:
          // http://filmicworlds.com/blog/filmic-tonemapping-operators/
          r = QuickMath.max(0, r - 0.004);
          r = (r * (6.2 * r + .5)) / (r * (6.2 * r + 1.7) + 0.06);
          g = QuickMath.max(0, g - 0.004);
          g = (g * (6.2 * g + .5)) / (g * (6.2 * g + 1.7) + 0.06);
          b = QuickMath.max(0, b - 0.004);
          b = (b * (6.2 * b + .5)) / (b * (6.2 * b + 1.7) + 0.06);
          break;
        case TONEMAP2:
          // https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/
          float aces_a = 2.51f;
          float aces_b = 0.03f;
          float aces_c = 2.43f;
          float aces_d = 0.59f;
          float aces_e = 0.14f;
          r = QuickMath.max(QuickMath.min((r * (aces_a * r + aces_b)) / (r * (aces_c * r + aces_d) + aces_e), 1), 0);
          g = QuickMath.max(QuickMath.min((g * (aces_a * g + aces_b)) / (g * (aces_c * g + aces_d) + aces_e), 1), 0);
          b = QuickMath.max(QuickMath.min((b * (aces_a * b + aces_b)) / (b * (aces_c * b + aces_d) + aces_e), 1), 0);
          r = FastMath.pow(r, 1 / Scene.DEFAULT_GAMMA);
          g = FastMath.pow(g, 1 / Scene.DEFAULT_GAMMA);
          b = FastMath.pow(b, 1 / Scene.DEFAULT_GAMMA);
          break;
        case TONEMAP3:
          // http://filmicworlds.com/blog/filmic-tonemapping-operators/
          float hA = 0.15f;
          float hB = 0.50f;
          float hC = 0.10f;
          float hD = 0.20f;
          float hE = 0.02f;
          float hF = 0.30f;
          // This adjusts the exposure by a factor of 16 so that the resulting exposure approximately matches the other
          // post-processing methods. Without this, the image would be very dark.
          r *= 16;
          g *= 16;
          b *= 16;
          r = ((r * (hA * r + hC * hB) + hD * hE) / (r * (hA * r + hB) + hD * hF)) - hE / hF;
          g = ((g * (hA * g + hC * hB) + hD * hE) / (g * (hA * g + hB) + hD * hF)) - hE / hF;
          b = ((b * (hA * b + hC * hB) + hD * hE) / (b * (hA * b + hB) + hD * hF)) - hE / hF;
          float hW = 11.2f;
          float whiteScale =
                1.0f / (((hW * (hA * hW + hC * hB) + hD * hE) / (hW * (hA * hW + hB) + hD * hF)) - hE / hF);
          r *= whiteScale;
          g *= whiteScale;
          b *= whiteScale;
          break;
        case GAMMA:
          r = FastMath.pow(r, 1 / Scene.DEFAULT_GAMMA);
          g = FastMath.pow(g, 1 / Scene.DEFAULT_GAMMA);
          b = FastMath.pow(b, 1 / Scene.DEFAULT_GAMMA);
          break;
      }
    }
    else {
      r = FastMath.sqrt(r);
      g = FastMath.sqrt(g);
      b = FastMath.sqrt(b);
    }

    result[0] = r;
    result[1] = g;
    result[2] = b;
  }

  /**
   * Compute the alpha channel based on sky visibility.
   */
  public void computeAlpha(int x, int y, WorkerState state) {
    Ray ray = state.ray;
    double halfWidth = scene.width / (2.0 * scene.height);
    double invHeight = 1.0 / scene.height;

    // Rotated grid supersampling.

    scene.camera.calcViewRay(ray, -halfWidth + (x - 3 / 8.0) * invHeight, -.5 + (y + 1 / 8.0) * invHeight);
    ray.o.x -= scene.origin.x;
    ray.o.y -= scene.origin.y;
    ray.o.z -= scene.origin.z;

    double occlusion = PreviewRayTracer.skyOcclusion(scene, state);

    scene.camera.calcViewRay(ray, -halfWidth + (x + 1 / 8.0) * invHeight, -.5 + (y + 3 / 8.0) * invHeight);
    ray.o.x -= scene.origin.x;
    ray.o.y -= scene.origin.y;
    ray.o.z -= scene.origin.z;

    occlusion += PreviewRayTracer.skyOcclusion(scene, state);

    scene.camera.calcViewRay(ray, -halfWidth + (x - 1 / 8.0) * invHeight, -.5 + (y - 3 / 8.0) * invHeight);
    ray.o.x -= scene.origin.x;
    ray.o.y -= scene.origin.y;
    ray.o.z -= scene.origin.z;

    occlusion += PreviewRayTracer.skyOcclusion(scene, state);

    scene.camera.calcViewRay(ray, -halfWidth + (x + 3 / 8.0) * invHeight, -.5 + (y - 1 / 8.0) * invHeight);
    ray.o.x -= scene.origin.x;
    ray.o.y -= scene.origin.y;
    ray.o.z -= scene.origin.z;

    occlusion += PreviewRayTracer.skyOcclusion(scene, state);

    scene.sceneSaver.alphaChannelPng[y * scene.width + x] = (byte)(255 * occlusion * 0.25 + 0.5);
  }

  /**
   * Copies a pixel in-buffer.
   */
  public void copyPixel(int jobId, int offset) {
    previewDoubleBufferBackStaging.data[jobId + offset] = previewDoubleBufferBackStaging.data[jobId];
  }


  /**
   * Call the consumer with the current front frame buffer.
   */
  public synchronized void withBufferedImage(Consumer<BitmapImage> consumer) {
    if (previewDoubleBufferFrontShow != null) {
      consumer.accept(previewDoubleBufferFrontShow);
    }
  }

}

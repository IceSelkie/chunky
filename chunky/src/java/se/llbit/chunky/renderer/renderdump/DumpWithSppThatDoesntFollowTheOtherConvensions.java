/* Copyright (c) 2021 Chunky contributors
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
package se.llbit.chunky.renderer.renderdump;

import se.llbit.chunky.renderer.scene.SampleBuffer;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.math.IntBoundingBox;
import se.llbit.util.TaskTracker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

/**
 * This is a dump format for chunky that should allow for storing of SPP per pixel in addition to actual sample values.
 *
 * TODO: Allow for only storing partial regions and/or defining regions of SPPs.
 * TODO:  - Possibly deflate + huffman coding of spp values? That should have very good compression
 * TODO:    (~<50 bytes for all but the antithetical cases)
 */
class DumpWithSppThatDoesntFollowTheOtherConvensions extends DumpFormat {

  public static final DumpFormat INSTANCE = new DumpWithSppThatDoesntFollowTheOtherConvensions(true);
  public static final DumpFormat INSTANCE_NO_DEFLATE = new DumpWithSppThatDoesntFollowTheOtherConvensions(false);

  // low overhead + large space saved.
  boolean deflateSpp; // TODO

  private DumpWithSppThatDoesntFollowTheOtherConvensions(boolean deflateSpp) {
    this.deflateSpp = deflateSpp;
  }

  @Override
  public void save(DataOutputStream outputStream, Scene scene, TaskTracker taskTracker) throws IOException {
    IntBoundingBox pixelRange;
    SampleBuffer samples = scene.getSampleBuffer();

    //****HEADER****//
    try (TaskTracker.Task task = taskTracker.task("Saving render dump - Header", 5)) {
      pixelRange = calculateRange(scene.getSampleBuffer(), task);
      task.update(4);

      // TODO: save hash of the scene
      //      outputStream.writeInt(scene.toJson().toString().hashCode());
      task.update(5);

      // Render size:
      outputStream.writeInt(scene.renderWidth());
      outputStream.writeInt(scene.renderHeight());
      // Saved region's dimensions:
      outputStream.writeInt(pixelRange.xmin);
      outputStream.writeInt(pixelRange.zmin);
      outputStream.writeInt(pixelRange.widthX());
      outputStream.writeInt(pixelRange.widthZ());
      // Scene spp range & render time
      outputStream.writeInt(scene.spp); // TODO: store spp min and max instead of only one value
      outputStream.writeInt(scene.spp); // TODO: store spp min and max instead of only one value
      outputStream.writeLong(scene.renderTime);
    }

    //****SAMPLES****//
    FloatingPointCompressor.CompressionStream compressionStream =
        new FloatingPointCompressor.CompressionStream((long) pixelRange.widthX() * pixelRange.widthZ(), outputStream);
    try (TaskTracker.Task task = taskTracker.task("Saving render dump - Samples", pixelRange.widthX() * pixelRange.widthZ())){
      for (int y = pixelRange.zmin; y < pixelRange.zmax; y++)
        for (int x = pixelRange.xmin; x < pixelRange.xmax; x++) {
          compressionStream.writePixel(samples, x, y);
//          task.update(x * pixelRange.widthX() + y);
        }
    }
    outputStream.flush();

    //****SPP****//
    try (TaskTracker.Task task = taskTracker.task("Saving render dump - Sample Count", pixelRange.widthZ())) {
//    compressionStream = DeflateCompressionStream;
//    if (deflateSpp)
      for (int y = pixelRange.zmin; y < pixelRange.zmax; y++) {
        for (int x = pixelRange.xmin; x < pixelRange.xmax; x++) {
          outputStream.writeInt(samples.getSpp(x, y));
        }
//        task.update(y-pixelRange.zmin);
      }
    }

    outputStream.close();
  }

  @Override
  public void load(DataInputStream inputStream, Scene scene, TaskTracker taskTracker) throws IOException {

    SampleBuffer samples = scene.getSampleBuffer();
    int xmin;
    int ymin;
    int xmax;
    int ymax;

    try (TaskTracker.Task task = taskTracker.task("Loading render dump - Header", scene.renderWidth() * scene.renderHeight())) {
      int renderWidth = inputStream.readInt();
      int renderHeight = inputStream.readInt();

      if (renderWidth != scene.renderWidth() || renderHeight != scene.renderHeight()) {
        throw new IllegalStateException("Scene size does not match dump size");
      }

      xmin = inputStream.readInt();
      ymin = inputStream.readInt();
      xmax = xmin + inputStream.readInt();
      ymax = ymin + inputStream.readInt();

      int sppmin = inputStream.readInt();
      scene.spp = sppmin; // TODO: store spp min and max instead of only one value
      int sppmax = inputStream.readInt();
      scene.spp = sppmax; // TODO: store spp min and max instead of only one value

      scene.renderTime = inputStream.readLong();
    }

    int width = ymax - ymin;
    FloatingPointCompressor.DecompressionStream decompressionStream =
        new FloatingPointCompressor.DecompressionStream((long) width * (xmax - xmin), inputStream);
    try (TaskTracker.Task task = taskTracker.task("Loading render dump - Samples", scene.renderWidth() * scene.renderHeight())) {
      for (int y = ymin; y < ymax; y++)
        for (int x = xmin; x < xmax; x++) {
          samples.setPixel(x,y,inputStream.readDouble(),inputStream.readDouble(),inputStream.readDouble());
//          decompressionStream.readPixel(samples, x, y);
//          task.update(x * width + y);
        }
    }
    try (TaskTracker.Task task = taskTracker.task("Loading render dump - SPP", scene.renderWidth() * scene.renderHeight())) {
      for (int y = ymin; y < ymax; y++)
        for (int x = xmin; x < xmax; x++) {
          samples.setSpp(x, y, inputStream.readInt());
//          task.update(x * width + y);
        }
    }

    decompressionStream.close();
  }






  private IntBoundingBox calculateRange(SampleBuffer sampleBuffer, TaskTracker.Task task) {

    // On a full render, each loop will exit on first index tested, and will complete in constant time.

    // Find highest pixel...
    IntBoundingBox ret = new IntBoundingBox();
    boolean done = false;
    for (int y = 0; y < sampleBuffer.rowCountSpp && !done; y++)
      for (int x = 0; x < sampleBuffer.rowSizeSpp && !done; x++)
        if (sampleBuffer.getSpp(x, y)>0) {
          ret.include(x, y);
          done = true;
        }
    task.update(1);

    // If no pixel found, dump can be empty.
    // TODO: if maxSPP in scene is 0, can return this directly without traversal.
    if (ret.equals(new IntBoundingBox()))
      return ret.include(0,0);

    // Find lowest pixel...
    done=false;
    for (int y = sampleBuffer.rowCountSpp-1; y >= 0 && !done; y--)
      for (int x = 0; x < sampleBuffer.rowSizeSpp && !done; x++)
        if (sampleBuffer.getSpp(x, y)>0) {
          ret.include(x, y);
          done = true;
        }
    task.update(2);

    // Find leftmost pixel...
    done = false;
    for (int x = 0; x < sampleBuffer.rowSizeSpp && !done; x++)
      for (int y = 0; y < sampleBuffer.rowCountSpp && !done; y++)
        if (sampleBuffer.getSpp(x, y)>0) {
          ret.include(x, y);
          done = true;
        }
    task.update(3);

    // Find rightmost pixel...
    done = false;
    for (int x = sampleBuffer.rowSizeSpp-1; x >= 0 && !done; x--)
      for (int y = 0; y < sampleBuffer.rowCountSpp && !done; y++)
        if (sampleBuffer.getSpp(x, y)>0) {
          ret.include(x, y);
          done = true;
        }

    // Move x2 and y2 to be exclusive limits.
    ret.addMax(1);
    return ret;
  }


  @Override
  protected void readSamples(DataInputStream inputStream, Scene scene, PixelConsumer consumer, LongConsumer pixelProgress) throws IOException {
    try{throw new IllegalStateException("This shouldn't be getting called!");}
    catch(IllegalStateException e){e.printStackTrace();}
    throw new IllegalStateException("This shouldn't be getting called!");
  }

  @Override
  protected void writeSamples(DataOutputStream outputStream, Scene scene, LongConsumer pixelProgress) throws IOException {
    try{throw new IllegalStateException("This shouldn't be getting called!");}
    catch(IllegalStateException e){e.printStackTrace();}
    throw new IllegalStateException("This shouldn't be getting called!");
  }
}

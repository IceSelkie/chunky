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

import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.entity.Entity;
import se.llbit.chunky.entity.PlayerEntity;
import se.llbit.chunky.renderer.*;
import se.llbit.chunky.renderer.projection.ProjectionMode;
import se.llbit.chunky.world.ChunkPosition;
import se.llbit.chunky.world.EmptyWorld;
import se.llbit.chunky.world.World;
import se.llbit.json.*;
import se.llbit.log.Log;
import se.llbit.math.Grid;
import se.llbit.math.PackedOctree;
import se.llbit.util.FloatingPointCompressor;
import se.llbit.util.TaskTracker;
import se.llbit.util.file.OctreeFileFormat;
import se.llbit.util.file.OutputMode;
import se.llbit.util.file.ZipExport;
import se.llbit.util.file.image.PfmFileWriter;
import se.llbit.util.file.image.TiffFileWriter;
import se.llbit.util.file.image.png.ITXT;
import se.llbit.util.file.image.png.PngFileWriter;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class SceneSaver {

  public static final String EXTENSION = ".json";

  /** The current Scene Description Format (SDF) version. */
  public static final int SDF_VERSION = 9;
  public int sdfVersion = -1;

  private static final int DUMP_FORMAT_VERSION = 1;
  public static final byte[] DUMP_FORMAT_MAGIC_NUMBER = {0x44, 0x55, 0x4D, 0x50};

  protected byte[] alphaChannelPng;
  public OutputMode outputMode = OutputMode.DEFAULT;





  // Static Methods:
  /**
   * Delete all scene files from the scene directory, leaving only
   * snapshots untouched.
   */
  public static void delete(String name, File sceneDir) {
    String[] extensions = {
        ".json", ".dump", ".octree2", ".foliage", ".grass", ".json.backup", ".dump.backup",
    };
    for (String extension : extensions) {
      File file = new File(sceneDir, name + extension);
      if (file.isFile()) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      }
    }
  }

  /**
   * Export the scene to a zip file.
   */
  public static void exportToZip(String name, File targetFile) {
    String[] extensions = { ".json", ".dump", ".octree2", ".foliage", ".grass", };
    ZipExport.zip(targetFile, SynchronousSceneManager.resolveSceneDirectory(name), name, extensions);
  }

  /**
   * @return a list of available scene description files in the given scene
   * directory
   */
  public static List<File> getAvailableSceneFiles(File sceneDir) {
    //Get all the files with either a .json extension or get all directories in the given folder since scenes can be held in directories now.
    File[] sceneList = sceneDir.listFiles((dir, name) ->  name.endsWith(EXTENSION) || new File(dir, name).isDirectory());
    if (sceneList == null) {
      return Collections.emptyList();
    }

    List<File> sceneFiles = new ArrayList<>();
    for (File file : sceneList) {
      //If the file was a directory, we just run this method on that folder, otherwise, we know it's a json file, so we add it to the "sceneFiles" list
      if (file.isDirectory()) {
        sceneFiles.addAll(getAvailableSceneFiles(file));
      } else {
        sceneFiles.add(file);
      }
    }
    return sceneFiles;
  }




  protected final Scene scene;

  public SceneSaver(Scene scene) {
    this.scene = scene;
  }




  /**
   * Save the scene description, render dump, and foliage
   * and grass textures.
   *
   * @throws IOException
   */
  public synchronized void saveScene(RenderContext context, TaskTracker taskTracker) throws IOException {
    try (TaskTracker.Task task = taskTracker.task("Saving scene", 2)) {
      task.update(1);

      try (BufferedOutputStream out = new BufferedOutputStream(context.getSceneDescriptionOutputStream(scene.name))) {
        saveDescription(out);
      }

      saveOctree(context, taskTracker);
      saveDump(context, taskTracker);
      saveEmitterGrid(context, taskTracker);
    }
  }

  /**
   * Load a stored scene by file name.
   *
   * @param sceneName file name of the scene to load
   */
  public synchronized void loadScene(RenderContext context, String sceneName, TaskTracker taskTracker) throws IOException
  {
    loadDescription(context.getSceneDescriptionInputStream(sceneName));

    if (sdfVersion < SDF_VERSION) {
      Log.warn("Old scene version detected! The scene may not have been loaded correctly.");
    } else if (sdfVersion > SDF_VERSION) {
      Log.warn(
          "This scene was created with a newer version of Chunky! The scene may not have been loaded correctly.");
    }

    // Load the configured skymap file.
    scene.sky.loadSkymap();

    if (!scene.worldPath.isEmpty()) {
      File worldDirectory = new File(scene.worldPath);
      if (World.isWorldDir(worldDirectory)) {
        scene.loadedWorld = World.loadWorld(worldDirectory, scene.worldDimension, World.LoggedWarnings.NORMAL);
      } else {
        Log.info("Could not load world: " + scene.worldPath);
      }
    }

    if (loadDump(context, taskTracker)) {
      scene.render.postProcessFrame(taskTracker);
    }

    if (scene.spp == 0) {
      scene.mode = RenderState.PREVIEW;
    } else if (scene.mode == RenderState.RENDERING) {
      scene.mode = RenderState.PAUSED;
    }

    boolean emitterGridNeedChunkReload = false;
    if(scene.emitterSamplingStrategy != EmitterSamplingStrategy.NONE)
      emitterGridNeedChunkReload = !loadEmitterGrid(context, taskTracker);
    boolean octreeLoaded = loadOctree(context, taskTracker);
    if (emitterGridNeedChunkReload || !octreeLoaded) {
      // Could not load stored octree or emitter grid.
      // Load the chunks from the world.
      if (scene.loadedWorld == EmptyWorld.INSTANCE) {
        Log.warn("Could not load chunks (no world found for scene)");
      } else {
        scene.loadChunks(taskTracker, scene.loadedWorld, scene.chunks);
      }
    }

    notifyAll();
  }

  /**
   * Save a snapshot
   */
  public void saveSnapshot(File directory, TaskTracker progress, int threadCount) {
    if (directory == null) {
      Log.error("Can't save snapshot: bad output directory!");
      return;
    }
    String fileName = String.format("%s-%d%s", scene.name, scene.spp, outputMode.getExtension());
    File targetFile = new File(directory, fileName);
    if (!directory.exists()) directory.mkdirs();
    scene.render.computeAlpha(progress, threadCount);
    if (!scene.render.finalized) {
      scene.render.postProcessFrame(progress);
    }
    writeImage(targetFile, outputMode, progress);
  }

  /**
   * Save the current frame as a PNG or TIFF image, depending on this scene's outputMode.
   */
  public synchronized void saveFrame(File targetFile, TaskTracker progress, int threadCount) {
    this.saveFrame(targetFile, outputMode, progress, threadCount);
  }

  /**
   * Save the current frame as a PNG or TIFF image.
   */
  public synchronized void saveFrame(File targetFile, OutputMode mode, TaskTracker progress, int threadCount) {
    scene.render.computeAlpha(progress, threadCount);
    if (!scene.render.finalized) {
      scene.render.postProcessFrame(progress);
    }
    writeImage(targetFile, mode, progress);
  }

  /**
   * Save the current frame as a PNG or TIFF image into the given output stream.
   */
  public synchronized void writeFrame(OutputStream out, OutputMode mode, TaskTracker progress, int threadCount) throws IOException {
    scene.render.computeAlpha(progress, threadCount);
    if (!scene.render.finalized) {
      scene.render.postProcessFrame(progress);
    }
    writeImage(out, mode, progress);
  }

  /**
   * Write buffer data to image.
   *
   * @param out output stream to write to.
   */
  private void writeImage(OutputStream out, OutputMode mode, TaskTracker progress) throws IOException {
    if (mode == OutputMode.PNG) {
      writePng(out, progress);
    } else if (mode == OutputMode.TIFF_32) {
      writeTiff(out, progress);
    } else if (mode == OutputMode.PFM) {
      writePfm(out, progress);
    } else {
      Log.warn("Unknown Output Type");
    }
  }

  private void writeImage(File targetFile, OutputMode mode, TaskTracker progress) {
    try (FileOutputStream out = new FileOutputStream(targetFile)) {
      writeImage(out, mode, progress);
    } catch (IOException e) {
      Log.warn("Failed to write file: " + targetFile.getAbsolutePath(), e);
    }
  }

  /**
   * Write PNG image.
   *
   * @param out output stream to write to.
   */
  private void writePng(OutputStream out, TaskTracker progress) throws IOException {
    try (TaskTracker.Task task = progress.task("Writing PNG");
         PngFileWriter writer = new PngFileWriter(out)) {
      if (scene.transparentSky) {
        writer.write(scene.render.previewDoubleBufferBackStaging.data, alphaChannelPng, scene.width, scene.height, task);
      } else {
        writer.write(scene.render.previewDoubleBufferBackStaging.data, scene.width, scene.height, task);
      }
      if (scene.camera.getProjectionMode() == ProjectionMode.PANORAMIC
          && scene.camera.getFov() >= 179
          && scene.camera.getFov() <= 181) {
        String xmp = "";
        xmp += "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>\n";
        xmp += " <rdf:Description rdf:about=''\n";
        xmp += "   xmlns:GPano='http://ns.google.com/photos/1.0/panorama/'>\n";
        xmp += " <GPano:CroppedAreaImageHeightPixels>";
        xmp += scene.height;
        xmp += "</GPano:CroppedAreaImageHeightPixels>\n";
        xmp += " <GPano:CroppedAreaImageWidthPixels>";
        xmp += scene.width;
        xmp += "</GPano:CroppedAreaImageWidthPixels>\n";
        xmp += " <GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>\n";
        xmp += " <GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>\n";
        xmp += " <GPano:FullPanoHeightPixels>";
        xmp += scene.height;
        xmp += "</GPano:FullPanoHeightPixels>\n";
        xmp += " <GPano:FullPanoWidthPixels>";
        xmp += scene.width;
        xmp += "</GPano:FullPanoWidthPixels>\n";
        xmp += " <GPano:ProjectionType>equirectangular</GPano:ProjectionType>\n";
        xmp += " <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>\n";
        xmp += " </rdf:Description>\n";
        xmp += " </rdf:RDF>";
        ITXT iTXt = new ITXT("XML:com.adobe.xmp", xmp);
        writer.writeChunk(iTXt);
      }
    }
  }

  /**
   * Write TIFF image.
   *
   * @param out output stream to write to.
   */
  private void writeTiff(OutputStream out, TaskTracker progress) throws IOException {
    try (TaskTracker.Task task = progress.task("Writing TIFF");
         TiffFileWriter writer = new TiffFileWriter(out)) {
      writer.write32(scene, task);
    }
  }

  /**
   * Write PFM image.
   *
   * @param out output stream to write to.
   */
  private void writePfm(OutputStream out, TaskTracker progress) throws IOException {
    try (TaskTracker.Task task = progress.task("Writing PFM Rows", scene.canvasHeight());
         PfmFileWriter writer = new PfmFileWriter(out)) {
      writer.write(scene, task);
    }
  }

  private synchronized void saveEmitterGrid(RenderContext context, TaskTracker progress) {
    if(scene.emitterGrid == null)
      return;

    String filename = scene.name + ".emittergrid";
    // TODO Not save when unchanged?
    try(TaskTracker.Task task = progress.task("Saving Grid")) {
      Log.info("Saving Grid " + filename);

      try(DataOutputStream out = new DataOutputStream(new GZIPOutputStream(context.getSceneFileOutputStream(filename)))) {
        scene.emitterGrid.store(out);
      } catch(IOException e) {
        Log.warn("Couldn't save Grid", e);
      }
    }
  }

  private synchronized void saveOctree(RenderContext context, TaskTracker progress) {
    String fileName = scene.name + ".octree2";
    if (context.fileUnchangedSince(fileName, scene.worldOctree.getTimestamp())) {
      Log.info("Skipping redundant Octree write");
      return;
    }
    try (TaskTracker.Task task = progress.task("Saving octree", 2)) {
      task.update(1);
      Log.info("Saving octree " + fileName);

      try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(context.getSceneFileOutputStream(fileName)))) {
        OctreeFileFormat.store(out, scene.worldOctree, scene.waterOctree, scene.palette,
            scene.grassTexture, scene.foliageTexture, scene.waterTexture);
        scene.worldOctree.setTimestamp(context.fileTimestamp(fileName));

        task.update(2);
        Log.info("Octree saved");
      } catch (IOException e) {
        Log.warn("IO exception while saving octree", e);
      }
    }
  }

  public synchronized void saveDump(RenderContext context, TaskTracker progress) {
    String fileName = scene.name + ".dump";
    try (TaskTracker.Task task = progress.task("Saving render dump", 2)) {
      task.update(1);
      Log.info("Saving render dump " + fileName);
      try(OutputStream out = context.getSceneFileOutputStream(fileName)) {
        out.write(DUMP_FORMAT_MAGIC_NUMBER);
        DataOutputStream dataOutput = new DataOutputStream(out);
        dataOutput.writeInt(DUMP_FORMAT_VERSION);
        dataOutput.writeInt(scene.width);
        dataOutput.writeInt(scene.height);
        dataOutput.writeInt(scene.spp);
        dataOutput.writeLong(scene.renderTime);
        FloatingPointCompressor.compress(scene.render.sampleBuffer, out);
      } catch(IOException e) {
        Log.warn("IO exception while saving render dump!", e);
      }
    }
  }

  private synchronized boolean loadEmitterGrid(RenderContext context, TaskTracker progress) {
    String filename = scene.name + ".emittergrid";
    try(TaskTracker.Task task = progress.task("Loading grid")) {
      Log.info("Load grid " + filename);
      try(DataInputStream in = new DataInputStream(new GZIPInputStream(context.getSceneFileInputStream(filename)))) {
        scene.emitterGrid = Grid.load(in);
        return true;
      } catch(Exception e) {
        Log.info("Couldn't load the grid", e);
        return false;
      }
    }
  }

  private synchronized boolean loadOctree(RenderContext context, TaskTracker progress) {
    String fileName = scene.name + ".octree2";
    try (TaskTracker.Task task = progress.task("Loading octree", 2)) {
      task.update(1);
      Log.info("Loading octree " + fileName);
      try {
        long fileTimestamp = context.fileTimestamp(fileName);
        OctreeFileFormat.OctreeData data;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(context.getSceneFileInputStream(fileName)))) {
          data = OctreeFileFormat.load(in, scene.octreeImplementation);
        } catch(PackedOctree.OctreeTooBigException e) {
          // Octree too big, reload file and force loading as NodeBasedOctree
          Log.warn("Octree was too big when loading dump, reloading with old (slower and bigger) implementation.");
          DataInputStream inRetry = new DataInputStream(new GZIPInputStream(context.getSceneFileInputStream(fileName)));
          data = OctreeFileFormat.load(inRetry, "NODE");
        }
        scene.worldOctree = data.worldTree;
        scene.worldOctree.setTimestamp(fileTimestamp);
        scene.waterOctree = data.waterTree;
        scene.grassTexture = data.grassColors;
        scene.foliageTexture = data.foliageColors;
        scene.waterTexture = data.waterColors;
        scene.palette = data.palette;
        scene.palette.applyMaterials();
        task.update(2);
        Log.info("Octree loaded");
        scene.calculateOctreeOrigin(scene.chunks);
        scene.camera.setWorldSize(1 << scene.worldOctree.getDepth());
        scene.buildBvhEntity();
        scene.buildBvhActor();
        return true;
      } catch (IOException e) {
        Log.error("Failed to load chunk data!", e);
        return false;
      }
    }
  }

  public synchronized boolean loadDump(RenderContext context, TaskTracker taskTracker) {
    if (!tryLoadDump(context, scene.name + ".dump", taskTracker)) {
      // Failed to load the default render dump - try the backup file.
      if (!tryLoadDump(context, scene.name + ".dump.backup", taskTracker)) {
        // we don't have the old render state, so reset spp and render time
        scene.spp = 0;
        scene.renderTime = 0;
        return false;
      }
    }
    return true;
  }

  /**
   * @return {@code true} if the render dump was successfully loaded
   */
  protected boolean tryLoadDump(RenderContext context, String fileName, TaskTracker taskTracker) {
    File dumpFile = context.getSceneFile(fileName);
    if (!dumpFile.isFile()) {
      if (scene.spp != 0) {
        // The scene state says the render had some progress, so we should warn
        // that the render dump does not exist.
        Log.warn("Render dump not found: " + fileName);
      }
      return false;
    }
    try(PushbackInputStream input = new PushbackInputStream(new FileInputStream(dumpFile), 4)) {
      byte[] magicNumber = new byte[4];
      input.read(magicNumber, 0, 4);
      if(Arrays.equals(magicNumber, DUMP_FORMAT_MAGIC_NUMBER)) {
        // Format with a version number
        try(DataInputStream dataInput = new DataInputStream(input);
            TaskTracker.Task task = taskTracker.task("Loading render dump", 2)) {
          int dumpVersion = dataInput.readInt();
          if(dumpVersion == 1) {
            task.update(1);
            Log.info("Reading render dump " + fileName);
            int dumpWidth = dataInput.readInt();
            int dumpHeight = dataInput.readInt();
            if (dumpWidth != scene.width || dumpHeight != scene.height) {
              Log.warn("Render dump discarded: incorrect width or height!");
              return false;
            }
            scene.spp = dataInput.readInt();
            scene.renderTime = dataInput.readLong();

            FloatingPointCompressor.decompress(input, scene.render.sampleBuffer);
          }
        }
      } else {
        // Old format that is a gzipped stream, the header needs to be pushed back
        input.unread(magicNumber, 0, 4);
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(input));
             TaskTracker.Task task = taskTracker.task("Loading render dump", 2)) {
          task.update(1);
          Log.info("Reading render dump " + fileName);
          int dumpWidth = in.readInt();
          int dumpHeight = in.readInt();
          if (dumpWidth != scene.width || dumpHeight != scene.height) {
            Log.warn("Render dump discarded: incorrect width or height!");
            return false;
          }
          scene.spp = in.readInt();
          scene.renderTime = in.readLong();

          for (int x = 0; x < scene.width; ++x) {
            task.update(scene.width, x + 1);
            for (int y = 0; y < scene.height; ++y) {
              scene.render.sampleBuffer[(y * scene.width + x) * 3 + 0] = in.readDouble();
              scene.render.sampleBuffer[(y * scene.width + x) * 3 + 1] = in.readDouble();
              scene.render.sampleBuffer[(y * scene.width + x) * 3 + 2] = in.readDouble();
              scene.render.finalizePixel(x, y);
            }
          }
        }
      }
    } catch (IOException e) {
      // The render dump was possibly corrupt.
      Log.warn("Failed to load render dump", e);
      return false;
    }

    Log.info("Render dump loaded: " + fileName);
    return true;
  }


  /** Create a backup of a scene file. */
  public void backupFile(RenderContext context, String fileName) {
    File renderDir = context.getSceneDirectory();
    File file = new File(renderDir, fileName);
    backupFile(context, file);
  }

  /** Create a backup of a scene file. */
  public void backupFile(RenderContext context, File file) {
    if (file.exists()) {
      // Try to create backup. It is not a problem if we fail this.
      String backupFileName = file.getName() + ".backup";
      File renderDir = context.getSceneDirectory();
      File backup = new File(renderDir, backupFileName);
      if (backup.exists()) {
        //noinspection ResultOfMethodCallIgnored
        backup.delete();
      }
      if (!file.renameTo(new File(renderDir, backupFileName))) {
        Log.info("Could not create backup " + backupFileName);
      }
    }
  }




  /**
   * Parse the scene description from a JSON file.
   *
   * <p>This initializes the sample buffers.
   *
   * @param in Input stream to read the JSON data from. The stream will
   * be closed when done.
   */
  public void loadDescription(InputStream in) throws IOException {
    try (JsonParser parser = new JsonParser(in)) {
      JsonObject json = parser.parse().object();
      fromJson(json);
    } catch (JsonParser.SyntaxError e) {
      throw new IOException("JSON syntax error");
    }
  }

  /**
   * Write the scene description as JSON.
   *
   * @param out Output stream to write the JSON data to.
   * The stream will not be closed when done.
   */
  public void saveDescription(OutputStream out) throws IOException {
    PrettyPrinter pp = new PrettyPrinter("  ", new PrintStream(out));
    JsonObject json = scene.toJson();
    json.prettyPrint(pp);
  }

  /**
   * Replace the current settings from exported JSON settings.
   *
   * <p>This (re)initializes the sample buffers for the scene.
   */
  public synchronized void importFromJson(JsonObject json) {
    // The scene is refreshed so that any ongoing renders will restart.
    // We do this in case some setting that requires restart changes.
    // TODO: check if we actually need to reset the scene based on changed settings.
    scene.refresh();

    int newWidth = json.get("width").intValue(scene.width);
    int newHeight = json.get("height").intValue(scene.height);
    if (scene.width != newWidth || scene.height != newHeight || scene.render.sampleBuffer == null) {
      scene.width = newWidth;
      scene.height = newHeight;
      scene.initBuffers();
    }

    scene.yClipMin = json.get("yClipMin").asInt(0);
    scene.yClipMax = json.get("yClipMax").asInt(256);

    scene.exposure = json.get("exposure").doubleValue(scene.exposure);
    scene.postprocess = Postprocess.get(json.get("postprocess").stringValue(scene.postprocess.name()));
    outputMode = OutputMode.get(json.get("outputMode").stringValue(outputMode.name()));
    scene.sppTarget = json.get("sppTarget").intValue(scene.sppTarget);
    scene.rayDepth = json.get("rayDepth").intValue(scene.rayDepth);
    if (!json.get("pathTrace").isUnknown()) {
      boolean pathTrace = json.get("pathTrace").boolValue(false);
      if (pathTrace) {
        scene.mode = RenderState.PAUSED;
      } else {
        scene.mode = RenderState.PREVIEW;
      }
    }
    scene.dumpFrequency = json.get("dumpFrequency").intValue(scene.dumpFrequency);
    scene.saveSnapshots = json.get("saveSnapshots").boolValue(scene.saveSnapshots);
    scene.emittersEnabled = json.get("emittersEnabled").boolValue(scene.emittersEnabled);
    scene.emitterIntensity = json.get("emitterIntensity").doubleValue(scene.emitterIntensity);
    scene.sunEnabled = json.get("sunEnabled").boolValue(scene.sunEnabled);
    scene.stillWater = json.get("stillWater").boolValue(scene.stillWater);
    scene.waterOpacity = json.get("waterOpacity").doubleValue(scene.waterOpacity);
    scene.waterVisibility = json.get("waterVisibility").doubleValue(scene.waterVisibility);
    scene.useCustomWaterColor = json.get("useCustomWaterColor").boolValue(scene.useCustomWaterColor);
    if (scene.useCustomWaterColor) {
      JsonObject colorObj = json.get("waterColor").object();
      scene.waterColor.x = colorObj.get("red").doubleValue(scene.waterColor.x);
      scene.waterColor.y = colorObj.get("green").doubleValue(scene.waterColor.y);
      scene.waterColor.z = colorObj.get("blue").doubleValue(scene.waterColor.z);
    }
    JsonObject fogColorObj = json.get("fogColor").object();
    scene.fogColor.x = fogColorObj.get("red").doubleValue(scene.fogColor.x);
    scene.fogColor.y = fogColorObj.get("green").doubleValue(scene.fogColor.y);
    scene.fogColor.z = fogColorObj.get("blue").doubleValue(scene.fogColor.z);
    scene.fastFog = json.get("fastFog").boolValue(scene.fastFog);
    scene.biomeColors = json.get("biomeColorsEnabled").boolValue(scene.biomeColors);
    scene.transparentSky = json.get("transparentSky").boolValue(scene.transparentSky);
    scene.fogDensity = json.get("fogDensity").doubleValue(scene.fogDensity);
    scene.skyFogDensity = json.get("skyFogDensity").doubleValue(scene.skyFogDensity);
    scene.waterHeight = json.get("waterHeight").intValue(scene.waterHeight);
    scene.renderActors = json.get("renderActors").boolValue(scene.renderActors);
    scene.materials = json.get("materials").object().copy().toMap();

    // Load world info.
    if (json.get("world").isObject()) {
      JsonObject world = json.get("world").object();
      scene.worldPath = world.get("path").stringValue(scene.worldPath);
      scene.worldDimension = world.get("dimension").intValue(scene.worldDimension);
    }

    if (json.get("camera").isObject()) {
      scene.camera.importFromJson(json.get("camera").object());
    }

    if (json.get("sun").isObject()) {
      scene.sun.importFromJson(json.get("sun").object());
    }

    if (json.get("sky").isObject()) {
      scene.sky.importFromJson(json.get("sky").object());
    }

    if (json.get("cameraPresets").isObject()) {
      scene.cameraPresets = json.get("cameraPresets").object();
    }

    // Current SPP and render time are read after loading
    // other settings which can reset the render status.
    scene.spp = json.get("spp").intValue(scene.spp);
    scene.renderTime = json.get("renderTime").longValue(scene.renderTime);

    if (json.get("chunkList").isArray()) {
      JsonArray chunkList = json.get("chunkList").array();
      scene.chunks.clear();
      for (JsonValue elem : chunkList) {
        JsonArray chunk = elem.array();
        int x = chunk.get(0).intValue(Integer.MAX_VALUE);
        int z = chunk.get(1).intValue(Integer.MAX_VALUE);
        if (x != Integer.MAX_VALUE && z != Integer.MAX_VALUE) {
          scene.chunks.add(ChunkPosition.get(x, z));
        }
      }
    }

    if (json.get("entities").isArray() || json.get("actors").isArray()) {
      scene.entities = new LinkedList<>();
      scene.actors = new LinkedList<>();
      // Previously poseable entities were stored in the entities array
      // rather than the actors array. In future versions only the actors
      // array should contain poseable entities.
      for (JsonValue element : json.get("entities").array()) {
        Entity entity = Entity.fromJson(element.object());
        if (entity != null) {
          if (entity instanceof PlayerEntity) {
            scene.actors.add(entity);
          } else {
            scene.entities.add(entity);
          }
        }
      }
      for (JsonValue element : json.get("actors").array()) {
        Entity entity = Entity.fromJson(element.object());
        scene.actors.add(entity);
      }
    }

    scene.octreeImplementation = json.get("octreeImplementation").asString(PersistentSettings.getOctreeImplementation());

    scene.emitterSamplingStrategy = EmitterSamplingStrategy.valueOf(json.get("emitterSamplingStrategy").asString("NONE"));
    scene.preventNormalEmitterWithSampling = json.get("preventNormalEmitterWithSampling").asBoolean(PersistentSettings.getPreventNormalEmitterWithSampling());
  }


  static JsonObject mapToJson(Map<String, JsonValue> map) {
    JsonObject object = new JsonObject(map.size());
    map.forEach(object::add);
    return object;
  }

  /**
   * Reset the scene settings and import from a JSON object.
   */
  public synchronized void fromJson(JsonObject json) {
    boolean finalizeBufferPrev = this.scene.render.shouldFinalizeBuffer;  // Remember the finalize setting.
    Scene scene = new Scene();
    scene.sceneSaver.importFromJson(json);
    this.scene.copyState(scene);
    this.scene.copyTransients(scene);
    this.scene.render.shouldFinalizeBuffer = finalizeBufferPrev; // Restore the finalize setting.

    this.scene.setResetReason(ResetReason.SCENE_LOADED);
    this.sdfVersion = json.get("sdfVersion").intValue(-1);
    this.scene.name = json.get("name").stringValue("loaded_"+ new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()));
  }
}

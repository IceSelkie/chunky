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
import se.llbit.chunky.block.Air;
import se.llbit.chunky.block.Block;
import se.llbit.chunky.block.Lava;
import se.llbit.chunky.block.Water;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.chunk.ChunkData;
import se.llbit.chunky.chunk.SimpleChunkData;
import se.llbit.chunky.entity.ArmorStand;
import se.llbit.chunky.entity.Entity;
import se.llbit.chunky.entity.Lectern;
import se.llbit.chunky.entity.PaintingEntity;
import se.llbit.chunky.entity.PlayerEntity;
import se.llbit.chunky.entity.Poseable;
import se.llbit.chunky.renderer.EmitterSamplingStrategy;
import se.llbit.chunky.renderer.Postprocess;
import se.llbit.chunky.renderer.Refreshable;
import se.llbit.chunky.renderer.RenderState;
import se.llbit.chunky.renderer.ResetReason;
import se.llbit.chunky.renderer.WorkerState;
import se.llbit.chunky.world.*;
import se.llbit.json.Json;
import se.llbit.json.JsonArray;
import se.llbit.json.JsonObject;
import se.llbit.json.JsonValue;
import se.llbit.log.Log;
import se.llbit.math.BVH;
import se.llbit.math.Grid;
import se.llbit.math.IntBoundingBox;
import se.llbit.math.Octree;
import se.llbit.math.QuickMath;
import se.llbit.math.Ray;
import se.llbit.math.Vector3;
import se.llbit.math.Vector3i;
import se.llbit.math.primitive.Primitive;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.Tag;
import se.llbit.util.JsonSerializable;
import se.llbit.util.MCDownloader;
import se.llbit.util.NotNull;
import se.llbit.util.TaskTracker;
import se.llbit.util.file.OutputMode;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Encapsulates scene and render state.
 *
 * <p>Render state is stored in a sample buffer. Two frame buffers
 * are also kept for when a snapshot should be rendered.
 */
public class Scene implements JsonSerializable, Refreshable {

  // Limits (This could probably go in another file)
  /** Minimum canvas width. */
  public static final int MIN_CANVAS_WIDTH = 20;
  /** Minimum canvas height. */
  public static final int MIN_CANVAS_HEIGHT = 20;
  /** Minimum exposure. */
  public static final double MIN_EXPOSURE = 0.001;
  /** Maximum exposure. */
  public static final double MAX_EXPOSURE = 1000.0;
  /** Minimum emitter intensity. */
  public static final double MIN_EMITTER_INTENSITY = 0.01;
  /** Maximum emitter intensity. */
  public static final double MAX_EMITTER_INTENSITY = 1000;



  // Default Values (This could probably go in another file)
  public static final int DEFAULT_DUMP_FREQUENCY = 500;
  /** Default gamma for the gamma correction post process. */
  public static final float DEFAULT_GAMMA = 2.2f;
  public static final boolean DEFAULT_EMITTERS_ENABLED = false;
  /** Default emitter intensity. */
  public static final double DEFAULT_EMITTER_INTENSITY = 13;
  /** Default exposure. */
  public static final double DEFAULT_EXPOSURE = 1.0;
  /** Default fog density. */
  public static final double DEFAULT_FOG_DENSITY = 0.0;



  // New helper/container objects
  public SceneSaver sceneSaver;
  public RenderPreview render;



  // Scene State (This could probably be moved into the `render` helper/container object)
  /** Current SPP for the scene. */
  public int spp = 0;
  public long renderTime;
  protected RenderState mode = RenderState.PREVIEW;

  /** Indicates if the render should be forced to reset. */
  protected ResetReason resetReason = ResetReason.NONE;
  protected boolean forceReset = false;



  // Octree Stuff (This could probably be moved into a new helper/container object)
  protected BlockPalette palette;
  protected Octree worldOctree;
  protected Octree waterOctree;
  protected Grid emitterGrid;
  /** The octree implementation to use */
  protected String octreeImplementation = PersistentSettings.getOctreeImplementation();
  /** Octree origin. */
  protected Vector3i origin = new Vector3i();
  protected int yMax = 256;
  protected int yMin = 0;

  // Entities
  protected BVH bvhEntities = new BVH(Collections.emptyList());
  protected BVH bvhActors = new BVH(Collections.emptyList());
  /** Entities in the scene. */
  protected Collection<Entity> entities = new LinkedList<>();
  /** Poseable entities in the scene. */
  protected Collection<Entity> actors = new LinkedList<>();
  /** Poseable players in the scene. */
  protected Map<PlayerEntity, JsonObject> profiles = new HashMap<>();

  // Other World Stuff
  /** World reference. */
  @NotNull protected World loadedWorld = EmptyWorld.INSTANCE;
  protected int worldDimension = 0;
  protected Collection<ChunkPosition> chunks = new ArrayList<>();


  // Materials and textures (This could probably be moved into a new helper/container object)
  /** Material properties for this scene. */
  public Map<String, JsonValue> materials = new HashMap<>();
  protected WorldTexture grassTexture = new WorldTexture();
  protected WorldTexture foliageTexture = new WorldTexture();
  protected WorldTexture waterTexture = new WorldTexture();




  // Scene Properties; The stuff that gets saved to a scene.json file.
  //   (This could probably be moved into a new container object for ease of Json and keeping stuff consolidated.)
  public String name = "default_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
  /** Canvas width. */
  public int width;
  /** Canvas height. */
  public int height;
  public Postprocess postprocess = Postprocess.DEFAULT;
  protected String worldPath = "";
  /** Recursive ray depth limit (not including Russian Roulette). */
  protected int rayDepth = PersistentSettings.getRayDepthDefault();

  /** Target SPP for the scene. */
  protected int sppTarget = PersistentSettings.getSppTargetDefault();
  protected int dumpFrequency = DEFAULT_DUMP_FREQUENCY;
  protected boolean saveSnapshots = false;

  // Camera Stuff
  protected final Camera camera = new Camera(this);
  protected JsonObject cameraPresets = new JsonObject();
  protected double exposure = DEFAULT_EXPOSURE;
  protected boolean renderActors = true;
  /** Lower Y clip plane. */
  public int yClipMin = PersistentSettings.getYClipMin();
  /** Upper Y clip plane. */
  public int yClipMax = PersistentSettings.getYClipMax();


  // Emitters
  protected boolean emittersEnabled = DEFAULT_EMITTERS_ENABLED;
  protected double emitterIntensity = DEFAULT_EMITTER_INTENSITY;
  protected EmitterSamplingStrategy emitterSamplingStrategy = EmitterSamplingStrategy.NONE;
  protected int emitterGridSize = PersistentSettings.getGridSizeDefault();
  protected boolean preventNormalEmitterWithSampling = PersistentSettings.getPreventNormalEmitterWithSampling();

  // sky stuff
  protected boolean transparentSky = false;
  protected final Sky sky = new Sky(this);
  protected boolean sunEnabled = true;
  protected final Sun sun = new Sun(this);

  // Fog Stuff
  /** Enables fast fog algorithm */
  protected boolean fastFog = true;
  /** Fog thickness. */
  protected double fogDensity = DEFAULT_FOG_DENSITY;
  /** Controls how much the fog color is blended over the sky/skymap. */
  protected double skyFogDensity = 1;
  protected final Vector3 fogColor = new Vector3(PersistentSettings.getFogColorRed(),
        PersistentSettings.getFogColorGreen(), PersistentSettings.getFogColorBlue());

  // Biome Color Stuff
  protected boolean biomeColors = true;

  // Water Stuff
  /** Water opacity modifier. */
  protected double waterOpacity = PersistentSettings.getWaterOpacity();
  protected double waterVisibility = PersistentSettings.getWaterVisibility();
  protected int waterHeight = PersistentSettings.getWaterHeight();
  protected boolean stillWater = PersistentSettings.getStillWater();
  protected boolean useCustomWaterColor = PersistentSettings.getUseCustomWaterColor();
  protected final Vector3 waterColor = new Vector3(PersistentSettings.getWaterColorRed(),
        PersistentSettings.getWaterColorGreen(), PersistentSettings.getWaterColorBlue());
















  // Loading and creation stuff

  /**
   * Creates a scene with all default settings.
   *
   * <p>Note: this does not initialize the render buffers for the scene!
   * Render buffers are initialized either by using loadDescription(), fromJson(), or importFromJson(), or by calling
   * initBuffers().
   */
  public Scene() {
    width = PersistentSettings.get3DCanvasWidth();
    height = PersistentSettings.get3DCanvasHeight();
    sppTarget = PersistentSettings.getSppTargetDefault();

    palette = new BlockPalette();
    worldOctree = new Octree(octreeImplementation, 1);
    waterOctree = new Octree(octreeImplementation, 1);
    emitterGrid = null;

    sceneSaver = new SceneSaver(this);
    render = new RenderPreview(this);
  }

  /**
   * This initializes the render buffers when initializing the scene and after scene canvas size changes.
   */
  public synchronized void initBuffers() {
    render.initBuffers();
    render.sampleBuffer = new double[width * height * 3];
  }

  /**
   * Creates a copy of another scene.
   */
  public Scene(Scene other) {
    sceneSaver = new SceneSaver(this);
    render = new RenderPreview(this);

    copyState(other);
    copyTransients(other);
  }

  @Override
  public synchronized JsonObject toJson() {
    JsonObject json = new JsonObject();
    json.add("sdfVersion", SceneSaver.SDF_VERSION);
    json.add("name", name);
    json.add("width", width);
    json.add("height", height);
    json.add("yClipMin", yClipMin);
    json.add("yClipMax", yClipMax);
    json.add("exposure", exposure);
    json.add("postprocess", postprocess.name());
    json.add("outputMode", sceneSaver.outputMode.name());
    json.add("renderTime", renderTime);
    json.add("spp", spp);
    json.add("sppTarget", sppTarget);
    json.add("rayDepth", rayDepth);
    json.add("pathTrace", mode != RenderState.PREVIEW);
    json.add("dumpFrequency", dumpFrequency);
    json.add("saveSnapshots", saveSnapshots);
    json.add("emittersEnabled", emittersEnabled);
    json.add("emitterIntensity", emitterIntensity);
    json.add("sunEnabled", sunEnabled);
    json.add("stillWater", stillWater);
    json.add("waterOpacity", waterOpacity);
    json.add("waterVisibility", waterVisibility);
    json.add("useCustomWaterColor", useCustomWaterColor);
    if (useCustomWaterColor) {
      JsonObject colorObj = new JsonObject();
      colorObj.add("red", waterColor.x);
      colorObj.add("green", waterColor.y);
      colorObj.add("blue", waterColor.z);
      json.add("waterColor", colorObj);
    }
    JsonObject fogColorObj = new JsonObject();
    fogColorObj.add("red", fogColor.x);
    fogColorObj.add("green", fogColor.y);
    fogColorObj.add("blue", fogColor.z);
    json.add("fogColor", fogColorObj);
    json.add("fastFog", fastFog);
    json.add("biomeColorsEnabled", biomeColors);
    json.add("transparentSky", transparentSky);
    json.add("fogDensity", fogDensity);
    json.add("skyFogDensity", skyFogDensity);
    json.add("waterHeight", waterHeight);
    json.add("renderActors", renderActors);

    if (!worldPath.isEmpty()) {
      // Save world info.
      JsonObject world = new JsonObject();
      world.add("path", worldPath);
      world.add("dimension", worldDimension);
      json.add("world", world);
    }

    json.add("camera", camera.toJson());
    json.add("sun", sun.toJson());
    json.add("sky", sky.toJson());
    json.add("cameraPresets", cameraPresets.copy());
    JsonArray chunkList = new JsonArray();
    for (ChunkPosition pos : chunks) {
      JsonArray chunk = new JsonArray();
      chunk.add(pos.x);
      chunk.add(pos.z);
      chunkList.add(chunk);
    }

    // Save material settings.
    json.add("materials", SceneSaver.mapToJson(materials));

    // TODO: add regionList to compress the scene description size.
    json.add("chunkList", chunkList);

    JsonArray entityArray = new JsonArray();
    for (Entity entity : entities) {
      entityArray.add(entity.toJson());
    }
    if (!entityArray.isEmpty()) {
      json.add("entities", entityArray);
    }
    JsonArray actorArray = new JsonArray();
    for (Entity entity : actors) {
      actorArray.add(entity.toJson());
    }
    if (!actorArray.isEmpty()) {
      json.add("actors", actorArray);
    }
    json.add("octreeImplementation", octreeImplementation);
    json.add("emitterSamplingStrategy", emitterSamplingStrategy.name());
    json.add("preventNormalEmitterWithSampling", preventNormalEmitterWithSampling);

    return json;
  }

  /**
   * Import scene state from another scene.
   */
  public synchronized void copyState(Scene other, boolean copyChunks) {
    if (copyChunks) {
      loadedWorld = other.loadedWorld;
      worldPath = other.worldPath;
      worldDimension = other.worldDimension;

      // The octree reference is overwritten to save time.
      // When the other scene is changed it must create a new octree.
      palette = other.palette;
      worldOctree = other.worldOctree;
      waterOctree = other.waterOctree;
      entities = other.entities;
      actors = new LinkedList<>(other.actors); // Create a copy so that entity changes can be reset.
      profiles = other.profiles;
      bvhEntities = other.bvhEntities;
      bvhActors = other.bvhActors;
      renderActors = other.renderActors;
      grassTexture = other.grassTexture;
      foliageTexture = other.foliageTexture;
      waterTexture = other.waterTexture;
      origin.set(other.origin);

      chunks = other.chunks;

      emitterGrid = other.emitterGrid;
    }

    // Copy material properties.
    materials = other.materials;

    exposure = other.exposure;

    stillWater = other.stillWater;
    waterOpacity = other.waterOpacity;
    waterVisibility = other.waterVisibility;
    useCustomWaterColor = other.useCustomWaterColor;
    waterColor.set(other.waterColor);
    fogColor.set(other.fogColor);
    biomeColors = other.biomeColors;
    sunEnabled = other.sunEnabled;
    emittersEnabled = other.emittersEnabled;
    emitterIntensity = other.emitterIntensity;
    emitterSamplingStrategy = other.emitterSamplingStrategy;
    preventNormalEmitterWithSampling = other.preventNormalEmitterWithSampling;
    transparentSky = other.transparentSky;
    fogDensity = other.fogDensity;
    skyFogDensity = other.skyFogDensity;
    fastFog = other.fastFog;

    camera.set(other.camera);
    sky.set(other.sky);
    sun.set(other.sun);

    waterHeight = other.waterHeight;

    spp = other.spp;
    renderTime = other.renderTime;

    resetReason = other.resetReason;

    render.finalized = false;

    if (render.sampleBuffer != other.render.sampleBuffer) {
      width = other.width;
      height = other.height;
      render.previewDoubleBufferBackStaging = other.render.previewDoubleBufferBackStaging;
      render.previewDoubleBufferFrontShow = other.render.previewDoubleBufferFrontShow;
      sceneSaver.alphaChannelPng = other.sceneSaver.alphaChannelPng;
      render.sampleBuffer = other.render.sampleBuffer;
    }

    octreeImplementation = other.octreeImplementation;
  }

  /**
   * Import scene state from another scene.
   */
  public synchronized void copyState(Scene other) {
    copyState(other, true);
  }

  /**
   * Copy scene state that does not require a render restart.
   *
   * @param other scene to copy transient state from.
   */
  public synchronized void copyTransients(Scene other) {
    name = other.name;
    postprocess = other.postprocess;
    exposure = other.exposure;
    dumpFrequency = other.dumpFrequency;
    saveSnapshots = other.saveSnapshots;
    sppTarget = other.sppTarget;
    rayDepth = other.rayDepth;
    mode = other.mode;
    sceneSaver.outputMode = other.sceneSaver.outputMode;
    cameraPresets = other.cameraPresets;
    camera.copyTransients(other.camera);
    render.shouldFinalizeBuffer = other.render.shouldFinalizeBuffer;
  }

  /**
   * Merge a render dump into this scene.
   */
  public void mergeDump(File dumpFile, TaskTracker taskTracker) {
    int dumpSpp;
    long dumpTime;
    try (TaskTracker.Task task = taskTracker.task("Merging render dump", 2);
         DataInputStream in = new DataInputStream(
               new GZIPInputStream(new FileInputStream(dumpFile)))) {
      task.update(1);
      Log.info("Loading render dump " + dumpFile.getAbsolutePath());
      int dumpWidth = in.readInt();
      int dumpHeight = in.readInt();
      if (dumpWidth != width || dumpHeight != height) {
        Log.warn("Render dump discarded: incorrect width or height!");
        return;
      }
      dumpSpp = in.readInt();
      dumpTime = in.readLong();

      double sa = spp / (double)(spp + dumpSpp);
      double sb = 1 - sa;

      for (int x = 0; x < width; ++x) {
        task.update(width, x + 1);
        for (int y = 0; y < height; ++y) {
          render.sampleBuffer[(y * width + x) * 3 + 0] = render.sampleBuffer[(y * width + x) * 3 + 0] * sa + in.readDouble() * sb;
          render.sampleBuffer[(y * width + x) * 3 + 1] = render.sampleBuffer[(y * width + x) * 3 + 1] * sa + in.readDouble() * sb;
          render.sampleBuffer[(y * width + x) * 3 + 2] = render.sampleBuffer[(y * width + x) * 3 + 2] * sa + in.readDouble() * sb;
          render.finalizePixel(x, y);
        }
      }
      Log.info("Render dump loaded");

      // Update render status.
      spp += dumpSpp;
      renderTime += dumpTime;
    } catch (IOException e) {
      Log.info("Render dump not loaded");
    }
  }



  /**
   * Reload all loaded chunks.
   */
  public synchronized void reloadChunks(TaskTracker progress) {
    if (loadedWorld == EmptyWorld.INSTANCE) {
      Log.warn("Can not reload chunks for scene - world directory not found!");
      return;
    }
    loadedWorld = World.loadWorld(loadedWorld.getWorldDirectory(), worldDimension, World.LoggedWarnings.NORMAL);
    loadChunks(progress, loadedWorld, chunks);
    refresh();
  }

  /**
   * Load chunks into the octree.
   *
   * <p>This is the main method loading all voxels into the octree.
   * The octree finalizer is then run to compute block properties like fence connectedness.
   */
  public synchronized void loadChunks(TaskTracker progress, World world, Collection<ChunkPosition> chunksToLoad) {

    if (world == null) {
      return;
    }

    Set<ChunkPosition> loadedChunks = new HashSet<>();
    int numChunks = 0;

    try (TaskTracker.Task task = progress.task("Loading regions")) {
      task.update(2, 1);

      loadedWorld = world;
      worldPath = loadedWorld.getWorldDirectory().getAbsolutePath();
      worldDimension = world.currentDimension();

      if (chunksToLoad.isEmpty()) {
        return;
      }

      int requiredDepth = calculateOctreeOrigin(chunksToLoad);

      // Create new octree to fit all chunks.
      palette = new BlockPalette();
      worldOctree = new Octree(octreeImplementation, requiredDepth);
      waterOctree = new Octree(octreeImplementation, requiredDepth);
      if (emitterSamplingStrategy != EmitterSamplingStrategy.NONE) {
        emitterGrid = new Grid(emitterGridSize);
      }

      // Parse the regions first - force chunk lists to be populated!
      Set<ChunkPosition> regions = new HashSet<>();
      for (ChunkPosition cp : chunksToLoad) {
        regions.add(cp.getRegionPosition());
      }

      for (ChunkPosition region : regions) {
        world.getRegion(region).parse();
      }
    }

    try (TaskTracker.Task task = progress.task("Loading entities")) {
      entities = new LinkedList<>();
      if (actors.isEmpty() && PersistentSettings.getLoadPlayers()) {
        // We don't load actor entities if some already exists. Loading actor entities
        // risks resetting posed actors when reloading chunks for an existing scene.
        actors = new LinkedList<>();
        profiles = new HashMap<>();
        Collection<PlayerEntity> players = world.playerEntities();
        int done = 1;
        int target = players.size();
        for (PlayerEntity entity : players) {
          entity.randomPose();
          task.update(target, done);
          done += 1;
          JsonObject profile;
          try {
            profile = MCDownloader.fetchProfile(entity.uuid);
          } catch (IOException e) {
            Log.error(e);
            profile = new JsonObject();
          }
          profiles.put(entity, profile);
          actors.add(entity);
        }
      }
    }

    Heightmap biomeIdMap = new Heightmap();

    yMin = Math.max(0, yClipMin);
    yMax = Math.min(256, yClipMax);

    ChunkData chunkData = new SimpleChunkData();

    try (TaskTracker.Task task = progress.task("Loading chunks")) {
      int done = 1;
      int target = chunksToLoad.size();
      for (ChunkPosition cp : chunksToLoad) {
        task.update(target, done);
        done += 1;

        if (loadedChunks.contains(cp)) {
          continue;
        }

        loadedChunks.add(cp);

        world.getChunk(cp).getChunkData(chunkData, palette);
        numChunks += 1;

        int wx0 = cp.x * 16; // Start of this chunk in world coordinates.
        int wz0 = cp.z * 16;
        for (int cz = 0; cz < 16; ++cz) {
          int wz = cz + wz0;
          for (int cx = 0; cx < 16; ++cx) {
            int wx = cx + wx0;
            int biomeId = 0xFF & chunkData.getBiomeAt(cx, 0, cz); // TODO add vertical biomes support (1.15+)
            biomeIdMap.set(biomeId, wx, wz);
          }
        }

        // Load entities from the chunk:
        for (CompoundTag tag : chunkData.getEntities()) {
          Tag posTag = tag.get("Pos");
          if (posTag.isList()) {
            ListTag pos = posTag.asList();
            double x = pos.get(0).doubleValue();
            double y = pos.get(1).doubleValue();
            double z = pos.get(2).doubleValue();

            if (y >= yClipMin && y <= yClipMax) {
              String id = tag.get("id").stringValue("");
              if (id.equals("minecraft:painting") || id.equals("Painting")) {
                // Before 1.12 paintings had id=Painting.
                // After 1.12 paintings had id=minecraft:painting.
                float yaw = tag.get("Rotation").get(0).floatValue();
                entities.add(
                      new PaintingEntity(new Vector3(x, y, z), tag.get("Motive").stringValue(), yaw));
              }
              else if (id.equals("minecraft:armor_stand")) {
                actors.add(new ArmorStand(new Vector3(x, y, z), tag));
              }
            }
          }
        }

        for (int cy = yMin; cy < yMax; ++cy) { //Uses chunk min and max, rather than globa - minor optimisation for
          // pre1.13 worlds
          for (int cz = 0; cz < 16; ++cz) {
            int z = cz + cp.z * 16 - origin.z;
            for (int cx = 0; cx < 16; ++cx) {
              int x = cx + cp.x * 16 - origin.x;

              // Change the type of hidden blocks to ANY_TYPE
              boolean notOnEdge = !chunkData.isBlockOnEdge(cx, cy, cz);
              boolean isHidden = notOnEdge
                    && palette.get(chunkData.getBlockAt(cx + 1, cy, cz)).opaque
                    && palette.get(chunkData.getBlockAt(cx - 1, cy, cz)).opaque
                    && palette.get(chunkData.getBlockAt(cx, cy + 1, cz)).opaque
                    && palette.get(chunkData.getBlockAt(cx, cy - 1, cz)).opaque
                    && palette.get(chunkData.getBlockAt(cx, cy, cz + 1)).opaque
                    && palette.get(chunkData.getBlockAt(cx, cy, cz - 1)).opaque;

              if (isHidden) {
                worldOctree.set(Octree.ANY_TYPE, x, cy - origin.y, z);
              }
              else {
                int currentBlock = chunkData.getBlockAt(cx, cy, cz);
                Octree.Node octNode = new Octree.Node(currentBlock);
                Block block = palette.get(currentBlock);

                if (block.isEntity()) {
                  Vector3 position = new Vector3(cx + cp.x * 16, cy, cz + cp.z * 16);
                  Entity entity = block.toEntity(position);

                  if (entity instanceof Poseable && !(entity instanceof Lectern && !((Lectern)entity).hasBook())) {
                    // don't add the actor again if it was already loaded from json
                    if (actors.stream().noneMatch(actor -> {
                      if (actor.getClass().equals(entity.getClass())) {
                        Vector3 distance = new Vector3(actor.position);
                        distance.sub(entity.position);
                        return distance.lengthSquared() < Ray.EPSILON;
                      }
                      return false;
                    })) {
                      actors.add(entity);
                    }
                  }
                  else {
                    entities.add(entity);
                    if (emitterGrid != null) {
                      for (Grid.EmitterPosition emitterPos : entity.getEmitterPosition()) {
                        emitterPos.x -= origin.x;
                        emitterPos.y -= origin.y;
                        emitterPos.z -= origin.z;
                        emitterGrid.addEmitter(emitterPos);
                      }
                    }
                  }

                  if (!block.isBlockWithEntity()) {
                    if (block.waterlogged) {
                      block = palette.water;
                      octNode = new Octree.Node(palette.waterId);
                    }
                    else {
                      block = Air.INSTANCE;
                      octNode = new Octree.Node(palette.airId);
                    }
                  }
                }

                if (block.isWaterFilled()) {
                  Octree.Node waterNode = new Octree.Node(palette.waterId);
                  if (cy + 1 < yMax) {
                    if (palette.get(chunkData.getBlockAt(cx, cy + 1, cz)).isWaterFilled()) {
                      waterNode = new Octree.Node(palette.getWaterId(8, 1 << Water.FULL_BLOCK));
                    }
                  }
                  if (block.isWater()) {
                    // Move plain water blocks to the water octree.
                    octNode = new Octree.Node(palette.airId);

                    if (notOnEdge) {
                      // Perform water computation now for water blocks that are not on th edge of the chunk
                      if (((Water)palette.get(waterNode.type)).data == 0) {
                        // Test if the block has not already be marked as full
                        int level0 = 8 - ((Water)block).level;
                        int corner0 = level0;
                        int corner1 = level0;
                        int corner2 = level0;
                        int corner3 = level0;

                        int level = Chunk.waterLevelAt(chunkData, palette, cx - 1, cy, cz, level0);
                        corner3 += level;
                        corner0 += level;

                        level = Chunk.waterLevelAt(chunkData, palette, cx - 1, cy, cz + 1, level0);
                        corner0 += level;

                        level = Chunk.waterLevelAt(chunkData, palette, cx, cy, cz + 1, level0);
                        corner0 += level;
                        corner1 += level;

                        level = Chunk.waterLevelAt(chunkData, palette, cx + 1, cy, cz + 1, level0);
                        corner1 += level;

                        level = Chunk.waterLevelAt(chunkData, palette, cx + 1, cy, cz, level0);
                        corner1 += level;
                        corner2 += level;

                        level = Chunk.waterLevelAt(chunkData, palette, cx + 1, cy, cz - 1, level0);
                        corner2 += level;

                        level = Chunk.waterLevelAt(chunkData, palette, cx, cy, cz - 1, level0);
                        corner2 += level;
                        corner3 += level;

                        level = Chunk.waterLevelAt(chunkData, palette, cx - 1, cy, cz - 1, level0);
                        corner3 += level;

                        corner0 = Math.min(7, 8 - (corner0 / 4));
                        corner1 = Math.min(7, 8 - (corner1 / 4));
                        corner2 = Math.min(7, 8 - (corner2 / 4));
                        corner3 = Math.min(7, 8 - (corner3 / 4));
                        waterNode = new Octree.Node(
                              palette.getWaterId(((Water)block).level, (corner0 << Water.CORNER_0)
                                    | (corner1 << Water.CORNER_1)
                                    | (corner2 << Water.CORNER_2)
                                    | (corner3 << Water.CORNER_3)));
                      }
                    }
                  }
                  waterOctree.set(waterNode, x, cy - origin.y, z);
                }
                else if (cy + 1 < yMax && block instanceof Lava) {
                  if (palette.get(chunkData.getBlockAt(cx, cy + 1, cz)) instanceof Lava) {
                    octNode = new Octree.Node(
                          palette.getLavaId(((Lava)block).level, 1 << Water.FULL_BLOCK));
                  }
                  else if (notOnEdge) {
                    // Compute lava level for blocks not on edge
                    Lava lava = (Lava)block;
                    int level0 = 8 - lava.level;
                    int corner0 = level0;
                    int corner1 = level0;
                    int corner2 = level0;
                    int corner3 = level0;

                    int level = Chunk.lavaLevelAt(chunkData, palette, cx - 1, cy, cz, level0);
                    corner3 += level;
                    corner0 += level;

                    level = Chunk.lavaLevelAt(chunkData, palette, cx - 1, cy, cz + 1, level0);
                    corner0 += level;

                    level = Chunk.lavaLevelAt(chunkData, palette, cx, cy, cz + 1, level0);
                    corner0 += level;
                    corner1 += level;

                    level = Chunk.lavaLevelAt(chunkData, palette, cx + 1, cy, cz + 1, level0);
                    corner1 += level;

                    level = Chunk.lavaLevelAt(chunkData, palette, cx + 1, cy, cz, level0);
                    corner1 += level;
                    corner2 += level;

                    level = Chunk.lavaLevelAt(chunkData, palette, cx + 1, cy, cz - 1, level0);
                    corner2 += level;

                    level = Chunk.lavaLevelAt(chunkData, palette, cx, cy, cz - 1, level0);
                    corner2 += level;
                    corner3 += level;

                    level = Chunk.lavaLevelAt(chunkData, palette, cx - 1, cy, cz - 1, level0);
                    corner3 += level;

                    corner0 = Math.min(7, 8 - (corner0 / 4));
                    corner1 = Math.min(7, 8 - (corner1 / 4));
                    corner2 = Math.min(7, 8 - (corner2 / 4));
                    corner3 = Math.min(7, 8 - (corner3 / 4));
                    octNode = new Octree.Node(palette.getLavaId(
                          lava.level,
                          (corner0 << Water.CORNER_0)
                                | (corner1 << Water.CORNER_1)
                                | (corner2 << Water.CORNER_2)
                                | (corner3 << Water.CORNER_3)
                    ));
                  }
                }
                worldOctree.set(octNode, x, cy - origin.y, z);

                if (emitterGrid != null && block.emittance > 1e-4) {
                  emitterGrid.addEmitter(new Grid.EmitterPosition(x + 0.5f, cy - origin.y + 0.5f, z + 0.5f));
                }

              }
            }
          }
        }

        // Block entities are also called "tile entities". These are extra bits of metadata
        // about certain blocks or entities.
        // Block entities are loaded after the base block data so that metadata can be updated.
        for (CompoundTag entityTag : chunkData.getTileEntities()) {
          int y = entityTag.get("y").intValue(0);
          if (y >= yClipMin && y <= yClipMax) {
            int x = entityTag.get("x").intValue(0) - wx0; // Chunk-local coordinates.
            int z = entityTag.get("z").intValue(0) - wz0;
            if (x < 0 || x > 15 || z < 0 || z > 15) {
              // Block entity is out of range (bad chunk data?), ignore it
              continue;
            }
            Block block = palette.get(chunkData.getBlockAt(x, y, z));
            // Metadata is the old block data (to be replaced in future Minecraft versions?).
            Vector3 position = new Vector3(x + wx0, y, z + wz0);
            if (block.isBlockEntity()) {
              Entity blockEntity = block.toBlockEntity(position, entityTag);
              if (blockEntity == null) {
                continue;
              }
              if (blockEntity instanceof Poseable) {
                // don't add the actor again if it was already loaded from json
                if (actors.stream().noneMatch(actor -> {
                  if (actor.getClass().equals(blockEntity.getClass())) {
                    Vector3 distance = new Vector3(actor.position);
                    distance.sub(blockEntity.position);
                    return distance.lengthSquared() < Ray.EPSILON;
                  }
                  return false;
                })) {
                  actors.add(blockEntity);
                }
              }
              else {
                entities.add(blockEntity);
                if (emitterGrid != null) {
                  for (Grid.EmitterPosition emitterPos : blockEntity.getEmitterPosition()) {
                    emitterPos.x -= origin.x;
                    emitterPos.y -= origin.y;
                    emitterPos.z -= origin.z;
                    emitterGrid.addEmitter(emitterPos);
                  }
                }
              }
            }
            /*
            switch (block) {
              case Block.HEAD_ID:
                entities.add(new SkullEntity(position, entityTag, metadata));
                break;
              case Block.WALL_BANNER_ID: {
                entities.add(new WallBanner(position, metadata, entityTag));
                break;
              }
            }
            */
          }
        }
      }
    }

    grassTexture = new WorldTexture();
    foliageTexture = new WorldTexture();
    waterTexture = new WorldTexture();

    Set<ChunkPosition> chunkSet = new HashSet<>(chunksToLoad);

    try (TaskTracker.Task task = progress.task("Finalizing octree")) {

      worldOctree.startFinalization();
      waterOctree.startFinalization();

      int done = 0;
      int target = chunksToLoad.size();
      for (ChunkPosition cp : chunksToLoad) {

        // Finalize grass and foliage textures.
        // 3x3 box blur.
        for (int x = 0; x < 16; ++x) {
          for (int z = 0; z < 16; ++z) {

            int nsum = 0;
            float[] grassMix = {0, 0, 0};
            float[] foliageMix = {0, 0, 0};
            float[] waterMix = {0, 0, 0};
            for (int sx = x - 1; sx <= x + 1; ++sx) {
              int wx = cp.x * 16 + sx;
              for (int sz = z - 1; sz <= z + 1; ++sz) {
                int wz = cp.z * 16 + sz;

                ChunkPosition ccp = ChunkPosition.get(wx >> 4, wz >> 4);
                if (chunkSet.contains(ccp)) {
                  nsum += 1;
                  int biomeId = biomeIdMap.get(wx, wz);
                  float[] grassColor = Biomes.getGrassColorLinear(biomeId);
                  grassMix[0] += grassColor[0];
                  grassMix[1] += grassColor[1];
                  grassMix[2] += grassColor[2];
                  float[] foliageColor = Biomes.getFoliageColorLinear(biomeId);
                  foliageMix[0] += foliageColor[0];
                  foliageMix[1] += foliageColor[1];
                  foliageMix[2] += foliageColor[2];
                  float[] waterColor = Biomes.getWaterColorLinear(biomeId);
                  waterMix[0] += waterColor[0];
                  waterMix[1] += waterColor[1];
                  waterMix[2] += waterColor[2];
                }
              }
            }
            grassMix[0] /= nsum;
            grassMix[1] /= nsum;
            grassMix[2] /= nsum;
            grassTexture.set(cp.x * 16 + x - origin.x, cp.z * 16 + z - origin.z, grassMix);

            foliageMix[0] /= nsum;
            foliageMix[1] /= nsum;
            foliageMix[2] /= nsum;
            foliageTexture.set(cp.x * 16 + x - origin.x, cp.z * 16 + z - origin.z, foliageMix);

            waterMix[0] /= nsum;
            waterMix[1] /= nsum;
            waterMix[2] /= nsum;
            waterTexture.set(cp.x * 16 + x - origin.x, cp.z * 16 + z - origin.z, waterMix);
          }
        }
        task.update(target, done);
        done += 1;
        OctreeFinalizer.finalizeChunk(worldOctree, waterOctree, palette, origin, cp, yMin, yMax);
      }

      worldOctree.endFinalization();
      waterOctree.endFinalization();
    }

    if (emitterGrid != null) { emitterGrid.prepare(); }

    chunks = loadedChunks;
    camera.setWorldSize(1 << worldOctree.getDepth());
    buildBvhEntity();
    buildBvhActor();
    Log.info(String.format("Loaded %d chunks", numChunks));
  }





  // Scene state stuff

  /**
   * Start rendering. This wakes up threads waiting on a scene state change, even if the scene state did not actually
   * change.
   */
  public synchronized void startHeadlessRender() {
    mode = RenderState.RENDERING;
    notifyAll();
  }

  /**
   * @return <code>true</code> if the rendering of this scene should be
   * restarted
   */
  public boolean shouldRefresh() {
    return resetReason != ResetReason.NONE;
  }

  /**
   * Start rendering the scene.
   */
  public synchronized void startRender() {
    if (mode == RenderState.PAUSED) {
      mode = RenderState.RENDERING;
      notifyAll();
    }
    else if (mode != RenderState.RENDERING) {
      mode = RenderState.RENDERING;
      refresh();
    }
  }

  /**
   * Pause the renderer.
   */
  public synchronized void pauseRender() {
    mode = RenderState.PAUSED;

    // Wake up threads in awaitSceneStateChange().
    notifyAll();
  }

  /**
   * Halt the rendering process. Puts the renderer back in preview mode.
   */
  public synchronized void stopRender() {
    if (mode != RenderState.PREVIEW) {
      mode = RenderState.PREVIEW;
      resetReason = ResetReason.MODE_CHANGE;
      forceReset = true;
      refresh();
    }
  }


  /**
   * Clear the scene refresh flag
   */
  synchronized public void clearResetFlags() {
    resetReason = ResetReason.NONE;
    forceReset = false;
  }

  /**
   * The status that is displayed in the bottom left corner of the render preview.
   *
   * @return scene status text.
   */
  public synchronized String sceneStatus() {
    try {
      if (!haveLoadedChunks()) {
        return "No chunks loaded!";
      }
      else {
        StringBuilder buf = new StringBuilder();
        Ray ray = new Ray();
        if (traceTarget(ray) && ray.getCurrentMaterial() instanceof Block) {
          Block block = (Block)ray.getCurrentMaterial();
          buf.append(String.format("target: %.2f m\n", ray.distance));
          buf.append(block.name);
          String description = block.description();
          if (!description.isEmpty()) {
            buf.append(" (").append(description).append(")");
          }
          buf.append("\n");
        }
        Vector3 pos = camera.getPosition();
        buf.append(String.format("pos: (%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z));
        return buf.toString();
      }

    } catch (IllegalStateException e) {
      Log.error("Unexpected exception while rendering back buffer", e);
    }
    return "";
  }

  /**
   * Clears the scene, preparing to load fresh chunks.
   */
  public void clear() {
    cameraPresets = new JsonObject();
    entities.clear();
    actors.clear();
  }

  /**
   * Resets the scene state to the default state.
   *
   * @param name sets the name for the scene
   */
  public synchronized void resetScene(String name, SceneFactory sceneFactory) {
    boolean finalizeBufferPrev = render.shouldFinalizeBuffer;  // Remember the finalize setting.
    Scene newScene = sceneFactory.newScene();
    newScene.initBuffers();
    if (name != null) {
      newScene.setName(name);
    }
    copyState(newScene, false);
    copyTransients(newScene);
    moveCameraToCenter();
    forceReset = true;
    resetReason = ResetReason.SETTINGS_CHANGED;
    mode = RenderState.PREVIEW;
    render.shouldFinalizeBuffer = finalizeBufferPrev;
  }

  /**
   * Called when the scene description has been altered in a way that forces the rendering to restart.
   */
  @Override
  public synchronized void refresh() {
    refresh(ResetReason.SETTINGS_CHANGED);
  }

  private synchronized void refresh(ResetReason reason) {
    if (mode == RenderState.PAUSED) {
      mode = RenderState.RENDERING;
    }
    spp = 0;
    renderTime = 0;
    setResetReason(reason);
    notifyAll();
  }

  public synchronized void forceReset() {
    setResetReason(ResetReason.MODE_CHANGE);
    forceReset = true;

    // Wake up waiting threads.
    notifyAll();
  }





  // Getters and Setters:

  /** @return Current exposure value */
  public double getExposure() { return exposure; }

  /** @return <code>true</code> if sunlight is enabled */
  public boolean getDirectLight() { return sunEnabled; }

  /** @return <code>true</code> if emitters are enabled */
  public boolean getEmittersEnabled() { return emittersEnabled; }

  /** @return The <code>BlockPallete</code> for the scene */
  public BlockPalette getPalette() { return palette; }

  /** @return The name of this scene */
  public String name() { return name; }

  /** @return <code>true</code> if the scene has loaded chunks */
  public synchronized boolean haveLoadedChunks() { return !chunks.isEmpty(); }

  /** @return <code>true</code> if still water is enabled */
  public boolean stillWaterEnabled() { return stillWater; }

  /** @return <code>true</code> if biome colors are enabled */
  public boolean biomeColorsEnabled() { return biomeColors; }

  /** @return Recursive ray depth limit */
  public int getRayDepth() { return rayDepth; }

  /**
   * Find the current camera target position.
   *
   * @return {@code null} if the camera is not aiming at some intersectable object
   */
  public Vector3 getTargetPosition() {
    Ray ray = new Ray();
    if (!traceTarget(ray)) {
      return null;
    }
    else {
      Vector3 target = new Vector3(ray.o);
      target.add(origin.x, origin.y, origin.z);
      return target;
    }
  }

  /** @return The current emitter intensity */
  public double getEmitterIntensity() { return emitterIntensity; }

  /** @return {@code true} if transparent sky is enabled */
  public boolean transparentSky() { return transparentSky; }

  /** @return The current postprocessing mode */
  public Postprocess getPostprocess() { return postprocess; }

  /** @return The ocean water height */
  public int getWaterHeight() { return waterHeight; }

  /** @return the dumpFrequency */
  public int getDumpFrequency() { return dumpFrequency; }

  /** @return The target SPP */
  public int getTargetSpp() { return sppTarget; }

  /** @return Canvas width */
  public int canvasWidth() { return width; }

  /** @return Canvas height */
  public int canvasHeight() { return height; }

  /** @return World origin in the Octree */
  public Vector3i getOrigin() { return origin; }

  /** @return The sun state object. */
  public Sun sun() { return sun; }

  /** @return The sky state object. */
  public Sky sky() { return sky; }

  /** @return The camera state object. */
  public Camera camera() { return camera; }

  /**
   * Get direct access to the sample buffer.
   *
   * @return The sample buffer for this scene
   */
  public double[] getSampleBuffer() { return render.sampleBuffer; }

  /** @return <code>true</code> if the rendered buffer should be finalized */
  public boolean shouldFinalizeBuffer() { return render.shouldFinalizeBuffer; }

  public double getWaterOpacity() { return waterOpacity; }

  public double getWaterVisibility() { return waterVisibility; }

  public Vector3 getWaterColor() { return waterColor; }

  public Vector3 getFogColor() { return fogColor; }

  public boolean getUseCustomWaterColor() { return useCustomWaterColor; }

  public JsonObject getCameraPresets() { return cameraPresets; }

  public RenderState getMode() { return mode; }

  public double getFogDensity() { return fogDensity; }

  public double getSkyFogDensity() { return skyFogDensity; }

  public boolean fastFog() { return fastFog; }

  public OutputMode getOutputMode() { return sceneSaver.outputMode; }

  public Collection<ChunkPosition> getChunks() { return Collections.unmodifiableCollection(chunks); }

  public int numberOfChunks() { return chunks.size(); }

  /**
   * Clears the reset reason and returns the previous reason.
   *
   * @return the current reset reason
   */
  public synchronized ResetReason getResetReason() { return resetReason; }

  public int getYClipMin() { return yClipMin; }

  public int getYClipMax() { return yClipMax; }

  public Grid getEmitterGrid() { return emitterGrid; }

  public String getOctreeImplementation() { return octreeImplementation; }

  public EmitterSamplingStrategy getEmitterSamplingStrategy() { return emitterSamplingStrategy; }

  public int getEmitterGridSize() { return emitterGridSize; }

  public boolean isPreventNormalEmitterWithSampling() { return preventNormalEmitterWithSampling; }

  public boolean shouldSaveSnapshots() { return saveSnapshots; }

  /** @return the saveDumps */
  public boolean shouldSaveDumps() { return dumpFrequency > 0; }

  /** @return {@code true} if volumetric fog is enabled */
  public boolean fogEnabled() { return fogDensity > 0.0; }

  public boolean getForceReset() { return forceReset; }

  public Collection<Entity> getEntities() { return entities; }

  public Collection<Entity> getActors() { return actors; }


  /** Set the exposure value */
  public synchronized void setExposure(double value) {
    exposure = value;
    if (mode == RenderState.PREVIEW) {
      // don't interrupt the render if we are currently rendering
      refresh();
    }
  }

  /** Set still water mode. */
  public void setStillWater(boolean value) {
    if (value != stillWater) {
      stillWater = value;
      refresh();
    }
  }

  /** Set emitters enable flag. */
  public synchronized void setEmittersEnabled(boolean value) {
    if (value != emittersEnabled) {
      emittersEnabled = value;
      refresh();
    }
  }

  /** Set sunlight enable flag. */
  public synchronized void setDirectLight(boolean value) {
    if (value != sunEnabled) {
      sunEnabled = value;
      refresh();
    }
  }

  /** Set the biome colors flag. */
  public void setBiomeColorsEnabled(boolean value) {
    if (value != biomeColors) {
      biomeColors = value;
      refresh();
    }
  }

  /** Set the recursive ray depth limit (persistent) */
  public synchronized void setRayDepth(int value) {
    value = Math.max(1, value);
    if (rayDepth != value) {
      rayDepth = value;
      PersistentSettings.setRayDepth(rayDepth);
      refresh();
    }
  }

  /** Set the scene name. */
  public void setName(String newName) {
    newName = AsynchronousSceneManager.sanitizedSceneName(newName);
    if (newName.length() > 0) {
      name = newName;
    }
  }

  /**
   * Change the postprocessing mode
   *
   * @param p The new postprocessing mode
   */
  public synchronized void setPostprocess(Postprocess p) {
    postprocess = p;
    if (mode == RenderState.PREVIEW) {
      // Don't interrupt the render if we are currently rendering.
      refresh();
    }
  }

  /** Set the emitter intensity. */
  public void setEmitterIntensity(double value) {
    emitterIntensity = value;
    refresh();
  }

  /** Set the transparent sky option. */
  public void setTransparentSky(boolean value) {
    if (value != transparentSky) {
      transparentSky = value;
      refresh();
    }
  }

  /** Set the ocean water height. */
  public void setWaterHeight(int value) {
    value = Math.max(0, value);
    value = Math.min(256, value);
    if (value != waterHeight) {
      waterHeight = value;
      refresh();
    }
  }

  /** @param value Target SPP value */
  public void setTargetSpp(int value) {
    // TODO: update the progress bar & stop point when this is updated mid-render
    sppTarget = value;
  }

  /** Sets the dump frequency. If value is zero then render dumps are disabled. */
  public void setDumpFrequency(int value) { dumpFrequency = Math.max(0, value); }

  public void setWaterOpacity(double opacity) {
    if (opacity != waterOpacity) {
      this.waterOpacity = opacity;
      refresh();
    }
  }

  public void setWaterVisibility(double visibility) {
    if (visibility != waterVisibility) {
      this.waterVisibility = visibility;
      refresh();
    }
  }

  public void setWaterColor(Vector3 color) {
    waterColor.set(color);
    refresh();
  }

  public void setFogColor(Vector3 color) {
    fogColor.set(color);
    refresh();
  }

  public void setUseCustomWaterColor(boolean value) {
    if (value != useCustomWaterColor) {
      useCustomWaterColor = value;
      refresh();
    }
  }

  public void setFogDensity(double newValue) {
    if (newValue != fogDensity) {
      this.fogDensity = newValue;
      refresh();
    }
  }

  public void setSkyFogDensity(double newValue) {
    if (newValue != skyFogDensity) {
      this.skyFogDensity = newValue;
      refresh();
    }
  }

  public void setFastFog(boolean value) {
    if (fastFog != value) {
      fastFog = value;
      refresh();
    }
  }

  public void setSaveSnapshots(boolean value) { saveSnapshots = value; }

  /**
   * Set the buffer update flag. The buffer update flag decides whether the renderer should update the buffered image.
   */
  public void setBufferFinalization(boolean value) { render.shouldFinalizeBuffer = value; }

  public void setOutputMode(OutputMode mode) { sceneSaver.outputMode = mode; }

  public void setResetReason(ResetReason resetReason) {
    if (this.resetReason != ResetReason.SCENE_LOADED) {
      this.resetReason = resetReason;
    }
  }

  public void setYClipMin(int yClipMin) { this.yClipMin = yClipMin; }

  public void setYClipMax(int yClipMax) { this.yClipMax = yClipMax; }

  public void setOctreeImplementation(String octreeImplementation) { this.octreeImplementation = octreeImplementation; }

  public void setEmitterSamplingStrategy(EmitterSamplingStrategy emitterSamplingStrategy) {
    if (this.emitterSamplingStrategy != emitterSamplingStrategy) {
      this.emitterSamplingStrategy = emitterSamplingStrategy;
      refresh();
    }
  }

  public void setEmitterGridSize(int emitterGridSize) { this.emitterGridSize = emitterGridSize; }

  public void setPreventNormalEmitterWithSampling(boolean preventNormalEmitterWithSampling) {
    this.preventNormalEmitterWithSampling = preventNormalEmitterWithSampling;
    refresh();
  }

  /**
   * Change the canvas size for this scene. This will refresh the scene and reinitialize the sample buffers if the new
   * canvas size is not identical to the current canvas size.
   */
  public synchronized void setCanvasSize(int canvasWidth, int canvasHeight) {
    int newWidth = Math.max(MIN_CANVAS_WIDTH, canvasWidth);
    int newHeight = Math.max(MIN_CANVAS_HEIGHT, canvasHeight);
    if (newWidth != width || newHeight != height) {
      width = newWidth;
      height = newHeight;
      initBuffers();
      refresh();
    }
  }

  public synchronized void setRenderMode(RenderState renderMode) { this.mode = renderMode; }





  /**
   * @param x X coordinate in octree space
   * @param z Z coordinate in octree space
   * @return Foliage color for the given coordinates
   */
  public float[] getFoliageColor(int x, int z) {
    if (biomeColors) {
      return foliageTexture.get(x, z);
    }
    else {
      return Biomes.getFoliageColorLinear(0);
    }
  }

  /**
   * @param x X coordinate in octree space
   * @param z Z coordinate in octree space
   * @return Grass color for the given coordinates
   */
  public float[] getGrassColor(int x, int z) {
    if (biomeColors) {
      return grassTexture.get(x, z);
    }
    else {
      return Biomes.getGrassColorLinear(0);
    }
  }

  /**
   * @param x X coordinate in octree space
   * @param z Z coordinate in octree space
   * @return Water color for the given coordinates
   */
  public float[] getWaterColor(int x, int z) {
    if (biomeColors && waterTexture != null && waterTexture.contains(x, z)) {
      float[] color = waterTexture.get(x, z);
      if (color[0] > 0 || color[1] > 0 || color[2] > 0) {
        return color;
      }
      return Biomes.getWaterColorLinear(0);
    }
    else {
      return Biomes.getWaterColorLinear(0);
    }
  }

  /**
   * Modifies the emittance property for the given material.
   */
  public void setEmittance(String materialName, float value) {
    JsonObject material = materials.getOrDefault(materialName, new JsonObject()).object();
    material.set("emittance", Json.of(value));
    materials.put(materialName, material);
    refresh(ResetReason.MATERIALS_CHANGED);
  }

  /**
   * Modifies the specular coefficient property for the given material.
   */
  public void setSpecular(String materialName, float value) {
    JsonObject material = materials.getOrDefault(materialName, new JsonObject()).object();
    material.set("specular", Json.of(value));
    materials.put(materialName, material);
    refresh(ResetReason.MATERIALS_CHANGED);
  }

  /**
   * Modifies the index of refraction property for the given material.
   */
  public void setIor(String materialName, float value) {
    JsonObject material = materials.getOrDefault(materialName, new JsonObject()).object();
    material.set("ior", Json.of(value));
    materials.put(materialName, material);
    refresh(ResetReason.MATERIALS_CHANGED);
  }

  /**
   * Modifies the roughness property for the given material.
   */
  public void setPerceptualSmoothness(String materialName, float value) {
    JsonObject material = materials.getOrDefault(materialName, new JsonObject()).object();
    material.set("roughness", Json.of(Math.pow(1 - value, 2)));
    materials.put(materialName, material);
    refresh(ResetReason.MATERIALS_CHANGED);
  }





  // Octree stuff

  protected void buildBvhEntity() {
    final List<Primitive> entityPrimitives = new LinkedList<>();

    Vector3 worldOffset = new Vector3(-origin.x, -origin.y, -origin.z);
    for (Entity entity : entities) {
      entityPrimitives.addAll(entity.primitives(worldOffset));
    }
    bvhEntities = new BVH(entityPrimitives);
  }

  protected void buildBvhActor() {
    final List<Primitive> actorPrimitives = new LinkedList<>();

    Vector3 worldOffset = new Vector3(-origin.x, -origin.y, -origin.z);
    for (Entity entity : actors) {
      actorPrimitives.addAll(entity.primitives(worldOffset));
    }
    bvhActors = new BVH(actorPrimitives);
  }

  /**
   * Rebuild the actors and the other blocks bounding volume hierarchy.
   */
  public void rebuildBvh() {
    buildBvhEntity();
    buildBvhActor();
    refresh();
  }

  /**
   * Rebuild the actors bounding volume hierarchy.
   */
  public void rebuildActorBvh() {
    buildBvhActor();
    refresh();
  }

  protected int calculateOctreeOrigin(Collection<ChunkPosition> chunksToLoad) {
    IntBoundingBox range = ChunkPosition.chunkBounds(chunksToLoad);

    int maxDimension = Math.max(yMax - yMin, range.maxDimension());
    int requiredDepth = QuickMath.log2(QuickMath.nextPow2(maxDimension));

    int xroom = (1 << requiredDepth) - (range.widthX());
    int yroom = (1 << requiredDepth) - (yMax - yMin);
    int zroom = (1 << requiredDepth) - (range.widthZ());

    origin.set(range.xmin - xroom / 2, -yroom / 2, range.zmin - zroom / 2);
    return requiredDepth;
  }

  public JsonObject getPlayerProfile(PlayerEntity entity) {
    if (profiles.containsKey(entity)) {
      return profiles.get(entity);
    }
    else {
      return new JsonObject();
    }
  }

  public void removeEntity(Entity player) {
    if (player instanceof PlayerEntity) {
      profiles.remove(player);
    }
    actors.remove(player);
    rebuildActorBvh();
  }

  public void addPlayer(PlayerEntity player) {
    if (!actors.contains(player)) {
      profiles.put(player, new JsonObject());
      actors.add(player);
      rebuildActorBvh();
    }
    else {
      Log.warn("Failed to add player: entity already exists (" + player + ")");
    }
  }





  // Tracing stuff

  public boolean isInWater(Ray ray) {
    if (waterHeight > 0 && ray.o.y < waterHeight - 0.125) {
      return true;
    }
    if (waterOctree.isInside(ray.o)) {
      int x = (int)QuickMath.floor(ray.o.x);
      int y = (int)QuickMath.floor(ray.o.y);
      int z = (int)QuickMath.floor(ray.o.z);
      Octree.Node node = waterOctree.get(x, y, z);
      Material block = palette.get(node.type);
      return block.isWater()
            && ((ray.o.y - y) < 0.875 || ((Water)block).isFullBlock());
    }
    return false;
  }

  public boolean isInsideOctree(Vector3 vec) {
    return worldOctree.isInside(vec);
  }

  /**
   * Trace a ray in this scene. This offsets the ray origin to move it into the scene coordinate space.
   */
  public void rayTrace(RayTracer rayTracer, WorkerState state) {
    state.ray.o.x -= origin.x;
    state.ray.o.y -= origin.y;
    state.ray.o.z -= origin.z;

    rayTracer.trace(this, state);
  }

  /**
   * Find closest intersection between ray and scene. This advances the ray by updating the ray origin if an
   * intersection is found.
   *
   * @param ray ray to test against scene
   * @return <code>true</code> if an intersection was found
   */
  public boolean intersectScene(Ray ray) {
    boolean hit = false;
    if (bvhEntities.closestIntersection(ray)) {
      hit = true;
    }
    if (renderActors) {
      if (bvhActors.closestIntersection(ray)) {
        hit = true;
      }
    }
    if (intersectOctree(ray)) {
      hit = true;
    }
    if (hit) {
      ray.distance += ray.t;
      ray.o.scaleAdd(ray.t, ray.d);
      updateRayOpacity(ray);
      return true;
    }
    return false;
  }

  /**
   * Test whether the ray intersects any voxel before exiting the Octree.
   *
   * @param ray the ray
   * @return {@code true} if the ray intersects a voxel
   */
  private boolean intersectOctree(Ray ray) {
    Ray start = new Ray(ray);
    start.setCurrentMaterial(ray.getPrevMaterial(), ray.getPrevData());
    boolean hit = false;
    Ray r = new Ray(start);
    r.setCurrentMaterial(start.getPrevMaterial(), start.getPrevData());
    if (worldOctree.enterBlock(this, r, palette) && r.distance < ray.t) {
      ray.t = r.distance;
      ray.n.set(r.n);
      ray.color.set(r.color);
      ray.setPrevMaterial(r.getPrevMaterial(), r.getPrevData());
      ray.setCurrentMaterial(r.getCurrentMaterial(), r.getCurrentData());
      hit = true;
    }
    if (start.getCurrentMaterial().isWater()) {
      if (start.getCurrentMaterial() != Water.OCEAN_WATER) {
        r = new Ray(start);
        r.setCurrentMaterial(start.getPrevMaterial(), start.getPrevData());
        if (waterOctree.exitWater(this, r, palette) && r.distance < ray.t - Ray.EPSILON) {
          ray.t = r.distance;
          ray.n.set(r.n);
          ray.color.set(r.color);
          ray.setPrevMaterial(r.getPrevMaterial(), r.getPrevData());
          ray.setCurrentMaterial(r.getCurrentMaterial(), r.getCurrentData());
          hit = true;
        }
        else if (ray.getPrevMaterial() == Air.INSTANCE) {
          ray.setPrevMaterial(Water.INSTANCE, 1 << Water.FULL_BLOCK);
        }
      }
    }
    else {
      r = new Ray(start);
      r.setCurrentMaterial(start.getPrevMaterial(), start.getPrevData());
      if (waterOctree.enterBlock(this, r, palette) && r.distance < ray.t) {
        ray.t = r.distance;
        ray.n.set(r.n);
        ray.color.set(r.color);
        ray.setPrevMaterial(r.getPrevMaterial(), r.getPrevData());
        ray.setCurrentMaterial(r.getCurrentMaterial(), r.getCurrentData());
        hit = true;
      }
    }
    return hit;
  }

  public void updateRayOpacity(Ray ray) {
    if (ray.getCurrentMaterial().isWater() || (ray.getCurrentMaterial() == Air.INSTANCE
          && ray.getPrevMaterial().isWater())) {
      if (useCustomWaterColor) {
        ray.color.x = waterColor.x;
        ray.color.y = waterColor.y;
        ray.color.z = waterColor.z;
      }
      else {
        float[] waterColor = ray.getBiomeWaterColor(this);
        ray.color.x *= waterColor[0];
        ray.color.y *= waterColor[1];
        ray.color.z *= waterColor[2];
      }
      ray.color.w = waterOpacity;
    }
  }

  /**
   * Test if the ray should be killed (using Russian Roulette).
   *
   * @return {@code true} if the ray needs to die now
   */
  public final boolean rayShouldDie(int depth, Random random) {
    return depth >= rayDepth && random.nextDouble() < .5f;
  }

  /**
   * Trace a ray in the Octree towards the current view target. The ray is displaced to the target position if it hits
   * something.
   *
   * <p>The view target is defined by the current camera state.
   *
   * @return {@code true} if the ray hit something
   */
  public boolean traceTarget(Ray ray) {
    WorkerState state = new WorkerState();
    state.ray = ray;
    if (isInWater(ray)) {
      ray.setCurrentMaterial(Water.INSTANCE);
    }
    else {
      ray.setCurrentMaterial(Air.INSTANCE);
    }
    camera.getTargetDirection(ray);
    ray.o.x -= origin.x;
    ray.o.y -= origin.y;
    ray.o.z -= origin.z;
    while (PreviewRayTracer.nextIntersection(this, ray)) {
      if (ray.getCurrentMaterial() != Air.INSTANCE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Renders a fog effect over the sky near the horizon.
   */
  public void addSkyFog(Ray ray) {
    if (fogEnabled()) {
      // This does not take fog density into account because the sky is
      // most consistently treated as being infinitely far away.
      double fog;
      if (ray.d.y > 0) {
        fog = 1 - ray.d.y;
        fog *= fog;
      }
      else {
        fog = 1;
      }
      fog *= skyFogDensity;
      ray.color.x = (1 - fog) * ray.color.x + fog * fogColor.x;
      ray.color.y = (1 - fog) * ray.color.y + fog * fogColor.y;
      ray.color.z = (1 - fog) * ray.color.z + fog * fogColor.z;
    }
  }





  // camera stuff

  /**
   * Center the camera over the loaded chunks
   */
  public synchronized void moveCameraToCenter() {
    if (chunks.isEmpty()) {
      camera.setPosition(new Vector3(0, 128, 0));
      return;
    }

    IntBoundingBox range = ChunkPosition.chunkBounds(chunks);
    int xcenter = range.midpointX();
    int zcenter = range.midpointZ();
    int ycenter = (yMax + yMin) / 2;

    for (int y = ycenter + 128; y >= ycenter - 128; --y) {
      Material block = worldOctree.getMaterial(xcenter - origin.x, y - origin.y, zcenter - origin.z,
            palette);
      if (!(block instanceof Air)) {
        camera.setPosition(new Vector3(xcenter, y + 5, zcenter));
        return;
      }
    }

    camera.setPosition(new Vector3(xcenter, 128, zcenter));
  }

  /**
   * Move the camera to the player position, if available.
   */
  public void moveCameraToPlayer() {
    for (Entity entity : actors) {
      if (entity instanceof PlayerEntity) {
        camera.moveToPlayer(entity);
      }
    }
  }

  /**
   * Perform auto focus.
   */
  public void autoFocus() {
    Ray ray = new Ray();
    if (!traceTarget(ray)) {
      camera.setDof(Double.POSITIVE_INFINITY);
    }
    else {
      camera.setSubjectDistance(ray.distance);
      camera.setDof(ray.distance * ray.distance);
    }
  }

  public void saveCameraPreset(String name) {
    camera.name = name;
    cameraPresets.set(name, camera.toJson());
  }

  public void loadCameraPreset(String name) {
    JsonValue value = cameraPresets.get(name);
    if (value.isObject()) {
      camera.importFromJson(value.object());
      refresh();
    }
  }

  public void deleteCameraPreset(String name) {
    for (int i = 0; i < cameraPresets.size(); ++i) {
      if (cameraPresets.get(i).name.equals(name)) {
        cameraPresets.remove(i);
        return;
      }
    }
  }





  // Materials stuff

  public void importMaterials() {
    ExtraMaterials.loadDefaultMaterialProperties();
    MaterialStore.collections.forEach((name, coll) -> importMaterial(materials, name, coll));
    MaterialStore.blockIds.forEach((name) -> {
      JsonValue properties = materials.get(name);
      if (properties != null) {
        palette.updateProperties(name, block -> {
          block.loadMaterialProperties(properties.asObject());
        });
      }
    });
    ExtraMaterials.idMap.forEach((name, material) -> {
      JsonValue properties = materials.get(name);
      if (properties != null) {
        material.loadMaterialProperties(properties.asObject());
      }
    });
  }

  private void importMaterial(Map<String, JsonValue> propertyMap, String name,
                              Collection<? extends Material> materials) {
    JsonValue value = propertyMap.get(name);
    if (value != null) {
      JsonObject properties = value.object();
      for (Material material : materials) {
        material.loadMaterialProperties(properties);
      }
    }
  }


}

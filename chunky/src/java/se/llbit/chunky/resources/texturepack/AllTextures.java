/* Copyright (c) 2020-2021 Chunky contributors
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
package se.llbit.chunky.resources.texturepack;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;
import se.llbit.util.BitmapImage;

/**
 * This texture will try to load several textures and fail if at least one of them could not be
 * loaded.
 */
public class AllTextures extends TextureLoader {
  private final TextureLoader[] textures;

  /**
   * Attempts to load all given textures.
   *
   * @param textures List of textures to load
   */
  public AllTextures(TextureLoader... textures) {
    this.textures = textures;
  }

  /** Don't use this. */
  public AllTextures(TextureLoader ignored) {
    throw new Error("It is pointless to create an all texture loader with only one texture.");
  }

  @Override
  public boolean load(ZipFile texturePack, String topLevelDir) {
    int loaded = 0;
    for (TextureLoader alternative : textures) {
      if (alternative.load(texturePack, topLevelDir)) {
        loaded++;
      }
    }
    return loaded == textures.length;
  }

  @Override
  public boolean loadFromTerrain(BitmapImage[] terrain) {
    int loaded = 0;
    for (TextureLoader alternative : textures) {
      if (alternative.loadFromTerrain(terrain)) {
        loaded++;
      }
    }
    return loaded == textures.length;
  }

  @Override
  protected boolean load(InputStream imageStream) throws IOException, TextureFormatError {
    throw new UnsupportedOperationException("Call load(ZipFile) instead!");
  }
}

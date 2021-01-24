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

import se.llbit.util.BitmapImage;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;

/** This texture loader will load different textures depending on a texture being available. */
public class ConditionalTextures extends TextureLoader {
  private final String testFor;
  private final TextureLoader then;
  private final TextureLoader otherwise;

  public ConditionalTextures(String testFor, TextureLoader then, TextureLoader otherwise) {
    this.testFor = testFor;
    this.then = then;
    this.otherwise = otherwise;
  }

  @Override
  public boolean load(ZipFile texturePack, String topLevelDir) {
    if (texturePack.getEntry(topLevelDir + testFor) != null) {
      return then.load(texturePack, topLevelDir);
    }
    return otherwise.load(texturePack, topLevelDir);
  }

  @Override
  public boolean loadFromTerrain(BitmapImage[] terrain) {
    throw new UnsupportedOperationException("ConditionalTextures doesn't support loadFromTerrain");
  }

  @Override
  protected boolean load(InputStream imageStream) throws IOException, TextureFormatError {
    throw new UnsupportedOperationException("Call load(ZipFile) instead!");
  }
}

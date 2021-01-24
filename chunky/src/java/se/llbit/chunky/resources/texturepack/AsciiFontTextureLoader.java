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

import se.llbit.chunky.resources.Texture;
import se.llbit.chunky.resources.texturepack.FontTexture.Glyph;
import se.llbit.util.BitmapImage;
import se.llbit.util.file.ImageLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;

/** @author Jesper Öqvist <jesper@llbit.se> */
public class AsciiFontTextureLoader extends TextureLoader {
  private final String[] ASCII_MAP =
      new String[]{
          "ÀÁÂÈÊËÍÓÔÕÚßãõğİ", "ıŒœŞşŴŵžȇ", " !\"#$%&\\'()*+,-./", "0123456789:;<=>?",
          "@ABCDEFGHIJKLMNO", "PQRSTUVWXYZ[\\\\]^_", "`abcdefghijklmno", "pqrstuvwxyz{|}~",
          "ÇüéâäàåçêëèïîìÄÅ", "ÉæÆôöòûùÿÖÜø£Ø×ƒ", "áíóúñÑªº¿®¬½¼¡«»", "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐",
          "└┴┬├─┼╞╟╚╔╩╦╠═╬╧", "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀", "αβΓπΣσμτΦΘΩδ∞∅∈∩", "≡±≥≤⌠⌡÷≈°∙·√ⁿ²■"
      };

  private final String file;

  public AsciiFontTextureLoader(String file) {
    this.file = file;
  }

  @Override
  protected boolean load(InputStream imageStream) throws IOException, TextureFormatError {
    BitmapImage spritemap = ImageLoader.read(imageStream);
    if (spritemap.width != 128 || spritemap.height != 128) {
      throw new TextureFormatError("ASCII font texture must be 128 by 128 pixels");
    }
    loadGlyphs(spritemap, ASCII_MAP, 8, 8, 7);
    return true;
  }

  @Override
  public boolean load(ZipFile texturePack, String topLevelDir) {
    return load(topLevelDir + file, texturePack);
  }

  private static void loadGlyphs(
      BitmapImage spritemap, String[] map, int width, int height, int ascent) {
    for (int y = 0; y < map.length; y++) {
      String line = map[y];
      for (int x = 0; x < line.length(); x++) {
        Texture.fonts.loadGlyph(spritemap, x, y, line.charAt(x), width, height, ascent);
      }
    }

    Texture.fonts.setGlyph(' ', new Glyph(new int[8], 0, 2, 8, 8, 7));
  }
}

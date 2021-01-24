/* Copyright (c) 2015-2021 Jesper Öqvist <jesper@llbit.se>
 * Copyright (c) 2015-2021 Chunky contributors
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

import java.util.HashMap;
import java.util.Map;

public class FontTexture {
  public static class Glyph {
    public final int[] lines;
    public final int xmin;
    public final int xmax;
    public final int width;
    public final int spriteWidth;
    public final int height;
    public final int ascent;

    public Glyph(int[] lines, int xmin, int xmax, int width, int height, int ascent) {
      this.lines = lines;
      this.xmin = xmin;
      this.xmax = xmax;
      if (xmax >= xmin) {
        this.width = xmax - xmin + 2;
      } else {
        this.width = 8;
      }
      this.spriteWidth = width;
      this.height = height;
      this.ascent = ascent;
    }
  }

  /** Maps code points to glyphs */
  private final Map<Integer, Glyph> glyphs = new HashMap<>();

  public Glyph getGlyph(int codePoint) {
    return glyphs.get(codePoint);
  }

  public void setGlyph(int codePoint, Glyph glyph) {
    glyphs.put(codePoint, glyph);
  }

  public boolean containsGlyph(int codePoint) {
    return glyphs.containsKey(codePoint);
  }

  public void clear() {
    glyphs.clear();
  }

  /**
   * Load a glyph from a spritemap where all glyphs have the same dimensions.
   *
   * @param spritemap Spritemap
   * @param x0        Column of the glyph
   * @param y0        Row of the glyph
   * @param codePoint Code point the glyph corresponds to
   * @param width     Width of the glyphs, in pixels
   * @param height    Height of the glyphs, in pixels
   * @param ascent    The number of pixels to move the glyph up in the line, used e.g. for accents on capital letters
   */
  void loadGlyph(
      BitmapImage spritemap, int x0, int y0, int codePoint, int width, int height, int ascent) {
    int xmin = width;
    int xmax = 0;
    int[] lines = new int[height];
    for (int i = 0; i < height; ++i) {
      for (int j = 0; j < width; ++j) {
        int rgb = spritemap.getPixel(x0 * width + j, y0 * height + i);
        if (rgb != 0) {
          lines[i] |= 1 << j;
          if (j < xmin) {
            xmin = j;
          }
          if (j > xmax) {
            xmax = j;
          }
        }
      }
    }
    setGlyph(codePoint, new Glyph(lines, xmin, xmax, width, height, ascent));
  }
}

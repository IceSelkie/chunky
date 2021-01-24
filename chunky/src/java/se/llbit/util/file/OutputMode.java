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
package se.llbit.util.file;

public enum OutputMode // outputFileTypes
{

  /** Standard PNG with 8-bit color channels. **/
  PNG(".png", "PNG"),

  /** TIFF with 32-bit color channels. **/
  TIFF_32(".tiff", "TIFF, 32-bit floating point"),

  /** PFM with 32-bit color channels. **/
  PFM(".pfm", "PFM, Portable FloatMap (32-bit)");

  private final String description;
  private final String extension;


  public static final OutputMode DEFAULT = PNG;

  OutputMode(String extension, String description) {
    this.description = description;
    this.extension = extension;
  }
  @Override
  public String toString() {return description;}
  public String getExtension() {return extension;}


  public static OutputMode get(String name) {
    try {
      return OutputMode.valueOf(name);
    } catch (IllegalArgumentException e) {
      return DEFAULT;
    }
  }

  public static OutputMode fromExtension(String extension) {
    switch (extension.toLowerCase()) {
      case ".png":
        return PNG;
      case ".tiff":
        return TIFF_32;
      case ".pfm":
        return PFM;
      default:
        return DEFAULT;
    }
  }
}

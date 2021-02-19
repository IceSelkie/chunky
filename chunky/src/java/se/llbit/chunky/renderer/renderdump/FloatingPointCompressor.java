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
package se.llbit.chunky.renderer.renderdump;

import se.llbit.chunky.renderer.scene.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.function.LongConsumer;

/**
 * Implementation of the FPC algorithm
 * (compression algorithm for double precision floating pointer number)
 * http://cs.txstate.edu/~burtscher/papers/tr06.pdf
 */
public class FloatingPointCompressor {

  private static class EncoderDecoder {
    private static final int TABLE_SIZE = 1 << 10; // Must be a power of 2

    private final long[] fcm = new long[TABLE_SIZE];
    private final long[] dfcm = new long[TABLE_SIZE];
    private int fcm_hash = 0;
    private int dfcm_hash = 0;
    private long current = 0;
    private long previous = 0;
    private final byte[] smallBuffer = new byte[17]; // In the worst case, a double pair takes 17 bytes
    private int bytesWritten;

    private long predictFcm() {
      long prediction = fcm[fcm_hash];
      fcm[fcm_hash] = current;
      fcm_hash = (int) (((fcm_hash << 6) ^ (current >>> 48)) & (TABLE_SIZE - 1));
      return prediction;
    }

    private long predictDfcm() {
      long prediction = dfcm[dfcm_hash] + previous;
      dfcm[dfcm_hash] = current - previous;
      dfcm_hash = (int) (((dfcm_hash << 2) ^ ((current - previous) >>> 40)) & (TABLE_SIZE - 1));
      previous = current;
      return prediction;
    }

    private static int zeroBytes(long value) {
      return Long.numberOfLeadingZeros(value) / 8;
    }

    private byte encodeSingle(double d) {
      long bits = Double.doubleToRawLongBits(d);

      long xoredValue = predictFcm() ^ bits;
      long dfcmXoredValue = predictDfcm() ^ bits;
      current = bits;

      int zeroBytesCount = zeroBytes(xoredValue);
      int dfcmZeroBytesCount = zeroBytes(dfcmXoredValue);

      byte choiceBit = 0;

      if (dfcmZeroBytesCount > zeroBytesCount) {
        // Choose the prediction with the most leading zero bytes
        xoredValue = dfcmXoredValue;
        zeroBytesCount = dfcmZeroBytesCount;
        choiceBit = 1;
      }

      // We use 3 bits to represent numbers between 0 and 8 inclusive (ie 9 values)
      // One of them cannot be represented, and is excluded
      // According to the paper, it is best to exclude 4
      if (zeroBytesCount == 4)
        zeroBytesCount = 3;

      // Encode the number of 0 bytes to be between 0 and 7
      byte encodedZeroBytes = (byte) zeroBytesCount;
      if (encodedZeroBytes > 4)
        --encodedZeroBytes;

      byte header = (byte) ((encodedZeroBytes << 1) | choiceBit);

      for (int byteNo = 7 - zeroBytesCount; byteNo >= 0; --byteNo) {
        smallBuffer[bytesWritten] = (byte) (xoredValue >>> (byteNo * 8));
        ++bytesWritten;
      }

      return header;
    }

    public void encodePair(double first, double second, BufferedOutputStream output) throws IOException {
      bytesWritten = 1;
      byte headerFirst = encodeSingle(first);
      byte headerSecond = encodeSingle(second);
      byte header = (byte) ((headerFirst << 4) | headerSecond);
      smallBuffer[0] = header;

      output.write(smallBuffer, 0, bytesWritten);
    }

    public void encodeSingleWithOddTerminator(double val, BufferedOutputStream output) throws IOException {
      bytesWritten = 1;
      byte headerFirst = encodeSingle(val);
      byte headerSecond = 0b1100; // Pretend we have 7 zero bytes but the 8th byte is 0 as well
      smallBuffer[bytesWritten] = 0;
      ++bytesWritten;
      byte header = (byte) ((headerFirst << 4) | headerSecond);
      smallBuffer[0] = header;

      output.write(smallBuffer, 0, bytesWritten);
    }

    public double decodeSingle(byte header, BufferedInputStream input) throws IOException {
      long prediction = predictFcm();
      long dfcmPrediction = predictDfcm();

      byte choiceBit = (byte) (header & 1);
      byte encodedZeroBytes = (byte) ((header >>> 1) & 0x07);

      if (choiceBit == 1)
        prediction = dfcmPrediction;

      int zeroBytes = encodedZeroBytes;
      if (zeroBytes > 3)
        ++zeroBytes;

      long difference = 0;
      int byteToRead = 8 - zeroBytes;
      for (int i = 0; i < byteToRead; ++i) {
        difference = (difference << 8) | input.read();
      }

      long bits = prediction ^ difference;
      current = bits;

      return Double.longBitsToDouble(bits);
    }
  }



  public static void compress(SampleBuffer input, OutputStream output, LongConsumer pixelProgress) throws IOException {
    try (CompressionStream compressionStream = new CompressionStream(input.numberOfPixels(), output)) {
      for (int y = 0; y < input.width; y++)
        for (int x = 0; x < input.height; x++) {
          compressionStream.writePixel(input, x, y);
          pixelProgress.accept((long) x * input.width + y);
        }
    }
  }

  public static class CompressionStream implements Closeable {
    final BufferedOutputStream out;
    final long expectedPixels;
    long index;
    final EncoderDecoder r, g, b;
    final LinkedList<Double> queue;

    public CompressionStream(long pixelCount, OutputStream output) {
      this.out = new BufferedOutputStream(output);
      this.expectedPixels = pixelCount;
      this.index = 0;
      this.r = new EncoderDecoder();
      this.g = new EncoderDecoder();
      this.b = new EncoderDecoder();
      queue = new LinkedList<>();
    }

    public void writePixel(SampleBuffer input, int x, int y) throws IOException {
      if (++index > expectedPixels)
        throw new IllegalStateException("Stream already completed. (Received " + index + " of " + expectedPixels + " expected values.)");

//      if (queue.size() == 3) {
//        r.encodePair(queue.removeFirst(), input.get(x, y, 0), out);
//        g.encodePair(queue.removeFirst(), input.get(x, y, 1), out);
//        b.encodePair(queue.removeFirst(), input.get(x, y, 2), out);
//      } else {
//        queue.addLast(input.get(x, y, 0));
//        queue.addLast(input.get(x, y, 1));
//        queue.addLast(input.get(x, y, 2));
//      }

      DataOutputStream ds = new DataOutputStream(out);
      ds.writeDouble(input.get(x,y,0));
      ds.writeDouble(input.get(x,y,1));
      ds.writeDouble(input.get(x,y,2));
//      ds.flush();
    }

    public void close() throws IOException {
      try {
        if (queue.size() == 3) {
          r.encodeSingleWithOddTerminator(queue.removeFirst(), out);
          g.encodeSingleWithOddTerminator(queue.removeFirst(), out);
          b.encodeSingleWithOddTerminator(queue.removeFirst(), out);
        }
      } catch (IOException e) {
        out.close();
        throw e;
      }

      if (expectedPixels != index)
        throw new IllegalStateException("Stream not yet completed. Only received " + index + " of " + expectedPixels + " expected values.");

      if (queue.size() != 0)
        throw new IllegalStateException("Compression queue has " + queue.size() + " unexpected values left.");
      out.close();
    }
  }

  public static class DecompressionStream implements Closeable {
    final BufferedInputStream in;
    final long expectedPixels;
    long index;
    final EncoderDecoder r, g, b;
    final double[] cache;
    boolean hasQueued = false;

    public DecompressionStream(long expectedPixels, InputStream input) {
      this.in = new BufferedInputStream(input);
      this.expectedPixels = expectedPixels;
      this.index = 0;
      this.r = new EncoderDecoder();
      this.g = new EncoderDecoder();
      this.b = new EncoderDecoder();
      cache = new double[6];
    }

    public void readPixel(SampleBuffer output, int x, int y) throws IOException {
      int idx = readPixel();
      output.setPixel(x, y, cache[idx + 0], cache[idx + 1], cache[idx + 2]);
    }

    private int readPixel() throws IOException {
      if (++index > expectedPixels)
        throw new IllegalStateException("Stream already completed. (Requesting " + index + " of " + expectedPixels + " expected values.)");
      if (hasQueued) {
        hasQueued = false;
        return 3;
      }
      readPair(r, 0);
      readPair(g, 1);
      readPair(b, 2);
      hasQueued = (index != expectedPixels);
      return 0;
    }

    public void close() throws IOException {
      in.close();
      if (expectedPixels != index)
        System.err.println("Not done yet! (only at "+index+" of "+expectedPixels+")");
      if (hasQueued)
        throw new IllegalStateException("Decompression queue has unexpected extra value.");
    }

    private void readPair(EncoderDecoder decoder, int offset) throws IOException {
      byte groupedHeader = (byte) in.read();
      byte firstHeader = (byte) ((groupedHeader >>> 4) & 0x0F);
      byte secondHeader = (byte) (groupedHeader & 0x0F);
      cache[offset] = decoder.decodeSingle(firstHeader, in);
      cache[offset + 3] = decoder.decodeSingle(secondHeader, in);
    }
  }

  public static void decompress(InputStream input, long bufferLength, PixelConsumer consumer, LongConsumer pixelProgress)
      throws IOException {
    int idx;
    try (DecompressionStream decompressor = new DecompressionStream(bufferLength / 3, input)) {
      double[] p = decompressor.cache;
      for (long i = 0; i < bufferLength; i++) {
        idx = decompressor.readPixel();
        consumer.consume(i, p[idx + 0], p[idx + 1], p[idx + 2]);
        pixelProgress.accept(i);
      }
    }
  }
}

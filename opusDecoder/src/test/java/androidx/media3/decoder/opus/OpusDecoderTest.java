package androidx.media3.decoder.opus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33, instrumentedPackages = "androidx.media3.decoder.opus",
    shadows = {OpusDecoderTest.ShadowOpusLibrary.class, OpusDecoderTest.ShadowOpusDecoder.class})
public class OpusDecoderTest {
  private static final long NATIVE_CONTEXT = 0x1234L;

  @Before
  public void resetNativeFailures() {
    ShadowOpusDecoder.failInit = false;
    ShadowOpusDecoder.failFloat = false;
    ShadowOpusDecoder.closed = false;
  }

  @Test
  public void constructorFailuresReleaseWorkerAndAnyNativeContext() {
    long threadsBefore = decoderThreadCount();
    assertThrows(OpusDecoderException.class, () -> createDecoder(List.of(new byte[0])));
    assertEquals(threadsBefore, decoderThreadCount());

    ShadowOpusDecoder.failInit = true;
    assertThrows(OpusDecoderException.class, () -> createDecoder(List.of(header())));
    assertEquals(threadsBefore, decoderThreadCount());
    ShadowOpusDecoder.failInit = false;

    ShadowOpusDecoder.failFloat = true;
    assertThrows(IllegalStateException.class,
        () -> new OpusDecoder(1, 1, 16, List.of(header()), null, true));
    assertEquals(threadsBefore, decoderThreadCount());
    assertTrue(ShadowOpusDecoder.closed);
  }

  private static long decoderThreadCount() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.isAlive() && thread.getName().equals("ExoPlayer:SimpleDecoder"))
        .count();
  }

  @Test
  public void truncatedHeaderThrowsDecoderException() {
    for (int length = 0; length < 19; length++) {
      byte[] truncatedHeader = new byte[length];
      OpusDecoderException error =
          assertThrows(OpusDecoderException.class, () -> createDecoder(List.of(truncatedHeader)));
      assertEquals("Invalid header length", error.getMessage());
    }
  }

  @Test
  public void invalidOptionalDelayBuffersStillThrowDecoderException() {
    for (int index = 1; index <= 2; index++) {
      List<byte[]> initializationData =
          List.of(header(), new byte[index == 1 ? 7 : 8], new byte[index == 2 ? 9 : 8]);
      OpusDecoderException error =
          assertThrows(OpusDecoderException.class, () -> createDecoder(initializationData));
      assertEquals("Invalid pre-skip or seek pre-roll", error.getMessage());
    }
  }

  @Test
  public void validHeadersRetainPreSkipAndSeekPreRoll() throws Exception {
    List<byte[]> singleBuffer = List.of(header());
    OpusDecoder decoder = createDecoder(singleBuffer);
    try {
      assertEquals(2, decoder.channelCount);
      assertEquals(312, OpusDecoder.getPreSkipSamples(singleBuffer));
      assertEquals(3840, OpusDecoder.getSeekPreRollSamples(singleBuffer));
    } finally {
      decoder.release();
    }

    List<byte[]> fullData = List.of(header(), nanoseconds(10_000_000), nanoseconds(90_000_000));
    decoder = createDecoder(fullData);
    try {
      assertEquals(480, OpusDecoder.getPreSkipSamples(fullData));
      assertEquals(4320, OpusDecoder.getSeekPreRollSamples(fullData));
    } finally {
      decoder.release();
    }
  }

  @Test
  public void decodeErrorReadsMessageFromNativeContext() throws Exception {
    OpusDecoder decoder = createDecoder(List.of(header()));
    try {
      DecoderInputBuffer input = decoder.createInputBuffer();
      input.ensureSpaceForWrite(1);
      input.data.put((byte) 0xff);
      input.flip();
      OpusDecoderException error = decoder.decode(input, decoder.createOutputBuffer(), false);
      assertNotNull(error);
      assertEquals("Decode error: corrupted stream", error.getMessage());
    } finally {
      decoder.release();
    }
  }

  private static OpusDecoder createDecoder(List<byte[]> initializationData)
      throws OpusDecoderException {
    return new OpusDecoder(1, 1, 16, initializationData, null, false);
  }

  private static byte[] header() {
    return new byte[] {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 2, 0x38, 1, 0, 0, 0, 0, 0, 0, 0};
  }

  private static byte[] nanoseconds(long value) {
    return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array();
  }

  @Implements(OpusLibrary.class)
  public static class ShadowOpusLibrary {
    @Implementation
    protected static boolean isAvailable() {
      return true;
    }
  }

  @Implements(OpusDecoder.class)
  public static class ShadowOpusDecoder {
    static boolean failInit;
    static boolean failFloat;
    static boolean closed;

    @Implementation
    protected void opusSetFloatOutput(long context) {
      if (failFloat) throw new IllegalStateException("float output failed");
    }

    @Implementation
    protected long opusInit(int sampleRate, int channelCount, int numStreams, int numCoupled,
        int gain, byte[] streamMap) {
      return failInit ? 0 : NATIVE_CONTEXT;
    }

    @Implementation
    protected int opusDecode(long context, long timeUs, ByteBuffer input, int inputSize,
        SimpleDecoderOutputBuffer output) {
      assertEquals(NATIVE_CONTEXT, context);
      return -4;
    }

    @Implementation
    protected String opusGetErrorMessage(long context) {
      assertEquals(NATIVE_CONTEXT, context);
      return "corrupted stream";
    }

    @Implementation
    protected void opusClose(long context) {
      closed = true;
      assertEquals(NATIVE_CONTEXT, context);
    }
  }
}

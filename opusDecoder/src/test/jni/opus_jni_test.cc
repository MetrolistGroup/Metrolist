#ifdef NDEBUG
#undef NDEBUG
#endif

#include "../../main/jni/opus_jni.cc"

#include <cassert>
#include <cstdarg>

namespace {
int initCalls;
int directBufferCalls;
bool initThrows;
jint expectedOutputSize;
alignas(float) uint8_t output[5760 * 2 * sizeof(float)];

jobject InitOutput(JNIEnv*, jobject, jmethodID, va_list args) {
  ++initCalls;
  assert(va_arg(args, jlong) == 123);
  assert(va_arg(args, jint) == expectedOutputSize);
  return reinterpret_cast<jobject>(output);
}

jboolean ExceptionCheck(JNIEnv*) { return initThrows ? JNI_TRUE : JNI_FALSE; }

void* DirectBufferAddress(JNIEnv*, jobject buffer) {
  ++directBufferCalls;
  return reinterpret_cast<void*>(buffer);
}

void DecodeInitializesOutputOnce(JNIEnv* env, bool outputFloat, int channels) {
  int error;
  const unsigned char mapping[] = {0, 1};
  OpusDecoderContext context;
  context.decoder =
      opus_multistream_decoder_create(48000, channels, 1, channels - 1, mapping, &error);
  assert(error == OPUS_OK);
  context.channelCount = channels;
  context.outputFloat = outputFloat;
  const jlong nativeContext = reinterpret_cast<intptr_t>(&context);
  const int bytesPerFrame = channels * (outputFloat ? sizeof(float) : sizeof(int16_t));
  expectedOutputSize = 960 * bytesPerFrame;
  uint8_t silence[] = {0xf8, 0xff, 0xfe};

  initCalls = directBufferCalls = 0;
  initThrows = false;
  assert(opusDecode(env, nullptr, nativeContext, 123,
                    reinterpret_cast<jobject>(silence), sizeof(silence),
                    nullptr) == 960 * bytesPerFrame);
  assert(initCalls == 1);
  assert(directBufferCalls == 2);
  assert(context.errorCode == OPUS_OK);

  uint8_t invalidPacket[] = {0xff};
  initCalls = 0;
  assert(opusDecode(env, nullptr, nativeContext, 123,
                    reinterpret_cast<jobject>(invalidPacket),
                    sizeof(invalidPacket), nullptr) == OPUS_INVALID_PACKET);
  assert(initCalls == 0);
  assert(context.errorCode == OPUS_INVALID_PACKET);

  // Six 20 ms CBR frames make a valid maximum-duration packet.
  uint8_t longPacket[] = {0xfb, 6, 0xff, 0xfe, 0xff, 0xfe, 0xff, 0xfe,
                         0xff, 0xfe, 0xff, 0xfe, 0xff, 0xfe};
  expectedOutputSize = 5760 * bytesPerFrame;
  assert(opusDecode(env, nullptr, nativeContext, 123,
                    reinterpret_cast<jobject>(longPacket), sizeof(longPacket),
                    nullptr) == expectedOutputSize);
  assert(initCalls == 1);
  assert(context.errorCode == OPUS_OK);

  longPacket[1] = 7;  // Over the 120 ms maximum.
  initCalls = 0;
  assert(opusDecode(env, nullptr, nativeContext, 123,
                    reinterpret_cast<jobject>(longPacket), sizeof(longPacket),
                    nullptr) == OPUS_INVALID_PACKET);
  assert(initCalls == 0);
  assert(context.errorCode == OPUS_INVALID_PACKET);

  assert(opusDecode(env, nullptr, nativeContext, 123, nullptr, 0,
                    nullptr) == expectedOutputSize);
  assert(initCalls == 1);
  assert(context.errorCode == OPUS_OK);
  expectedOutputSize = 960 * bytesPerFrame;

  initCalls = directBufferCalls = 0;
  initThrows = true;
  assert(opusDecode(env, nullptr, nativeContext, 123,
                    reinterpret_cast<jobject>(silence), sizeof(silence),
                    nullptr) == -1);
  assert(initCalls == 1);
  assert(directBufferCalls == 1);
  opus_multistream_decoder_destroy(context.decoder);
}
}  // namespace

int main() {
  JNINativeInterface_ functions = {};
  functions.CallObjectMethodV = InitOutput;
  functions.ExceptionCheck = ExceptionCheck;
  functions.GetDirectBufferAddress = DirectBufferAddress;
  JNIEnv env = {&functions};
  for (int channels = 1; channels <= 2; ++channels) {
    DecodeInitializesOutputOnce(&env, false, channels);
    DecodeInitializesOutputOnce(&env, true, channels);
  }
}

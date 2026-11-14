/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <jni.h>

#include <cstdint>
#include <cstdlib>

#include "opus.h"              // NOLINT
#include "opus_multistream.h"  // NOLINT

#ifdef __ANDROID__
#define LOG_TAG "opus_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#else  //  __ANDROID__
#define LOGE(...) \
  do {            \
  } while (0)
#endif  //  __ANDROID__

// JNI references for SimpleOutputBuffer class.
static jmethodID outputBufferInit;

static const int kBytesPerIntPcmSample = 2;
static const int kBytesPerFloatSample = 4;
static const int kMaxOpusOutputPacketSizeSamples = 960 * 6;

struct OpusDecoderContext {
  OpusMSDecoder* decoder = nullptr;
  int channelCount = 0;
  bool outputFloat = false;
  int errorCode = 0;
};

jlong opusInit(JNIEnv* env, jobject thiz, jint sampleRate, jint channelCount,
               jint numStreams, jint numCoupled, jint gain,
               jbyteArray jStreamMap) {
  int status = OPUS_INVALID_STATE;
  jbyte* streamMapBytes = env->GetByteArrayElements(jStreamMap, 0);
  uint8_t* streamMap = reinterpret_cast<uint8_t*>(streamMapBytes);
  OpusMSDecoder* decoder = opus_multistream_decoder_create(
      sampleRate, channelCount, numStreams, numCoupled, streamMap, &status);
  env->ReleaseByteArrayElements(jStreamMap, streamMapBytes, 0);
  if (!decoder || status != OPUS_OK) {
    LOGE("Failed to create Opus Decoder; status=%s", opus_strerror(status));
    return 0;
  }
  status = opus_multistream_decoder_ctl(decoder, OPUS_SET_GAIN(gain));
  if (status != OPUS_OK) {
    LOGE("Failed to set Opus header gain; status=%s", opus_strerror(status));
    opus_multistream_decoder_destroy(decoder);
    return 0;
  }

  // Populate JNI References.
  const jclass outputBufferClass =
      env->FindClass("androidx/media3/decoder/SimpleDecoderOutputBuffer");
  outputBufferInit =
      env->GetMethodID(outputBufferClass, "init", "(JI)Ljava/nio/ByteBuffer;");

  OpusDecoderContext* context = new OpusDecoderContext();
  context->decoder = decoder;
  context->channelCount = channelCount;
  context->outputFloat = false;
  context->errorCode = 0;

  return reinterpret_cast<intptr_t>(context);
}

jint opusDecode(JNIEnv* env, jobject thiz, jlong jContext, jlong jTimeUs,
                jobject jInputBuffer, jint inputSize, jobject jOutputBuffer) {
  OpusDecoderContext* context = reinterpret_cast<OpusDecoderContext*>(jContext);
  OpusMSDecoder* decoder = context->decoder;
  const uint8_t* inputBuffer = reinterpret_cast<const uint8_t*>(
      env->GetDirectBufferAddress(jInputBuffer));

  // Empty input retains libopus packet-loss concealment at the maximum duration.
  const int outputSamples = inputSize == 0
      ? kMaxOpusOutputPacketSizeSamples
      : opus_packet_get_nb_samples(inputBuffer, inputSize, 48000);
  if (outputSamples < 0 || outputSamples > kMaxOpusOutputPacketSizeSamples) {
    context->errorCode = outputSamples < 0 ? outputSamples : OPUS_INVALID_PACKET;
    return context->errorCode;
  }

  const int byteSizePerSample =
      context->outputFloat ? kBytesPerFloatSample : kBytesPerIntPcmSample;
  const jint outputSize = outputSamples * byteSizePerSample *
                          context->channelCount;

  const jobject jOutputBufferData = env->CallObjectMethod(
      jOutputBuffer, outputBufferInit, jTimeUs, outputSize);
  if (env->ExceptionCheck()) {
    // Exception is thrown in Java when returning from the native call.
    return -1;
  }

  int sampleCount;
  if (context->outputFloat) {
    float* outputBufferData = reinterpret_cast<float*>(
        env->GetDirectBufferAddress(jOutputBufferData));
    sampleCount = opus_multistream_decode_float(
        decoder, inputBuffer, inputSize, outputBufferData,
        outputSamples, 0);
  } else {
    int16_t* outputBufferData = reinterpret_cast<int16_t*>(
        env->GetDirectBufferAddress(jOutputBufferData));
    sampleCount = opus_multistream_decode(decoder, inputBuffer, inputSize,
                                          outputBufferData,
                                          outputSamples, 0);
  }

  // record error code
  context->errorCode = (sampleCount < 0) ? sampleCount : 0;
  return (sampleCount < 0)
             ? sampleCount
             : sampleCount * byteSizePerSample * context->channelCount;
}

jint opusSecureDecode(JNIEnv* env, jobject thiz, jlong jContext, jlong jTimeUs,
                      jobject jInputBuffer, jint inputSize,
                      jobject jOutputBuffer, jint sampleRate,
                      jobject mediaCrypto, jint inputMode, jbyteArray key,
                      jbyteArray javaIv, jint inputNumSubSamples,
                      jintArray numBytesOfClearData,
                      jintArray numBytesOfEncryptedData) {
  // Doesn't support
  // Java client should have checked vpxSupportSecureDecode
  // and avoid calling this
  // return -2 (DRM Error)
  return -2;
}

void opusClose(JNIEnv* env, jobject thiz, jlong jContext) {
  OpusDecoderContext* context = reinterpret_cast<OpusDecoderContext*>(jContext);
  if (context) {
    if (context->decoder) {
      opus_multistream_decoder_destroy(context->decoder);
    }
    delete context;
  }
}

void opusReset(JNIEnv* env, jobject thiz, jlong jContext) {
  OpusDecoderContext* context = reinterpret_cast<OpusDecoderContext*>(jContext);
  if (context && context->decoder) {
    opus_multistream_decoder_ctl(context->decoder, OPUS_RESET_STATE);
  }
}

jstring opusGetErrorMessage(JNIEnv* env, jobject thiz, jlong jContext) {
  OpusDecoderContext* context = reinterpret_cast<OpusDecoderContext*>(jContext);
  int errorCode = context ? context->errorCode : OPUS_INVALID_STATE;
  return env->NewStringUTF(opus_strerror(errorCode));
}

jint opusGetErrorCode(JNIEnv* env, jobject thiz, jlong jContext) {
  OpusDecoderContext* context = reinterpret_cast<OpusDecoderContext*>(jContext);
  return context ? context->errorCode : OPUS_INVALID_STATE;
}

void opusSetFloatOutput(JNIEnv* env, jobject thiz, jlong jContext) {
  OpusDecoderContext* context = reinterpret_cast<OpusDecoderContext*>(jContext);
  if (context) {
    context->outputFloat = true;
  }
}

jboolean opusIsSecureDecodeSupported(JNIEnv* env, jobject thiz) {
  // Doesn't support
  return JNI_FALSE;
}

jstring opusGetVersion(JNIEnv* env, jobject thiz) {
  return env->NewStringUTF(opus_get_version_string());
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return -1;
  }

  static const JNINativeMethod kOpusDecoderMethods[] = {
      {"opusInit", "(IIIII[B)J", reinterpret_cast<void*>(opusInit)},
      {"opusClose", "(J)V", reinterpret_cast<void*>(opusClose)},
      {"opusReset", "(J)V", reinterpret_cast<void*>(opusReset)},
      {"opusDecode",
       "(JJLjava/nio/ByteBuffer;ILandroidx/media3/decoder/"
       "SimpleDecoderOutputBuffer;)I",
       reinterpret_cast<void*>(opusDecode)},
      {"opusSecureDecode",
       "(JJLjava/nio/ByteBuffer;ILandroidx/media3/decoder/"
       "SimpleDecoderOutputBuffer;ILandroidx/media3/decoder/"
       "CryptoConfig;I[B[BI[I[I)I",
       reinterpret_cast<void*>(opusSecureDecode)},
      {"opusGetErrorMessage", "(J)Ljava/lang/String;",
       reinterpret_cast<void*>(opusGetErrorMessage)},
      {"opusGetErrorCode", "(J)I", reinterpret_cast<void*>(opusGetErrorCode)},
      {"opusSetFloatOutput", "(J)V",
       reinterpret_cast<void*>(opusSetFloatOutput)},
  };

  static const JNINativeMethod kOpusLibraryMethods[] = {
      {"opusGetVersion", "()Ljava/lang/String;",
       reinterpret_cast<void*>(opusGetVersion)},
      {"opusIsSecureDecodeSupported", "()Z",
       reinterpret_cast<void*>(opusIsSecureDecodeSupported)},
  };

  jclass decoderClazz =
      env->FindClass("androidx/media3/decoder/opus/OpusDecoder");
  if (!decoderClazz) {
    LOGE("JNI_OnLoad: FindClass failed for OpusDecoder");
    return -1;
  }
  if (env->RegisterNatives(
          decoderClazz, kOpusDecoderMethods,
          sizeof(kOpusDecoderMethods) / sizeof(kOpusDecoderMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for OpusDecoder");
    return -1;
  }

  jclass libraryClazz =
      env->FindClass("androidx/media3/decoder/opus/OpusLibrary");
  if (!libraryClazz) {
    LOGE("JNI_OnLoad: FindClass failed for OpusLibrary");
    return -1;
  }
  if (env->RegisterNatives(
          libraryClazz, kOpusLibraryMethods,
          sizeof(kOpusLibraryMethods) / sizeof(kOpusLibraryMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for OpusLibrary");
    return -1;
  }

  return JNI_VERSION_1_6;
}

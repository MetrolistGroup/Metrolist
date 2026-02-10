#include <jni.h>
#include <string>
#include "../include/vibra.h"

extern "C"
JNIEXPORT jstring JNICALL
Java_com_metrolist_music_recognition_VibraSignature_fromI16(JNIEnv *env, jclass /*clazz*/, jbyteArray rawPcm) {
    if (rawPcm == nullptr) {
        jclass iae = env->FindClass("java/lang/IllegalArgumentException");
        if (iae) env->ThrowNew(iae, "rawPcm must not be null");
        return nullptr;
    }
    jbyte *pcmData = env->GetByteArrayElements(rawPcm, nullptr);
    if (pcmData == nullptr) {
        if (!env->ExceptionCheck()) {
            jclass rte = env->FindClass("java/lang/RuntimeException");
            if (rte) env->ThrowNew(rte, "GetByteArrayElements returned null");
        }
        return nullptr;
    }
    jsize size = env->GetArrayLength(rawPcm);
    try {
        Fingerprint *fp = vibra_get_fingerprint_from_signed_pcm(
                reinterpret_cast<const char *>(pcmData),
                static_cast<int>(size),
                /*sample rate*/ 16000,
                /*bits per sample*/16,
                /*channels*/1
        );
        env->ReleaseByteArrayElements(rawPcm, pcmData, JNI_ABORT);
        if (fp == nullptr) {
            if (!env->ExceptionCheck()) {
                jclass rte = env->FindClass("java/lang/RuntimeException");
                if (rte) env->ThrowNew(rte, "Failed to generate fingerprint from signed PCM");
            }
            return nullptr;
        }
        std::string uri = fp->uri;
        unsigned int sample_ms = fp->sample_ms;
        vibra_free_fingerprint(fp);
        jstring result = env->NewStringUTF(uri.c_str());
//        std::string json = R"({"uri":")" + uri + R"(","sample_ms":)" + std::to_string(sample_ms) + "}";
//        jstring result = env->NewStringUTF(json.c_str());
        if (result == nullptr) {
            if (!env->ExceptionCheck()) {
                jclass oom = env->FindClass("java/lang/OutOfMemoryError");
                if (oom) env->ThrowNew(oom, "Failed to allocate result string");
            }
            return nullptr;
        }
        return result;
    } catch (const std::exception& e) {
        env->ReleaseByteArrayElements(rawPcm, pcmData, JNI_ABORT);
        if (!env->ExceptionCheck()) {
            jclass rte = env->FindClass("java/lang/RuntimeException");
            if (rte) env->ThrowNew(rte, e.what());
        }
        return nullptr;
    } catch (...) {
        env->ReleaseByteArrayElements(rawPcm, pcmData, JNI_ABORT);
        if (!env->ExceptionCheck()) {
            jclass rte = env->FindClass("java/lang/RuntimeException");
            if (rte) env->ThrowNew(rte, "Unknown error in native fingerprint generation");
        }
        return nullptr;
    }
}

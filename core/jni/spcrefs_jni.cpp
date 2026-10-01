// JNI bridge for the Android app (class org.spcrefs.app.NativeCore). Licensed under the GNU General Public License v3.
#include <jni.h>
#include <vector>
#include "spcrefs_core.h"

namespace {
    void throwRuntime(JNIEnv * env, const char * message) {
        jclass cls = env->FindClass("java/lang/RuntimeException");
        if (cls != nullptr) {
            env->ThrowNew(cls, message);
        }
    }
}

extern "C" {

JNIEXPORT jbyteArray JNICALL Java_org_spcrefs_app_NativeCore_inflate(JNIEnv * env, jobject, jbyteArray raw) {
    const jsize size = env->GetArrayLength(raw);
    std::vector<uint8_t> in(static_cast<size_t>(size));
    env->GetByteArrayRegion(raw, 0, size, reinterpret_cast<jbyte *>(in.data()));
    char error[200] = {0};
    const long needed = spc_blosc_inflate(in.data(), in.size(), nullptr, 0, error, sizeof error);
    if (needed < 0) {
        throwRuntime(env, error);
        return nullptr;
    }
    std::vector<uint8_t> out(static_cast<size_t>(needed));
    if (spc_blosc_inflate(in.data(), in.size(), out.data(), out.size(), error, sizeof error) < 0) {
        throwRuntime(env, error);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(needed));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(needed), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT jfloatArray JNICALL Java_org_spcrefs_app_NativeCore_decodeFloats(JNIEnv * env, jobject, jbyteArray raw, jstring dtype,
                                                                             jboolean compressed, jint count) {
    const jsize size = env->GetArrayLength(raw);
    std::vector<uint8_t> in(static_cast<size_t>(size));
    env->GetByteArrayRegion(raw, 0, size, reinterpret_cast<jbyte *>(in.data()));
    const char * type = env->GetStringUTFChars(dtype, nullptr);
    std::vector<float> out(static_cast<size_t>(count));
    char error[200] = {0};
    const int rc = spc_decode_chunk(in.data(), in.size(), type, compressed ? 1 : 0, out.data(), out.size(), error, sizeof error);
    env->ReleaseStringUTFChars(dtype, type);
    if (rc != 0) {
        throwRuntime(env, error);
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(count);
    env->SetFloatArrayRegion(result, 0, count, out.data());
    return result;
}

JNIEXPORT jdoubleArray JNICALL Java_org_spcrefs_app_NativeCore_latLonToGrid(JNIEnv * env, jobject, jdouble lat, jdouble lon) {
    double c = 0, r = 0;
    spc_latlon_to_grid(lat, lon, &c, &r);
    const double v[2] = {c, r};
    jdoubleArray result = env->NewDoubleArray(2);
    env->SetDoubleArrayRegion(result, 0, 2, v);
    return result;
}

JNIEXPORT jdoubleArray JNICALL Java_org_spcrefs_app_NativeCore_gridToLatLon(JNIEnv * env, jobject, jdouble column, jdouble row) {
    double lat = 0, lon = 0;
    spc_grid_to_latlon(column, row, &lat, &lon);
    const double v[2] = {lat, lon};
    jdoubleArray result = env->NewDoubleArray(2);
    env->SetDoubleArrayRegion(result, 0, 2, v);
    return result;
}

JNIEXPORT void JNICALL Java_org_spcrefs_app_NativeCore_render(JNIEnv * env, jobject, jfloatArray values, jint col0, jint row0, jint cols,
                                                              jint rows, jint scale, jint step, jint kind, jdoubleArray stopValues, jintArray stopRgb,
                                                              jdouble factor, jdouble offset, jdouble hideBelow, jintArray memberRgb,
                                                              jintArray argb) {
    const jsize n = env->GetArrayLength(values);
    if (n < SPC_GRID_COLUMNS * SPC_GRID_ROWS || env->GetArrayLength(argb) < (cols * scale / (step > 1 ? step : 1)) * (rows * scale / (step > 1 ? step : 1))) {
        throwRuntime(env, "render buffers are too small");
        return;
    }
    jfloat * v = env->GetFloatArrayElements(values, nullptr);
    const jsize stopCount = env->GetArrayLength(stopValues);
    jdouble * sv = env->GetDoubleArrayElements(stopValues, nullptr);
    jint * sc = env->GetIntArrayElements(stopRgb, nullptr);
    std::vector<spc_stop> stops(static_cast<size_t>(stopCount));
    for (jsize i = 0; i < stopCount; i += 1) {
        stops[static_cast<size_t>(i)] = {sv[i], static_cast<uint8_t>(sc[i] >> 16), static_cast<uint8_t>(sc[i] >> 8), static_cast<uint8_t>(sc[i])};
    }
    const jsize memberCount = env->GetArrayLength(memberRgb);
    jint * mr = env->GetIntArrayElements(memberRgb, nullptr);
    std::vector<uint32_t> members(static_cast<size_t>(memberCount));
    for (jsize i = 0; i < memberCount; i += 1) {
        members[static_cast<size_t>(i)] = static_cast<uint32_t>(mr[i]);
    }
    const spc_view view{col0, row0, cols, rows, scale, step};
    const spc_style style{kind, stops.data(), static_cast<int>(stops.size()), factor, offset, hideBelow, members.data(), static_cast<int>(members.size())};
    jint * out = env->GetIntArrayElements(argb, nullptr);
    spc_render(v, &view, &style, reinterpret_cast<uint32_t *>(out));
    env->ReleaseIntArrayElements(argb, out, 0);
    env->ReleaseIntArrayElements(memberRgb, mr, JNI_ABORT);
    env->ReleaseIntArrayElements(stopRgb, sc, JNI_ABORT);
    env->ReleaseDoubleArrayElements(stopValues, sv, JNI_ABORT);
    env->ReleaseFloatArrayElements(values, v, JNI_ABORT);
}

}

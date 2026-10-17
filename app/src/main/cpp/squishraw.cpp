// Camera RAW through LibRaw (CR2, CR3, NEF, ARW, RAF, ORF, RW2, PEF, SRW,
// DNG and some hundreds of others), for the photos ImageDecoder refuses.
// Part of libsquishav.so; AvImages.kt is the caller.
//
// A RAW is developed, not decoded: demosaiced from the sensor's mosaic,
// white-balanced with the camera's own multipliers and brought into sRGB.
// Half size is asked for whenever the picture would still be large enough
// - it skips the demosaic's interpolation altogether, four times faster,
// and a 24 MP sensor at half size is still 3000 px across, which is what
// the app keeps of any photo (StillClips' 3840 px ceiling) near enough.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include <libraw/libraw.h>

#define TAG "SquishRaw"
#define FN(name) Java_com_squish_app_av_AvNative_##name

extern "C" {

/** Opens the RAW behind [fd] (owned by the native side from here). A handle, or 0. */
JNIEXPORT jlong JNICALL FN(rawOpen)(JNIEnv *, jclass, jint fd) {
    char path[64];
    snprintf(path, sizeof path, "/proc/self/fd/%d", fd);
    LibRaw *raw = new LibRaw(LIBRAW_OPTIONS_NONE);
    int r = raw->open_file(path);
    // LibRaw opens the path itself, which gives it a descriptor of its own.
    close(fd);
    if (r != LIBRAW_SUCCESS) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "not a RAW LibRaw reads: %s", libraw_strerror(r));
        delete raw;
        return 0;
    }
    return (jlong) (intptr_t) raw;
}

/**
 * [width, height, flip, thumbFormat] - the developed picture's size before
 * any turn, LibRaw's flip code (0 none, 3 half turn, 5 a quarter
 * anticlockwise, 6 a quarter clockwise) and the embedded preview's kind
 * (1 JPEG, 2 bitmap, 0 none).
 */
JNIEXPORT jintArray JNICALL FN(rawInfo)(JNIEnv *env, jclass, jlong h) {
    LibRaw *raw = (LibRaw *) (intptr_t) h;
    jint v[4];
    v[0] = raw->imgdata.sizes.width;
    v[1] = raw->imgdata.sizes.height;
    v[2] = raw->imgdata.sizes.flip;
    v[3] = raw->imgdata.thumbnail.tformat == LIBRAW_THUMBNAIL_JPEG ? 1
         : raw->imgdata.thumbnail.tformat == LIBRAW_THUMBNAIL_BITMAP ? 2 : 0;
    jintArray out = env->NewIntArray(4);
    if (out) env->SetIntArrayRegion(out, 0, 4, v);
    return out;
}

/** The camera's embedded JPEG preview, or null - what a contact sheet wants, at a fraction of the cost. */
JNIEXPORT jbyteArray JNICALL FN(rawThumbJpeg)(JNIEnv *env, jclass, jlong h) {
    LibRaw *raw = (LibRaw *) (intptr_t) h;
    if (raw->unpack_thumb() != LIBRAW_SUCCESS) return nullptr;
    int err = 0;
    libraw_processed_image_t *img = raw->dcraw_make_mem_thumb(&err);
    if (!img) return nullptr;
    jbyteArray out = nullptr;
    if (img->type == LIBRAW_IMAGE_JPEG) {
        out = env->NewByteArray((jsize) img->data_size);
        if (out) env->SetByteArrayRegion(out, 0, (jsize) img->data_size, (const jbyte *) img->data);
    }
    LibRaw::dcraw_clear_mem(img);
    return out;
}

/**
 * Develops the RAW and draws it, unturned, into an ARGB_8888 [bitmap]
 * scaled to the bitmap's size by box averaging. [half] asks for the
 * half-size development. 0, or a negative LibRaw error.
 */
JNIEXPORT jint JNICALL FN(rawDevelop)(JNIEnv *env, jclass, jlong h, jobject bitmap, jboolean half) {
    LibRaw *raw = (LibRaw *) (intptr_t) h;
    raw->imgdata.params.half_size = half ? 1 : 0;
    raw->imgdata.params.use_camera_wb = 1;
    raw->imgdata.params.use_camera_matrix = 1;
    raw->imgdata.params.output_bps = 8;
    raw->imgdata.params.output_color = 1;  // sRGB
    raw->imgdata.params.user_flip = 0;     // turned by the caller, from rawInfo's flip
    int r = raw->unpack();
    if (r != LIBRAW_SUCCESS) return r < 0 ? r : -r;
    r = raw->dcraw_process();
    if (r != LIBRAW_SUCCESS) return r < 0 ? r : -r;
    int err = 0;
    libraw_processed_image_t *img = raw->dcraw_make_mem_image(&err);
    if (!img) return err < 0 ? err : -1;
    if (img->type != LIBRAW_IMAGE_BITMAP || img->bits != 8 || (img->colors != 3 && img->colors != 1)) {
        LibRaw::dcraw_clear_mem(img);
        return -2;
    }
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LibRaw::dcraw_clear_mem(img);
        return -3;
    }
    const int sw = img->width, sh = img->height, colors = img->colors;
    const int dw = (int) info.width, dh = (int) info.height;
    const unsigned char *src = img->data;
    for (int y = 0; y < dh; y++) {
        int y0 = (int) ((long long) y * sh / dh), y1 = (int) ((long long) (y + 1) * sh / dh);
        if (y1 <= y0) y1 = y0 + 1;
        unsigned char *out = (unsigned char *) pixels + (size_t) y * info.stride;
        for (int x = 0; x < dw; x++) {
            int x0 = (int) ((long long) x * sw / dw), x1 = (int) ((long long) (x + 1) * sw / dw);
            if (x1 <= x0) x1 = x0 + 1;
            unsigned sum[3] = {0, 0, 0};
            unsigned n = 0;
            for (int yy = y0; yy < y1 && yy < sh; yy++) {
                const unsigned char *row = src + (size_t) yy * sw * colors;
                for (int xx = x0; xx < x1 && xx < sw; xx++) {
                    const unsigned char *p = row + (size_t) xx * colors;
                    sum[0] += p[0];
                    sum[1] += colors == 3 ? p[1] : p[0];
                    sum[2] += colors == 3 ? p[2] : p[0];
                    n++;
                }
            }
            if (!n) n = 1;
            out[x * 4 + 0] = (unsigned char) (sum[0] / n);
            out[x * 4 + 1] = (unsigned char) (sum[1] / n);
            out[x * 4 + 2] = (unsigned char) (sum[2] / n);
            out[x * 4 + 3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    LibRaw::dcraw_clear_mem(img);
    return 0;
}

/** The developed size [width, height] dcraw_process will give, half or not, before the turn. */
JNIEXPORT jintArray JNICALL FN(rawDevelopedSize)(JNIEnv *env, jclass, jlong h, jboolean half) {
    LibRaw *raw = (LibRaw *) (intptr_t) h;
    jint v[2];
    v[0] = raw->imgdata.sizes.width >> (half ? 1 : 0);
    v[1] = raw->imgdata.sizes.height >> (half ? 1 : 0);
    jintArray out = env->NewIntArray(2);
    if (out) env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

JNIEXPORT void JNICALL FN(rawClose)(JNIEnv *, jclass, jlong h) {
    delete (LibRaw *) (intptr_t) h;
}

}  // extern "C"

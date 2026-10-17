// Squish's own FFmpeg: every demuxer and every decoder FFmpeg has, behind a
// small JNI surface (com.squish.app.av.AvNative). Built by
// tools/native/build.sh into app/src/main/jniLibs/<abi>/libsquishav.so with
// FFmpeg linked in statically and nothing exported but the JNI entry points,
// so it cannot collide with any other copy of FFmpeg in the process.
//
// Three kinds of handle, each a pointer handed to Kotlin as a long:
//
// - a *file* (SqFile): a container opened over a file descriptor, read with
//   pread so the descriptor's own offset is never touched. Packets come out
//   of it one at a time, with times in microseconds on the container's clock.
// - a *decoder* (SqDec): opened from a parameter blob (see write_params /
//   read_params - Kotlin writes the same layout for Media3's own formats in
//   AvParams.kt). Packets in, frames out, and a NULL packet at the end so
//   the frames it holds for reordering are drained - the thing nextlib's
//   decoders could not do, which is why the app used to convert such files.
// - the frame a decoder last produced, copied out on request as Y'CbCr
//   planes (into an ImageWriter's image), as RGBA (into a Bitmap), as a grey
//   grid, or as interleaved 16-bit PCM.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <strings.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

#define TAG "SquishAv"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define FN(name) Java_com_squish_app_av_AvNative_##name

static const AVRational US = {1, 1000000};

// Status codes shared with AvNative.kt.
#define SQ_OK 0
#define SQ_AGAIN 1
#define SQ_EOF 2
#define SQ_SKIPPED 3

#define IO_BUFFER 65536

/* ------------------------------------------------------------------ */
/* A growable string, for the JSON description of a file.             */
/* ------------------------------------------------------------------ */

typedef struct {
    char *data;
    size_t len, cap;
} Str;

static void str_add(Str *s, const char *text, size_t n) {
    if (s->len + n + 1 > s->cap) {
        size_t cap = s->cap ? s->cap : 1024;
        while (s->len + n + 1 > cap) cap *= 2;
        char *grown = realloc(s->data, cap);
        if (!grown) return;
        s->data = grown;
        s->cap = cap;
    }
    memcpy(s->data + s->len, text, n);
    s->len += n;
    s->data[s->len] = 0;
}

static void str_cat(Str *s, const char *text) { str_add(s, text, strlen(text)); }

static void str_fmt(Str *s, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void str_fmt(Str *s, const char *fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (n > 0) str_add(s, buf, n < (int) sizeof buf ? (size_t) n : sizeof buf - 1);
}

// A JSON string literal. Bytes below 0x20 are escaped; everything else -
// UTF-8 included - goes through as it is. A tag that is not valid UTF-8
// would make NewStringUTF throw, so the result is built as bytes and handed
// over as a byte array (see json_result).
static void str_json(Str *s, const char *text) {
    str_cat(s, "\"");
    if (text) {
        for (const unsigned char *p = (const unsigned char *) text; *p; p++) {
            char esc[8];
            switch (*p) {
                case '"': str_cat(s, "\\\""); break;
                case '\\': str_cat(s, "\\\\"); break;
                case '\n': str_cat(s, "\\n"); break;
                case '\r': str_cat(s, "\\r"); break;
                case '\t': str_cat(s, "\\t"); break;
                default:
                    if (*p < 0x20) {
                        snprintf(esc, sizeof esc, "\\u%04x", *p);
                        str_cat(s, esc);
                    } else {
                        str_add(s, (const char *) p, 1);
                    }
            }
        }
    }
    str_cat(s, "\"");
}

static jbyteArray json_result(JNIEnv *env, Str *s) {
    if (!s->data) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) s->len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize) s->len, (const jbyte *) s->data);
    free(s->data);
    return out;
}

/* ------------------------------------------------------------------ */
/* Files                                                              */
/* ------------------------------------------------------------------ */

typedef struct {
    int fd;
    int seekable;
    int64_t pos;
    int64_t size;
    AVIOContext *pb;
    AVFormatContext *fmt;
    AVPacket *pkt;
} SqFile;

static int io_read(void *opaque, uint8_t *buf, int n) {
    SqFile *f = opaque;
    ssize_t r;
    if (f->seekable) {
        do { r = pread(f->fd, buf, (size_t) n, f->pos); } while (r < 0 && errno == EINTR);
    } else {
        do { r = read(f->fd, buf, (size_t) n); } while (r < 0 && errno == EINTR);
    }
    if (r < 0) return AVERROR(errno);
    if (r == 0) return AVERROR_EOF;
    f->pos += r;
    return (int) r;
}

static int64_t io_seek(void *opaque, int64_t offset, int whence) {
    SqFile *f = opaque;
    if (whence & AVSEEK_SIZE) return f->size >= 0 ? f->size : AVERROR(ENOSYS);
    whence &= ~AVSEEK_FORCE;
    int64_t target;
    switch (whence) {
        case SEEK_SET: target = offset; break;
        case SEEK_CUR: target = f->pos + offset; break;
        case SEEK_END:
            if (f->size < 0) return AVERROR(ENOSYS);
            target = f->size + offset;
            break;
        default: return AVERROR(EINVAL);
    }
    if (target < 0) return AVERROR(EINVAL);
    f->pos = target;
    return target;
}

static void file_free(SqFile *f) {
    if (!f) return;
    if (f->fmt) avformat_close_input(&f->fmt);
    if (f->pb) {
        av_freep(&f->pb->buffer);
        avio_context_free(&f->pb);
    }
    av_packet_free(&f->pkt);
    if (f->fd >= 0) close(f->fd);
    av_free(f);
}

static int open_with(SqFile *f, const AVInputFormat *forced, enum AVCodecID video_codec) {
    f->pos = 0;
    if (f->pb) {
        av_freep(&f->pb->buffer);
        avio_context_free(&f->pb);
    }
    uint8_t *buffer = av_malloc(IO_BUFFER);
    if (!buffer) return AVERROR(ENOMEM);
    f->pb = avio_alloc_context(buffer, IO_BUFFER, 0, f, io_read, NULL, f->seekable ? io_seek : NULL);
    if (!f->pb) {
        av_free(buffer);
        return AVERROR(ENOMEM);
    }
    f->pb->seekable = f->seekable ? AVIO_SEEKABLE_NORMAL : 0;
    f->fmt = avformat_alloc_context();
    if (!f->fmt) return AVERROR(ENOMEM);
    f->fmt->pb = f->pb;
    f->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (video_codec != AV_CODEC_ID_NONE) f->fmt->video_codec_id = video_codec;
    // No name is given to the probe: with a custom reader, a name would
    // only invite the image2 demuxer to look for files of that name.
    int r = avformat_open_input(&f->fmt, "", forced, NULL);
    if (r < 0) f->fmt = NULL;  // freed by avformat_open_input
    return r;
}

/** Opens [fd], which the native side now owns and closes. Returns a handle, or a negative error. */
JNIEXPORT jlong JNICALL FN(fileOpen)(JNIEnv *env, jclass cls, jint fd, jstring jext) {
    (void) cls;
    SqFile *f = av_mallocz(sizeof *f);
    if (!f) {
        close(fd);
        return AVERROR(ENOMEM);
    }
    f->fd = fd;
    struct stat st;
    f->size = (fstat(fd, &st) == 0 && S_ISREG(st.st_mode)) ? (int64_t) st.st_size : -1;
    f->seekable = lseek(fd, 0, SEEK_CUR) >= 0;
    f->pkt = av_packet_alloc();

    int r = open_with(f, NULL, AV_CODEC_ID_NONE);
    if (r < 0 && jext) {
        // The few formats with no signature to sniff - a Targa above all -
        // are told apart by their name alone, so the caller passes it.
        const char *ext = (*env)->GetStringUTFChars(env, jext, NULL);
        enum AVCodecID id = AV_CODEC_ID_NONE;
        if (ext) {
            if (!strcasecmp(ext, "tga") || !strcasecmp(ext, "targa") || !strcasecmp(ext, "icb") ||
                !strcasecmp(ext, "vda") || !strcasecmp(ext, "vst")) id = AV_CODEC_ID_TARGA;
            else if (!strcasecmp(ext, "pcx")) id = AV_CODEC_ID_PCX;
            else if (!strcasecmp(ext, "sgi") || !strcasecmp(ext, "rgb") || !strcasecmp(ext, "rgba") ||
                     !strcasecmp(ext, "bw")) id = AV_CODEC_ID_SGI;
            else if (!strcasecmp(ext, "ras") || !strcasecmp(ext, "sun")) id = AV_CODEC_ID_SUNRAST;
            else if (!strcasecmp(ext, "xbm")) id = AV_CODEC_ID_XBM;
            else if (!strcasecmp(ext, "xwd")) id = AV_CODEC_ID_XWD;
            (*env)->ReleaseStringUTFChars(env, jext, ext);
        }
        if (id != AV_CODEC_ID_NONE) {
            const AVInputFormat *pipe = av_find_input_format("image2pipe");
            if (pipe) r = open_with(f, pipe, id);
        }
    }
    if (r < 0) {
        file_free(f);
        return r < 0 ? r : AVERROR_UNKNOWN;
    }
    // Ten seconds of analysis at most: enough for a frame rate and for every
    // MPEG-TS stream to be found, not so much that opening a long file waits.
    f->fmt->max_analyze_duration = 10 * AV_TIME_BASE;
    r = avformat_find_stream_info(f->fmt, NULL);
    if (r < 0 && f->fmt->nb_streams == 0) {
        file_free(f);
        return r;
    }
    return (jlong) (intptr_t) f;
}

JNIEXPORT void JNICALL FN(fileClose)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    file_free((SqFile *) (intptr_t) h);
}

static int rotation_of(const AVStream *st) {
    const AVPacketSideData *sd = av_packet_side_data_get(
        st->codecpar->coded_side_data, st->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (!sd || sd->size < 9 * 4) return 0;
    double theta = -av_display_rotation_get((const int32_t *) sd->data);
    if (theta != theta) return 0;  // NaN: a matrix that is not a rotation
    int degrees = (int) (theta < 0 ? theta - 0.5 : theta + 0.5);
    degrees %= 360;
    if (degrees < 0) degrees += 360;
    // Only quarter turns are a rotation anybody stores on purpose.
    return ((degrees + 45) / 90 * 90) % 360;
}

static const char *type_name(enum AVMediaType t) {
    switch (t) {
        case AVMEDIA_TYPE_VIDEO: return "video";
        case AVMEDIA_TYPE_AUDIO: return "audio";
        case AVMEDIA_TYPE_SUBTITLE: return "subtitle";
        case AVMEDIA_TYPE_ATTACHMENT: return "attachment";
        default: return "data";
    }
}

static int64_t to_us(int64_t v, AVRational tb) {
    return v == AV_NOPTS_VALUE ? INT64_MIN : av_rescale_q(v, tb, US);
}

/** The file and its streams as JSON (UTF-8 bytes); AvProbe.kt reads it. */
JNIEXPORT jbyteArray JNICALL FN(fileInfo)(JNIEnv *env, jclass cls, jlong h) {
    (void) cls;
    SqFile *f = (SqFile *) (intptr_t) h;
    AVFormatContext *fmt = f->fmt;
    Str s = {0};
    str_cat(&s, "{\"format\":");
    str_json(&s, fmt->iformat ? fmt->iformat->name : "");
    str_cat(&s, ",\"formatLong\":");
    str_json(&s, fmt->iformat && fmt->iformat->long_name ? fmt->iformat->long_name : "");
    str_fmt(&s, ",\"durationUs\":%lld", (long long) (fmt->duration == AV_NOPTS_VALUE ? -1 : fmt->duration));
    str_fmt(&s, ",\"startUs\":%lld", (long long) (fmt->start_time == AV_NOPTS_VALUE ? 0 : fmt->start_time));
    str_fmt(&s, ",\"bitRate\":%lld", (long long) fmt->bit_rate);
    str_fmt(&s, ",\"size\":%lld", (long long) f->size);
    str_fmt(&s, ",\"seekable\":%s", f->seekable ? "true" : "false");
    str_cat(&s, ",\"streams\":[");
    for (unsigned i = 0; i < fmt->nb_streams; i++) {
        AVStream *st = fmt->streams[i];
        AVCodecParameters *p = st->codecpar;
        const AVCodecDescriptor *desc = avcodec_descriptor_get(p->codec_id);
        const AVCodec *dec = avcodec_find_decoder(p->codec_id);
        if (i) str_cat(&s, ",");
        str_fmt(&s, "{\"index\":%u,\"type\":\"%s\",\"codec\":", i, type_name(p->codec_type));
        str_json(&s, desc ? desc->name : "none");
        str_cat(&s, ",\"codecLong\":");
        str_json(&s, desc && desc->long_name ? desc->long_name : "");
        const char *profile = p->profile != AV_PROFILE_UNKNOWN ? avcodec_profile_name(p->codec_id, p->profile) : NULL;
        str_cat(&s, ",\"profile\":");
        str_json(&s, profile ? profile : "");
        str_fmt(&s, ",\"decoder\":%s", dec ? "true" : "false");
        str_fmt(&s, ",\"tag\":%u", p->codec_tag);
        str_fmt(&s, ",\"bitRate\":%lld", (long long) p->bit_rate);
        str_fmt(&s, ",\"durationUs\":%lld", (long long) (st->duration == AV_NOPTS_VALUE ? -1 : to_us(st->duration, st->time_base)));
        str_fmt(&s, ",\"startUs\":%lld", (long long) (st->start_time == AV_NOPTS_VALUE ? 0 : to_us(st->start_time, st->time_base)));
        str_fmt(&s, ",\"frames\":%lld", (long long) st->nb_frames);
        str_fmt(&s, ",\"attachedPic\":%s", (st->disposition & AV_DISPOSITION_ATTACHED_PIC) ? "true" : "false");
        str_fmt(&s, ",\"isDefault\":%s", (st->disposition & AV_DISPOSITION_DEFAULT) ? "true" : "false");
        if (p->codec_type == AVMEDIA_TYPE_VIDEO) {
            AVRational fr = st->avg_frame_rate.num > 0 && st->avg_frame_rate.den > 0 ? st->avg_frame_rate : st->r_frame_rate;
            AVRational sar = st->sample_aspect_ratio.num > 0 ? st->sample_aspect_ratio : p->sample_aspect_ratio;
            const char *pix = av_get_pix_fmt_name((enum AVPixelFormat) p->format);
            const AVPixFmtDescriptor *pd = av_pix_fmt_desc_get((enum AVPixelFormat) p->format);
            int bits = pd ? pd->comp[0].depth : 8;
            if (p->bits_per_raw_sample > 0 && p->bits_per_raw_sample <= 16) bits = p->bits_per_raw_sample;
            str_fmt(&s, ",\"width\":%d,\"height\":%d,\"sarNum\":%d,\"sarDen\":%d", p->width, p->height, sar.num, sar.den);
            str_fmt(&s, ",\"fpsNum\":%d,\"fpsDen\":%d,\"rotation\":%d", fr.num, fr.den, rotation_of(st));
            str_cat(&s, ",\"pixFmt\":");
            str_json(&s, pix ? pix : "");
            str_fmt(&s, ",\"bits\":%d,\"alpha\":%s", bits, pd && (pd->flags & AV_PIX_FMT_FLAG_ALPHA) ? "true" : "false");
            str_fmt(&s, ",\"colorRange\":%d,\"colorPrimaries\":%d,\"colorTrc\":%d,\"colorSpace\":%d",
                    p->color_range, p->color_primaries, p->color_trc, p->color_space);
            str_fmt(&s, ",\"stillImage\":%s", desc && (desc->props & AV_CODEC_PROP_INTRA_ONLY) && st->nb_frames == 1 ? "true" : "false");
        } else if (p->codec_type == AVMEDIA_TYPE_AUDIO) {
            const char *sf = av_get_sample_fmt_name((enum AVSampleFormat) p->format);
            str_fmt(&s, ",\"sampleRate\":%d,\"channels\":%d", p->sample_rate, p->ch_layout.nb_channels);
            str_cat(&s, ",\"sampleFmt\":");
            str_json(&s, sf ? sf : "");
            int bits = p->bits_per_raw_sample > 0 ? p->bits_per_raw_sample :
                       (sf ? av_get_bytes_per_sample((enum AVSampleFormat) p->format) * 8 : 0);
            str_fmt(&s, ",\"bits\":%d", bits);
        }
        const AVDictionaryEntry *lang = av_dict_get(st->metadata, "language", NULL, 0);
        const AVDictionaryEntry *title = av_dict_get(st->metadata, "title", NULL, 0);
        str_cat(&s, ",\"language\":");
        str_json(&s, lang ? lang->value : "");
        str_cat(&s, ",\"title\":");
        str_json(&s, title ? title->value : "");
        str_cat(&s, "}");
    }
    str_cat(&s, "]");
    const AVDictionaryEntry *title = av_dict_get(fmt->metadata, "title", NULL, 0);
    str_cat(&s, ",\"title\":");
    str_json(&s, title ? title->value : "");
    const AVDictionaryEntry *created = av_dict_get(fmt->metadata, "creation_time", NULL, 0);
    str_cat(&s, ",\"creationTime\":");
    str_json(&s, created ? created->value : "");
    str_cat(&s, "}");
    return json_result(env, &s);
}

/** The best stream of [type] (0 video, 1 audio) that has a decoder, or a negative error. */
JNIEXPORT jint JNICALL FN(fileBestStream)(JNIEnv *env, jclass cls, jlong h, jint type) {
    (void) env; (void) cls;
    SqFile *f = (SqFile *) (intptr_t) h;
    enum AVMediaType t = type == 0 ? AVMEDIA_TYPE_VIDEO : AVMEDIA_TYPE_AUDIO;
    const AVCodec *codec = NULL;
    int best = av_find_best_stream(f->fmt, t, -1, -1, &codec, 0);
    if (best < 0 || !codec) return best < 0 ? best : AVERROR_DECODER_NOT_FOUND;
    // A cover picture in an audio file is a "video" stream with one frame.
    if (t == AVMEDIA_TYPE_VIDEO && (f->fmt->streams[best]->disposition & AV_DISPOSITION_ATTACHED_PIC)) {
        for (unsigned i = 0; i < f->fmt->nb_streams; i++) {
            AVStream *st = f->fmt->streams[i];
            if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && !(st->disposition & AV_DISPOSITION_ATTACHED_PIC) &&
                avcodec_find_decoder(st->codecpar->codec_id)) return (jint) i;
        }
    }
    return best;
}

/* The parameter blob: "SQP1", then 30 int32, 2 int64, the codec name and
 * the extradata - little-endian throughout. AvParams.kt writes the same. */

#define PARAM_INTS 30

static void put32(uint8_t **p, int32_t v) {
    uint32_t u = (uint32_t) v;
    (*p)[0] = u; (*p)[1] = u >> 8; (*p)[2] = u >> 16; (*p)[3] = u >> 24;
    *p += 4;
}

static void put64(uint8_t **p, int64_t v) {
    put32(p, (int32_t) (uint32_t) ((uint64_t) v & 0xffffffffu));
    put32(p, (int32_t) (uint32_t) ((uint64_t) v >> 32));
}

static int32_t get32(const uint8_t **p) {
    const uint8_t *b = *p;
    *p += 4;
    return (int32_t) ((uint32_t) b[0] | (uint32_t) b[1] << 8 | (uint32_t) b[2] << 16 | (uint32_t) b[3] << 24);
}

static int64_t get64(const uint8_t **p) {
    uint64_t lo = (uint32_t) get32(p);
    uint64_t hi = (uint32_t) get32(p);
    return (int64_t) (lo | hi << 32);
}

/** The stream's codec parameters as a blob, for Format.initializationData. */
JNIEXPORT jbyteArray JNICALL FN(fileStreamParams)(JNIEnv *env, jclass cls, jlong h, jint index) {
    (void) cls;
    SqFile *f = (SqFile *) (intptr_t) h;
    if (index < 0 || (unsigned) index >= f->fmt->nb_streams) return NULL;
    AVStream *st = f->fmt->streams[index];
    AVCodecParameters *p = st->codecpar;
    const AVCodecDescriptor *desc = avcodec_descriptor_get(p->codec_id);
    const char *name = desc ? desc->name : "none";
    size_t name_len = strlen(name);
    size_t size = 4 + PARAM_INTS * 4 + 16 + 4 + name_len + 4 + (size_t) (p->extradata_size > 0 ? p->extradata_size : 0);
    uint8_t *buf = malloc(size);
    if (!buf) return NULL;
    uint8_t *w = buf;
    memcpy(w, "SQP1", 4);
    w += 4;
    AVRational sar = st->sample_aspect_ratio.num > 0 ? st->sample_aspect_ratio : p->sample_aspect_ratio;
    AVRational fr = st->avg_frame_rate.num > 0 ? st->avg_frame_rate : st->r_frame_rate;
    int32_t ints[PARAM_INTS] = {
        p->codec_type, (int32_t) p->codec_tag, p->format, p->bits_per_coded_sample, p->bits_per_raw_sample,
        p->profile, p->level, p->width, p->height, sar.num, sar.den, p->field_order, p->color_range,
        p->color_primaries, p->color_trc, p->color_space, p->chroma_location, p->video_delay, p->sample_rate,
        p->ch_layout.order, p->ch_layout.nb_channels, p->block_align, p->frame_size, p->initial_padding,
        p->trailing_padding, p->seek_preroll, fr.num, fr.den, st->time_base.num, st->time_base.den
    };
    for (int i = 0; i < PARAM_INTS; i++) put32(&w, ints[i]);
    put64(&w, p->bit_rate);
    put64(&w, p->ch_layout.order == AV_CHANNEL_ORDER_NATIVE ? (int64_t) p->ch_layout.u.mask : 0);
    put32(&w, (int32_t) name_len);
    memcpy(w, name, name_len);
    w += name_len;
    put32(&w, p->extradata_size > 0 ? p->extradata_size : 0);
    if (p->extradata_size > 0) {
        memcpy(w, p->extradata, (size_t) p->extradata_size);
        w += p->extradata_size;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) (w - buf));
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize) (w - buf), (const jbyte *) buf);
    free(buf);
    return out;
}

static int read_params(const uint8_t *b, size_t size, AVCodecParameters *p) {
    const uint8_t *r = b;
    const uint8_t *end = b + size;
    if (size < 4 + PARAM_INTS * 4 + 16 + 8 || memcmp(r, "SQP1", 4)) return AVERROR_INVALIDDATA;
    r += 4;
    int32_t v[PARAM_INTS];
    for (int i = 0; i < PARAM_INTS; i++) v[i] = get32(&r);
    int64_t bit_rate = get64(&r);
    uint64_t mask = (uint64_t) get64(&r);
    int32_t name_len = get32(&r);
    if (name_len < 0 || name_len > 64 || r + name_len + 4 > end) return AVERROR_INVALIDDATA;
    char name[72];
    memcpy(name, r, (size_t) name_len);
    name[name_len] = 0;
    r += name_len;
    int32_t extra = get32(&r);
    if (extra < 0 || r + extra > end) return AVERROR_INVALIDDATA;

    const AVCodecDescriptor *desc = avcodec_descriptor_get_by_name(name);
    if (!desc) return AVERROR_DECODER_NOT_FOUND;
    p->codec_type = (enum AVMediaType) v[0];
    p->codec_id = desc->id;
    p->codec_tag = (uint32_t) v[1];
    p->format = v[2];
    p->bits_per_coded_sample = v[3];
    p->bits_per_raw_sample = v[4];
    p->profile = v[5];
    p->level = v[6];
    p->width = v[7];
    p->height = v[8];
    p->sample_aspect_ratio = (AVRational) {v[9], v[10]};
    p->field_order = (enum AVFieldOrder) v[11];
    p->color_range = (enum AVColorRange) v[12];
    p->color_primaries = (enum AVColorPrimaries) v[13];
    p->color_trc = (enum AVColorTransferCharacteristic) v[14];
    p->color_space = (enum AVColorSpace) v[15];
    p->chroma_location = (enum AVChromaLocation) v[16];
    p->video_delay = v[17];
    p->sample_rate = v[18];
    av_channel_layout_uninit(&p->ch_layout);
    if (v[20] > 0) {
        if (v[19] == AV_CHANNEL_ORDER_NATIVE && mask) av_channel_layout_from_mask(&p->ch_layout, mask);
        else av_channel_layout_default(&p->ch_layout, v[20]);
        if (p->ch_layout.nb_channels != v[20]) {
            av_channel_layout_uninit(&p->ch_layout);
            av_channel_layout_default(&p->ch_layout, v[20]);
        }
    }
    p->block_align = v[21];
    p->frame_size = v[22];
    p->initial_padding = v[23];
    p->trailing_padding = v[24];
    p->seek_preroll = v[25];
    p->framerate = (AVRational) {v[26], v[27]};
    p->bit_rate = bit_rate;
    av_freep(&p->extradata);
    p->extradata_size = 0;
    if (extra > 0) {
        p->extradata = av_mallocz((size_t) extra + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!p->extradata) return AVERROR(ENOMEM);
        memcpy(p->extradata, r, (size_t) extra);
        p->extradata_size = extra;
    }
    return 0;
}

/**
 * Reads the next packet into the file's own packet. Writes [stream, ptsUs,
 * dtsUs, durationUs, flags, size, bytePosition] into [meta] (flags: 1 key,
 * 2 discard, 4 corrupt); returns SQ_OK, SQ_EOF or a negative error.
 */
JNIEXPORT jint JNICALL FN(fileReadPacket)(JNIEnv *env, jclass cls, jlong h, jlongArray jmeta) {
    (void) cls;
    SqFile *f = (SqFile *) (intptr_t) h;
    av_packet_unref(f->pkt);
    int r = av_read_frame(f->fmt, f->pkt);
    if (r == AVERROR_EOF || (r < 0 && avio_feof(f->fmt->pb))) return SQ_EOF;
    if (r < 0) return r;
    AVStream *st = f->fmt->streams[f->pkt->stream_index];
    jlong meta[7];
    meta[0] = f->pkt->stream_index;
    meta[1] = to_us(f->pkt->pts, st->time_base);
    meta[2] = to_us(f->pkt->dts, st->time_base);
    meta[3] = f->pkt->duration > 0 ? av_rescale_q(f->pkt->duration, st->time_base, US) : 0;
    meta[4] = ((f->pkt->flags & AV_PKT_FLAG_KEY) ? 1 : 0) | ((f->pkt->flags & AV_PKT_FLAG_DISCARD) ? 2 : 0) |
              ((f->pkt->flags & AV_PKT_FLAG_CORRUPT) ? 4 : 0);
    meta[5] = f->pkt->size;
    meta[6] = f->pkt->pos;
    (*env)->SetLongArrayRegion(env, jmeta, 0, 7, meta);
    return SQ_OK;
}

/** Copies the packet last read into [dst] at [offset]; returns its size. */
JNIEXPORT jint JNICALL FN(filePacketData)(JNIEnv *env, jclass cls, jlong h, jbyteArray dst, jint offset) {
    (void) cls;
    SqFile *f = (SqFile *) (intptr_t) h;
    jsize room = (*env)->GetArrayLength(env, dst) - offset;
    if (f->pkt->size > room) return AVERROR(ENOSPC);
    (*env)->SetByteArrayRegion(env, dst, offset, f->pkt->size, (const jbyte *) f->pkt->data);
    return f->pkt->size;
}

/**
 * Seeks so the next packets read are from the last key frame at or before
 * [timeUs] (container time, start included). Every stream at once.
 */
JNIEXPORT jint JNICALL FN(fileSeek)(JNIEnv *env, jclass cls, jlong h, jlong timeUs) {
    (void) env; (void) cls;
    SqFile *f = (SqFile *) (intptr_t) h;
    av_packet_unref(f->pkt);
    int r = avformat_seek_file(f->fmt, -1, INT64_MIN, timeUs, timeUs, 0);
    if (r < 0) r = av_seek_frame(f->fmt, -1, timeUs, AVSEEK_FLAG_BACKWARD);
    if (r < 0) {
        // Some streams cannot seek by time at all; byte zero is still a place to start from.
        if (timeUs <= 0 || av_seek_frame(f->fmt, -1, 0, AVSEEK_FLAG_BYTE) >= 0) {
            if (timeUs <= 0) avio_seek(f->fmt->pb, 0, SEEK_SET);
            return 0;
        }
    }
    return r;
}

/** The byte offset of the index entry for [stream] at or before [timeUs], or -1. */
JNIEXPORT jlong JNICALL FN(fileIndexPosition)(JNIEnv *env, jclass cls, jlong h, jint stream, jlong timeUs) {
    (void) env; (void) cls;
    SqFile *f = (SqFile *) (intptr_t) h;
    if (stream < 0 || (unsigned) stream >= f->fmt->nb_streams) return -1;
    AVStream *st = f->fmt->streams[stream];
    int64_t ts = av_rescale_q(timeUs, US, st->time_base);
    int i = av_index_search_timestamp(st, ts, AVSEEK_FLAG_BACKWARD);
    if (i < 0) return -1;
    const AVIndexEntry *e = avformat_index_get_entry(st, i);
    return e ? e->pos : -1;
}

/* ------------------------------------------------------------------ */
/* Decoders                                                           */
/* ------------------------------------------------------------------ */

typedef struct {
    AVCodecContext *ctx;
    AVFrame *frame;
    AVPacket *pkt;
    int draining;
    struct SwsContext *sws;
    SwrContext *swr;
    int swr_fmt, swr_rate;
    AVChannelLayout swr_layout;
    int out_channels;
    uint8_t *tmp[4];
    int tmp_lines[4];
    int tmp_w, tmp_h;
} SqDec;

static void dec_free(SqDec *d) {
    if (!d) return;
    avcodec_free_context(&d->ctx);
    av_frame_free(&d->frame);
    av_packet_free(&d->pkt);
    sws_freeContext(d->sws);
    swr_free(&d->swr);
    av_channel_layout_uninit(&d->swr_layout);
    av_freep(&d->tmp[0]);
    av_free(d);
}

/** A decoder for the parameter blob [params], with up to [threads] threads; a handle or a negative error. */
JNIEXPORT jlong JNICALL FN(decOpen)(JNIEnv *env, jclass cls, jbyteArray jparams, jint threads) {
    (void) cls;
    jsize size = (*env)->GetArrayLength(env, jparams);
    jbyte *bytes = (*env)->GetByteArrayElements(env, jparams, NULL);
    if (!bytes) return AVERROR(ENOMEM);
    AVCodecParameters *par = avcodec_parameters_alloc();
    int r = par ? read_params((const uint8_t *) bytes, (size_t) size, par) : AVERROR(ENOMEM);
    (*env)->ReleaseByteArrayElements(env, jparams, bytes, JNI_ABORT);
    if (r < 0) {
        avcodec_parameters_free(&par);
        return r;
    }
    const AVCodec *codec = avcodec_find_decoder(par->codec_id);
    if (!codec) {
        avcodec_parameters_free(&par);
        return AVERROR_DECODER_NOT_FOUND;
    }
    SqDec *d = av_mallocz(sizeof *d);
    if (!d) {
        avcodec_parameters_free(&par);
        return AVERROR(ENOMEM);
    }
    d->ctx = avcodec_alloc_context3(codec);
    d->frame = av_frame_alloc();
    d->pkt = av_packet_alloc();
    if (!d->ctx || !d->frame || !d->pkt) {
        avcodec_parameters_free(&par);
        dec_free(d);
        return AVERROR(ENOMEM);
    }
    r = avcodec_parameters_to_context(d->ctx, par);
    avcodec_parameters_free(&par);
    if (r < 0) {
        dec_free(d);
        return r;
    }
    d->ctx->pkt_timebase = US;
    if (d->ctx->codec_type == AVMEDIA_TYPE_VIDEO) {
        d->ctx->thread_count = threads > 0 ? threads : 0;
        d->ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    } else {
        d->ctx->thread_count = 1;
    }
    // Corrupt frames are shown rather than dropped: a scratch in an old
    // capture is better seen than a hole in the clip.
    d->ctx->flags |= AV_CODEC_FLAG_OUTPUT_CORRUPT;
    r = avcodec_open2(d->ctx, codec, NULL);
    if (r < 0) {
        dec_free(d);
        return r;
    }
    return (jlong) (intptr_t) d;
}

JNIEXPORT void JNICALL FN(decClose)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    dec_free((SqDec *) (intptr_t) h);
}

JNIEXPORT void JNICALL FN(decFlush)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    avcodec_flush_buffers(d->ctx);
    av_frame_unref(d->frame);
    d->draining = 0;
    if (d->swr) swr_free(&d->swr);
}

JNIEXPORT jstring JNICALL FN(decName)(JNIEnv *env, jclass cls, jlong h) {
    (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    return (*env)->NewStringUTF(env, d->ctx->codec ? d->ctx->codec->name : "ffmpeg");
}

static int send(SqDec *d, const uint8_t *data, int size, int64_t pts_us, int key) {
    int r;
    if (size < 0) {
        if (d->draining) return SQ_EOF;
        r = avcodec_send_packet(d->ctx, NULL);
        if (r == 0 || r == AVERROR_EOF) d->draining = 1;
    } else {
        av_packet_unref(d->pkt);
        r = av_new_packet(d->pkt, size);
        if (r < 0) return r;
        if (size) memcpy(d->pkt->data, data, (size_t) size);
        d->pkt->pts = pts_us == INT64_MIN ? AV_NOPTS_VALUE : pts_us;
        d->pkt->dts = AV_NOPTS_VALUE;
        if (key) d->pkt->flags |= AV_PKT_FLAG_KEY;
        r = avcodec_send_packet(d->ctx, d->pkt);
    }
    if (r == AVERROR(EAGAIN)) return SQ_AGAIN;
    if (r == AVERROR_EOF) return SQ_EOF;
    // A packet the decoder refuses is one bad sample, not the end of the
    // file: say so and let the caller go on to the next.
    if (r == AVERROR_INVALIDDATA || r == AVERROR(EINVAL) || r == AVERROR_PATCHWELCOME) return SQ_SKIPPED;
    return r;
}

/**
 * Sends [size] bytes of [buffer] (a direct ByteBuffer) from [offset] as one
 * packet at [ptsUs]; a negative [size] starts the drain. SQ_OK, SQ_AGAIN
 * (take a frame first, then send the same packet again), SQ_EOF, SQ_SKIPPED
 * or a negative error.
 */
JNIEXPORT jint JNICALL FN(decSend)(JNIEnv *env, jclass cls, jlong h, jobject buffer, jint offset, jint size,
                                   jlong ptsUs, jboolean key) {
    (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    const uint8_t *data = NULL;
    if (size > 0) {
        data = (*env)->GetDirectBufferAddress(env, buffer);
        if (!data) return AVERROR(EINVAL);
        data += offset;
    }
    return send(d, data, size, ptsUs, key);
}

static void frame_meta(SqDec *d, JNIEnv *env, jlongArray jmeta) {
    AVFrame *fr = d->frame;
    jlong meta[12];
    int64_t pts = fr->best_effort_timestamp != AV_NOPTS_VALUE ? fr->best_effort_timestamp : fr->pts;
    meta[0] = pts == AV_NOPTS_VALUE ? INT64_MIN : pts;
    meta[1] = fr->width;
    meta[2] = fr->height;
    meta[3] = fr->colorspace;
    meta[4] = fr->color_range;
    meta[5] = fr->color_trc;
    meta[6] = fr->nb_samples;
    meta[7] = fr->sample_rate;
    meta[8] = fr->ch_layout.nb_channels > 8 ? 2 : fr->ch_layout.nb_channels;
    meta[9] = (fr->flags & AV_FRAME_FLAG_KEY) ? 1 : 0;
    meta[10] = fr->duration > 0 ? fr->duration : 0;
    meta[11] = fr->format;
    (*env)->SetLongArrayRegion(env, jmeta, 0, 12, meta);
}

static int receive(SqDec *d) {
    av_frame_unref(d->frame);
    int r = avcodec_receive_frame(d->ctx, d->frame);
    if (r == AVERROR(EAGAIN)) return SQ_AGAIN;
    if (r == AVERROR_EOF) return SQ_EOF;
    return r < 0 ? r : SQ_OK;
}

/**
 * Takes the next decoded frame, writing [ptsUs, width, height, colorspace,
 * range, transfer, samples, sampleRate, outChannels, key, durationUs,
 * format] into [meta]. SQ_OK, SQ_AGAIN, SQ_EOF or a negative error.
 */
JNIEXPORT jint JNICALL FN(decReceive)(JNIEnv *env, jclass cls, jlong h, jlongArray jmeta) {
    (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    int r = receive(d);
    if (r == SQ_OK) frame_meta(d, env, jmeta);
    return r;
}

/**
 * Takes the frame last received out of the decoder as a handle of its own -
 * a reference, not a copy - so a player can hold several until it is time
 * to show each. Freed with frameFree.
 */
JNIEXPORT jlong JNICALL FN(decTakeFrame)(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    AVFrame *held = av_frame_alloc();
    if (!held) return 0;
    av_frame_move_ref(held, d->frame);
    return (jlong) (intptr_t) held;
}

JNIEXPORT void JNICALL FN(frameFree)(JNIEnv *env, jclass cls, jlong frame) {
    (void) env; (void) cls;
    AVFrame *f = (AVFrame *) (intptr_t) frame;
    av_frame_free(&f);
}

static AVFrame *frame_of(SqDec *d, jlong frame) {
    return frame ? (AVFrame *) (intptr_t) frame : d->frame;
}

/**
 * Reads [file]'s packets of [stream] into [dec] until a frame comes out,
 * draining at the end of the file. SQ_OK with the frame's [meta], SQ_EOF, or
 * a negative error. The packets of other streams are passed over.
 */
JNIEXPORT jint JNICALL FN(fileDecodeNext)(JNIEnv *env, jclass cls, jlong fh, jlong dh, jint stream,
                                          jlongArray jmeta) {
    (void) cls;
    SqFile *f = (SqFile *) (intptr_t) fh;
    SqDec *d = (SqDec *) (intptr_t) dh;
    int failures = 0;
    for (;;) {
        int r = receive(d);
        if (r == SQ_OK) {
            frame_meta(d, env, jmeta);
            return SQ_OK;
        }
        if (r == SQ_EOF) return SQ_EOF;
        if (r < 0 && ++failures > 64) return r;
        if (d->draining) return SQ_EOF;
        av_packet_unref(f->pkt);
        int rr = av_read_frame(f->fmt, f->pkt);
        if (rr < 0) {
            send(d, NULL, -1, 0, 0);
            continue;
        }
        if (f->pkt->stream_index != stream) continue;
        AVStream *st = f->fmt->streams[stream];
        int64_t pts = f->pkt->pts != AV_NOPTS_VALUE ? f->pkt->pts : f->pkt->dts;
        int s = send(d, f->pkt->data, f->pkt->size, to_us(pts, st->time_base),
                     (f->pkt->flags & AV_PKT_FLAG_KEY) != 0);
        if (s == SQ_AGAIN) {
            // Cannot happen after a receive said AGAIN, but if a decoder
            // does it the packet is simply lost rather than the loop stuck.
            LOGW("decoder refused a packet after asking for one");
        } else if (s < 0 && ++failures > 64) {
            return s;
        }
    }
}

/** Seeks [file] to [timeUs] and empties [dec], ready for fileDecodeNext. */
JNIEXPORT jint JNICALL FN(fileSeekDecoder)(JNIEnv *env, jclass cls, jlong fh, jlong dh, jlong timeUs) {
    int r = FN(fileSeek)(env, cls, fh, timeUs);
    FN(decFlush)(env, cls, dh);
    return r;
}

static enum AVPixelFormat plain(enum AVPixelFormat f, int *full) {
    // The deprecated "J" formats are their plain twins at full range.
    switch (f) {
        case AV_PIX_FMT_YUVJ420P: *full = 1; return AV_PIX_FMT_YUV420P;
        case AV_PIX_FMT_YUVJ422P: *full = 1; return AV_PIX_FMT_YUV422P;
        case AV_PIX_FMT_YUVJ444P: *full = 1; return AV_PIX_FMT_YUV444P;
        case AV_PIX_FMT_YUVJ440P: *full = 1; return AV_PIX_FMT_YUV440P;
        case AV_PIX_FMT_YUVJ411P: *full = 1; return AV_PIX_FMT_YUV411P;
        default: return f;
    }
}

static int is_rgb(enum AVPixelFormat f) {
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(f);
    return d && (d->flags & AV_PIX_FMT_FLAG_RGB);
}

/**
 * A scaler from the frame to [dw]x[dh] of [dst], with the frame's own matrix
 * and range on the way in and [dst_full] on the way out.
 */
static struct SwsContext *scaler(SqDec *d, AVFrame *fr, int dw, int dh, enum AVPixelFormat dst, int dst_full, int flags) {
    int full = fr->color_range == AVCOL_RANGE_JPEG;
    enum AVPixelFormat src = plain((enum AVPixelFormat) fr->format, &full);
    d->sws = sws_getCachedContext(d->sws, fr->width, fr->height, src, dw, dh, dst, flags, NULL, NULL, NULL);
    if (!d->sws) return NULL;
    int space = fr->colorspace;
    if (space == AVCOL_SPC_UNSPECIFIED || space == AVCOL_SPC_RESERVED || space == AVCOL_SPC_RGB)
        space = fr->height >= 720 ? AVCOL_SPC_BT709 : AVCOL_SPC_SMPTE170M;
    if (space == AVCOL_SPC_BT2020_CL) space = AVCOL_SPC_BT2020_NCL;
    const int *coeffs = sws_getCoefficients(space);
    int src_full = is_rgb(src) ? 1 : full;
    sws_setColorspaceDetails(d->sws, coeffs, src_full, coeffs, dst_full, 0, 1 << 16, 1 << 16);
    return d->sws;
}

/** Makes [d->tmp] a planar 8-bit 4:2:0 copy of the frame at its own size; null planes on failure. */
static int to_i420(SqDec *d, AVFrame *fr, const uint8_t **planes, int *lines) {
    int full = fr->color_range == AVCOL_RANGE_JPEG;
    enum AVPixelFormat src = plain((enum AVPixelFormat) fr->format, &full);
    if (src == AV_PIX_FMT_YUV420P) {
        for (int i = 0; i < 3; i++) {
            planes[i] = fr->data[i];
            lines[i] = fr->linesize[i];
        }
        return 0;
    }
    if (d->tmp_w != fr->width || d->tmp_h != fr->height || !d->tmp[0]) {
        av_freep(&d->tmp[0]);
        int r = av_image_alloc(d->tmp, d->tmp_lines, fr->width, fr->height, AV_PIX_FMT_YUV420P, 32);
        if (r < 0) return r;
        d->tmp_w = fr->width;
        d->tmp_h = fr->height;
    }
    // An RGB source is written out limited range, as a camera's YUV would be;
    // a YUV source keeps its own range so the DataSpace named for it is true.
    int dst_full = is_rgb(src) ? 0 : full;
    struct SwsContext *sws = scaler(d, fr, fr->width, fr->height, AV_PIX_FMT_YUV420P, dst_full, SWS_BILINEAR);
    if (!sws) return AVERROR(EINVAL);
    sws_scale(sws, (const uint8_t *const *) fr->data, fr->linesize, 0, fr->height, d->tmp, d->tmp_lines);
    for (int i = 0; i < 3; i++) {
        planes[i] = d->tmp[i];
        lines[i] = d->tmp_lines[i];
    }
    return 0;
}

static void copy_plane(uint8_t *dst, int dst_row, int dst_pixel, const uint8_t *src, int src_row, int w, int h) {
    for (int y = 0; y < h; y++) {
        const uint8_t *s = src + (size_t) y * src_row;
        uint8_t *o = dst + (size_t) y * dst_row;
        if (dst_pixel == 1) {
            memcpy(o, s, (size_t) w);
        } else {
            for (int x = 0; x < w; x++) o[x * dst_pixel] = s[x];
        }
    }
}

/**
 * Writes the frame into an image's three planes (ImageWriter's
 * YUV_420_888: each plane's buffer, row stride and pixel stride). Whether
 * the frame was written full range is returned (1) or not (0), or a
 * negative error.
 */
JNIEXPORT jint JNICALL FN(decFrameToYuv)(JNIEnv *env, jclass cls, jlong h, jlong frame,
                                         jobject y, jint yRow, jobject u, jint uRow, jint uPixel,
                                         jobject v, jint vRow, jint vPixel) {
    (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    AVFrame *fr = frame_of(d, frame);
    const uint8_t *planes[3];
    int lines[3];
    int r = to_i420(d, fr, planes, lines);
    if (r < 0) return r;
    uint8_t *yd = (*env)->GetDirectBufferAddress(env, y);
    uint8_t *ud = (*env)->GetDirectBufferAddress(env, u);
    uint8_t *vd = (*env)->GetDirectBufferAddress(env, v);
    if (!yd || !ud || !vd) return AVERROR(EINVAL);
    int w = fr->width, hh = fr->height;
    int cw = (w + 1) / 2, ch = (hh + 1) / 2;
    copy_plane(yd, yRow, 1, planes[0], lines[0], w, hh);
    copy_plane(ud, uRow, uPixel, planes[1], lines[1], cw, ch);
    copy_plane(vd, vRow, vPixel, planes[2], lines[2], cw, ch);
    int full = fr->color_range == AVCOL_RANGE_JPEG;
    enum AVPixelFormat src = plain((enum AVPixelFormat) fr->format, &full);
    return is_rgb(src) ? 0 : full;
}

/** Writes the frame as RGBA into [buffer] (rows [rowStride] bytes apart) at [w]x[h]. */
JNIEXPORT jint JNICALL FN(decFrameToRgbaBuffer)(JNIEnv *env, jclass cls, jlong h, jlong frame, jobject buffer,
                                                jint rowStride, jint w, jint hh) {
    (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    AVFrame *fr = frame_of(d, frame);
    uint8_t *dst = (*env)->GetDirectBufferAddress(env, buffer);
    if (!dst) return AVERROR(EINVAL);
    struct SwsContext *sws = scaler(d, fr, w, hh, AV_PIX_FMT_RGBA, 1, SWS_BILINEAR);
    if (!sws) return AVERROR(EINVAL);
    uint8_t *out[4] = {dst, NULL, NULL, NULL};
    int lines[4] = {rowStride, 0, 0, 0};
    sws_scale(sws, (const uint8_t *const *) fr->data, fr->linesize, 0, fr->height, out, lines);
    return 0;
}

/**
 * Draws the frame into an ARGB_8888 [bitmap], scaled to the bitmap's size.
 * The bitmap's own size decides the scale; the caller turns it upright.
 */
JNIEXPORT jint JNICALL FN(decFrameToBitmap)(JNIEnv *env, jclass cls, jlong h, jlong frame, jobject bitmap) {
    (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    AVFrame *fr = frame_of(d, frame);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return AVERROR(EINVAL);
    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return AVERROR(EINVAL);
    int r = 0;
    // Area averaging when shrinking a lot (thumbnails of 4K), bilinear otherwise.
    int flags = (fr->width > (int) info.width * 2) ? SWS_AREA : SWS_BILINEAR;
    enum AVPixelFormat dst = AV_PIX_FMT_RGBA;
    struct SwsContext *sws = scaler(d, fr, (int) info.width, (int) info.height, dst, 1, flags);
    if (!sws) {
        r = AVERROR(EINVAL);
    } else {
        uint8_t *out[4] = {pixels, NULL, NULL, NULL};
        int lines[4] = {(int) info.stride, 0, 0, 0};
        sws_scale(sws, (const uint8_t *const *) fr->data, fr->linesize, 0, fr->height, out, lines);
        // A picture with no alpha of its own comes out opaque from swscale
        // already; one with alpha is left straight, and Android bitmaps are
        // premultiplied, so it is multiplied through here.
        const AVPixFmtDescriptor *pd = av_pix_fmt_desc_get((enum AVPixelFormat) fr->format);
        if (pd && (pd->flags & AV_PIX_FMT_FLAG_ALPHA) && !(info.flags & ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL)) {
            for (uint32_t yy = 0; yy < info.height; yy++) {
                uint8_t *p = (uint8_t *) pixels + (size_t) yy * info.stride;
                for (uint32_t x = 0; x < info.width; x++, p += 4) {
                    unsigned a = p[3];
                    if (a == 255) continue;
                    p[0] = (uint8_t) ((p[0] * a + 127) / 255);
                    p[1] = (uint8_t) ((p[1] * a + 127) / 255);
                    p[2] = (uint8_t) ((p[2] * a + 127) / 255);
                }
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return r;
}

/** The frame's luma, scaled to [w]x[h], into [out] (w*h bytes, full range). */
JNIEXPORT jint JNICALL FN(decFrameToGray)(JNIEnv *env, jclass cls, jlong h, jlong frame, jbyteArray out,
                                          jint w, jint hh) {
    (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    AVFrame *fr = frame_of(d, frame);
    if ((*env)->GetArrayLength(env, out) < w * hh) return AVERROR(ENOSPC);
    struct SwsContext *sws = scaler(d, fr, w, hh, AV_PIX_FMT_GRAY8, 1, SWS_AREA);
    if (!sws) return AVERROR(EINVAL);
    jbyte *bytes = (*env)->GetByteArrayElements(env, out, NULL);
    if (!bytes) return AVERROR(ENOMEM);
    uint8_t *dst[4] = {(uint8_t *) bytes, NULL, NULL, NULL};
    int lines[4] = {w, 0, 0, 0};
    sws_scale(sws, (const uint8_t *const *) fr->data, fr->linesize, 0, fr->height, dst, lines);
    (*env)->ReleaseByteArrayElements(env, out, bytes, 0);
    return 0;
}

/**
 * The audio frame as interleaved signed 16-bit PCM at its own rate, into the
 * direct [buffer] from [offset] with [capacity] bytes of room; more than
 * eight channels are folded to stereo (the most an AudioTrack takes).
 * Returns the bytes written, or a negative error (ENOSPC: give more room;
 * a frame's samples times its channels times two, plus a little, is enough).
 */
JNIEXPORT jint JNICALL FN(decFrameToPcm16)(JNIEnv *env, jclass cls, jlong h, jlong frame, jobject buffer,
                                           jint offset, jint capacity) {
    (void) cls;
    SqDec *d = (SqDec *) (intptr_t) h;
    AVFrame *fr = frame_of(d, frame);
    if (fr->nb_samples <= 0 || fr->ch_layout.nb_channels <= 0) return 0;
    uint8_t *dst = (*env)->GetDirectBufferAddress(env, buffer);
    if (!dst) return AVERROR(EINVAL);
    dst += offset;
    int in_channels = fr->ch_layout.nb_channels;
    int out_channels = in_channels > 8 ? 2 : in_channels;
    if (!d->swr || d->swr_fmt != fr->format || d->swr_rate != fr->sample_rate ||
        av_channel_layout_compare(&d->swr_layout, &fr->ch_layout)) {
        swr_free(&d->swr);
        av_channel_layout_uninit(&d->swr_layout);
        AVChannelLayout out_layout = {0};
        if (out_channels == in_channels && fr->ch_layout.order == AV_CHANNEL_ORDER_NATIVE) {
            av_channel_layout_copy(&out_layout, &fr->ch_layout);
        } else {
            av_channel_layout_default(&out_layout, out_channels);
        }
        AVChannelLayout in_layout = {0};
        if (fr->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) av_channel_layout_default(&in_layout, in_channels);
        else av_channel_layout_copy(&in_layout, &fr->ch_layout);
        int r = swr_alloc_set_opts2(&d->swr, &out_layout, AV_SAMPLE_FMT_S16, fr->sample_rate,
                                    &in_layout, (enum AVSampleFormat) fr->format, fr->sample_rate, 0, NULL);
        av_channel_layout_uninit(&out_layout);
        av_channel_layout_uninit(&in_layout);
        if (r < 0 || swr_init(d->swr) < 0) {
            swr_free(&d->swr);
            return r < 0 ? r : AVERROR(EINVAL);
        }
        d->swr_fmt = fr->format;
        d->swr_rate = fr->sample_rate;
        av_channel_layout_copy(&d->swr_layout, &fr->ch_layout);
        d->out_channels = out_channels;
    }
    int room = capacity / (2 * d->out_channels);
    int need = swr_get_out_samples(d->swr, fr->nb_samples);
    if (need > room) return AVERROR(ENOSPC);
    uint8_t *out[1] = {dst};
    int n = swr_convert(d->swr, out, room, (const uint8_t *const *) fr->extended_data, fr->nb_samples);
    if (n < 0) return n;
    return n * 2 * d->out_channels;
}

/* ------------------------------------------------------------------ */
/* Misc                                                               */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL FN(version)(JNIEnv *env, jclass cls) {
    (void) cls;
    char buf[160];
    snprintf(buf, sizeof buf, "FFmpeg %s, libavcodec %u.%u, libavformat %u.%u", av_version_info(),
             avcodec_version() >> 16, (avcodec_version() >> 8) & 0xff,
             avformat_version() >> 16, (avformat_version() >> 8) & 0xff);
    return (*env)->NewStringUTF(env, buf);
}

/** Whether a decoder exists for the codec FFmpeg names [name] ("h264", "prores", "wmv3"...). */
JNIEXPORT jboolean JNICALL FN(hasDecoder)(JNIEnv *env, jclass cls, jstring jname) {
    (void) cls;
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!name) return JNI_FALSE;
    const AVCodecDescriptor *desc = avcodec_descriptor_get_by_name(name);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return desc && avcodec_find_decoder(desc->id) ? JNI_TRUE : JNI_FALSE;
}

/** Counts of what this build reads: [decoders, demuxers]. */
JNIEXPORT jintArray JNICALL FN(counts)(JNIEnv *env, jclass cls) {
    (void) cls;
    int decoders = 0, demuxers = 0;
    void *it = NULL;
    const AVCodec *c;
    while ((c = av_codec_iterate(&it))) if (av_codec_is_decoder(c)) decoders++;
    it = NULL;
    while (av_demuxer_iterate(&it)) demuxers++;
    jintArray out = (*env)->NewIntArray(env, 2);
    jint v[2] = {decoders, demuxers};
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 2, v);
    return out;
}

static void log_callback(void *avcl, int level, const char *fmt, va_list vl) {
    (void) avcl;
    if (level > AV_LOG_WARNING) return;
    char line[512];
    vsnprintf(line, sizeof line, fmt, vl);
    __android_log_print(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "SquishAvLib", "%s", line);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm; (void) reserved;
    av_log_set_level(AV_LOG_WARNING);
    av_log_set_callback(log_callback);
    return JNI_VERSION_1_6;
}

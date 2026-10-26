// libsquishwhisper.so: whisper.cpp (MIT) behind a small JNI surface, for
// captions on phones with no on-device recognizer (EDGE_PLAN E1). Built by
// tools/native/build_whisper.sh on GitHub's runners and committed under
// app/src/main/jniLibs, like libsquishav.so.
//
// Words, not sentences: whisper.cpp splits its segments on word boundaries
// when max_len is 1 and split_on_word is set, and each segment then carries
// its own start and end - the times the captions' word reveal needs.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "SquishWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static void quiet_log(enum ggml_log_level, const char *, void *) {}

extern "C" JNIEXPORT jlong JNICALL
Java_com_squish_app_media_audio_WhisperNative_open(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_log_set(quiet_log, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_squish_app_media_audio_WhisperNative_close(JNIEnv *, jclass, jlong h) {
    if (h != 0) whisper_free(reinterpret_cast<whisper_context *>(h));
}

// Transcribes [pcm] (16 kHz mono floats). [lang] is a code ("en", "hi", "ml")
// or "auto". Fills [out] with one line per word: "t0ms\tt1ms\ttext", then a
// last line "lang\t<code>"; false when whisper.cpp failed.
static bool run(JNIEnv *env, jlong h, jfloatArray jpcm, jstring jlang, jint threads, std::string &out,
                jstring jprompt = nullptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(h);
    if (ctx == nullptr) return false;
    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);

    jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = language.c_str();
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.token_timestamps = true;
    p.max_len = 1;
    p.split_on_word = true;
    p.suppress_blank = true;
    // A line in the language's own script, as the text before the first
    // window: told only "ml", whisper.cpp's small model heard Malayalam right
    // and wrote it in Devanagari (26 Oct, on the moto).
    std::string prompt;
    if (jprompt != nullptr) {
        const char *pr = env->GetStringUTFChars(jprompt, nullptr);
        prompt = pr;
        env->ReleaseStringUTFChars(jprompt, pr);
    }
    if (!prompt.empty()) p.initial_prompt = prompt.c_str();

    if (whisper_full(ctx, p, pcm.data(), n) != 0) return false;

    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        std::string word(text);
        // Trim the spaces whisper.cpp leaves round a word; skip empties.
        size_t a = word.find_first_not_of(" \t\r\n");
        if (a == std::string::npos) continue;
        size_t b = word.find_last_not_of(" \t\r\n");
        word = word.substr(a, b - a + 1);
        for (char &c : word) if (c == '\t' || c == '\n') c = ' ';
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;  // centiseconds -> ms
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        out += std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + word + "\n";
    }
    const int id = whisper_full_lang_id(ctx);
    out += std::string("lang\t") + (id >= 0 ? whisper_lang_str(id) : "") + "\n";
    return true;
}

// The first surface, kept so an app built before transcribeBytes still runs.
// NewStringUTF wants modified UTF-8, and whisper.cpp's pieces can end inside
// a multi-byte character: the first Hindi file on the moto aborted the app
// here (26 Oct). New callers use transcribeBytes.
extern "C" JNIEXPORT jstring JNICALL
Java_com_squish_app_media_audio_WhisperNative_transcribe(JNIEnv *env, jclass, jlong h, jfloatArray jpcm,
                                                          jstring jlang, jint threads) {
    std::string out;
    if (!run(env, h, jpcm, jlang, threads, out)) return nullptr;
    return env->NewStringUTF(out.c_str());
}

// The same lines as raw bytes, read by WhisperText.parseBytes, which joins a
// character cut across two pieces and decodes whatever is left as U+FFFD.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_squish_app_media_audio_WhisperNative_transcribeBytes(JNIEnv *env, jclass, jlong h, jfloatArray jpcm,
                                                               jstring jlang, jint threads) {
    std::string out;
    if (!run(env, h, jpcm, jlang, threads, out)) return nullptr;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}

// transcribeBytes with a starting prompt in the language's script (see run).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_squish_app_media_audio_WhisperNative_transcribePrompted(JNIEnv *env, jclass, jlong h, jfloatArray jpcm,
                                                                  jstring jlang, jstring jprompt, jint threads) {
    std::string out;
    if (!run(env, h, jpcm, jlang, threads, out, jprompt)) return nullptr;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}

// Sirrak inference bridge: llama.cpp (vendored third_party/llama.cpp, MIT) behind a small JNI API.
//
// Design notes:
//  * One Session owns one model file and one KV context; the Kotlin engine loads a model and streams
//    tokens until stop is requested, a stop sequence matches, or the model emits EOG.
//  * Tokens cross JNI through a sink callback. Text is converted UTF-8 -> UTF-16 for JNI so 4-byte
//    code points (emoji) are safe; pieces that split a code point are buffered.
//  * The KV cache is cleared before every request: the full conversation is re-prefilled. At 2k context
//    on a phone this keeps behaviour predictable without a cache-invalidation bug surface.
//  * Cancellation is cooperative: nativeRequestStop() flips an atomic flag checked once per token.
//  * M5 model control: the JNI surface carries a model-facts report (GGUF metadata), the model's own
//    chat template, the built-in template names, a prompt preview, and the full generation parameter
//    set. The sampler chain mirrors common/sampling.cpp order, incl. mirostat_v2 and greedy modes.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

#define TAG "SirrakLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_once;

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    uint32_t n_ctx = 0;
    uint32_t n_batch = 0;
    std::atomic<bool> stop{false};
    // M9: multimodal projector (mmproj GGUF). Null for a text-only model; when present the session
    // can evaluate an image chunk plus text through mtmd before sampling the answer.
    mtmd_context * mctx = nullptr;
};

// Generation parameters, in the exact index order the Kotlin FloatArray/IntArray promises.
struct GenerationParams {
    float temperature = 0.7f;
    float top_p = 0.95f;
    float repeat_penalty = 1.0f;
    float presence_penalty = 0.0f;
    float frequency_penalty = 0.0f;
    float min_p = 0.05f;
    float typical_p = 1.0f;
    float mirostat_tau = 5.0f;
    float mirostat_eta = 0.1f;
    float xtc_probability = 0.0f;
    float xtc_threshold = 0.1f;
    float dry_multiplier = 0.0f;
    float top_n_sigma = -1.0f;
    int32_t top_k = 40;
    int32_t repeat_last_n = 64;
    int32_t seed = -1;
    int32_t sampling_mode = 0; // 0 default, 1 greedy, 2 mirostat_v2
};

std::string jstring_to_std(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// UTF-8 -> UTF-16, so JNI receives legal Java strings even for 4-byte code points.
void append_utf8_as_utf16(const std::string & utf8, std::vector<jchar> & out) {
    size_t i = 0;
    while (i < utf8.size()) {
        const unsigned char c = static_cast<unsigned char>(utf8[i]);
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2;
            cp = c & 0x1F;
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
            cp = c & 0x0F;
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
            cp = c & 0x07;
        }
        if (i + len > utf8.size()) len = 1;
        if (len > 1) {
            bool valid = true;
            for (size_t k = 1; k < len; ++k) {
                const unsigned char cc = static_cast<unsigned char>(utf8[i + k]);
                if ((cc & 0xC0) != 0x80) {
                    valid = false;
                    break;
                }
                cp = (cp << 6) | (cc & 0x3F);
            }
            if (!valid) {
                cp = 0xFFFD;
                len = 1;
            }
        }
        i += len;
        if (cp >= 0x10000 && cp <= 0x10FFFF) {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(cp > 0xFFFF ? 0xFFFD : cp));
        }
    }
}

jstring new_jstring(JNIEnv * env, const std::string & utf8) {
    std::vector<jchar> out;
    out.reserve(utf8.size() + 2);
    append_utf8_as_utf16(utf8, out);
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

jbyteArray new_jbytes(JNIEnv * env, const std::string & bytes) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (array != nullptr && !bytes.empty()) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte *>(bytes.data()));
    }
    return array;
}

long read_proc_status_kb(const char * key) {
    FILE * f = fopen("/proc/self/status", "r");
    if (f == nullptr) return -1;
    char line[256];
    const size_t klen = strlen(key);
    long value = -1;
    while (fgets(line, static_cast<int>(sizeof(line)), f) != nullptr) {
        if (strncmp(line, key, klen) == 0) {
            value = atol(line + klen);
            break;
        }
    }
    fclose(f);
    return value;
}

// llama_token_to_piece can return a partial UTF-8 sequence; only emit complete code points.
size_t complete_utf8_prefix(const std::string & s) {
    size_t i = 0;
    size_t complete = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len = 1;
        if (c < 0x80) {
            len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
        }
        if (i + len > s.size()) break;
        bool valid = true;
        for (size_t k = 1; k < len; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) {
                valid = false;
                break;
            }
        }
        if (!valid) {
            i += 1;
            continue;
        }
        i += len;
        complete = i;
    }
    return complete;
}

struct JniSink {
    JNIEnv * env = nullptr;
    jobject sink = nullptr;
    jmethodID on_token = nullptr;
    bool broken = false;

    void emit(const std::string & text) {
        if (broken || text.empty()) return;
        jstring piece = new_jstring(env, text);
        if (piece == nullptr) {
            env->ExceptionClear();
            broken = true;
            return;
        }
        env->CallVoidMethod(sink, on_token, piece);
        env->DeleteLocalRef(piece);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            broken = true;
        }
    }
};

struct ChatPrompt {
    std::vector<llama_token> tokens;
    size_t turns_dropped = 0;
};

std::string meta_val(llama_model * model, const char * key) {
    char buf[8192];
    const int32_t n = llama_model_meta_val_str(model, key, buf, sizeof(buf));
    if (n < 0) return {};
    return std::string(buf, static_cast<size_t>(n));
}

void append_meta_kv(std::string & out, llama_model * model, const char * key) {
    const std::string value = meta_val(model, key);
    if (value.empty()) return;
    out += key;
    out += '=';
    out += value;
    out += '\n';
}

std::string first_line(const std::string & text) {
    size_t end = text.find('\n');
    std::string line = text.substr(0, end == std::string::npos ? text.size() : end);
    while (!line.empty() && (line.back() == '\r' || line.back() == ' ' || line.back() == '\t')) line.pop_back();
    size_t start = 0;
    while (start < line.size() && (line[start] == ' ' || line[start] == '\t')) start += 1;
    return line.substr(start);
}

// Formats the conversation with the model's own template, an explicitly forced built-in template, or
// chatml when the model ships none (llama_chat_apply_template's documented default).
bool format_chat(llama_model * model, const std::string & system_prompt,
                 std::vector<std::pair<std::string, std::string>> & messages,
                 const char * forced_template, std::string & out) {
    if (messages.empty()) return false;
    const char * model_template = llama_model_chat_template(model, nullptr);
    const char * tmpl = (forced_template != nullptr && forced_template[0] != '\0') ? forced_template : model_template;

    auto apply = [&](const char * candidate) -> int32_t {
        std::vector<llama_chat_message> chat;
        chat.reserve(messages.size() + 1);
        if (!system_prompt.empty()) chat.push_back({"system", system_prompt.c_str()});
        for (auto & m : messages) chat.push_back({m.first.c_str(), m.second.c_str()});
        const int32_t need = llama_chat_apply_template(candidate, chat.data(), chat.size(), true, nullptr, 0);
        if (need <= 0) return need;
        out.assign(static_cast<size_t>(need), '\0');
        const int32_t n = llama_chat_apply_template(candidate, chat.data(), chat.size(), true, out.data(), need);
        if (n <= 0) return n;
        out.resize(static_cast<size_t>(n));
        return n;
    };

    int32_t n = apply(tmpl);
    if (n <= 0 && forced_template != nullptr && forced_template[0] != '\0') {
        LOGE("forced template '%s' is not a supported built-in; using the model template", forced_template);
        n = apply(model_template);
    }
    if (n <= 0) {
        LOGE("chat template could not be applied (forced=%s)", tmpl == nullptr ? "<model>" : tmpl);
        return false;
    }
    return true;
}

// Builds "<template>...assistant start" for the conversation, dropping the oldest turns until the
// prompt plus the requested generation budget fits the context window.
bool build_prompt(llama_model * model, const llama_vocab * vocab, const std::string & system_prompt,
                  std::vector<std::pair<std::string, std::string>> & messages, int32_t max_tokens,
                  uint32_t n_ctx, const char * forced_template, ChatPrompt & out) {
    while (true) {
        std::string formatted;
        if (!format_chat(model, system_prompt, messages, forced_template, formatted)) return false;

        std::vector<llama_token> tokens(formatted.size() + 8);
        int32_t nt = llama_tokenize(vocab, formatted.c_str(), static_cast<int32_t>(formatted.size()),
                                    tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
        if (nt < 0) {
            tokens.resize(static_cast<size_t>(-nt));
            nt = llama_tokenize(vocab, formatted.c_str(), static_cast<int32_t>(formatted.size()),
                                tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
        }
        if (nt <= 0) {
            LOGE("tokenize failed (%d)", nt);
            return false;
        }
        tokens.resize(static_cast<size_t>(nt));

        if (static_cast<uint32_t>(nt) + static_cast<uint32_t>(max_tokens) <= n_ctx) {
            out.tokens = std::move(tokens);
            return true;
        }
        if (messages.size() <= 2) {
            LOGE("conversation does not fit context: prompt=%d max=%d n_ctx=%u", nt, max_tokens, n_ctx);
            return false;
        }
        // Drop the oldest user+assistant pair and retry the template.
        messages.erase(messages.begin(), messages.begin() + 2);
        out.turns_dropped++;
    }
}

uint32_t resolve_seed(int32_t seed) {
    if (seed >= 0) return static_cast<uint32_t>(seed);
    return static_cast<uint32_t>(llama_time_us() & 0x7FFFFFFF);
}

// Mirrors commons/common/sampling.cpp: filters first, temperature near the end, penalties last.
llama_sampler * build_sampler(const llama_vocab * vocab, const GenerationParams & p, uint32_t seed) {
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (chain == nullptr) return nullptr;

    const bool greedy = p.sampling_mode == 1 || (p.sampling_mode == 0 && p.temperature <= 0.0f);
    if (greedy) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }
    if (p.sampling_mode == 2) {
        llama_sampler_chain_add(chain, llama_sampler_init_mirostat_v2(seed, p.mirostat_tau, p.mirostat_eta));
        return chain;
    }

    auto add = [&](llama_sampler * sampler) {
        if (sampler != nullptr) llama_sampler_chain_add(chain, sampler);
    };

    if (p.dry_multiplier > 0.0f) {
        static const char * BREAKERS[] = {"\n", ":", "\"", "*", "}", "{", "(", ")", "[", "]", "<", ">"};
        add(llama_sampler_init_dry(vocab, p.dry_multiplier, 1.75f, 2, p.repeat_last_n, BREAKERS,
                                   sizeof(BREAKERS) / sizeof(BREAKERS[0])));
    }
    if (p.top_k > 0) add(llama_sampler_init_top_k(p.top_k));
    if (p.top_p < 1.0f) add(llama_sampler_init_top_p(p.top_p, 1));
    if (p.top_n_sigma >= 0.0f) add(llama_sampler_init_top_n_sigma(p.top_n_sigma));
    if (p.min_p > 0.0f) add(llama_sampler_init_min_p(p.min_p, 1));
    if (p.xtc_probability > 0.0f) add(llama_sampler_init_xtc(p.xtc_probability, p.xtc_threshold, 1, seed));
    if (p.typical_p < 1.0f) add(llama_sampler_init_typical(p.typical_p, 1));
    if (p.temperature != 1.0f) add(llama_sampler_init_temp(p.temperature));
    if (p.repeat_penalty != 1.0f || p.presence_penalty != 0.0f || p.frequency_penalty != 0.0f) {
        add(llama_sampler_init_penalties(static_cast<int32_t>(llama_vocab_n_tokens(vocab)), p.repeat_last_n,
                                         p.repeat_penalty, p.frequency_penalty, p.presence_penalty));
    }
    add(llama_sampler_init_dist(seed));
    return chain;
}

std::vector<std::string> split_stops(const std::string & joined) {
    std::vector<std::string> stops;
    if (joined.empty()) return stops;
    size_t start = 0;
    while (start <= joined.size()) {
        const size_t sep = joined.find('\x1F', start);
        const size_t end = sep == std::string::npos ? joined.size() : sep;
        if (end > start) stops.push_back(joined.substr(start, end - start));
        if (sep == std::string::npos) break;
        start = sep + 1;
    }
    return stops;
}

// Emits complete UTF-8 text while holding back a suffix that could still become a stop sequence.
class StopFilter {
  public:
    explicit StopFilter(std::vector<std::string> stops) : stops_(std::move(stops)) {
        for (auto & stop : stops_) {
            if (stop.size() > max_len_) max_len_ = stop.size();
        }
    }

    void feed(const std::string & text, JniSink & sink) {
        buffer_.append(text);
        drain(false, sink);
    }

    void finish(JniSink & sink) { drain(true, sink); }

    bool matched() const { return matched_; }

  private:
    void drain(bool flush, JniSink & sink) {
        while (!sink.broken) {
            size_t best = std::string::npos;
            for (auto & stop : stops_) {
                const size_t pos = buffer_.find(stop);
                if (pos != std::string::npos && (best == std::string::npos || pos < best)) best = pos;
            }
            if (best != std::string::npos) {
                if (best > 0) sink.emit(buffer_.substr(0, best));
                buffer_.clear();
                matched_ = true;
                return;
            }
            if (stops_.empty() || flush) {
                if (!buffer_.empty()) sink.emit(buffer_);
                buffer_.clear();
                return;
            }
            if (buffer_.size() < max_len_) return;
            const size_t safe = buffer_.size() - (max_len_ - 1);
            const size_t cut = complete_utf8_prefix(buffer_.substr(0, safe));
            if (cut == 0) return;
            sink.emit(buffer_.substr(0, cut));
            buffer_.erase(0, cut);
        }
    }

    std::vector<std::string> stops_;
    std::string buffer_;
    size_t max_len_ = 0;
    bool matched_ = false;
};

// ---------------------------------------------------------------------------------------------
// M9: shared by the text and vision entry points.
// ---------------------------------------------------------------------------------------------

// Role/content pairs arrive as one flat array: [user, text, assistant, text, ..., user, prompt].
bool read_messages(JNIEnv * env, jobjectArray jmessages,
                   std::vector<std::pair<std::string, std::string>> & messages) {
    if (jmessages == nullptr) return false;
    const jsize length = env->GetArrayLength(jmessages);
    if (length <= 0 || length % 2 != 0) {
        LOGE("messages must be role/content pairs, got %d", length);
        return false;
    }
    messages.reserve(static_cast<size_t>(length) / 2);
    for (jsize i = 0; i + 1 < length; i += 2) {
        auto * role = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i));
        auto * content = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i + 1));
        messages.emplace_back(jstring_to_std(env, role), jstring_to_std(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    return true;
}

// The flat float/int arrays are positional; their order is documented in LlamaNative.kt.
GenerationParams read_params(JNIEnv * env, jfloatArray jfloats, jintArray jints) {
    GenerationParams params;
    if (jfloats != nullptr && env->GetArrayLength(jfloats) >= 13) {
        jfloat values[13];
        env->GetFloatArrayRegion(jfloats, 0, 13, values);
        params.temperature = values[0];
        params.top_p = values[1];
        params.repeat_penalty = values[2];
        params.presence_penalty = values[3];
        params.frequency_penalty = values[4];
        params.min_p = values[5];
        params.typical_p = values[6];
        params.mirostat_tau = values[7];
        params.mirostat_eta = values[8];
        params.xtc_probability = values[9];
        params.xtc_threshold = values[10];
        params.dry_multiplier = values[11];
        params.top_n_sigma = values[12];
    }
    if (jints != nullptr && env->GetArrayLength(jints) >= 4) {
        jint values[4];
        env->GetIntArrayRegion(jints, 0, 4, values);
        params.top_k = values[0];
        params.repeat_last_n = values[1];
        params.seed = values[2];
        params.sampling_mode = values[3];
    }
    return params;
}

bool resolve_sink(JNIEnv * env, jobject jsink, jmethodID & on_token) {
    jclass sink_class = env->GetObjectClass(jsink);
    on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(sink_class);
    if (on_token == nullptr) {
        LOGE("TokenSink.onToken method not found");
        env->ExceptionClear();
        return false;
    }
    return true;
}

// One sampling loop for every entry point: the prompt is already prefilled, so this only streams
// tokens until EOG, a stop sequence, the token budget, or a cooperative stop request.
void run_generation(Session * session, llama_sampler * sampler, int32_t max_tokens,
                    const std::vector<std::string> & stop_sequences, JniSink & sink, int64_t t0,
                    int64_t t_prefilled, size_t prompt_tokens, size_t turns_dropped,
                    int image_tokens, const std::string & forced_template,
                    const GenerationParams & params) {
    const double prefill_ms = static_cast<double>(t_prefilled - t0) / 1000.0;
    StopFilter filter(stop_sequences);
    std::string pending;
    int generated = 0;
    double ttft_ms = -1.0;
    for (int i = 0; i < max_tokens && !session->stop.load() && !sink.broken; ++i) {
        const llama_token id = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, id)) break;
        llama_sampler_accept(sampler, id);

        char buf[512];
        const int32_t n_piece =
            llama_token_to_piece(session->vocab, id, buf, static_cast<int32_t>(sizeof(buf)), 0, false);
        if (n_piece > 0) {
            pending.append(buf, static_cast<size_t>(n_piece));
            const size_t cut = complete_utf8_prefix(pending);
            if (cut > 0) {
                if (ttft_ms < 0.0) ttft_ms = static_cast<double>(llama_time_us() - t0) / 1000.0;
                filter.feed(pending.substr(0, cut), sink);
                pending.erase(0, cut);
            }
        }
        ++generated;
        if (filter.matched()) break;

        llama_token next = id;
        if (llama_decode(session->ctx, llama_batch_get_one(&next, 1)) != 0) {
            LOGE("step decode failed");
            break;
        }
    }
    if (!pending.empty() && !sink.broken) filter.feed(pending, sink);
    filter.finish(sink);

    const int64_t t_end = llama_time_us();
    const double gen_ms = static_cast<double>(t_end - t_prefilled) / 1000.0;
    const double tok_s = gen_ms > 0.0 ? static_cast<double>(generated) * 1000.0 / gen_ms : 0.0;
    const long peak_kb = read_proc_status_kb("VmHWM:");
    LOGI("STATS prompt_tokens=%zu dropped_turns=%zu prefill_ms=%.1f ttft_ms=%.1f generated=%d "
         "gen_ms=%.1f tok_s=%.2f peak_rss_mb=%.1f stopped=%d stop_seq=%d stops=%zu vision=%d "
         "image_tokens=%d template=%s temp=%.2f top_k=%d top_p=%.2f min_p=%.2f repeat_penalty=%.2f "
         "seed=%u mode=%d",
         prompt_tokens, turns_dropped, prefill_ms, ttft_ms, generated, gen_ms, tok_s,
         peak_kb / 1024.0, session->stop.load() ? 1 : 0, filter.matched() ? 1 : 0,
         stop_sequences.size(), image_tokens > 0 ? 1 : 0, image_tokens,
         forced_template.empty() ? "<model>" : forced_template.c_str(), params.temperature,
         params.top_k, params.top_p, params.min_p, params.repeat_penalty, resolve_seed(params.seed),
         params.sampling_mode);
    llama_sampler_free(sampler);
    session->stop.store(false);
}

} // namespace

// Loads a GGUF text model and, when [jmmproj] is a path, its multimodal projector (M9 vision).
// Returns the session handle, 0 on a text-model load failure, -1 when the projector cannot be
// initialised, and -2 when the projector carries no vision capability (e.g. an audio-only mmproj).
extern "C" JNIEXPORT jlong JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeLoadModel(JNIEnv * env, jclass /*clazz*/,
                                                            jstring jpath, jint jcontext_tokens,
                                                            jstring jmmproj) {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
        LOGI("llama_backend_init done, llama.cpp %s", llama_version());
    });

    const std::string path = jstring_to_std(env, jpath);
    LOGI("loading model: %s", path.c_str());
    const int64_t t0 = llama_time_us();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU on the emulator; GPU offload is a later milestone.
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed (%s)", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(jcontext_tokens > 0 ? jcontext_tokens : 2048);
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    const unsigned hw = std::thread::hardware_concurrency();
    cparams.n_threads = static_cast<int32_t>(hw > 4 ? 4 : (hw > 0 ? hw : 2));
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    // M9: optional multimodal projector. It must be initialised against the already loaded text
    // model, and vision support is a property of the projector file, so it is verified here rather
    // than guessed from the model name.
    mtmd_context * mctx = nullptr;
    const std::string mmproj_path = jstring_to_std(env, jmmproj);
    if (!mmproj_path.empty()) {
        const int64_t t_mmproj = llama_time_us();
        mtmd_context_params vparams = mtmd_context_params_default();
        vparams.use_gpu = false;
        vparams.print_timings = false;
        vparams.n_threads = cparams.n_threads;
        mctx = mtmd_init_from_file(mmproj_path.c_str(), model, vparams);
        if (mctx == nullptr) {
            LOGE("mtmd_init_from_file failed (%s)", mmproj_path.c_str());
            llama_free(ctx);
            llama_model_free(model);
            return -1;
        }
        if (!mtmd_support_vision(mctx)) {
            LOGE("projector has no vision capability (%s)", mmproj_path.c_str());
            mtmd_free(mctx);
            llama_free(ctx);
            llama_model_free(model);
            return -2;
        }
        LOGI("VISION_PROJECTOR_LOADED ms=%.0f marker=%s vision=1 audio=%d",
             static_cast<double>(llama_time_us() - t_mmproj) / 1000.0, mtmd_default_marker(),
             mtmd_support_audio(mctx) ? 1 : 0);
    }

    auto * session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = llama_n_ctx(ctx);
    session->n_batch = llama_n_batch(ctx);
    session->stop.store(false);
    session->mctx = mctx;
    const double load_ms = static_cast<double>(llama_time_us() - t0) / 1000.0;
    LOGI("MODEL_LOADED ms=%.0f n_ctx=%u n_batch=%u n_threads=%d vision=%d", load_ms,
         session->n_ctx, session->n_batch, cparams.n_threads, mctx != nullptr ? 1 : 0);
    return reinterpret_cast<jlong>(session);
}

// GGUF facts as newline-separated key=value lines. Byte arrays avoid modified-UTF-8 limits.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeModelInfo(JNIEnv * env, jclass /*clazz*/, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->model == nullptr) return env->NewByteArray(0);

    llama_model * model = session->model;
    std::string out;
    // Canonical names here are the contract with ModelFacts.parse (Kotlin); GGUF keys are translated.
    const std::string architecture = meta_val(model, "general.architecture");
    if (!architecture.empty()) {
        out += "architecture=";
        out += architecture;
        out += '\n';
    }

    char number[64];
    snprintf(number, sizeof(number), "n_ctx_train=%u\n", llama_model_n_ctx_train(model));
    out += number;
    snprintf(number, sizeof(number), "n_params=%llu\n",
             static_cast<unsigned long long>(llama_model_n_params(model)));
    out += number;
    snprintf(number, sizeof(number), "n_vocab=%d\n", llama_vocab_n_tokens(session->vocab));
    out += number;
    const std::string bos = meta_val(model, "tokenizer.ggml.bos_token_id");
    if (!bos.empty()) {
        out += "bos_token_id=";
        out += bos;
        out += '\n';
    }
    const std::string eos = meta_val(model, "tokenizer.ggml.eos_token_id");
    if (!eos.empty()) {
        out += "eos_token_id=";
        out += eos;
        out += '\n';
    }

    // Every general.sampling.* key the file declares, whatever its set.
    const int32_t count = llama_model_meta_count(model);
    for (int32_t i = 0; i < count; ++i) {
        char key[512];
        if (llama_model_meta_key_by_index(model, i, key, sizeof(key)) < 0) continue;
        if (strncmp(key, "general.sampling.", 17) != 0) continue;
        const std::string value = meta_val(model, key);
        if (value.empty()) continue;
        out += key;
        out += '=';
        out += value;
        out += '\n';
    }

    const char * tmpl = llama_model_chat_template(model, nullptr);
    const bool present = tmpl != nullptr && tmpl[0] != '\0';
    out += present ? "chat_template_present=1\n" : "chat_template_present=0\n";
    if (present) {
        out += "chat_template_first_line=";
        out += first_line(tmpl);
        out += '\n';
    }
    return new_jbytes(env, out);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeChatTemplate(JNIEnv * env, jclass /*clazz*/, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->model == nullptr) return env->NewByteArray(0);
    const char * tmpl = llama_model_chat_template(session->model, nullptr);
    return new_jbytes(env, tmpl == nullptr ? std::string() : std::string(tmpl));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeBuiltinTemplates(JNIEnv * env, jclass /*clazz*/) {
    const char * names[128];
    const int32_t n = llama_chat_builtin_templates(names, sizeof(names) / sizeof(names[0]));
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(n > 0 ? n : 0, string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (array == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    for (int32_t i = 0; i < n; ++i) {
        jstring name = new_jstring(env, names[i]);
        env->SetObjectArrayElement(array, i, name);
        env->DeleteLocalRef(name);
    }
    return array;
}

// Renders the prompt exactly as generation would, for the Settings > Model preview.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeFormatPrompt(
    JNIEnv * env, jclass /*clazz*/, jlong handle, jstring jsystem, jobjectArray jmessages,
    jstring jtemplate) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->model == nullptr || jmessages == nullptr) return env->NewByteArray(0);
    const jsize length = env->GetArrayLength(jmessages);
    if (length <= 0 || length % 2 != 0) return env->NewByteArray(0);

    std::vector<std::pair<std::string, std::string>> messages;
    messages.reserve(static_cast<size_t>(length) / 2);
    for (jsize i = 0; i + 1 < length; i += 2) {
        auto * role = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i));
        auto * content = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i + 1));
        messages.emplace_back(jstring_to_std(env, role), jstring_to_std(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    const std::string system_prompt = jstring_to_std(env, jsystem);
    const std::string forced = jstring_to_std(env, jtemplate);
    std::string formatted;
    if (!format_chat(session->model, system_prompt, messages, forced.c_str(), formatted)) {
        return env->NewByteArray(0);
    }
    return new_jbytes(env, formatted);
}

extern "C" JNIEXPORT void JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeGenerate(
    JNIEnv * env, jclass /*clazz*/, jlong handle, jstring jsystem, jobjectArray jmessages,
    jint jmax_tokens, jfloatArray jfloats, jintArray jints, jstring jstops, jstring jtemplate,
    jobject jsink) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    session->stop.store(false);

    std::vector<std::pair<std::string, std::string>> messages;
    if (!read_messages(env, jmessages, messages)) return;
    const GenerationParams params = read_params(env, jfloats, jints);
    const std::string system_prompt = jstring_to_std(env, jsystem);
    const std::string forced_template = jstring_to_std(env, jtemplate);
    const int32_t max_tokens = jmax_tokens > 0 ? jmax_tokens : 512;
    const std::vector<std::string> stop_sequences = split_stops(jstring_to_std(env, jstops));

    ChatPrompt prompt;
    if (!build_prompt(session->model, session->vocab, system_prompt, messages, max_tokens,
                      session->n_ctx, forced_template.c_str(), prompt)) {
        LOGE("could not build a prompt that fits the context");
        return;
    }

    jmethodID on_token = nullptr;
    if (!resolve_sink(env, jsink, on_token)) return;
    JniSink sink{env, jsink, on_token, false};

    llama_memory_clear(llama_get_memory(session->ctx), true);

    const uint32_t seed = resolve_seed(params.seed);
    llama_sampler * sampler = build_sampler(session->vocab, params, seed);
    if (sampler == nullptr) {
        LOGE("sampler chain could not be created");
        return;
    }

    const int64_t t0 = llama_time_us();
    // A llama_decode call may carry at most n_batch tokens; llama.cpp asserts hard (killing the
    // process) on an oversized batch. Retrieval prompts routinely exceed 512 tokens, so the prefill
    // is split into n_batch-sized chunks. Positions continue automatically inside the memory module.
    const int32_t prefill_chunk = static_cast<int32_t>(session->n_batch > 0 ? session->n_batch : 512);
    LOGI("PREFILL tokens=%zu n_batch=%d chunks=%d", prompt.tokens.size(), prefill_chunk,
         static_cast<int>((prompt.tokens.size() + static_cast<size_t>(prefill_chunk) - 1) /
                          static_cast<size_t>(prefill_chunk)));
    int32_t rc = 0;
    for (size_t offset = 0; offset < prompt.tokens.size(); offset += static_cast<size_t>(prefill_chunk)) {
        const size_t remaining = prompt.tokens.size() - offset;
        const int32_t take = static_cast<int32_t>(std::min(remaining, static_cast<size_t>(prefill_chunk)));
        rc = llama_decode(session->ctx, llama_batch_get_one(prompt.tokens.data() + offset, take));
        if (rc != 0) {
            LOGE("prefill decode failed at offset %zu (tokens=%d): %d", offset, take, rc);
            break;
        }
    }
    if (rc != 0) {
        llama_sampler_free(sampler);
        return;
    }
    const int64_t t_prefilled = llama_time_us();
    run_generation(session, sampler, max_tokens, stop_sequences, sink, t0, t_prefilled,
                   prompt.tokens.size(), prompt.turns_dropped, 0, forced_template, params);
}

// M9: the marker libmtmd replaces with the encoded image chunk inside a formatted prompt.
extern "C" JNIEXPORT jstring JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeVisionMarker(JNIEnv * env, jclass /*clazz*/) {
    return new_jstring(env, mtmd_default_marker());
}

// Projector capabilities without loading the text model: the import screen uses this to warn early
// when a chosen mmproj cannot see images (VIS-2).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeProjectorInfo(JNIEnv * env, jclass /*clazz*/,
                                                                jstring jpath) {
    const std::string path = jstring_to_std(env, jpath);
    if (path.empty()) return env->NewByteArray(0);
    const mtmd_caps caps = mtmd_get_cap_from_file(path.c_str());
    std::string out;
    out += "vision=";
    out += caps.inp_vision ? "1" : "0";
    out += '\n';
    out += "audio=";
    out += caps.inp_audio ? "1" : "0";
    out += '\n';
    out += "audio_gen=";
    out += caps.gen_audio ? "1" : "0";
    out += '\n';
    return new_jbytes(env, out);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeVisionSupported(JNIEnv * /*env*/, jclass /*clazz*/,
                                                                  jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->mctx == nullptr) return JNI_FALSE;
    return mtmd_support_vision(session->mctx) ? JNI_TRUE : JNI_FALSE;
}

// M9: one image plus the whole conversation. The Kotlin engine puts the media marker into the last
// user message; mtmd replaces it with the encoded image chunk, and the helper then prefills text and
// image together (handling M-RoPE / non-causal models such as SmolVLM internally).
extern "C" JNIEXPORT void JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeGenerateVision(
    JNIEnv * env, jclass /*clazz*/, jlong handle, jstring jsystem, jobjectArray jmessages,
    jbyteArray jimage, jint jmax_tokens, jfloatArray jfloats, jintArray jints, jstring jstops,
    jstring jtemplate, jobject jsink) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->ctx == nullptr) return;
    session->stop.store(false);
    if (session->mctx == nullptr) {
        LOGE("vision generation requested without a projector");
        return;
    }
    std::vector<std::pair<std::string, std::string>> messages;
    if (!read_messages(env, jmessages, messages)) return;
    const GenerationParams params = read_params(env, jfloats, jints);
    const std::string system_prompt = jstring_to_std(env, jsystem);
    const std::string forced_template = jstring_to_std(env, jtemplate);
    const int32_t max_tokens = jmax_tokens > 0 ? jmax_tokens : 512;
    const std::vector<std::string> stop_sequences = split_stops(jstring_to_std(env, jstops));

    if (jimage == nullptr) {
        LOGE("vision generation needs an encoded image");
        return;
    }
    const jsize image_size = env->GetArrayLength(jimage);
    if (image_size <= 0) {
        LOGE("vision generation got an empty image");
        return;
    }
    std::vector<unsigned char> image(static_cast<size_t>(image_size));
    env->GetByteArrayRegion(jimage, 0, image_size, reinterpret_cast<jbyte *>(image.data()));

    const mtmd_helper_bitmap_wrapper wrapper = mtmd_helper_bitmap_init_from_buf(
        session->mctx, image.data(), image.size(), false, mtmd_helper_init_opt_default());
    if (wrapper.bitmap == nullptr) {
        LOGE("image could not be decoded (%d bytes)", image_size);
        return;
    }

    // Format + tokenize, dropping the oldest turns until the prompt plus the answer budget fits.
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    std::string formatted;
    size_t turns_dropped = 0;
    size_t prompt_tokens = 0;
    int32_t tokenize_rc = -1;
    while (true) {
        if (!format_chat(session->model, system_prompt, messages, forced_template.c_str(), formatted)) {
            LOGE("vision prompt could not be formatted");
            break;
        }
        const mtmd_input_text text{formatted.c_str(), formatted.size(), true, true};
        const mtmd_bitmap * bitmaps[] = {wrapper.bitmap};
        mtmd_input_chunks_free(chunks);
        chunks = mtmd_input_chunks_init();
        tokenize_rc = mtmd_tokenize(session->mctx, chunks, &text, bitmaps, 1);
        if (tokenize_rc != 0) {
            LOGE("mtmd_tokenize failed: %d", tokenize_rc);
            break;
        }
        prompt_tokens = mtmd_helper_get_n_tokens(chunks);
        if (prompt_tokens + static_cast<size_t>(max_tokens) <= session->n_ctx) break;
        if (messages.size() <= 2) {
            LOGE("vision conversation does not fit context: prompt=%zu max=%d n_ctx=%u", prompt_tokens,
                 max_tokens, session->n_ctx);
            tokenize_rc = -1;
            break;
        }
        messages.erase(messages.begin(), messages.begin() + 2);
        turns_dropped++;
    }
    if (tokenize_rc != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(wrapper.bitmap);
        return;
    }

    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    size_t image_tokens = 0;
    for (size_t i = 0; i < n_chunks; ++i) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, i);
        if (chunk != nullptr && mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
            image_tokens += mtmd_input_chunk_get_n_tokens(chunk);
        }
    }

    jmethodID on_token = nullptr;
    if (!resolve_sink(env, jsink, on_token)) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(wrapper.bitmap);
        return;
    }
    JniSink sink{env, jsink, on_token, false};

    llama_memory_clear(llama_get_memory(session->ctx), true);
    const uint32_t seed = resolve_seed(params.seed);
    llama_sampler * sampler = build_sampler(session->vocab, params, seed);
    if (sampler == nullptr) {
        LOGE("sampler chain could not be created");
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(wrapper.bitmap);
        return;
    }

    const int64_t t0 = llama_time_us();
    llama_pos new_n_past = 0;
    const int32_t rc = mtmd_helper_eval_chunks(session->mctx, session->ctx, chunks, 0, 0,
                                               static_cast<int32_t>(session->n_batch), true,
                                               &new_n_past);
    if (rc != 0) {
        LOGE("vision prefill failed: %d", rc);
        llama_sampler_free(sampler);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(wrapper.bitmap);
        return;
    }
    const int64_t t_prefilled = llama_time_us();
    LOGI("VISION_PREFILL chunks=%zu prompt_tokens=%zu image_tokens=%zu dropped_turns=%zu n_past=%d",
         n_chunks, prompt_tokens, image_tokens, turns_dropped, static_cast<int>(new_n_past));
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(wrapper.bitmap);
    run_generation(session, sampler, max_tokens, stop_sequences, sink, t0, t_prefilled,
                   prompt_tokens, turns_dropped, static_cast<int>(image_tokens), forced_template,
                   params);
}

extern "C" JNIEXPORT void JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeRequestStop(JNIEnv * /*env*/, jclass /*clazz*/,
                                                              jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session != nullptr) session->stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_app_sirrak_engine_llamacpp_LlamaNative_nativeUnloadModel(JNIEnv * /*env*/, jclass /*clazz*/,
                                                              jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->mctx) mtmd_free(session->mctx);
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
    LOGI("model unloaded");
}
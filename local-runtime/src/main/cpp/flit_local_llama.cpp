#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "chat.h"
#include "llama.h"

namespace {

std::mutex runtime_mutex;
llama_model * model = nullptr;
llama_context * context = nullptr;
llama_sampler * sampler = nullptr;
const llama_vocab * vocab = nullptr;
bool backend_initialized = false;
bool cancelled = false;

void release_model() {
    if (sampler != nullptr) {
        llama_sampler_free(sampler);
        sampler = nullptr;
    }
    if (context != nullptr) {
        llama_free(context);
        context = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
    vocab = nullptr;
    cancelled = false;
}

void throw_state(JNIEnv * env, const std::string & message) {
    const jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message.c_str());
}

std::vector<llama_token> tokenize(const std::string & prompt) {
    const int count = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (count <= 0) return {};
    std::vector<llama_token> result(count);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), result.data(), result.size(), true, true) < 0) {
        return {};
    }
    return result;
}

std::string read_java_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_rikkahub_data_localai_LlamaCppRuntimeBridge_nativeInitialize(
    JNIEnv *, jobject
) {
    std::lock_guard<std::mutex> guard(runtime_mutex);
    if (!backend_initialized) {
        llama_backend_init();
        backend_initialized = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_rikkahub_data_localai_LlamaCppRuntimeBridge_nativeLoad(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jint context_size,
    jint threads,
    jfloat temperature,
    jfloat top_p
) {
    std::lock_guard<std::mutex> guard(runtime_mutex);
    release_model();
    if (model_path == nullptr) {
        throw_state(env, "A model path is required");
        return;
    }

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    llama_model_params model_params = llama_model_default_params();
    model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);
    if (model == nullptr) {
        throw_state(env, "Unable to load this GGUF model");
        return;
    }

    vocab = llama_model_get_vocab(model);
    llama_context_params context_params = llama_context_default_params();
    const uint32_t trained_context = llama_model_n_ctx_train(model);
    if (context_size > 0) {
        context_params.n_ctx = static_cast<uint32_t>(context_size);
    } else if (trained_context > 0) {
        // The GGUF metadata is the source of truth. A fixed 4K window silently made models
        // advertised with a larger context lose most of their usable input and output space.
        context_params.n_ctx = trained_context;
    } else {
        release_model();
        throw_state(env, "This GGUF model does not declare a context length");
        return;
    }
    context_params.n_batch = std::min(context_params.n_ctx, 512u);
    context_params.n_ubatch = context_params.n_batch;
    context_params.n_threads = std::max(1, static_cast<int>(threads));
    context_params.n_threads_batch = context_params.n_threads;
    context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        release_model();
        throw_state(env, "Unable to allocate memory for this model");
        return;
    }

    sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(
        std::clamp(static_cast<float>(top_p), 0.01f, 1.0f), 1));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_localai_LlamaCppRuntimeBridge_nativeStart(
    JNIEnv * env,
    jobject,
    jstring prompt
) {
    std::lock_guard<std::mutex> guard(runtime_mutex);
    if (model == nullptr || context == nullptr || sampler == nullptr || vocab == nullptr || prompt == nullptr) {
        throw_state(env, "Load a model before generating text");
        return 0;
    }
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    std::vector<llama_token> tokens = tokenize(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    if (tokens.empty() || tokens.size() >= llama_n_ctx(context)) {
        throw_state(env, "The prompt is empty or exceeds this model's context length");
        return 0;
    }
    llama_memory_clear(llama_get_memory(context), false);
    llama_sampler_reset(sampler);
    cancelled = false;
    const size_t batch_size = std::max<size_t>(1, llama_n_batch(context));
    for (size_t offset = 0; offset < tokens.size(); offset += batch_size) {
        const int32_t count = static_cast<int32_t>(std::min(batch_size, tokens.size() - offset));
        const llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(context, batch) != 0) {
            throw_state(env, "The model could not process this prompt");
            return 0;
        }
    }
    // Keep one position free for the next sampled token. The Kotlin layer uses this value as
    // the automatic output limit when the user has not set one.
    return static_cast<jint>(llama_n_ctx(context) - tokens.size() - 1);
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_rerere_rikkahub_data_localai_LlamaCppRuntimeBridge_nativeFormatPrompt(
    JNIEnv * env,
    jobject,
    jobjectArray roles,
    jobjectArray contents,
    jboolean disable_thinking
) {
    std::lock_guard<std::mutex> guard(runtime_mutex);
    if (model == nullptr || roles == nullptr || contents == nullptr) {
        throw_state(env, "Load a model before formatting a chat prompt");
        return nullptr;
    }
    const jsize message_count = env->GetArrayLength(roles);
    if (message_count <= 0 || env->GetArrayLength(contents) != message_count) {
        throw_state(env, "The local chat messages are invalid");
        return nullptr;
    }

    std::vector<std::string> role_strings;
    std::vector<std::string> content_strings;
    std::vector<llama_chat_message> messages;
    role_strings.reserve(message_count);
    content_strings.reserve(message_count);
    messages.reserve(message_count);
    for (jsize index = 0; index < message_count; ++index) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, index));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, index));
        role_strings.emplace_back(read_java_string(env, role));
        content_strings.emplace_back(read_java_string(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        if (role_strings.back().empty()) {
            throw_state(env, "A local chat message has no role");
            return nullptr;
        }
        messages.push_back({ role_strings.back().c_str(), content_strings.back().c_str() });
    }

    if (llama_model_chat_template(model, /* name */ nullptr) == nullptr) {
        throw_state(env, "This GGUF model has no embedded chat template");
        return nullptr;
    }
    try {
        auto templates = common_chat_templates_init(model, /* chat_template_override */ "");
        common_chat_templates_inputs inputs;
        inputs.messages.reserve(messages.size());
        inputs.add_generation_prompt = true;
        inputs.enable_thinking = !disable_thinking;
        for (size_t index = 0; index < messages.size(); ++index) {
            common_chat_msg message;
            message.role = role_strings[index];
            message.content = content_strings[index];
            inputs.messages.push_back(std::move(message));
        }
        const auto params = common_chat_templates_apply(templates.get(), inputs);
        if (params.prompt.empty()) {
            throw_state(env, "The GGUF chat template produced an empty prompt");
            return nullptr;
        }
        return env->NewStringUTF(params.prompt.c_str());
    } catch (const std::exception & exception) {
        throw_state(env, std::string("Unable to apply this GGUF chat template: ") + exception.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_rerere_rikkahub_data_localai_LlamaCppRuntimeBridge_nativeNextToken(
    JNIEnv * env,
    jobject
) {
    std::lock_guard<std::mutex> guard(runtime_mutex);
    if (cancelled || context == nullptr || sampler == nullptr || vocab == nullptr) return nullptr;
    llama_token token = llama_sampler_sample(sampler, context, -1);
    if (llama_vocab_is_eog(vocab, token)) return nullptr;

    std::vector<char> piece(4096);
    const int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
    if (size < 0) {
        throw_state(env, "Unable to decode a model token");
        return nullptr;
    }
    llama_sampler_accept(sampler, token);
    const llama_batch batch = llama_batch_get_one(&token, 1);
    if (llama_decode(context, batch) != 0) {
        throw_state(env, "The model stopped unexpectedly");
        return nullptr;
    }
    return env->NewStringUTF(std::string(piece.data(), size).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_rikkahub_data_localai_LlamaCppRuntimeBridge_nativeCancel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(runtime_mutex);
    cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_rikkahub_data_localai_LlamaCppRuntimeBridge_nativeRelease(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(runtime_mutex);
    release_model();
}

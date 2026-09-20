# FLIT local runtimes

FLIT keeps native local-model runtimes outside the Android application package. GGUF continues to use the existing llama.cpp workflow. LiteRT-LM uses the official Google Maven AAR as its source.

## GGUF runtime

The existing GGUF workflow builds `libflit_local_llama.so` for `arm64-v8a` and `x86_64`. Its CI job pins llama.cpp to commit `43f3dda6237a453a587a8f00230d52decfeaa8e5`, adds a `runtime.json` manifest, and writes SHA-256 checksums. Runtime archives are downloaded into the app's `noBackupFilesDir/local-ai/runtime` directory after integrity verification; do not copy them into `app/src/main/jniLibs`.

## LiteRT-LM runtime

The first integration supports text chat on the CPU with `.litertlm` files. In local-model settings, download **LiteRT-LM** runtime support, import a compatible model, then select it in a chat. GGUF models continue using their separate runtime. GPU/NPU selection, image/audio input, and native tool integration are not enabled by this change.

Each request rebuilds the native conversation from the current app history while retaining the loaded engine. This supports editing/regenerating messages and switching chats without mixing histories. Stopping generation cancels native work and waits for completion before releasing the conversation.

LiteRT-LM is pinned to **0.16.1**. The Kotlin API and native JNI library must always come from the same LiteRT-LM release; 0.17.1 requires Kotlin 2.4 and is not compatible with this project. The pinned AAR is:

`https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.16.1/litertlm-android-0.16.1.aar`

The script pins this AAR to SHA-256 `e407719c1a29f2685fcb6aa3feea0b9f7155fe316c66dae053c1b5b2f54cda73`. It checks that digest for both downloaded and locally supplied AAR files.

The app downloads this AAR on demand from Google Maven and extracts the ABI-specific native library into app-private storage. It is not packaged in the APK and does not need to be published to the runtime CDN first. The AAR currently contains `arm64-v8a` and `x86_64`, each with `liblitertlm_jni.so`.

`package-litert-runtime.sh` creates the same small runtime archive shape used for local verification. It discovers every `jni/<abi>/*.so` entry, requires `liblitertlm_jni.so`, extracts the libraries to `lib/`, and writes a `runtime.json` manifest containing the engine, pinned version, ABI, and SHA-256 for every file.

Each archive also carries the AAR's `LICENSE` and `THIRD_PARTY_NOTICE.txt` files.

Use the official AAR and package every ABI it contains:

```bash
bash local-runtime/package-litert-runtime.sh --output dist/litert-runtime
```

To verify or package an AAR that is already available locally, pass `--aar`:

```bash
bash local-runtime/package-litert-runtime.sh \
  --aar .firecrawl/litert-0.16.1/litertlm.aar \
  --abi arm64-v8a \
  --output dist/litert-runtime
```

Use `--list-abis` to inspect the ABIs in an AAR. An explicit `--abi` fails when that ABI is absent, and packaging fails when the required JNI library is missing. The generated files are named `flit-litert-runtime-0.16.1-<abi>.zip` with a matching `.sha256` file.

The GitHub Actions workflow packages the official AAR on a `litert-runtime-v*` tag or by manual dispatch and uploads the archives as workflow artifacts. A tagged run also attaches them to the GitHub release; no CDN upload is performed.

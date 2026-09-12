# FLIT local GGUF runtime

This project builds `libflit_local_llama.so` outside the Android application package.
It intentionally supports only the two published ABIs: `arm64-v8a` and `x86_64`.

The CI workflow pins llama.cpp to commit `43f3dda6237a453a587a8f00230d52decfeaa8e5`, builds a
runtime archive for each ABI, adds a `runtime.json` manifest, and writes SHA-256 checksums.

Do not copy the output into `app/src/main/jniLibs`: runtime archives are downloaded into the app's
`noBackupFilesDir/local-ai/runtime` directory after integrity verification.

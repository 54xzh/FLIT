#!/usr/bin/env bash
set -euo pipefail

# Builds both runtime ABIs locally. Upload happens only when --upload is explicitly passed.
# Usage: bash local-runtime/build-runtime-local.sh <version> [--upload] [--output <directory>]

usage() {
  echo "Usage: bash local-runtime/build-runtime-local.sh <version> [--upload] [--output <directory>]" >&2
}

version=""
upload=false
output_override=""
while (($# > 0)); do
  case "$1" in
    --upload) upload=true ;;
    --output)
      shift
      output_override="${1:?pass an output directory after --output}"
      ;;
    --help|-h) usage; exit 0 ;;
    -*) usage; exit 1 ;;
    *)
      [[ -z "$version" ]] || { usage; exit 1; }
      version="$1"
      ;;
  esac
  shift
done
[[ -n "$version" && "$version" =~ ^[A-Za-z0-9._-]+$ ]] || { usage; exit 1; }

project_root=$(cd "$(dirname "$0")/.." && pwd)
environment_file="$project_root/local-runtime/.env.local"
if [[ -f "$environment_file" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$environment_file"
  set +a
fi
output_directory="${output_override:-$(mktemp -d "/tmp/flit-runtime-${version}.XXXXXX")}" 
llama_directory="${LLAMA_CPP_DIR:-/tmp/flit-llama-cpp-20260912}"
ndk_directory="${ANDROID_NDK_HOME:-$project_root/.android-sdk/ndk/27.0.12077973}"
cmake_binary="${CMAKE_BINARY:-$project_root/.android-sdk/cmake/3.22.1/bin/cmake}"
llama_revision="43f3dda6237a453a587a8f00230d52decfeaa8e5"

[[ -x "$cmake_binary" ]] || cmake_binary="$(command -v cmake)"
[[ -d "$ndk_directory" ]] || { echo "Android NDK not found: $ndk_directory" >&2; exit 1; }
if [[ "$upload" == true ]]; then
  for variable in R2_ENDPOINT R2_BUCKET R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY; do
    value="${!variable:-}"
    [[ -n "$value" && "$value" != *"<"* ]] || {
      echo "Fill $variable in local-runtime/.env.local before using --upload." >&2
      exit 1
    }
  done
  command -v aws >/dev/null || { echo "AWS CLI is required for --upload." >&2; exit 1; }
fi

if [[ ! -f "$llama_directory/CMakeLists.txt" ]]; then
  git clone --recursive https://github.com/ggml-org/llama.cpp.git "$llama_directory"
fi
git -C "$llama_directory" fetch --quiet origin "$llama_revision"
git -C "$llama_directory" checkout --quiet "$llama_revision"

mkdir -p "$output_directory"
for abi in arm64-v8a x86_64; do
  build_directory="/tmp/flit-runtime-build-${version}-${abi}"
  "$cmake_binary" -S "$project_root/local-runtime" -B "$build_directory" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_directory/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static \
    -DLLAMA_CPP_DIR="$llama_directory"
  "$cmake_binary" --build "$build_directory" --parallel
  "$cmake_binary" --install "$build_directory" --prefix "$build_directory/install" --config Release
  bash "$project_root/local-runtime/package-debug-runtime.sh" \
    "$build_directory/install/lib/libflit_local_llama.so" "$version" "$abi" "$output_directory"
done

if [[ "$upload" == true ]]; then
  export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
  export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
  export AWS_DEFAULT_REGION=auto
  aws s3 cp "$project_root/local-runtime/catalog.json" "s3://${R2_BUCKET}/catalog.json" \
    --endpoint-url "$R2_ENDPOINT" --content-type application/json --cache-control no-cache
  for abi in arm64-v8a x86_64; do
    archive="$output_directory/flit-local-runtime-${version}-${abi}.zip"
    aws s3 cp "$archive" "s3://${R2_BUCKET}/${abi}.zip" \
      --endpoint-url "$R2_ENDPOINT" --content-type application/zip --cache-control no-cache
  done
fi

echo "Runtime packages: $output_directory"

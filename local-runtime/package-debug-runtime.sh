#!/usr/bin/env bash
set -euo pipefail

# Usage: bash package-debug-runtime.sh <library> <version> <abi> <output-directory>
library_path="${1:?pass the built libflit_local_llama.so path}"
version="${2:?pass a runtime version, for example 1.0.0}"
abi="${3:?pass arm64-v8a or x86_64}"
output_directory="${4:?pass an empty output directory}"

[[ "$version" =~ ^[A-Za-z0-9._-]+$ ]]
[[ "$abi" == "arm64-v8a" || "$abi" == "x86_64" ]]
[[ -f "$library_path" ]]

stage_directory="$output_directory/runtime-$version-$abi"
archive_path="$output_directory/flit-local-runtime-$version-$abi.zip"
mkdir -p "$output_directory"
mkdir "$stage_directory"
mkdir "$stage_directory/lib"
cp "$library_path" "$stage_directory/lib/libflit_local_llama.so"

library_hash=$(sha256sum "$stage_directory/lib/libflit_local_llama.so" | awk '{print $1}')
printf '{"version":"%s","abi":"%s","engine":"llama.cpp","files":[{"path":"lib/libflit_local_llama.so","sha256":"%s"}]}' \
  "$version" "$abi" "$library_hash" > "$stage_directory/runtime.json"

jar --create --file "$archive_path" -C "$stage_directory" runtime.json -C "$stage_directory" lib
printf 'Created %s\n' "$archive_path"

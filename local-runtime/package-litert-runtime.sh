#!/usr/bin/env bash
set -euo pipefail

# LiteRT-LM 0.16.1 is the version consumed by the app. Keep this value in one
# place: the Kotlin API and the native JNI library in the AAR must come from
# the same release.
readonly LITERT_LM_VERSION="0.16.1"
readonly LITERT_LM_AAR_URL="https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/${LITERT_LM_VERSION}/litertlm-android-${LITERT_LM_VERSION}.aar"
readonly LITERT_LM_AAR_SHA256="e407719c1a29f2685fcb6aa3feea0b9f7155fe316c66dae053c1b5b2f54cda73"

usage() {
  cat >&2 <<'USAGE'
Usage: bash package-litert-runtime.sh [options]

Extract the native libraries from the LiteRT-LM Android AAR and create one
runtime archive per ABI. With no --aar argument, the official Google Maven AAR
for LiteRT-LM 0.16.1 is downloaded.

Options:
  --aar <file>       Use a local litertlm-android-0.16.1.aar instead of downloading it
  --abi <abi>        Package only this ABI (otherwise package every ABI in the AAR)
  --output <dir>     Write archives to this directory (default: dist/litert-runtime)
  --list-abis        Print ABIs found in the AAR and exit
  -h, --help         Show this help
USAGE
}

aar_path=""
abi_filter=""
output_directory="dist/litert-runtime"
list_abis=false

while (($# > 0)); do
  case "$1" in
    --aar)
      shift
      aar_path="${1:?pass a local AAR path after --aar}"
      ;;
    --abi)
      shift
      abi_filter="${1:?pass an ABI after --abi}"
      ;;
    --output)
      shift
      output_directory="${1:?pass an output directory after --output}"
      ;;
    --list-abis)
      list_abis=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage
      exit 1
      ;;
  esac
  shift
done

if [[ -n "$abi_filter" && ! "$abi_filter" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Invalid ABI: $abi_filter" >&2
  exit 1
fi

command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
command -v jar >/dev/null || { echo "jar (from a JDK) is required" >&2; exit 1; }

mkdir -p "$output_directory"
output_directory="$(cd "$output_directory" && pwd)"

if [[ -n "$aar_path" ]]; then
  [[ -f "$aar_path" ]] || { echo "AAR not found: $aar_path" >&2; exit 1; }
else
  command -v curl >/dev/null || { echo "curl is required when --aar is not provided" >&2; exit 1; }
  # The temporary directory is intentionally left for the caller to inspect;
  # CI runners discard it after the job and this script never removes user data.
  download_directory="$(mktemp -d "${TMPDIR:-/tmp}/flit-litertlm-${LITERT_LM_VERSION}.XXXXXX")"
  aar_path="$download_directory/litertlm-android-${LITERT_LM_VERSION}.aar"
  echo "Downloading LiteRT-LM ${LITERT_LM_VERSION} AAR from Google Maven"
  curl --fail --location --retry 3 --connect-timeout 20 --output "$aar_path" "$LITERT_LM_AAR_URL"
fi

aar_sha256="$(sha256sum "$aar_path" | awk '{print $1}')"
if [[ "$aar_sha256" != "$LITERT_LM_AAR_SHA256" ]]; then
  echo "LiteRT-LM ${LITERT_LM_VERSION} AAR SHA-256 mismatch" >&2
  echo "Expected: $LITERT_LM_AAR_SHA256" >&2
  echo "Actual:   $aar_sha256" >&2
  exit 1
fi

unzip -t "$aar_path" >/dev/null
aar_entries="$(unzip -Z1 "$aar_path")"

for notice in LICENSE THIRD_PARTY_NOTICE.txt; do
  if ! printf '%s\n' "$aar_entries" | grep -Fxq "$notice"; then
    echo "The LiteRT-LM AAR is missing $notice" >&2
    exit 1
  fi
done

mapfile -t discovered_abis < <(
  printf '%s\n' "$aar_entries" |
    awk -F/ '$1 == "jni" && NF == 3 && $3 ~ /^[^/]+\.so$/ { print $2 }' |
    sort -u
)

if ((${#discovered_abis[@]} == 0)); then
  echo "The AAR contains no jni/<abi>/*.so files: $aar_path" >&2
  exit 1
fi

if [[ "$list_abis" == true ]]; then
  printf '%s\n' "${discovered_abis[@]}"
  exit 0
fi

abis=("${discovered_abis[@]}")
if [[ -n "$abi_filter" ]]; then
  if ! printf '%s\n' "${discovered_abis[@]}" | grep -Fxq "$abi_filter"; then
    echo "ABI '$abi_filter' is not present in the AAR; available ABIs: ${discovered_abis[*]}" >&2
    exit 1
  fi
  abis=("$abi_filter")
fi

for abi in "${abis[@]}"; do
  mapfile -t native_entries < <(
    printf '%s\n' "$aar_entries" |
      awk -F/ -v expected_abi="$abi" \
        '$1 == "jni" && $2 == expected_abi && NF == 3 && $3 ~ /^[^/]+\.so$/ { print }' |
      sort
  )

  if ((${#native_entries[@]} == 0)); then
    echo "No native libraries found for ABI '$abi'" >&2
    exit 1
  fi

  has_jni=false
  for entry in "${native_entries[@]}"; do
    library_name="${entry##*/}"
    if [[ "$library_name" == "liblitertlm_jni.so" ]]; then
      has_jni=true
    fi
    if [[ ! "$library_name" =~ ^[A-Za-z0-9._-]+\.so$ ]]; then
      echo "Unexpected native library name in AAR: $entry" >&2
      exit 1
    fi
  done
  if [[ "$has_jni" != true ]]; then
    echo "ABI '$abi' does not contain the required liblitertlm_jni.so" >&2
    exit 1
  fi

  stage_directory="$(mktemp -d "${TMPDIR:-/tmp}/flit-litertlm-stage-${LITERT_LM_VERSION}-${abi}.XXXXXX")"
  mkdir -p "$stage_directory/lib"
  for notice in LICENSE THIRD_PARTY_NOTICE.txt; do
    unzip -p "$aar_path" "$notice" > "$stage_directory/$notice"
    [[ -s "$stage_directory/$notice" ]] || { echo "AAR notice is empty: $notice" >&2; exit 1; }
  done
  manifest_files=""
  for entry in "${native_entries[@]}"; do
    library_name="${entry##*/}"
    destination="$stage_directory/lib/$library_name"
    unzip -p "$aar_path" "$entry" > "$destination"
    [[ -s "$destination" ]] || { echo "Extracted library is empty: $entry" >&2; exit 1; }
    library_sha256="$(sha256sum "$destination" | awk '{print $1}')"
    if [[ -n "$manifest_files" ]]; then
      manifest_files+=",
"
    fi
    manifest_files+="{\"path\":\"lib/$library_name\",\"sha256\":\"$library_sha256\"}"
  done

  printf '{"engine":"litert-lm","version":"%s","abi":"%s","files":[%s]}\n' \
    "$LITERT_LM_VERSION" "$abi" "$manifest_files" > "$stage_directory/runtime.json"

  archive_path="$output_directory/flit-litert-runtime-${LITERT_LM_VERSION}-${abi}.zip"
  jar --create --file "$archive_path" \
    -C "$stage_directory" runtime.json \
    -C "$stage_directory" LICENSE \
    -C "$stage_directory" THIRD_PARTY_NOTICE.txt \
    -C "$stage_directory" lib
  archive_sha256="$(sha256sum "$archive_path" | awk '{print $1}')"
  printf '%s  %s\n' "$archive_sha256" "$(basename "$archive_path")" > "$archive_path.sha256"
  printf 'Created %s\n' "$archive_path"
done

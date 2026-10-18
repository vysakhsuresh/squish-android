#!/usr/bin/env bash
# Puts the libsquishav.so of the newest successful run of the native workflow
# (or of run [id]) into app/src/main/jniLibs/<abi>/, and the test ffmpeg for an
# arm64 phone into [dest]/ffmpeg-arm64 when a dest is given. Needs gh, logged in.
#
#   sh tools/native/fetch.sh [run-id] [dest-for-ffmpeg]
#
# Commit the libraries after: a Gradle build reads them from the tree and
# never needs the workflow.
set -euo pipefail
cd "$(dirname "$0")/../.."
RUN=${1:-$(gh run list --workflow native.yml --status success --limit 1 --json databaseId -q '.[0].databaseId')}
TMP=$(mktemp -d)
gh run download "$RUN" -D "$TMP"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "$TMP/squishav-$abi/libsquishav.so" "app/src/main/jniLibs/$abi/"
done
if [ -n "${2:-}" ] && [ -f "$TMP/squishav-cli/ffmpeg" ]; then cp "$TMP/squishav-cli/ffmpeg" "$2/ffmpeg-arm64"; fi
ls -l app/src/main/jniLibs/*/libsquishav.so
rm -rf "$TMP"

#!/bin/sh
# Suites that run app code against Media3 itself (desktop only: the jars come
# from the Gradle cache and the SDK, which the sandbox does not have).
#   sh tools/jvm/run_media3.sh
cd "$(dirname "$0")/../.."
G=/c/Users/vysak/.gradle/caches
first() { ls -d $1 2>/dev/null | sort | tail -1; }
LIBS="$(first "$G/8.9/transforms/*/transformed/media3-common-1.11.1/jars/classes.jar")
$(first "$G/modules-2/files-2.1/com.google.guava/guava/*/*/guava-*-android.jar")
$(first "$G/modules-2/files-2.1/androidx.annotation/annotation-jvm/*/*/annotation-jvm-*.jar")
$(first "$G/8.9/transforms/*/transformed/annotation-experimental-*/jars/classes.jar")
$(first "/c/Users/vysak/AppData/Local/Android/Sdk/platforms/*/android.jar")"
EXTRA_CP=$(echo "$LIBS" | while read -r j; do cygpath -m "$j"; done | paste -sd ';')
export EXTRA_CP
SRC=app/src/main/java/com/squish/app
sh tools/jvm/jc.sh VoiceEffectsChecks "$SRC/media/audio/VoiceProcessor.kt" "$SRC/media/audio/VoiceCleaner.kt" \
  "$SRC/timeline/VoiceEffect.kt" tools/jvm/VoiceEffectsChecks.kt 2>&1 | grep -v "^warning" | tail -5

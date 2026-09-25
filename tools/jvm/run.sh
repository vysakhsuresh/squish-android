#!/bin/sh
# Runs the pure-Kotlin checks on the JVM.
#
# There is no Android SDK in the sandbox this project is written in, so anything
# that can be separated from the framework is compiled and *executed* here
# instead of being reasoned about. Between them these cover the two places where
# a quiet arithmetic mistake would be invisible until someone rendered a file:
# the speed curve, and the grade.
#
#   KOTLINC=/path/to/kotlinc/bin/kotlinc tools/jvm/run.sh
set -e
KOTLINC=${KOTLINC:-kotlinc}
SRC=app/src/main/java/com/squish/app
OUT=$(mktemp -d)

run() {
  name=$1; shift
  printf '\n=== %s ===\n' "$name"
  "$KOTLINC" "$@" -include-runtime -d "$OUT/$name.jar" 2>&1 | grep -i '^.*error:' && exit 1
  java -jar "$OUT/$name.jar" | grep -v '^Picked up'
}

run looks      "$SRC/media/effects/Look.kt" tools/jvm/LookChecks.kt
run lookpreview "$SRC/media/effects/Look.kt" "$SRC/media/effects/LookPreview.kt" tools/jvm/LookPreviewChecks.kt
run crop       "$SRC/editor/CropRect.kt" tools/jvm/CropChecks.kt
run frames     "$SRC/media/video/FrameBatch.kt" tools/jvm/stub/Bitmap.kt \
               tools/jvm/stub/MediaMetadataRetriever.kt tools/jvm/FrameBatchChecks.kt
run beat       "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" tools/jvm/BeatChecks.kt
run preview    "$SRC/editor/PreviewBox.kt" tools/jvm/PreviewChecks.kt
run window     "$SRC/timeline/TimelineWindow.kt" tools/jvm/WindowChecks.kt
run span       "$SRC/timeline/TimelineSpan.kt" tools/jvm/SpanChecks.kt
run filmstrip  "$SRC/media/video/Filmstrip.kt" tools/jvm/FilmstripChecks.kt
run undo       "$SRC/editor/UndoStack.kt" tools/jvm/UndoChecks.kt
run ramp       "$SRC/timeline/SpeedRamp.kt" tools/jvm/RampChecks.kt
run slice      "$SRC/timeline/SpeedRamp.kt" tools/jvm/SliceChecks.kt
run timeline   "$SRC/timeline/SpeedRamp.kt" "$SRC/timeline/TimelineModels.kt" \
               "$SRC/timeline/Keyframe.kt" "$SRC/timeline/Mask.kt" "$SRC/timeline/ChromaKey.kt" \
               "$SRC/media/video/ObjectTracker.kt" "$SRC/media/video/MotionEstimator.kt" \
               tools/jvm/stub/Uri.kt tools/jvm/TimelineChecks.kt

rm -rf "$OUT"

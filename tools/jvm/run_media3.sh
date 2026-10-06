#!/bin/sh
# Suites that need a jar on the class path (desktop only: the jars come from the
# Gradle cache and the SDK, and run.sh's kotlinc call has no -classpath at all).
# Most of them run app code against Media3 itself, which is where the name comes
# from; the draft round trip at the end needs org.json and kotlin-reflect.
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
# The three level processors - the fades and the volume curves - run against
# Media3's BaseAudioProcessor on real PCM. AudioRulesChecks executes what a
# fade's level should be; these execute the buffer loops that apply it.
TIMELINE="$SRC/timeline/SpeedRamp.kt $SRC/timeline/TimelineModels.kt $SRC/timeline/VoiceEffect.kt
  $SRC/timeline/TimelineLanes.kt $SRC/timeline/Keyframe.kt $SRC/timeline/ValueTracks.kt $SRC/timeline/Mask.kt $SRC/timeline/ChromaKey.kt
  $SRC/timeline/Background.kt $SRC/timeline/LayerBlend.kt $SRC/editor/TimedEffect.kt $SRC/editor/MotionPreset.kt $SRC/editor/PolishRules.kt
  $SRC/media/effects/ToneCurve.kt $SRC/media/effects/Lut.kt $SRC/media/effects/ColorWheels.kt $SRC/media/effects/SkinTone.kt $SRC/media/effects/Look.kt $SRC/editor/ClipCrop.kt $SRC/editor/CropRect.kt
  tools/jvm/stub/Waveform.kt tools/jvm/stub/EffectSpan.kt
  $SRC/media/video/ObjectTracker.kt $SRC/media/video/MotionEstimator.kt
  $SRC/media/video/TrajectorySmoother.kt $SRC/media/video/StabilizerSolve.kt
  tools/jvm/stub/Uri.kt"
sh tools/jvm/jc.sh ProcessorChecks $TIMELINE "$SRC/editor/AudioRules.kt" "$SRC/media/audio/FadeProcessor.kt" \
  "$SRC/media/audio/GainProcessor.kt" "$SRC/media/audio/GainCurveProcessor.kt" \
  "$SRC/media/audio/VoiceProcessor.kt" "$SRC/media/audio/VoiceCleaner.kt" \
  tools/jvm/ProcessorChecks.kt 2>&1 | grep -v "^warning" | tail -8

# The draft round trip: a clip encoded, written as text, parsed and read back,
# compared field by field. Here rather than in run.sh for two jars - org.json,
# which the codec is written against, and kotlin-reflect, which is how the
# comparison walks Clip's own constructor so it cannot fall behind the model.
JSON=$(first "$G/modules-2/files-2.1/org.json/json/*/*/json-*[0-9].jar")
# 2.0.20 exactly, the version jc.sh drives the compiler and the stdlib at. The
# cache holds fifteen other kotlin-reflects and the newest of them resolves
# nothing against a 2.0.20 stdlib - every reflection call reads as unresolved,
# which looks exactly like a suite that was written wrong.
REFLECT=$(first "$G/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-reflect/2.0.20/*/kotlin-reflect-2.0.20.jar")
EXTRA_CP="$(cygpath -m "$JSON");$(cygpath -m "$REFLECT")" \
  sh tools/jvm/jc.sh DraftRoundTripChecks $TIMELINE "$SRC/data/ProjectRules.kt" \
  "$SRC/data/DraftNumbers.kt" "$SRC/data/DraftClipCodec.kt" \
  tools/jvm/DraftRoundTripChecks.kt 2>&1 | grep -v "^warning" | tail -30

# The same for a line of words, a sticker or a shape: 36 fields through text.
EXTRA_CP="$(cygpath -m "$JSON");$(cygpath -m "$REFLECT")" \
  sh tools/jvm/jc.sh DraftTextRoundTripChecks $TIMELINE \
  "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/editor/TextStyle.kt" \
  "$SRC/editor/Annotation.kt" "$SRC/editor/TextOverlay.kt" \
  "$SRC/data/DraftNumbers.kt" "$SRC/data/DraftTextCodec.kt" \
  tools/jvm/DraftTextRoundTripChecks.kt 2>&1 | grep -v "^warning" | tail -30

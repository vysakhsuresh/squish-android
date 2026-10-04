SRC=app/src/main/java/com/squish/app
# Desktop (Windows, Git Bash) runner for every suite in run.sh, with the Kotlin
# compiler from the Gradle cache: sh tools/jvm/run_desktop.sh. See CLAUDE.md.
S=$(cd "$(dirname "$0")" && pwd)  # jc.sh lives beside this file
run() {
  name=$1; shift
  last=""; for a in "$@"; do last=$a; done
  suite=$(basename "$last" .kt)
  out=$(sh $S/jc.sh "$suite" "$@" 2>&1)
  if echo "$out" | grep -qiE "error:|FAIL|Exception in thread|Could not find or load"; then echo "### $name FAILED"; echo "$out" | grep -iE "error:|FAIL|Exception|Could not" | head -5; else echo "ok  $name: $(echo "$out" | tail -1 | cut -c1-90)"; fi
}
TIMELINE="$SRC/timeline/SpeedRamp.kt $SRC/timeline/TimelineModels.kt $SRC/timeline/VoiceEffect.kt
  $SRC/timeline/TimelineLanes.kt $SRC/timeline/Keyframe.kt $SRC/timeline/ValueTracks.kt $SRC/timeline/Mask.kt $SRC/timeline/ChromaKey.kt
  $SRC/timeline/Background.kt $SRC/timeline/LayerBlend.kt $SRC/editor/TimedEffect.kt
  $SRC/media/effects/ToneCurve.kt $SRC/media/effects/Lut.kt $SRC/media/effects/Look.kt $SRC/editor/ClipCrop.kt $SRC/editor/CropRect.kt
  tools/jvm/stub/Waveform.kt tools/jvm/stub/EffectSpan.kt
  $SRC/media/video/ObjectTracker.kt $SRC/media/video/MotionEstimator.kt
  $SRC/media/video/TrajectorySmoother.kt $SRC/media/video/StabilizerSolve.kt
  tools/jvm/stub/Uri.kt"

run looks      "$SRC/media/effects/ToneCurve.kt" "$SRC/media/effects/Lut.kt" "$SRC/media/effects/Look.kt" tools/jvm/LookChecks.kt
run grade      "$SRC/media/effects/ToneCurve.kt" "$SRC/media/effects/Lut.kt" "$SRC/media/effects/Look.kt" tools/jvm/GradeChecks.kt
run lookpreview "$SRC/media/effects/ToneCurve.kt" "$SRC/media/effects/Lut.kt" "$SRC/media/effects/Look.kt" "$SRC/media/effects/LookPreview.kt" tools/jvm/LookPreviewChecks.kt
run framing    "$SRC/media/ExportPresets.kt" "$SRC/editor/CropRect.kt" tools/jvm/stub/Quality.kt tools/jvm/FramingChecks.kt
run exportsettings "$SRC/media/ExportPresets.kt" "$SRC/media/ExportSettings.kt" tools/jvm/stub/Quality.kt tools/jvm/ExportSettingsChecks.kt
run crop       "$SRC/editor/CropRect.kt" tools/jvm/CropChecks.kt
run clipcrop   "$SRC/editor/CropRect.kt" "$SRC/editor/ClipCrop.kt" tools/jvm/ClipCropChecks.kt
run maskoutline "$SRC/timeline/Mask.kt" "$SRC/media/video/ObjectTracker.kt" "$SRC/media/video/MotionEstimator.kt" \
               "$SRC/editor/MaskOutline.kt" tools/jvm/MaskOutlineChecks.kt
run frames     "$SRC/media/video/FrameBatch.kt" tools/jvm/stub/Bitmap.kt \
               tools/jvm/stub/MediaMetadataRetriever.kt tools/jvm/FrameBatchChecks.kt
run beat       "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" tools/jvm/BeatChecks.kt
run preview    "$SRC/editor/PreviewBox.kt" tools/jvm/PreviewChecks.kt
run previewrules "$SRC/editor/PreviewRules.kt" tools/jvm/PreviewRulesChecks.kt
run probegate  "$SRC/editor/ProbeGate.kt" tools/jvm/ProbeGateChecks.kt
run toolrules  "$SRC/editor/ToolRules.kt" tools/jvm/ToolRulesChecks.kt
run polish     $TIMELINE "$SRC/editor/MotionPreset.kt" "$SRC/editor/PolishRules.kt" tools/jvm/PolishRulesChecks.kt
run window     "$SRC/timeline/TimelineWindow.kt" tools/jvm/WindowChecks.kt
run span       "$SRC/timeline/TimelineSpan.kt" tools/jvm/SpanChecks.kt
run filmstrip  "$SRC/media/video/Filmstrip.kt" tools/jvm/FilmstripChecks.kt
run undo       "$SRC/editor/UndoStack.kt" tools/jvm/UndoChecks.kt
run housekeeping "$SRC/data/DraftHousekeeping.kt" "$SRC/data/DraftFiles.kt" tools/jvm/HousekeepingChecks.kt
run projectrules "$SRC/data/ProjectRules.kt" "$SRC/settings/StorageRules.kt" tools/jvm/ProjectRulesChecks.kt
run trimrules  "$SRC/tools/TrimRules.kt" tools/jvm/TrimRulesChecks.kt
run slowmo     "$SRC/timeline/SpeedRamp.kt" "$SRC/timeline/SlowMotion.kt" tools/jvm/SlowMotionChecks.kt
run ramp       "$SRC/timeline/SpeedRamp.kt" tools/jvm/RampChecks.kt
run slice      "$SRC/timeline/SpeedRamp.kt" tools/jvm/SliceChecks.kt
run timeline   $TIMELINE tools/jvm/TimelineChecks.kt
run magnetic   $TIMELINE tools/jvm/MagneticChecks.kt
run spanremoval $TIMELINE $SRC/timeline/SpanRemoval.kt tools/jvm/SpanRemovalChecks.kt
run lookkeys $TIMELINE tools/jvm/LookKeysChecks.kt
run layerblend $TIMELINE "$SRC/media/ExportPlan.kt" tools/jvm/LayerBlendChecks.kt
run editrules  $TIMELINE "$SRC/editor/EditRules.kt" tools/jvm/EditRulesChecks.kt
run audiorules $TIMELINE "$SRC/editor/AudioRules.kt" tools/jvm/AudioRulesChecks.kt
run exportplan $TIMELINE "$SRC/media/ExportPlan.kt" tools/jvm/ExportPlanChecks.kt
run lanes      $TIMELINE "$SRC/timeline/TimelineWindow.kt" tools/jvm/LaneChecks.kt
run overlay    $TIMELINE "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/media/ExportPlan.kt" tools/jvm/OverlayChecks.kt
run text       $TIMELINE "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/editor/TextStyle.kt" \
               "$SRC/media/audio/SpeechSegmenter.kt" tools/jvm/stub/MonoPcm.kt "$SRC/media/ExportPlan.kt" tools/jvm/TextChecks.kt
run clipops    $TIMELINE "$SRC/media/ExportPlan.kt" tools/jvm/ClipOpsChecks.kt
run animation  $TIMELINE tools/jvm/AnimationChecks.kt
run stabilizer "$SRC/timeline/Keyframe.kt" "$SRC/media/video/MotionEstimator.kt" \
               "$SRC/media/video/TrajectorySmoother.kt" "$SRC/media/video/StabilizerSolve.kt" tools/jvm/StabilizerChecks.kt
run frameblend "$SRC/media/video/FrameBlendPlan.kt" tools/jvm/FrameBlendChecks.kt
run framegrid "$SRC/media/video/FrameGrid.kt" tools/jvm/FrameGridChecks.kt
run chromakey "$SRC/timeline/ChromaKey.kt" tools/jvm/ChromaKeyChecks.kt
run tonecurve "$SRC/media/effects/ToneCurve.kt" tools/jvm/ToneCurveChecks.kt
run transcript "$SRC/editor/Transcript.kt" tools/jvm/TranscriptChecks.kt
run lut "$SRC/media/effects/Lut.kt" tools/jvm/LutChecks.kt
run safearea "$SRC/editor/SafeArea.kt" tools/jvm/SafeAreaChecks.kt
run stillrules $TIMELINE "$SRC/editor/StillRules.kt" tools/jvm/StillRulesChecks.kt
run timecode   $TIMELINE "$SRC/editor/Timecode.kt" tools/jvm/TimecodeChecks.kt
run framerules $TIMELINE "$SRC/editor/PreviewBox.kt" "$SRC/media/ExportPresets.kt" tools/jvm/stub/Quality.kt \
               "$SRC/editor/FrameRules.kt" tools/jvm/FrameRulesChecks.kt

# 30 September: the listening tools, layouts, GIF, auto adjust, translation.
run voicecleaner "$SRC/media/audio/VoiceCleaner.kt" tools/jvm/VoiceCleanerChecks.kt
run loudness   "$SRC/media/audio/Loudness.kt" tools/jvm/LoudnessChecks.kt
run duck       $TIMELINE "$SRC/timeline/DuckRules.kt" tools/jvm/DuckChecks.kt
run silence    $TIMELINE "$SRC/timeline/SilenceRules.kt" tools/jvm/SilenceChecks.kt
run beatfit    $TIMELINE tools/jvm/BeatFitChecks.kt
run beatspread $TIMELINE $SRC/timeline/BeatSpread.kt tools/jvm/BeatSpreadChecks.kt
run liveliness $TIMELINE "$SRC/timeline/Liveliness.kt" tools/jvm/LivelinessChecks.kt
run shapes     "$SRC/editor/Annotation.kt" tools/jvm/ShapeChecks.kt
run soundstickers "$SRC/editor/SoundStickers.kt" "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" "$SRC/media/audio/MusicSynth.kt" tools/jvm/SoundStickerChecks.kt
run templates  tools/jvm/TemplateChecks.kt
run splitscreen $TIMELINE "$SRC/editor/MaskOutline.kt" "$SRC/timeline/SplitScreen.kt" tools/jvm/SplitScreenChecks.kt
run gif        "$SRC/media/gif/GifEncoder.kt" tools/jvm/GifChecks.kt
run autoadjust "$SRC/media/effects/ToneCurve.kt" "$SRC/media/effects/Lut.kt" "$SRC/media/effects/Look.kt" "$SRC/media/effects/AutoAdjust.kt" tools/jvm/AutoAdjustChecks.kt
run translatechunks "$SRC/online/OnlineTranslate.kt" tools/jvm/stub/translate/OnlineStub.kt tools/jvm/stub/translate/ContextStub.kt tools/jvm/stub/translate/JsonStub.kt tools/jvm/TranslateChunkChecks.kt

rm -rf "$OUT"

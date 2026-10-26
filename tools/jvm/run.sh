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

# What a Clip pulls in: since B12 its own look (Look.kt) and crop (ClipCrop.kt,
# CropRect.kt) as well as the timeline's own files.
TIMELINE="$SRC/timeline/SpeedRamp.kt $SRC/timeline/TimelineModels.kt $SRC/timeline/VoiceEffect.kt
  $SRC/timeline/TimelineLanes.kt $SRC/timeline/TimelineWindow.kt $SRC/timeline/Keyframe.kt $SRC/timeline/ValueTracks.kt $SRC/timeline/Mask.kt $SRC/timeline/ChromaKey.kt
  $SRC/timeline/Background.kt $SRC/timeline/LayerBlend.kt $SRC/media/TransitionShapes.kt $SRC/editor/TimedEffect.kt $SRC/editor/MotionPreset.kt $SRC/editor/PolishRules.kt
  $SRC/media/effects/ToneCurve.kt $SRC/media/effects/Lut.kt $SRC/media/effects/ColorWheels.kt $SRC/media/effects/SkinTone.kt $SRC/media/effects/Look.kt $SRC/editor/ClipCrop.kt $SRC/editor/CropRect.kt
  tools/jvm/stub/Waveform.kt tools/jvm/stub/EffectSpan.kt
  $SRC/media/video/ObjectTracker.kt $SRC/media/video/MotionEstimator.kt
  $SRC/media/video/TrajectorySmoother.kt $SRC/media/video/StabilizerSolve.kt
  tools/jvm/stub/Uri.kt"

run looks      "$SRC/media/effects/ToneCurve.kt" "$SRC/media/effects/Lut.kt" "$SRC/media/effects/ColorWheels.kt" "$SRC/media/effects/SkinTone.kt" "$SRC/media/effects/Look.kt" tools/jvm/LookChecks.kt
run grade      "$SRC/media/effects/ToneCurve.kt" "$SRC/media/effects/Lut.kt" "$SRC/media/effects/ColorWheels.kt" "$SRC/media/effects/SkinTone.kt" "$SRC/media/effects/Look.kt" tools/jvm/GradeChecks.kt
run lookpreview "$SRC/media/effects/ToneCurve.kt" "$SRC/media/effects/Lut.kt" "$SRC/media/effects/ColorWheels.kt" "$SRC/media/effects/SkinTone.kt" "$SRC/media/effects/Look.kt" "$SRC/media/effects/LookPreview.kt" tools/jvm/LookPreviewChecks.kt
run framing    "$SRC/media/ExportPresets.kt" "$SRC/editor/CropRect.kt" tools/jvm/stub/Quality.kt tools/jvm/FramingChecks.kt
run exportsettings "$SRC/media/ExportPresets.kt" "$SRC/media/ExportSettings.kt" tools/jvm/stub/Quality.kt tools/jvm/ExportSettingsChecks.kt
run space      "$SRC/media/SpaceCheck.kt" tools/jvm/SpaceCheckChecks.kt
run reverseruns "$SRC/media/ReverseRuns.kt" tools/jvm/ReverseRunChecks.kt
run crop       "$SRC/editor/CropRect.kt" tools/jvm/CropChecks.kt
run clipcrop   "$SRC/editor/CropRect.kt" "$SRC/editor/ClipCrop.kt" tools/jvm/ClipCropChecks.kt
run tracker    "$SRC/media/video/MotionEstimator.kt" "$SRC/media/video/ObjectTracker.kt" tools/jvm/TrackerChecks.kt
run reframerules "$SRC/media/video/ReframeRules.kt" "$SRC/media/video/ObjectTracker.kt" "$SRC/media/video/MotionEstimator.kt" tools/jvm/ReframeRulesChecks.kt
run maskoutline "$SRC/timeline/Mask.kt" "$SRC/timeline/Keyframe.kt" "$SRC/media/video/ObjectTracker.kt" "$SRC/media/video/MotionEstimator.kt" \
               "$SRC/editor/MaskOutline.kt" tools/jvm/MaskOutlineChecks.kt
run frames     "$SRC/media/video/FrameBatch.kt" tools/jvm/stub/Bitmap.kt \
               tools/jvm/stub/MediaMetadataRetriever.kt tools/jvm/stub/AvRetriever.kt tools/jvm/FrameBatchChecks.kt
run beat       "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" tools/jvm/BeatChecks.kt
run preview    "$SRC/editor/PreviewBox.kt" tools/jvm/PreviewChecks.kt
run previewrules "$SRC/editor/PreviewRules.kt" tools/jvm/PreviewRulesChecks.kt
run probegate  "$SRC/editor/ProbeGate.kt" tools/jvm/ProbeGateChecks.kt
run toolrules  "$SRC/editor/ToolRules.kt" tools/jvm/ToolRulesChecks.kt
run polish     $TIMELINE "$SRC/editor/SheetRules.kt" tools/jvm/PolishRulesChecks.kt
run textfit    "$SRC/ui/components/TextFit.kt" tools/jvm/TextFitChecks.kt
run colourname "$SRC/ui/components/ColourName.kt" tools/jvm/ColourNameChecks.kt
run previewspan "$SRC/ui/components/PreviewSpan.kt" tools/jvm/PreviewSpanChecks.kt
run window     "$SRC/timeline/TimelineWindow.kt" tools/jvm/WindowChecks.kt
run span       "$SRC/timeline/TimelineSpan.kt" tools/jvm/SpanChecks.kt
run filmstrip  $TIMELINE "$SRC/media/video/Filmstrip.kt" tools/jvm/FilmstripChecks.kt
run stripdraw  $TIMELINE "$SRC/timeline/StripDraw.kt" tools/jvm/StripDrawChecks.kt
run undo       "$SRC/editor/UndoStack.kt" tools/jvm/UndoChecks.kt
run housekeeping "$SRC/data/DraftHousekeeping.kt" "$SRC/data/DraftFiles.kt" tools/jvm/HousekeepingChecks.kt
run projectrules "$SRC/data/ProjectRules.kt" "$SRC/settings/StorageRules.kt" "$SRC/home/HomeTabRules.kt" tools/jvm/ProjectRulesChecks.kt
run trimrules  "$SRC/tools/TrimRules.kt" tools/jvm/TrimRulesChecks.kt
run slowmo     "$SRC/timeline/SpeedRamp.kt" "$SRC/timeline/SlowMotion.kt" tools/jvm/SlowMotionChecks.kt
run ramp       "$SRC/timeline/SpeedRamp.kt" tools/jvm/RampChecks.kt
run slice      "$SRC/timeline/SpeedRamp.kt" tools/jvm/SliceChecks.kt
run timeline   $TIMELINE tools/jvm/TimelineChecks.kt
run magnetic   $TIMELINE tools/jvm/MagneticChecks.kt
run spanremoval $TIMELINE $SRC/timeline/SpanRemoval.kt tools/jvm/SpanRemovalChecks.kt
run lookkeys $TIMELINE tools/jvm/LookKeysChecks.kt
run valueeasing $TIMELINE tools/jvm/ValueEasingChecks.kt
run layerblend $TIMELINE "$SRC/media/ExportPlan.kt" tools/jvm/LayerBlendChecks.kt
run blendframes $TIMELINE "$SRC/media/video/BlendFrameRules.kt" tools/jvm/BlendFrameChecks.kt
run editrules  $TIMELINE "$SRC/editor/EditRules.kt" tools/jvm/EditRulesChecks.kt
run audiorules $TIMELINE "$SRC/editor/AudioRules.kt" tools/jvm/AudioRulesChecks.kt
run exportplan $TIMELINE "$SRC/media/ExportPlan.kt" tools/jvm/ExportPlanChecks.kt
run tshapes    "$SRC/media/TransitionShapes.kt" tools/jvm/TransitionShapeChecks.kt
run lanes      $TIMELINE tools/jvm/LaneChecks.kt
run overlay    $TIMELINE "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/media/ExportPlan.kt" tools/jvm/OverlayChecks.kt
run text       $TIMELINE "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/editor/TextStyle.kt" \
               "$SRC/media/audio/SpeechSegmenter.kt" tools/jvm/stub/MonoPcm.kt "$SRC/media/ExportPlan.kt" tools/jvm/TextChecks.kt
run clipops    $TIMELINE "$SRC/media/ExportPlan.kt" tools/jvm/ClipOpsChecks.kt
run animation  $TIMELINE tools/jvm/AnimationChecks.kt
run animoptions $TIMELINE "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/editor/TextStyle.kt" \
               "$SRC/media/audio/SpeechSegmenter.kt" tools/jvm/stub/MonoPcm.kt "$SRC/media/ExportPlan.kt" tools/jvm/AnimationOptionsChecks.kt
run stabilizer "$SRC/timeline/Keyframe.kt" "$SRC/media/video/MotionEstimator.kt" \
               "$SRC/media/video/TrajectorySmoother.kt" "$SRC/media/video/StabilizerSolve.kt" tools/jvm/StabilizerChecks.kt
run frameblend "$SRC/media/video/FrameBlendPlan.kt" tools/jvm/FrameBlendChecks.kt
run framegrid "$SRC/media/video/FrameGrid.kt" tools/jvm/FrameGridChecks.kt
# Reads squish_chroma_key_es2.glsl, so it runs from the repo root like the rest.
run chromakey "$SRC/timeline/ChromaKey.kt" tools/jvm/ChromaKeyChecks.kt
run tonecurve "$SRC/media/effects/ToneCurve.kt" tools/jvm/ToneCurveChecks.kt
run transcript "$SRC/editor/Transcript.kt" tools/jvm/TranscriptChecks.kt
run segmenter "$SRC/media/audio/SpeechSegmenter.kt" tools/jvm/stub/MonoPcm.kt tools/jvm/SegmenterChecks.kt
run lut "$SRC/media/effects/Lut.kt" tools/jvm/LutChecks.kt
run safearea "$SRC/editor/SafeArea.kt" tools/jvm/SafeAreaChecks.kt
# Fft and BeatDetector because MusicSynth.beatMap returns a BeatMap: this line
# said MusicSynth.kt alone and had not compiled since, which nobody noticed
# because the suite was in this file and not in run_desktop.sh.
run musicsynth "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" "$SRC/media/audio/MusicSynth.kt" tools/jvm/MusicSynthChecks.kt
run synthtempo "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" "$SRC/media/audio/MusicSynth.kt" tools/jvm/SynthTempoChecks.kt
run originalsvariety "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" "$SRC/media/audio/MusicSynth.kt" tools/jvm/OriginalsVarietyChecks.kt
run effectrecipes $TIMELINE tools/jvm/EffectRecipeChecks.kt
run stillrules $TIMELINE "$SRC/editor/StillRules.kt" tools/jvm/StillRulesChecks.kt
run picsample "$SRC/media/PictureSample.kt" tools/jvm/PictureSampleChecks.kt
run stillprune "$SRC/media/StillPrune.kt" tools/jvm/StillPruneChecks.kt
run timecode   $TIMELINE "$SRC/editor/Timecode.kt" tools/jvm/TimecodeChecks.kt
run framerules $TIMELINE "$SRC/editor/PreviewBox.kt" "$SRC/media/ExportPresets.kt" tools/jvm/stub/Quality.kt \
               "$SRC/editor/FrameRules.kt" tools/jvm/FrameRulesChecks.kt
run canvaspaint $TIMELINE "$SRC/editor/PreviewBox.kt" "$SRC/media/ExportPresets.kt" tools/jvm/stub/Quality.kt \
               "$SRC/editor/FrameRules.kt" "$SRC/editor/CanvasPaint.kt" tools/jvm/CanvasPaintChecks.kt

# 30 September: the listening tools, layouts, GIF, auto adjust, translation.
run voicecleaner "$SRC/media/audio/VoiceCleaner.kt" tools/jvm/VoiceCleanerChecks.kt
run loudness   "$SRC/media/audio/Loudness.kt" tools/jvm/LoudnessChecks.kt
run duck       $TIMELINE "$SRC/timeline/DuckRules.kt" tools/jvm/DuckChecks.kt
run silence    $TIMELINE "$SRC/timeline/SilenceRules.kt" tools/jvm/SilenceChecks.kt
run beatfit    $TIMELINE tools/jvm/BeatFitChecks.kt
run beatspread $TIMELINE $SRC/timeline/BeatSpread.kt tools/jvm/BeatSpreadChecks.kt
run liveliness $TIMELINE "$SRC/timeline/Liveliness.kt" tools/jvm/LivelinessChecks.kt
run maskkeys   $TIMELINE tools/jvm/MaskKeyChecks.kt
run shapes     "$SRC/editor/Annotation.kt" tools/jvm/ShapeChecks.kt
run stickerart "$SRC/editor/Annotation.kt" "$SRC/editor/StickerArt.kt" tools/jvm/StickerArtChecks.kt
run soundstickers "$SRC/editor/SoundStickers.kt" "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" "$SRC/media/audio/MusicSynth.kt" tools/jvm/SoundStickerChecks.kt
run templates  tools/jvm/TemplateChecks.kt
run skin       "$SRC/media/effects/SkinTone.kt" "$SRC/media/effects/SpatialMoves.kt" tools/jvm/SkinChecks.kt
run wheels     "$SRC/media/effects/ColorWheels.kt" tools/jvm/WheelChecks.kt
run cutsounds  $TIMELINE "$SRC/timeline/CutSounds.kt" tools/jvm/CutSoundChecks.kt
run controls   tools/jvm/ControlChecks.kt
run framecompare tools/jvm/FrameCompare.kt
run exportjournal "$SRC/media/ExportJournal.kt" tools/jvm/ExportJournalChecks.kt
run syncanalysis "$SRC/media/SyncAnalysis.kt" tools/jvm/SyncAnalysisChecks.kt
run easing "$SRC/timeline/Keyframe.kt" tools/jvm/EasingChecks.kt
run whispertext "$SRC/media/audio/WhisperText.kt" tools/jvm/WhisperTextChecks.kt
run captionwords $TIMELINE "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/editor/TextStyle.kt" "$SRC/editor/Annotation.kt" "$SRC/editor/StickerArt.kt" "$SRC/editor/TextOverlay.kt" tools/jvm/CaptionWordsChecks.kt
run flowinterp "$SRC/media/video/MotionEstimator.kt" "$SRC/media/video/FlowInterp.kt" tools/jvm/FlowInterpChecks.kt
run noisereduce "$SRC/media/audio/Fft.kt" "$SRC/media/audio/NoiseReducer.kt" tools/jvm/NoiseReduceChecks.kt
run effectkeys $TIMELINE tools/jvm/EffectKeysChecks.kt
run groups $TIMELINE tools/jvm/GroupChecks.kt
run textdepth $TIMELINE "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/editor/TextStyle.kt" "$SRC/editor/Annotation.kt" "$SRC/editor/StickerArt.kt" "$SRC/editor/TextOverlay.kt" tools/jvm/TextDepthChecks.kt
run knockout $TIMELINE "$SRC/editor/EditRules.kt" "$SRC/editor/OverlayRules.kt" "$SRC/editor/TextStyle.kt" "$SRC/editor/Annotation.kt" "$SRC/editor/StickerArt.kt" "$SRC/editor/TextOverlay.kt" tools/jvm/KnockoutChecks.kt
run draftfields tools/jvm/DraftFieldChecks.kt
run runners   tools/jvm/RunnerChecks.kt
run draftkeys tools/jvm/DraftKeyChecks.kt
run privacy   tools/jvm/PrivacyChecks.kt
run searchterms "$SRC/online/SearchTerms.kt" tools/jvm/SearchTermChecks.kt
run envelope  tools/jvm/stub/MonoPcm.kt "$SRC/media/audio/Waveform.kt" tools/jvm/EnvelopeChecks.kt
run srt "$SRC/data/SrtFile.kt" "$SRC/data/PickedText.kt" tools/jvm/SrtChecks.kt
run shaderuniforms tools/jvm/ShaderUniformChecks.kt
run yuvdataspace "$SRC/media/YuvDataSpace.kt" tools/jvm/YuvDataSpaceChecks.kt
run tonemap "$SRC/av/ToneMap.kt" tools/jvm/ToneMapChecks.kt
run av "$SRC/av/AvCodecMap.kt" "$SRC/av/AvRouteRules.kt" tools/jvm/AvChecks.kt
run splitscreen $TIMELINE "$SRC/editor/MaskOutline.kt" "$SRC/timeline/SplitScreen.kt" tools/jvm/SplitScreenChecks.kt
run gif        "$SRC/media/gif/GifEncoder.kt" "$SRC/media/gif/GifSize.kt" tools/jvm/GifChecks.kt
run gifdecode  "$SRC/media/gif/GifEncoder.kt" "$SRC/media/gif/GifDecoder.kt" tools/jvm/GifDecodeChecks.kt
run maskrefine "$SRC/media/video/MaskRefine.kt" tools/jvm/MaskRefineChecks.kt
run cleanup    "$SRC/media/video/CleanUp.kt" tools/jvm/CleanUpChecks.kt
run exporterrors "$SRC/media/ExportErrorKinds.kt" tools/jvm/ExportErrorKindsChecks.kt
run audiofinish "$SRC/media/audio/AudioFinish.kt" tools/jvm/AudioFinishChecks.kt
run voicesplit "$SRC/media/audio/Fft.kt" "$SRC/media/audio/VoiceSplit.kt" tools/jvm/VoiceSplitChecks.kt
run capturerules "$SRC/capture/CaptureRules.kt" tools/jvm/CaptureRulesChecks.kt
run slottemplates $TIMELINE "$SRC/media/audio/Fft.kt" "$SRC/media/audio/BeatDetector.kt" "$SRC/media/audio/MusicSynth.kt" "$SRC/editor/SlotTemplate.kt" tools/jvm/SlotTemplateChecks.kt
run exportfacts "$SRC/export/ExportFacts.kt" tools/jvm/ExportFactsChecks.kt
run proxyrules "$SRC/media/ProxyRules.kt" tools/jvm/ProxyRulesChecks.kt
run decoderbudget "$SRC/media/DecoderBudget.kt" tools/jvm/DecoderBudgetChecks.kt
run ttsrules "$SRC/media/audio/TtsRules.kt" tools/jvm/VoiceFixture_ml.kt tools/jvm/VoiceFixture_hi.kt tools/jvm/VoiceFixture_en.kt tools/jvm/TtsRulesChecks.kt
run wer tools/jvm/Wer.kt
run autoadjust "$SRC/media/effects/ToneCurve.kt" "$SRC/media/effects/Lut.kt" "$SRC/media/effects/ColorWheels.kt" "$SRC/media/effects/SkinTone.kt" "$SRC/media/effects/Look.kt" "$SRC/media/effects/AutoAdjust.kt" tools/jvm/AutoAdjustChecks.kt
run translatechunks "$SRC/online/OnlineTranslate.kt" tools/jvm/stub/translate/OnlineStub.kt tools/jvm/stub/translate/ContextStub.kt tools/jvm/stub/translate/JsonStub.kt tools/jvm/TranslateChunkChecks.kt

rm -rf "$OUT"

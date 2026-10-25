package com.squish.app.media.video

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * MediaPipe's face and object detectors for auto-reframe (EDGE_PLAN F8), so a
 * crop follows people, pets and cars and not only a face the platform's old
 * detector happens to see. Both models are bundled - BlazeFace short-range
 * (230 KB) and EfficientDet-Lite0 (4.6 MB, the 80 COCO kinds) - and run on
 * the phone; nothing is downloaded and no frame leaves it.
 *
 * Licences, as found on 25 October: BlazeFace's model card is reported
 * Apache-2.0 (a mirror quoting Google's card - the card itself did not open
 * here); EfficientDet-Lite0 is from TensorFlow's model garden, Apache-2.0.
 *
 * Each opener answers null where the model will not load, and Reframer then
 * falls back to what it had: the platform's faces, the cut-out's person and
 * motion.
 */
object Detectors {
    private const val FACE_MODEL = "blaze_face_short_range.tflite"
    private const val THING_MODEL = "efficientdet_lite0.tflite"

    fun faces(context: Context): FaceDetector? = runCatching {
        FaceDetector.createFromOptions(
            context,
            FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(ReframeRules.MIN_SCORE)
                .build()
        )
    }.onFailure { android.util.Log.w("SquishReframe", "no face model", it) }.getOrNull()

    fun things(context: Context): ObjectDetector? = runCatching {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(THING_MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(6)
                .setScoreThreshold(ReframeRules.MIN_SCORE)
                .build()
        )
    }.onFailure { android.util.Log.w("SquishReframe", "no object model", it) }.getOrNull()

    /** The faces in [image], [w] x [h] pixels, as fractions of the frame. */
    fun seenFaces(d: FaceDetector, image: MPImage, w: Int, h: Int): List<ReframeRules.Seen> =
        runCatching { d.detect(image).detections() }.getOrNull().orEmpty().map { det ->
            val b = det.boundingBox()
            // Aimed a little below the box's middle: the face, not the forehead.
            ReframeRules.Seen(b.centerX() / w, (b.centerY() + b.height() * 0.1f) / h, b.width() / w, b.height() / h,
                det.categories().firstOrNull()?.score() ?: 0f, "face")
        }

    /** The things in [image], [w] x [h] pixels, as fractions of the frame, each with its COCO name. */
    fun seenThings(d: ObjectDetector, image: MPImage, w: Int, h: Int): List<ReframeRules.Seen> =
        runCatching { d.detect(image).detections() }.getOrNull().orEmpty().mapNotNull { det ->
            val c = det.categories().firstOrNull() ?: return@mapNotNull null
            val b = det.boundingBox()
            ReframeRules.Seen(b.centerX() / w, b.centerY() / h, b.width() / w, b.height() / h, c.score(), c.categoryName())
        }
}

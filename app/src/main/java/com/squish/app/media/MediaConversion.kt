package com.squish.app.media

/**
 * Where builds before 18 October kept the converted copy of a file only the
 * software decoders took - an H.264 and AAC copy made on import, because the
 * app's frame readers could not read the original then. Nothing converts any
 * more (EditorViewModel.prepareMedia says why), but drafts made then still
 * name those copies, so the folder is kept and the storage card clears what
 * no draft names, as it does every folder of made files.
 */
object MediaConversion {
    const val DIR = "converted"
}

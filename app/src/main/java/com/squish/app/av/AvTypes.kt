package com.squish.app.av

/**
 * The MIME types the app's file pickers offer. Every video and every image type alone
 * hid a good part of what the app now reads: a provider types an MXF, a
 * RealMedia file or an Ogg video as application/..., and anything it does
 * not recognise - an EXR, a camera RAW from an unusual maker, an MKV on some
 * file managers - as application/octet-stream. A file nothing here reads
 * still says so when it is opened, as it always did.
 */
object AvTypes {

    private val CONTAINERS = arrayOf(
        "application/octet-stream",
        "application/ogg",
        "application/mxf",
        "application/vnd.rn-realmedia",
        "application/vnd.rn-realmedia-vbr",
        "application/x-matroska",
        "application/x-mpegurl"
    )

    /** For footage and photos. */
    val PICTURES_AND_FOOTAGE: Array<String> = arrayOf("video/*", "image/*") + CONTAINERS

    /** For a sound, which may come from any video file too. */
    val SOUNDS: Array<String> = arrayOf("audio/*", "video/*") + CONTAINERS
}

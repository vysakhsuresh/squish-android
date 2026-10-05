package com.squish.app.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.InAppMp4Muxer

/**
 * Four hundred kilobytes off every file Media3 writes here.
 *
 * Media3's own MP4 muxer - the one `Transformer` uses by default, through
 * `DefaultMuxer`, which is a pure delegate over it - reserves
 * `DEFAULT_MOOV_BOX_SIZE_BYTES = 400_000` bytes of free space after the `ftyp`
 * box so the `moov` can be written at the front (`Mp4Writer.writeHeader`). When
 * the moov turns out smaller, and it always is, the remainder is left behind as
 * a `free` box and never trimmed (`maybeWriteMoovAtStart`).
 *
 * A three-second export measured 579,440 bytes on the phone, of which
 * `Mp4Probe` found 128,791 of picture and 50,597 of sound: 69% of the file was
 * padding, and the export sheet had promised 114 KB. The same 400 KB sat in
 * every rendered still and every proxy copy, and "Fit to 16 MB" was solving
 * against a budget with 400 KB in it that it could never spend.
 *
 * Streamable output is what buys that reserve - a moov at the front, so a
 * server can start playing a file before it has all of it. Nothing here serves
 * files: they go to the gallery, into a share, or into the app's own storage,
 * and Android's `MediaMuxer` writes its moov at the end too, so every recording
 * already on the phone is that shape. With it off the moov goes after the mdat
 * and the writer truncates the file to what it actually used.
 */
@OptIn(UnstableApi::class)
fun compactMuxerFactory(): InAppMp4Muxer.Factory =
    InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(false)

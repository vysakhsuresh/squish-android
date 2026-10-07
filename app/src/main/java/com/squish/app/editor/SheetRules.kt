package com.squish.app.editor

/**
 * How tall the text sheet is while the keyboard is up, and how tall it has to
 * be once the keyboard has folded but its room is being held - kept free of
 * Compose so it is executed on the JVM (tools/jvm/PolishRulesChecks.kt).
 *
 * The point of both is one number: the sheet's **top edge**, which must not
 * move when a tab folds the keyboard. The tabs sit at the top of the sheet, so
 * a top edge that moves is a tab row that jumps under the finger - and it has
 * jumped twice now.
 *
 * The second time it jumped is why [heldSheetHeight] takes the **room the
 * keyboard left** rather than the keyboard's own height. `imePadding()` applies
 * only what the navigation bar does not already cover, so the raw ime inset is
 * a navigation bar too big; holding that number made the sheet half a
 * navigation bar taller than the one it replaced and the tab row rose by 59 px
 * on a moto g84 (1017 with the keyboard up, 958 after folding it, read off the
 * accessibility tree). Measured as "what was there while typing", the
 * arithmetic cannot be wrong about an inset it never looks at.
 */
object SheetRules {

    /** Room for the sheet's heading and a line or two of field, however short the screen. */
    const val TYPING_SHEET_MIN_DP = 160f

    /** On a tall screen, past this the sheet is only taking room from the picture. */
    const val SHEET_MAX_DP = 460f

    /**
     * The sheet's height with the keyboard up: half of what the keyboard
     * leaves, so the picture keeps the other half and the words can be watched
     * landing. [availableDp] is the height the keyboard leaves.
     */
    fun typingSheetHeight(availableDp: Float): Float {
        val floor = minOf(TYPING_SHEET_MIN_DP, availableDp * 0.8f)
        return (availableDp * 0.5f).coerceIn(floor, maxOf(floor, SHEET_MAX_DP))
    }

    /**
     * The sheet's height once the keyboard has folded and its room is being
     * held: what it had over the keyboard, plus everything the keyboard just
     * gave back, so the top edge lands where it was.
     *
     * [typingRoomDp] is the height there was while typing - what
     * [typingSheetHeight] was measured against - and [availableDp] is the
     * height there is now.
     */
    fun heldSheetHeight(availableDp: Float, typingRoomDp: Float): Float =
        typingSheetHeight(typingRoomDp) + (availableDp - typingRoomDp).coerceAtLeast(0f)
}

/**
 * When the line Add text just made is taken back off again - executed on the
 * JVM with [SheetRules], since what it turns on is a race rather than a
 * number and the device is the only place it shows.
 *
 * Add text sets the editor's "this is the new line" handle and opens the sheet
 * from a tap handler, while the line itself and its selection come back
 * through the view model's flow a composition later. In between, the sheet is
 * open on a state that has neither - and a rule phrased only as "the sheet is
 * no longer on this line" says *take it off* at that moment. It then finds no
 * such line, takes nothing off, and the handle is spent: Done has nothing left
 * to discard with and the project keeps a caption reading "Your text" for
 * good. That happened once in five tries on 8 October.
 */
object NewLineRules {

    /**
     * Whether the sheet has let go of the new line and it should now be
     * discarded if it still says nothing.
     *
     * [lineExists] is the whole of the fix: nothing can be let go of before it
     * is there.
     */
    fun lettingGo(
        lineExists: Boolean,
        draftStillLoading: Boolean,
        editSheetOpen: Boolean,
        selectionIsTheLine: Boolean
    ): Boolean = lineExists && !draftStillLoading && !(editSheetOpen && selectionIsTheLine)
}

package com.xyzterm.terminal.virtualkeys

import android.widget.Button

/** The class that maintains a state of a [SpecialButton]. */
class SpecialButtonState(private val mVirtualKeysView: VirtualKeysView) {
    /** If special button has been created for the [VirtualKeysView]. */
    @JvmField var isCreated = false

    /** If special button is active. */
    @JvmField var isActive = false

    /** If special button is locked due to long hold and should not be deactivated when read. */
    @JvmField var isLocked = false

    @JvmField var buttons: MutableList<Button> = ArrayList()

    /** Set [isCreated]. */
    fun setIsCreated(value: Boolean) {
        isCreated = value
    }

    /** Set [isActive]. */
    fun setIsActive(value: Boolean) {
        isActive = value
        for (button in buttons) {
            button.setTextColor(
                if (value) mVirtualKeysView.getButtonActiveTextColor()
                else mVirtualKeysView.buttonTextColor,
            )
            // ponytail: also flip the background so an armed modifier (Ctrl/Alt/Fn) reads
            // as a filled key, not just a faint text-color shift — the original walk only
            // set text, which was easy to miss against onSurfaceColor.
            button.setBackgroundColor(
                if (value) mVirtualKeysView.getButtonActiveBackgroundColor()
                else mVirtualKeysView.getButtonBackgroundColor(),
            )
        }
    }

    /** Set [isLocked]. */
    fun setIsLocked(value: Boolean) {
        isLocked = value
    }
}

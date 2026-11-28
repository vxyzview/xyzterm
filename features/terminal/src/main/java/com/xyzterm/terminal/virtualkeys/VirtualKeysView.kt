package com.xyzterm.terminal.virtualkeys

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.PopupWindow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** A [View] showing extra keys (such as Escape, Ctrl, Alt) not normally available on soft keyboards. */
@Suppress("DEPRECATION") // setBackgroundColor + Handler() legacy APIs used for virtual key colors
class VirtualKeysView(context: Context, attrs: AttributeSet?) : GridLayout(context, attrs) {

    /** The client receiving virtual key callbacks. */
    var virtualKeysViewClient: IVirtualKeysView? = null

    private var mSpecialButtons: Map<SpecialButton, SpecialButtonState>? = null
    private var mSpecialButtonsKeys: Set<String>? = null
    private var mRepetitiveKeys: List<String>? = null

    var buttonTextColor: Int = DEFAULT_BUTTON_TEXT_COLOR
    private var mButtonActiveTextColor: Int = DEFAULT_BUTTON_ACTIVE_TEXT_COLOR
    private var mButtonBackgroundColor: Int = DEFAULT_BUTTON_BACKGROUND_COLOR
    private var mButtonActiveBackgroundColor: Int = DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR
    private var mButtonTextAllCaps = true
    private var mLongPressTimeout = FALLBACK_LONG_PRESS_DURATION
    private var mLongPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY

    private var mPopupWindow: PopupWindow? = null
    private var mScheduledExecutor: ScheduledExecutorService? = null
    private var mHandler: Handler? = null
    private var mSpecialButtonsLongHoldRunnable: SpecialButtonsLongHoldRunnable? = null
    private var mLongPressCount = 0

    init {
        setRepetitiveKeys(VirtualKeysConstants.PRIMARY_REPETITIVE_KEYS)
        setSpecialButtons(getDefaultSpecialButtons(this))
        setButtonColors(
            DEFAULT_BUTTON_TEXT_COLOR,
            DEFAULT_BUTTON_ACTIVE_TEXT_COLOR,
            DEFAULT_BUTTON_BACKGROUND_COLOR,
            DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR,
        )
        setLongPressTimeout(ViewConfiguration.getLongPressTimeout())
        setLongPressRepeatDelay(DEFAULT_LONG_PRESS_REPEAT_DELAY)
    }

    /** Set the button colors. */
    fun setButtonColors(
        buttonTextColor: Int,
        buttonActiveTextColor: Int,
        buttonBackgroundColor: Int,
        buttonActiveBackgroundColor: Int,
    ) {
        this.buttonTextColor = buttonTextColor
        mButtonActiveTextColor = buttonActiveTextColor
        mButtonBackgroundColor = buttonBackgroundColor
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor
    }

    /** Get the default map that can be used for [mSpecialButtons]. */
    fun getDefaultSpecialButtons(extraKeysView: VirtualKeysView): Map<SpecialButton, SpecialButtonState> =
        HashMap<SpecialButton, SpecialButtonState>().apply {
            put(SpecialButton.CTRL, SpecialButtonState(extraKeysView))
            put(SpecialButton.ALT, SpecialButtonState(extraKeysView))
            put(SpecialButton.SHIFT, SpecialButtonState(extraKeysView))
            put(SpecialButton.FN, SpecialButtonState(extraKeysView))
        }

    /** Get [mRepetitiveKeys]. */
    fun getRepetitiveKeys(): List<String>? = mRepetitiveKeys?.toList()

    /** Set [mRepetitiveKeys]. Must not be null. */
    fun setRepetitiveKeys(repetitiveKeys: List<String>) {
        mRepetitiveKeys = repetitiveKeys
    }

    /** Get [mSpecialButtons]. */
    fun getSpecialButtons(): Map<SpecialButton, SpecialButtonState>? = mSpecialButtons?.toMap()

    /** Set [mSpecialButtons]. Must not be null. */
    fun setSpecialButtons(specialButtons: Map<SpecialButton, SpecialButtonState>) {
        mSpecialButtons = specialButtons
        mSpecialButtonsKeys = specialButtons.keys.map { it.key }.toSet()
    }

    /** Get [mSpecialButtonsKeys]. */
    fun getSpecialButtonsKeys(): Set<String>? = mSpecialButtonsKeys?.toSet()

    fun getButtonActiveTextColor(): Int = mButtonActiveTextColor

    fun setButtonActiveTextColor(buttonActiveTextColor: Int) {
        mButtonActiveTextColor = buttonActiveTextColor
    }

    fun getButtonBackgroundColor(): Int = mButtonBackgroundColor

    fun setButtonBackgroundColor(buttonBackgroundColor: Int) {
        mButtonBackgroundColor = buttonBackgroundColor
    }

    fun getButtonActiveBackgroundColor(): Int = mButtonActiveBackgroundColor

    fun setButtonActiveBackgroundColor(buttonActiveBackgroundColor: Int) {
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor
    }

    fun setButtonTextAllCaps(buttonTextAllCaps: Boolean) {
        mButtonTextAllCaps = buttonTextAllCaps
    }

    fun getLongPressTimeout(): Int = mLongPressTimeout

    fun setLongPressTimeout(longPressDuration: Int) {
        mLongPressTimeout =
            if (longPressDuration >= MIN_LONG_PRESS_DURATION && longPressDuration <= MAX_LONG_PRESS_DURATION) {
                longPressDuration
            } else {
                FALLBACK_LONG_PRESS_DURATION
            }
    }

    fun getLongPressRepeatDelay(): Int = mLongPressRepeatDelay

    fun setLongPressRepeatDelay(longPressRepeatDelay: Int) {
        // Kept as-is: the bounds check reads the current field, not the argument.
        mLongPressRepeatDelay =
            if (mLongPressRepeatDelay >= MIN_LONG_PRESS__REPEAT_DELAY &&
                mLongPressRepeatDelay <= MAX_LONG_PRESS__REPEAT_DELAY
            ) {
                longPressRepeatDelay
            } else {
                DEFAULT_LONG_PRESS_REPEAT_DELAY
            }
    }

    /** Reload this view with the info passed in [extraKeysInfo]. */
    @SuppressLint("ClickableViewAccessibility")
    fun reload(extraKeysInfo: VirtualKeysInfo?) {
        if (extraKeysInfo == null) return

        for (state in mSpecialButtons!!.values) state.buttons = ArrayList()
        removeAllViews()

        val buttons = extraKeysInfo.getMatrix()
        setRowCount(buttons.size)
        setColumnCount(maximumLength(buttons))

        for (row in buttons.indices) {
            for (col in buttons[row].indices) {
                val buttonInfo = buttons[row][col]
                val button: Button =
                    if (isSpecialButton(buttonInfo)) {
                        createSpecialButton(buttonInfo.key, true) ?: return
                    } else {
                        Button(context, null, android.R.attr.buttonBarButtonStyle)
                    }

                button.setText(buttonInfo.display)
                button.setTextColor(buttonTextColor)
                button.setAllCaps(mButtonTextAllCaps)
                button.setPadding(0, 0, 0, 0)

                button.setOnClickListener { view ->
                    performVirtualKeyButtonHapticFeedback(view, buttonInfo, button)
                    onAnyVirtualKeyButtonClick(view, buttonInfo, button)
                }

                button.setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            view.setBackgroundColor(mButtonActiveBackgroundColor)
                            startScheduledExecutors(view, buttonInfo, button)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val popup = buttonInfo.popup
                            if (popup != null) {
                                if (mPopupWindow == null && event.getY() < 0) {
                                    stopScheduledExecutors()
                                    view.setBackgroundColor(mButtonBackgroundColor)
                                    showPopup(view, popup)
                                }
                                if (mPopupWindow != null && event.getY() > 0) {
                                    view.setBackgroundColor(mButtonActiveBackgroundColor)
                                    dismissPopup()
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            view.setBackgroundColor(mButtonBackgroundColor)
                            stopScheduledExecutors()
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            view.setBackgroundColor(mButtonBackgroundColor)
                            stopScheduledExecutors()
                            if (mLongPressCount == 0 || mPopupWindow != null) {
                                if (mPopupWindow != null) {
                                    dismissPopup()
                                    buttonInfo.popup?.let { onAnyVirtualKeyButtonClick(view, it, button) }
                                } else {
                                    view.performClick()
                                }
                            }
                            true
                        }
                        else -> true
                    }
                }

                val param = GridLayout.LayoutParams()
                param.width = 0
                param.height = 0
                param.setMargins(0, 0, 0, 0)
                param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1f)
                param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1f)
                button.layoutParams = param
                addView(button)
            }
        }
    }

    private fun performVirtualKeyButtonHapticFeedback(view: View, buttonInfo: VirtualKeyButton, button: Button) {
        if (virtualKeysViewClient?.performVirtualKeyButtonHapticFeedback(view, buttonInfo, button) == true) return

        if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {
            if (Build.VERSION.SDK_INT >= 28) {
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } else if (Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 2) {
                // Only perform haptic feedback if total silence is not enabled.
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    private fun onAnyVirtualKeyButtonClick(view: View, buttonInfo: VirtualKeyButton, button: Button) {
        if (isSpecialButton(buttonInfo)) {
            if (mLongPressCount > 0) return
            val specialButton = SpecialButton.valueOf(buttonInfo.key) ?: return
            val state = mSpecialButtons!![specialButton] ?: return
            state.setIsActive(!state.isActive)
            if (!state.isActive) state.setIsLocked(false)
        } else {
            onVirtualKeyButtonClick(view, buttonInfo, button)
        }
    }

    private fun onVirtualKeyButtonClick(view: View, buttonInfo: VirtualKeyButton, button: Button) {
        virtualKeysViewClient?.onVirtualKeyButtonClick(view, buttonInfo, button)
    }

    private fun startScheduledExecutors(view: View, buttonInfo: VirtualKeyButton, button: Button) {
        stopScheduledExecutors()
        mLongPressCount = 0
        if (mRepetitiveKeys!!.contains(buttonInfo.key)) {
            // Auto repeat while long pressed until ACTION_UP calls stopScheduledExecutors.
            mScheduledExecutor = Executors.newSingleThreadScheduledExecutor()
            mScheduledExecutor!!.scheduleWithFixedDelay(
                {
                    mLongPressCount++
                    onVirtualKeyButtonClick(view, buttonInfo, button)
                },
                mLongPressTimeout.toLong(),
                mLongPressRepeatDelay.toLong(),
                TimeUnit.MILLISECONDS,
            )
        } else if (isSpecialButton(buttonInfo)) {
            val specialButton = SpecialButton.valueOf(buttonInfo.key) ?: return
            val state = mSpecialButtons!![specialButton] ?: return
            if (mHandler == null) mHandler = Handler(Looper.getMainLooper())
            mSpecialButtonsLongHoldRunnable = SpecialButtonsLongHoldRunnable(state)
            mHandler!!.postDelayed(mSpecialButtonsLongHoldRunnable!!, mLongPressTimeout.toLong())
        }
    }

    private fun stopScheduledExecutors() {
        if (mScheduledExecutor != null) {
            mScheduledExecutor!!.shutdownNow()
            mScheduledExecutor = null
        }
        if (mSpecialButtonsLongHoldRunnable != null && mHandler != null) {
            mHandler!!.removeCallbacks(mSpecialButtonsLongHoldRunnable!!)
            mSpecialButtonsLongHoldRunnable = null
        }
    }

    fun showPopup(view: View, extraButton: VirtualKeyButton) {
        val width = view.measuredWidth
        val height = view.measuredHeight
        val button: Button =
            if (isSpecialButton(extraButton)) {
                createSpecialButton(extraButton.key, false) ?: return
            } else {
                Button(context, null, android.R.attr.buttonBarButtonStyle).apply {
                    setTextColor(buttonTextColor)
                }
            }
        button.setText(extraButton.display)
        button.setAllCaps(mButtonTextAllCaps)
        button.setPadding(0, 0, 0, 0)
        button.setMinHeight(0)
        button.setMinWidth(0)
        button.setMinimumWidth(0)
        button.setMinimumHeight(0)
        button.setWidth(width)
        button.setHeight(height)
        button.setBackgroundColor(mButtonActiveBackgroundColor)
        mPopupWindow = PopupWindow(this)
        mPopupWindow!!.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT)
        mPopupWindow!!.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
        mPopupWindow!!.setContentView(button)
        mPopupWindow!!.setOutsideTouchable(true)
        mPopupWindow!!.setFocusable(false)
        mPopupWindow!!.showAsDropDown(view, 0, -2 * height, Gravity.NO_GRAVITY)
    }

    private fun dismissPopup() {
        mPopupWindow!!.setContentView(null)
        mPopupWindow!!.dismiss()
        mPopupWindow = null
    }

    /** Check whether a [VirtualKeyButton] is a [SpecialButton]. */
    fun isSpecialButton(button: VirtualKeyButton): Boolean = mSpecialButtonsKeys!!.contains(button.key)

    private fun createSpecialButton(buttonKey: String, needUpdate: Boolean): Button? {
        val specialButton = SpecialButton.valueOf(buttonKey) ?: return null
        val state = mSpecialButtons!![specialButton] ?: return null
        state.setIsCreated(true)
        val button = Button(context, null, android.R.attr.buttonBarButtonStyle)
        button.setTextColor(if (state.isActive) mButtonActiveTextColor else buttonTextColor)
        button.setBackgroundColor(mButtonBackgroundColor)
        if (needUpdate) state.buttons.add(button)
        return button
    }

    /**
     * Read whether a [SpecialButton] is active.
     *
     * @return null if the button does not exist; otherwise true if created and active.
     */
    fun readSpecialButton(specialButton: SpecialButton, autoSetInActive: Boolean): Boolean? {
        val state = mSpecialButtons!![specialButton] ?: return null
        if (!state.isCreated || !state.isActive) return false
        if (autoSetInActive && !state.isLocked) state.setIsActive(false)
        return true
    }

    private inner class SpecialButtonsLongHoldRunnable(private val state: SpecialButtonState) : Runnable {
        override fun run() {
            state.setIsLocked(!state.isActive)
            state.setIsActive(!state.isActive)
            mLongPressCount++
        }
    }

    companion object {
        const val DEFAULT_BUTTON_TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_BUTTON_ACTIVE_TEXT_COLOR = 0xFFf44336.toInt()
        const val DEFAULT_BUTTON_BACKGROUND_COLOR = 0x00000000.toInt()
        const val DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR = 0xFF7F7F7F.toInt()
        const val MIN_LONG_PRESS_DURATION = 200
        const val MAX_LONG_PRESS_DURATION = 3000
        const val FALLBACK_LONG_PRESS_DURATION = 400
        const val MIN_LONG_PRESS__REPEAT_DELAY = 5
        const val MAX_LONG_PRESS__REPEAT_DELAY = 2000
        const val DEFAULT_LONG_PRESS_REPEAT_DELAY = 80

        @JvmStatic
        fun <T> maximumLength(matrix: Array<Array<T>>): Int {
            var m = 0
            for (row in matrix) m = Math.max(m, row.size)
            return m
        }
    }

    /** The client for the [VirtualKeysView]. */
    interface IVirtualKeysView {
        fun onVirtualKeyButtonClick(view: View?, buttonInfo: VirtualKeyButton?, button: Button?)

        fun performVirtualKeyButtonHapticFeedback(
            view: View?,
            buttonInfo: VirtualKeyButton?,
            button: Button?,
        ): Boolean
    }
}

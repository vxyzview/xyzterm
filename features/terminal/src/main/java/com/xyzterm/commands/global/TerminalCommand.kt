package com.xyzterm.commands.global

import android.content.Intent
import android.view.KeyEvent
import com.xyzterm.activities.terminal.Terminal
import com.xyzterm.commands.ActionContext
import com.xyzterm.commands.GlobalCommand
import com.xyzterm.commands.KeyCombination
import com.xyzterm.feature.FeatureRegistry
import com.xyzterm.icons.Icon
import com.xyzterm.resources.drawables
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings

object TerminalCommand : GlobalCommand() {
    override val id: String = "global.terminal"

    override fun getLabel(): String = strings.terminal.getString()

    override fun action(context: ActionContext) {
        val activity = context.currentActivity
        val intent = Intent(activity, Terminal::class.java)
        activity.startActivity(intent)
    }

    override fun isSupported(): Boolean = FeatureRegistry.isEnabled("feature_terminal")

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.terminal)

    override val defaultKeybinds: KeyCombination = KeyCombination(keyCode = KeyEvent.KEYCODE_J, ctrl = true)
}

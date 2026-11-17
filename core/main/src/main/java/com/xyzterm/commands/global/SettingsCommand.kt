package com.xyzterm.commands.global

import android.content.Intent
import android.view.KeyEvent
import com.xyzterm.activities.settings.SettingsActivity
import com.xyzterm.commands.ActionContext
import com.xyzterm.commands.GlobalCommand
import com.xyzterm.commands.KeyCombination
import com.xyzterm.icons.Icon
import com.xyzterm.resources.drawables
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings

class SettingsCommand : GlobalCommand() {
    override val id: String = "global.settings"

    override fun getLabel(): String = strings.settings.getString()

    override fun action(context: ActionContext) {
        val activity = context.currentActivity
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.settings)

    override val defaultKeybinds: KeyCombination = KeyCombination(keyCode = KeyEvent.KEYCODE_COMMA, ctrl = true)
}

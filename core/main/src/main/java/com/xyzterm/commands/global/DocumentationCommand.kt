package com.xyzterm.commands.global

import android.view.KeyEvent
import com.xyzterm.commands.ActionContext
import com.xyzterm.commands.GlobalCommand
import com.xyzterm.commands.KeyCombination
import com.xyzterm.icons.Icon
import com.xyzterm.icons.Menu_book
import com.xyzterm.icons.XyzIcons
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.utils.openUrl

class DocumentationCommand : GlobalCommand() {
    override val id: String = "global.documentation"

    override fun getLabel(): String = strings.docs.getString()

    override fun getIcon(): Icon = Icon.VectorIcon(XyzIcons.Menu_book)

    override fun action(context: ActionContext) {
        val url = "https://github.com/vxyzview/xyzterm#readme"
        context.currentActivity.openUrl(url)
    }

    override val defaultKeybinds: KeyCombination = KeyCombination(keyCode = KeyEvent.KEYCODE_F1)
}

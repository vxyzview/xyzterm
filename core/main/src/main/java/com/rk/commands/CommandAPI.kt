package com.rk.commands

import android.app.Activity
import com.rk.icons.Icon

open class ActionContext(open val currentActivity: Activity)

abstract class Command {
    abstract val id: String
    open val prefix: String? = null

    abstract fun getLabel(): String

    abstract fun getIcon(): Icon

    abstract fun action(context: ActionContext)

    open fun isEnabled(): Boolean = true

    open fun isSupported(): Boolean = true

    open val preferText: Boolean = false

    open val childCommands: List<Command> = emptyList()

    open fun getChildSearchPlaceholder(): String? = null

    open val sectionId: Int = 0
    open val defaultKeybinds: KeyCombination? = null

    open val repeatOnHold: Boolean = false

    open fun onLongClick(context: ActionContext): Boolean = false

    /** Executes this command's action. */
    fun performCommand(context: ActionContext) {
        action(context)
    }

    fun copy(
        id: String = this.id,
        prefix: String? = this.prefix,
        label: () -> String = { this.getLabel() },
        action: (ActionContext) -> Unit = { ctx -> this.action(ctx) },
        isEnabled: () -> Boolean = { this.isEnabled() },
        isSupported: () -> Boolean = { this.isSupported() },
        icon: () -> Icon = { this.getIcon() },
        preferText: Boolean = this.preferText,
        childCommands: List<Command> = this.childCommands,
        childSearchPlaceholder: () -> String? = { this.getChildSearchPlaceholder() },
        sectionId: Int = this.sectionId,
        defaultKeybinds: KeyCombination? = this.defaultKeybinds,
        repeatOnHold: Boolean = this.repeatOnHold,
        onLongClick: (ActionContext) -> Boolean = { ctx -> this.onLongClick(ctx) },
    ): Command {
        return object : Command() {
            override val id: String = id

            override val prefix: String? = prefix

            override fun getLabel(): String = label()

            override fun action(context: ActionContext) = action(context)

            override fun isEnabled(): Boolean = isEnabled()

            override fun isSupported(): Boolean = isSupported()

            override fun getIcon(): Icon = icon()

            override val preferText: Boolean = preferText

            override val childCommands: List<Command> = childCommands

            override fun getChildSearchPlaceholder(): String? = childSearchPlaceholder()

            override val sectionId: Int = sectionId

            override val defaultKeybinds: KeyCombination? = defaultKeybinds

            override val repeatOnHold: Boolean = repeatOnHold

            override fun onLongClick(context: ActionContext): Boolean = onLongClick(context)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Command
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

abstract class GlobalCommand : Command()

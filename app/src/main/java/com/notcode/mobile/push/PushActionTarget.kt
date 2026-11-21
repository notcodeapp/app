package com.notcode.mobile.push

/** Maps the `action` field of an FCM push message to in-app navigation. */
object PushActionTarget {
    const val OPEN_APP = "open_app"
    const val OPEN_UPDATES = "open_updates"
    const val OPEN_PROJECTS = "open_projects"
    const val OPEN_SETTINGS = "open_settings"
    const val OPEN_URL = "open_url"

    const val TAB_EDITOR = 0
    const val TAB_SETTINGS = 3

    /** Tab that must be selected, or null to keep the current one. */
    fun tabFor(action: String): Int? = when (action) {
        OPEN_UPDATES, OPEN_SETTINGS -> TAB_SETTINGS
        OPEN_PROJECTS -> TAB_EDITOR
        else -> null
    }

    fun opensProjectsDrawer(action: String): Boolean = action == OPEN_PROJECTS

    fun scrollsToUpdates(action: String): Boolean = action == OPEN_UPDATES

    /** Actions handled entirely by the system or by just bringing the app forward. */
    fun isPassthrough(action: String): Boolean =
        action.isBlank() || action == OPEN_APP || action == OPEN_URL
}

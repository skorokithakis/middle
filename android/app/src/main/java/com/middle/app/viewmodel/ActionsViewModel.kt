package com.middle.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.middle.app.data.Action
import com.middle.app.data.ActionType
import com.middle.app.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class ActionsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)

    private val _actions = MutableStateFlow(settings.actions)
    val actions: StateFlow<List<Action>> = _actions

    /**
     * Reloads the list from storage. A settings import writes actions directly,
     * so the cached flow would otherwise show the pre-import list.
     */
    fun refresh() {
        _actions.value = settings.actions
    }

    /** Appends a new alarm with the default pattern and webhook suppression. */
    fun addAction() {
        val action = Action(
            id = UUID.randomUUID().toString(),
            enabled = true,
            type = ActionType.ALARM,
            pattern = Action.DEFAULT_ALARM_PATTERN,
            suppressWebhook = true,
        )
        persist(settings.actions + action)
    }

    /** Replaces the action with the same id. An unknown id leaves the list alone. */
    fun updateAction(action: Action) {
        persist(settings.actions.map { if (it.id == action.id) action else it })
    }

    fun deleteAction(id: String) {
        persist(settings.actions.filterNot { it.id == id })
    }

    private fun persist(actions: List<Action>) {
        settings.actions = actions
        _actions.value = actions
    }
}

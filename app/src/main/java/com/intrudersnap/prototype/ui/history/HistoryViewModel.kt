package com.intrudersnap.prototype.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val events = EventRepository.get(app)
    private val settings = SettingsRepository.get(app)

    val history: StateFlow<List<SecurityEvent>> = events.events

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { events.reload() }
    }

    /** Clears the history AND the counters / test results so a new test run starts clean. */
    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            events.clear()
            settings.resetTestState()
        }
    }
}

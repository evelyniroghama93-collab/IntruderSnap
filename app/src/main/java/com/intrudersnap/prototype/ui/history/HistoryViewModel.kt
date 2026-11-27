package com.intrudersnap.prototype.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.auth.OwnerSession
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.SecurityEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val events = EventRepository.get(app)

    val history: StateFlow<List<SecurityEvent>> = events.events

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { events.reload() }
    }

    /**
     * Deletes all records. Requires an authentication within the last minute
     * ([OwnerSession.freshProof]); returns false if that proof is missing.
     */
    fun clearAll(): Boolean {
        val proof = OwnerSession.freshProof() ?: return false
        viewModelScope.launch(Dispatchers.IO) { events.clearAll(proof) }
        return true
    }
}

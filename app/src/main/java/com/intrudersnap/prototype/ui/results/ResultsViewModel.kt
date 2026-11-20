package com.intrudersnap.prototype.ui.results

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.RuntimeEnvironment
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.device.DeviceInfoProvider
import com.intrudersnap.prototype.domain.AcceptanceEvaluator
import com.intrudersnap.prototype.domain.AcceptanceItem
import com.intrudersnap.prototype.domain.AcceptanceResult
import com.intrudersnap.prototype.domain.ReportBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultsViewModel(app: Application) : AndroidViewModel(app) {

    private val events = EventRepository.get(app)
    private val settings = SettingsRepository.get(app)

    val results: StateFlow<List<AcceptanceResult>> =
        combine(events.events, settings.settings) { ev, st ->
            AcceptanceEvaluator.evaluate(ev, st, RuntimeEnvironment.processSessionId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            events.reload()
            settings.reload()
        }
    }

    fun setMarkedFailed(item: AcceptanceItem, failed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settings.setManualFailure(item.name, failed) }
    }

    suspend fun buildReport(): String = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val ev = events.events.value
        val st = settings.current
        ReportBuilder.build(
            device = DeviceInfoProvider.get(),
            adminActive = AdminController.isAdminActive(ctx),
            watchLoginGranted = AdminController.isWatchLoginGranted(ctx),
            settings = st,
            acceptance = AcceptanceEvaluator.evaluate(ev, st, RuntimeEnvironment.processSessionId),
            events = ev,
        )
    }
}

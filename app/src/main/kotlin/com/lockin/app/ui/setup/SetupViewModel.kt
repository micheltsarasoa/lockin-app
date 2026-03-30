package com.lockin.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lockin.admin.DeviceOwnerManager
import com.lockin.admin.PolicyEnforcer
import com.lockin.security.PinManager
import com.lockin.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val deviceOwnerManager: DeviceOwnerManager,
    private val policyEnforcer: PolicyEnforcer,
    private val pinManager: PinManager,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    sealed class SetupState {
        object WaitingForDeviceOwner : SetupState()
        object DeviceOwnerReady : SetupState()
        object PinSetupRequired : SetupState()
        data class Error(val message: String) : SetupState()
    }

    private val _state = MutableStateFlow<SetupState>(SetupState.WaitingForDeviceOwner)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private var pollJob: kotlinx.coroutines.Job? = null

    /**
     * Starts polling every 2 seconds to detect when Device Owner is provisioned.
     * Called when the setup screen is shown to the parent.
     */
    fun startPollingForDeviceOwner() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                if (deviceOwnerManager.isDeviceOwner()) {
                    _state.value = SetupState.DeviceOwnerReady
                    break
                }
                delay(2_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    /** Sets the master PIN and applies all Device Owner policies. */
    fun completeSetup(pin: String) {
        viewModelScope.launch {
            try {
                pinManager.setPin(pin)
                policyEnforcer.applyAllPolicies()
                syncScheduler.scheduleWeeklySync()
                syncScheduler.triggerImmediateSync()
            } catch (e: IllegalArgumentException) {
                _state.value = SetupState.Error(e.message ?: "Invalid PIN")
            }
        }
    }

    val isDeviceOwner: Boolean get() = deviceOwnerManager.isDeviceOwner()
    val isPinSet: Boolean get() = pinManager.isPinSet()

    /** The ADB command string to display to the parent. */
    fun getAdbCommand(packageName: String): String =
        "adb shell dpm set-device-owner $packageName/.admin.LockInDeviceAdminReceiver"
}

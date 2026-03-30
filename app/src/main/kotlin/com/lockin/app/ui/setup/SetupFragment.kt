package com.lockin.app.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.lockin.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Step 1 of setup: guides the parent through Device Owner provisioning via ADB.
 *
 * Displays the ADB command and polls every 2 seconds until Device Owner is granted.
 * Automatically advances to Step 2 (PIN setup) once detected.
 */
@AndroidEntryPoint
class SetupFragment : Fragment() {

    private val viewModel: SetupViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_setup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adbCommand = viewModel.getAdbCommand(requireContext().packageName)

        view.findViewById<TextView>(R.id.tvAdbCommand).text = adbCommand
        view.findViewById<Button>(R.id.btnCopyCommand).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCommand))
            Toast.makeText(requireContext(), "Command copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is SetupViewModel.SetupState.DeviceOwnerReady -> {
                            // Auto-advance to PIN setup
                            findNavController().navigate(R.id.action_setupFragment_to_pinSetupFragment)
                        }
                        else -> { /* waiting */ }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startPollingForDeviceOwner()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopPolling()
    }
}

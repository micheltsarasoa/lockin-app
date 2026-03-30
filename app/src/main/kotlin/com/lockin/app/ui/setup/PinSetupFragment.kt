package com.lockin.app.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.lockin.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Step 2 of setup: parent creates the master PIN.
 *
 * Validates PIN requirements (length, not sequential, not all same digit)
 * and asks for confirmation before saving.
 */
@AndroidEntryPoint
class PinSetupFragment : Fragment() {

    private val viewModel: SetupViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_pin_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etPin = view.findViewById<EditText>(R.id.etPin)
        val etPinConfirm = view.findViewById<EditText>(R.id.etPinConfirm)
        val btnSave = view.findViewById<Button>(R.id.btnSavePin)

        btnSave.setOnClickListener {
            val pin = etPin.text.toString()
            val confirm = etPinConfirm.text.toString()

            if (pin != confirm) {
                Toast.makeText(requireContext(), "PINs do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    viewModel.completeSetup(pin)
                    findNavController().navigate(R.id.action_pinSetupFragment_to_permissionsFragment)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

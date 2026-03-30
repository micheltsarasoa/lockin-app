package com.lockin.app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lockin.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Main screen shown after setup is complete.
 *
 * Displays:
 *   - Filter status (active/inactive)
 *   - Total blocked domains
 *   - Last sync time
 *   - "Sync Now" button (requires parent PIN)
 */
@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvFilterStatus = view.findViewById<TextView>(R.id.tvFilterStatus)
        val tvTotalDomains = view.findViewById<TextView>(R.id.tvTotalDomains)
        val btnSyncNow = view.findViewById<Button>(R.id.btnSyncNow)

        btnSyncNow.setOnClickListener {
            viewModel.syncNow()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    tvFilterStatus.text = if (state.isFilterReady) {
                        getString(R.string.filter_active)
                    } else {
                        getString(R.string.filter_inactive)
                    }
                    tvTotalDomains.text = state.totalDomains.toString()
                    btnSyncNow.isEnabled = !state.isSyncing
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStats()
    }
}

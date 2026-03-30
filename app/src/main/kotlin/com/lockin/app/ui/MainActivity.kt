package com.lockin.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import com.lockin.admin.DeviceOwnerManager
import com.lockin.app.R
import com.lockin.security.PinManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host using Navigation Component.
 *
 * Navigation graph:
 *   SetupFragment (start if not device owner or PIN not set)
 *   → PinSetupFragment
 *   → PermissionsFragment
 *   → InitialSyncFragment
 *   → DashboardFragment (main screen after setup)
 *
 * UnlockFragment can be shown as a dialog from any screen.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var deviceOwnerManager: DeviceOwnerManager
    @Inject lateinit var pinManager: PinManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        setupActionBarWithNavController(navController)

        // Determine start destination based on setup state
        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        graph.setStartDestination(
            when {
                !deviceOwnerManager.isDeviceOwner() -> R.id.setupFragment
                !pinManager.isPinSet() -> R.id.pinSetupFragment
                else -> R.id.dashboardFragment
            }
        )
        navController.setGraph(graph, null)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHostFragment.navController.navigateUp() || super.onSupportNavigateUp()
    }
}

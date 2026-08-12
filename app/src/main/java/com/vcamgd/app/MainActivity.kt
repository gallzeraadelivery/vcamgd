package com.vcamgd.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.vcamgd.app.databinding.ActivityMainBinding
import com.vcamgd.app.ui.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    private lateinit var vm: MainViewModel
    private var rebootDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val eulaAccepted = runBlocking {
            VCamApp.instance.settings.preferences.first().eulaAccepted
        }
        if (!eulaAccepted) {
            startActivity(Intent(this, EulaActivity::class.java))
            finish()
            return
        }

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)

        vm = ViewModelProvider(this)[MainViewModel::class.java]
        // Motor primario: KingEngine (kingvd + Zygisk soft)
        vm.uiState.observe(this) { state ->
            if (state.needsReboot && !rebootDialogShown) {
                rebootDialogShown = true
                AlertDialog.Builder(this)
                    .setTitle("Reinicie o telefone")
                    .setMessage(
                        "O KingEngine instalou o modulo Zygisk (hooks soft).\n\n" +
                            "Reinicie uma vez para a camera virtual funcionar.",
                    )
                    .setPositiveButton("OK") { _, _ -> vm.clearNeedsReboot() }
                    .setCancelable(false)
                    .show()
            }
        }

        lifecycleScope.launch {
            val prefs = VCamApp.instance.settings.preferences.first()
            com.vcamgd.app.camera.NativeBridge.syncPassthroughUnlessVirtualEnabled(
                prefs.virtualCameraEnabled,
            )
        }
    }
}

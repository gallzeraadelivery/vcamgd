package com.vcamgd.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.vcamgd.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
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

        lifecycleScope.launch {
            val prefs = VCamApp.instance.settings.preferences.first()
            // Se virtual nao esta ligada no app, forca mode=real (recupera camera nativa)
            com.vcamgd.app.camera.NativeBridge.syncPassthroughUnlessVirtualEnabled(
                prefs.virtualCameraEnabled,
            )
        }
    }
}

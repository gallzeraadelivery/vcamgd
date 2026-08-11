package com.vcamgd.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vcamgd.app.databinding.ActivityEulaBinding
import kotlinx.coroutines.launch

class EulaActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityEulaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.checkEula.setOnCheckedChangeListener { _, checked ->
            binding.btnAccept.isEnabled = checked
        }
        binding.btnAccept.setOnClickListener {
            lifecycleScope.launch {
                VCamApp.instance.settings.setEulaAccepted(true)
                startActivity(Intent(this@EulaActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}

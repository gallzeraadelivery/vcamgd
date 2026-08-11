package com.vcamgd.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vcamgd.app.BuildConfig
import com.vcamgd.app.databinding.FragmentStatusBinding

class StatusFragment : Fragment() {
    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnRefresh.setOnClickListener { vm.refresh() }
        vm.uiState.observe(viewLifecycleOwner) { state ->
            binding.rootStatus.text = "Root\n${state.root.detail}"
            binding.moduleStatus.text = "Modulo\n" + if (state.camera.moduleInstalled) {
                "Instalado (Zygisk)"
            } else {
                "Nao encontrado em /data/adb/modules/vcamgd"
            }
            binding.cameraStatus.text = "Camera virtual\n${state.camera.message}"
            binding.activationStatus.text = "Zygisk\n" + state.camera.zygiskEvent.ifBlank {
                if (state.prefs.activated) "Ativado" else "Nao ativado"
            }
            binding.appVersion.text =
                "App\nKingVCam ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

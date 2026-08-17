package com.vcamgd.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vcamgd.app.BuildConfig
import com.vcamgd.app.databinding.FragmentStatusBinding
import com.vcamgd.app.util.KingVCamLog

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
        binding.btnAuditRefresh.setOnClickListener { refreshAuditLog() }
        binding.btnAuditWhy.setOnClickListener {
            vm.auditWhyNoPreview { refreshAuditLog() }
        }
        binding.btnAuditCopy.setOnClickListener {
            val ok = KingVCamLog.copyToClipboard(requireContext())
            Toast.makeText(
                requireContext(),
                if (ok) "Log copiado" else "Falha ao copiar",
                Toast.LENGTH_SHORT,
            ).show()
        }
        binding.btnAuditClear.setOnClickListener {
            KingVCamLog.clear()
            refreshAuditLog()
            Toast.makeText(requireContext(), "Log limpo", Toast.LENGTH_SHORT).show()
        }
        vm.uiState.observe(viewLifecycleOwner) { state ->
            binding.rootStatus.text = "Root\n${state.root.detail}"
            binding.moduleStatus.text = "Motor\n" + if (state.camera.moduleInstalled) {
                "Zygisk hooks (app Camera) API ${android.os.Build.VERSION.SDK_INT}"
            } else {
                "modulo ausente — ative a virtual (1 reboot)"
            }
            binding.cameraStatus.text = "Camera virtual\n${state.camera.message}"
            binding.activationStatus.text = "Evento\n" + state.camera.zygiskEvent.ifBlank {
                if (state.prefs.activated) "Ativado" else "Nao ativado"
            }
            binding.appVersion.text =
                "App\nKingVCam ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            if (state.auditLog.isNotBlank()) {
                binding.auditLog.text = state.auditLog
            }
        }
        refreshAuditLog()
    }

    override fun onResume() {
        super.onResume()
        refreshAuditLog()
    }

    private fun refreshAuditLog() {
        val text = KingVCamLog.dump()
        binding.auditLog.text = text
        vm.setAuditLog(text)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

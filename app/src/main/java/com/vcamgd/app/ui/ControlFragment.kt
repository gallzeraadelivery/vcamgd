package com.vcamgd.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vcamgd.app.R
import com.vcamgd.app.databinding.FragmentControlBinding

class ControlFragment : Fragment() {
    private var _binding: FragmentControlBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            vm.setOverlayEnabled(isChecked)
        }
        binding.btnVirtual.setOnClickListener { vm.switchVirtual() }
        binding.btnReal.setOnClickListener { vm.switchReal() }

        vm.uiState.observe(viewLifecycleOwner) { state ->
            if (binding.switchOverlay.isChecked != state.prefs.overlayEnabled) {
                binding.switchOverlay.setOnCheckedChangeListener(null)
                binding.switchOverlay.isChecked = state.prefs.overlayEnabled
                binding.switchOverlay.setOnCheckedChangeListener { _, isChecked ->
                    vm.setOverlayEnabled(isChecked)
                }
            }
            binding.currentCamera.setText(
                if (state.camera.usingRealCamera) R.string.using_real_camera
                else R.string.using_virtual_camera,
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.vcamgd.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vcamgd.app.data.VideoSourceType
import com.vcamgd.app.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.onLocalVideoSelected(uri)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.sourceGroup.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                binding.sourceNetwork.id -> VideoSourceType.NETWORK_STREAM
                binding.sourceUsb.id -> VideoSourceType.USB_TRANSFER
                else -> VideoSourceType.LOCAL_FILE
            }
            vm.setSourceType(type)
            renderSource(type)
        }

        binding.btnPickVideo.setOnClickListener {
            ensureMediaPermission()
            videoPicker.launch(arrayOf("video/*"))
        }

        binding.networkUrl.doAfterTextChanged {
            vm.setNetworkUrl(it?.toString().orEmpty())
        }

        binding.switchVirtual.setOnCheckedChangeListener { _, isChecked ->
            ensureRuntimePermissions()
            vm.toggleVirtualCamera(isChecked)
        }

        binding.btnActivate.setOnClickListener {
            vm.activate(binding.activationCode.text?.toString().orEmpty())
        }

        vm.uiState.observe(viewLifecycleOwner) { state ->
            val prefs = state.prefs
            when (prefs.sourceType) {
                VideoSourceType.LOCAL_FILE -> binding.sourceLocal.isChecked = true
                VideoSourceType.NETWORK_STREAM -> binding.sourceNetwork.isChecked = true
                VideoSourceType.USB_TRANSFER -> binding.sourceUsb.isChecked = true
            }
            renderSource(prefs.sourceType)
            binding.localVideoLabel.text =
                prefs.localVideoUri?.substringAfterLast('/') ?: getString(com.vcamgd.app.R.string.no_video_selected)
            if (binding.networkUrl.text?.toString() != prefs.networkUrl) {
                binding.networkUrl.setText(prefs.networkUrl)
            }
            if (binding.switchVirtual.isChecked != prefs.virtualCameraEnabled) {
                binding.switchVirtual.setOnCheckedChangeListener(null)
                binding.switchVirtual.isChecked = prefs.virtualCameraEnabled
                binding.switchVirtual.setOnCheckedChangeListener { _, isChecked ->
                    ensureRuntimePermissions()
                    vm.toggleVirtualCamera(isChecked)
                }
            }
            binding.cameraStatus.text = listOfNotNull(
                state.camera.message,
                state.moduleMessage,
            ).joinToString("\n")
            binding.activationStatus.text =
                if (prefs.activated) getString(com.vcamgd.app.R.string.activated)
                else getString(com.vcamgd.app.R.string.not_activated)
            binding.switchVirtual.isEnabled = !state.busy
        }
    }

    private fun renderSource(type: VideoSourceType) {
        binding.btnPickVideo.visibility =
            if (type == VideoSourceType.LOCAL_FILE) View.VISIBLE else View.GONE
        binding.localVideoLabel.visibility =
            if (type == VideoSourceType.LOCAL_FILE) View.VISIBLE else View.GONE
        binding.networkUrlLayout.visibility =
            if (type == VideoSourceType.NETWORK_STREAM) View.VISIBLE else View.GONE
        binding.networkTip.visibility =
            if (type == VideoSourceType.NETWORK_STREAM) View.VISIBLE else View.GONE
        binding.usbHint.visibility =
            if (type == VideoSourceType.USB_TRANSFER) View.VISIBLE else View.GONE
    }

    private fun ensureMediaPermission() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) needed += Manifest.permission.READ_MEDIA_VIDEO
        } else if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun ensureRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

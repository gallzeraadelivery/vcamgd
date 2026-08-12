package com.vcamgd.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vcamgd.app.VCamApp
import com.vcamgd.app.camera.VcplaxEngine
import com.vcamgd.app.camera.VirtualCameraController
import com.vcamgd.app.camera.VirtualCameraStatus
import com.vcamgd.app.data.AppPreferences
import com.vcamgd.app.data.VideoSourceType
import com.vcamgd.app.root.RootChecker
import com.vcamgd.app.root.RootStatus
import com.vcamgd.app.service.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val prefs: AppPreferences = AppPreferences(),
    val root: RootStatus = RootStatus(false, false, false, "Nao verificado"),
    val camera: VirtualCameraStatus = VirtualCameraStatus(),
    val busy: Boolean = false,
    val message: String? = null,
    /** Zygisk legado — vcplax nao precisa reboot */
    val needsReboot: Boolean = false,
    val moduleMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = (application as VCamApp).settings
    private val controller = VirtualCameraController(application)

    private val _uiState = MutableLiveData(MainUiState())
    val uiState: LiveData<MainUiState> = _uiState

    init {
        viewModelScope.launch {
            settings.preferences.collectLatest { prefs ->
                _uiState.postValue(_uiState.value?.copy(prefs = prefs) ?: MainUiState(prefs = prefs))
            }
        }
        viewModelScope.launch {
            controller.status.collectLatest { status ->
                _uiState.postValue(_uiState.value?.copy(camera = status) ?: MainUiState(camera = status))
            }
        }
        refresh()
        // Nao sobe vcplax no init (Magisk grant pode demorar). Sobe ao ativar / Atualizar.
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val root = withContext(Dispatchers.IO) { RootChecker.check() }
                controller.refreshModuleStatus()
                _uiState.postValue(_uiState.value?.copy(root = root, message = null))
            }.onFailure {
                _uiState.postValue(
                    _uiState.value?.copy(message = "Falha ao atualizar: ${it.message}"),
                )
            }
        }
    }

    /** Prepara motor vcplax (APK + root). Sem Magisk ZIP / sem reboot. */
    fun warmVcplax() {
        viewModelScope.launch {
            _uiState.postValue(
                _uiState.value?.copy(busy = true, moduleMessage = "Preparando motor vcplax..."),
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { VcplaxEngine.ensureRunning(getApplication()) }
                    .getOrElse { VcplaxEngine.Result.Failed(it.message ?: "erro") }
            }
            when (result) {
                is VcplaxEngine.Result.Ok -> {
                    _uiState.postValue(
                        _uiState.value?.copy(
                            busy = false,
                            needsReboot = false,
                            moduleMessage = "Motor vcplax pronto (APK + root, sem modulo)",
                        ),
                    )
                    runCatching { controller.refreshModuleStatus() }
                }
                is VcplaxEngine.Result.Failed -> {
                    _uiState.postValue(
                        _uiState.value?.copy(
                            busy = false,
                            needsReboot = false,
                            moduleMessage = "Motor: ${result.reason}",
                        ),
                    )
                }
            }
        }
    }

    fun warmKingEngine() = warmVcplax()
    fun ensureEmbeddedModule() = warmVcplax()

    fun clearNeedsReboot() {
        _uiState.postValue(_uiState.value?.copy(needsReboot = false))
    }

    fun setSourceType(type: VideoSourceType) {
        viewModelScope.launch { settings.setSourceType(type) }
    }

    fun onLocalVideoSelected(uri: Uri) {
        viewModelScope.launch { settings.setLocalVideoUri(uri) }
    }

    fun setNetworkUrl(url: String) {
        viewModelScope.launch { settings.setNetworkUrl(url) }
    }

    fun activate(code: String) {
        viewModelScope.launch {
            val ok = code.trim().length >= 6
            settings.setActivation(code.trim(), ok)
            toast(if (ok) "Ativado" else "Codigo invalido")
        }
    }

    fun toggleVirtualCamera(enable: Boolean) {
        viewModelScope.launch {
            _uiState.postValue(_uiState.value?.copy(busy = true, needsReboot = false))
            if (enable) {
                val boot = withContext(Dispatchers.IO) {
                    VcplaxEngine.ensureRunning(getApplication())
                }
                if (boot is VcplaxEngine.Result.Failed) {
                    _uiState.postValue(_uiState.value?.copy(busy = false))
                    toast("Root/motor: ${boot.reason}")
                    return@launch
                }
            }
            val prefs = _uiState.value?.prefs ?: AppPreferences()
            val result = if (enable) {
                controller.enable(
                    sourceType = prefs.sourceType,
                    localUri = prefs.localVideoUri?.let(Uri::parse),
                    networkUrl = prefs.networkUrl,
                )
            } else {
                controller.disable()
            }
            settings.setVirtualCameraEnabled(result.isSuccess && enable)
            _uiState.postValue(_uiState.value?.copy(busy = false, needsReboot = false))
            toast(result.exceptionOrNull()?.message ?: if (enable) "Virtual solicitada" else "Desligado")
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        if (enabled) {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        } else {
            context.startService(
                Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_STOP
                },
            )
        }
        viewModelScope.launch { settings.setOverlayEnabled(enabled) }
    }

    fun switchReal() = controller.switchToRealCamera()
    fun switchVirtual() = controller.switchToVirtualCamera()

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}

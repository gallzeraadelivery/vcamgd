package com.vcamgd.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vcamgd.app.VCamApp
import com.vcamgd.app.camera.UniversalEngine
import com.vcamgd.app.camera.VirtualCameraController
import com.vcamgd.app.camera.VirtualCameraStatus
import com.vcamgd.app.data.AppPreferences
import com.vcamgd.app.data.VideoSourceType
import com.vcamgd.app.root.RootChecker
import com.vcamgd.app.root.RootStatus
import com.vcamgd.app.service.OverlayService
import com.vcamgd.app.util.KingVCamLog
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
    val needsReboot: Boolean = false,
    val moduleMessage: String? = null,
    val auditLog: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = (application as VCamApp).settings
    private val controller = VirtualCameraController(application)

    private val _uiState = MutableLiveData(MainUiState())
    val uiState: LiveData<MainUiState> = _uiState

    init {
        UniversalEngine.bindContext(application)
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

    /** Prepara motor universal (12–16). Sem reboot de modulo. */
    fun warmEngine() {
        viewModelScope.launch {
            _uiState.postValue(
                _uiState.value?.copy(busy = true, moduleMessage = "Preparando motor 12–16..."),
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { UniversalEngine.ensureRunning(getApplication()) }
                    .getOrElse { UniversalEngine.Result.Failed(it.message ?: "erro") }
            }
            when (result) {
                is UniversalEngine.Result.Ok -> {
                    _uiState.postValue(
                        _uiState.value?.copy(
                            busy = false,
                            needsReboot = false,
                            moduleMessage = "Motor OK: ${UniversalEngine.statusLine(getApplication())}",
                        ),
                    )
                    runCatching { controller.refreshModuleStatus() }
                }
                is UniversalEngine.Result.Failed -> {
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

    fun warmVcplax() = warmEngine()
    fun warmKingEngine() = warmEngine()
    fun ensureEmbeddedModule() = warmEngine()

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
            runCatching {
                if (enable) {
                    val boot = withContext(Dispatchers.IO) {
                        UniversalEngine.ensureRunning(getApplication())
                    }
                    if (boot is UniversalEngine.Result.Failed) {
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
            }.onFailure { t ->
                Log.e("KingVCam", "toggleVirtualCamera", t)
                _uiState.postValue(_uiState.value?.copy(busy = false))
                toast("Erro: ${t.message ?: "falha"}")
            }
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

    fun setAuditLog(text: String) {
        _uiState.postValue(_uiState.value?.copy(auditLog = text))
    }

    /** Snapshot: por que a camera do celular nao mostra o video. */
    fun auditWhyNoPreview(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                KingVCamLog.auditWhyNoPreview("manual_status_tab")
            }
            _uiState.postValue(_uiState.value?.copy(auditLog = KingVCamLog.dump()))
            onDone?.invoke()
            toast("Auditoria de preview gravada no log")
        }
    }

    fun switchReal() {
        viewModelScope.launch {
            runCatching { controller.switchToRealCamera() }
                .onFailure { toast("Erro REAL: ${it.message}") }
        }
    }

    fun switchVirtual() {
        viewModelScope.launch {
            runCatching { controller.switchToVirtualCamera() }
                .onFailure { toast("Erro VIRTUAL: ${it.message}") }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}

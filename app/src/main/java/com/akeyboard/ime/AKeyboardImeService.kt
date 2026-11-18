package com.akeyboard.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.akeyboard.AKeyboardApp
import com.akeyboard.audio.AudioCaptureManager
import com.akeyboard.audio.AudioCaptureResult
import com.akeyboard.audio.AudioFailureReason
import com.akeyboard.data.SettingsRepository
import com.akeyboard.live.GeminiLiveClient
import com.akeyboard.live.LiveError
import com.akeyboard.live.LiveEvent
import com.akeyboard.settings.SettingsActivity
import com.akeyboard.ui.KeyboardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AKeyboardImeService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleDispatcher = ServiceLifecycleDispatcher(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleDispatcher.lifecycle
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var app: AKeyboardApp
    private lateinit var liveClient: GeminiLiveClient
    private val audioManager = AudioCaptureManager()

    private val _imeState = MutableStateFlow<ImeState>(ImeState.Idle)
    val imeState: StateFlow<ImeState> = _imeState.asStateFlow()

    private val _stepXDp = MutableStateFlow(SettingsRepository.DEFAULT_STEP_X_DP)
    val stepXDp: StateFlow<Int> = _stepXDp.asStateFlow()

    private val _stepYDp = MutableStateFlow(SettingsRepository.DEFAULT_STEP_Y_DP)
    val stepYDp: StateFlow<Int> = _stepYDp.asStateFlow()

    private val _isMultiline = MutableStateFlow(false)
    val isMultiline: StateFlow<Boolean> = _isMultiline.asStateFlow()

    private val _showSwitchImeButton = MutableStateFlow(false)
    val showSwitchImeButton: StateFlow<Boolean> = _showSwitchImeButton.asStateFlow()

    private val _imeAction = MutableStateFlow(EditorInfo.IME_ACTION_NONE)
    val imeAction: StateFlow<Int> = _imeAction.asStateFlow()

    private var voiceAllowed = true
    private var currentEditorInfo: EditorInfo? = null
    private var liveEventsJob: Job? = null
    private var maxDurationJob: Job? = null
    private var finalizeJob: Job? = null

    private var runningComposingText = StringBuilder()
    private var hasCommittedCurrentTurn = false

    override fun onCreate() {
        lifecycleDispatcher.onServicePreSuperOnCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        lifecycleDispatcher.onServicePreSuperOnStart()

        app = application as AKeyboardApp
        liveClient = GeminiLiveClient(app.okHttpClient)

        serviceScope.launch {
            app.settingsRepository.settings.collect { s ->
                _stepXDp.value = s.touchpadStepXDp
                _stepYDp.value = s.touchpadStepYDp
                _showSwitchImeButton.value = s.showSwitchImeKey
            }
        }
        observeLiveEvents()
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KeyboardScreen(
                    imeStateFlow = imeState,
                    stepXDpFlow = stepXDp,
                    stepYDpFlow = stepYDp,
                    isMultilineFlow = isMultiline,
                    showSwitchImeFlow = showSwitchImeButton,
                    imeActionFlow = imeAction,
                    onMicPress = ::onMicPressed,
                    onMicRelease = ::onMicReleased,
                    onBackspace = ::performBackspace,
                    onEnter = ::performEnter,
                    onSwitchIme = ::performSwitchIme,
                    onDpadKey = ::sendDpadKey,
                    onOpenSettings = ::openSettings
                )
            }
        }
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (restarting) {
            abortActiveSession()
        }
        currentEditorInfo = info
        _isMultiline.value = isMultilineInput(info)
        _imeAction.value = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        voiceAllowed = !isSensitiveInput(info)

        if (!voiceAllowed) {
            _imeState.value = ImeState.Error(ImeErrorReason.PASSWORD_FIELD)
            liveClient.close()
            return
        }

        _imeState.value = ImeState.Idle
        serviceScope.launch {
            val key = app.apiKeyStore.load()
            val hasPerm = hasMicPermission()
            val settings = app.settingsRepository.settings.first()
            if (!key.isNullOrBlank() && hasPerm && isNetworkAvailable()) {
                liveClient.warmUp(
                    scope = serviceScope,
                    apiKey = key,
                    modelId = settings.selectedModelId,
                    languageCodes = settings.languageCodes,
                    smartMode = settings.smartModeEnabled
                )
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInputView(finishingInput: Boolean) {
        abortActiveSession()
        liveClient.close()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        abortActiveSession()
        liveClient.close()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        abortActiveSession()
        liveClient.close()
        serviceScope.cancel()
        lifecycleDispatcher.onServicePreSuperOnDestroy()
        super.onDestroy()
        store.clear()
    }

    private fun observeLiveEvents() {
        liveEventsJob?.cancel()
        liveEventsJob = serviceScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    LiveEvent.Ready -> {
                        if (_imeState.value == ImeState.Idle) {
                            _imeState.value = ImeState.Warm
                        }
                    }
                    is LiveEvent.Interim -> {
                        if (_imeState.value == ImeState.Recording || _imeState.value == ImeState.Finalizing) {
                            runningComposingText.clear()
                            runningComposingText.append(event.text)
                            currentInputConnection?.setComposingText(event.text, 1)
                        }
                    }
                    is LiveEvent.Final -> {
                        if (_imeState.value == ImeState.Recording || _imeState.value == ImeState.Finalizing) {
                            commitFinalText(event.text)
                            if (_imeState.value == ImeState.Finalizing) {
                                finalizeJob?.cancel()
                                _imeState.value = ImeState.Warm
                            }
                        }
                    }
                    LiveEvent.TurnComplete -> {
                        if (_imeState.value == ImeState.Finalizing) {
                            if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
                                commitFinalText(runningComposingText.toString())
                            }
                            finalizeJob?.cancel()
                            _imeState.value = ImeState.Warm
                        }
                    }
                    is LiveEvent.Failed -> {
                        audioManager.stop()
                        maxDurationJob?.cancel()
                        finalizeJob?.cancel()
                        if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
                            currentInputConnection?.finishComposingText()
                        }
                        runningComposingText.clear()
                        val reason = when (event.error) {
                            LiveError.InvalidKey -> ImeErrorReason.INVALID_KEY
                            LiveError.QuotaExceeded -> ImeErrorReason.QUOTA_EXCEEDED
                            is LiveError.Network -> ImeErrorReason.GENERIC
                        }
                        _imeState.value = ImeState.Error(reason)
                    }
                }
            }
        }
    }

    private fun onMicPressed() {
        if (!voiceAllowed) {
            _imeState.value = ImeState.Error(ImeErrorReason.PASSWORD_FIELD)
            return
        }
        if (_imeState.value == ImeState.Finalizing || _imeState.value == ImeState.Recording) {
            return
        }
        if (!hasMicPermission()) {
            _imeState.value = ImeState.Error(ImeErrorReason.NO_PERMISSION)
            return
        }
        if (!isNetworkAvailable()) {
            _imeState.value = ImeState.Error(ImeErrorReason.NO_NETWORK)
            return
        }

        serviceScope.launch {
            val key = app.apiKeyStore.load()
            if (key.isNullOrBlank()) {
                _imeState.value = ImeState.Error(ImeErrorReason.NO_KEY)
                return@launch
            }
            val settings = app.settingsRepository.settings.first()
            when (val capture = audioManager.start(serviceScope)) {
                is AudioCaptureResult.Failed -> {
                    _imeState.value = ImeState.Error(
                        when (capture.reason) {
                            AudioFailureReason.NO_PERMISSION -> ImeErrorReason.NO_PERMISSION
                            AudioFailureReason.MIC_UNAVAILABLE -> ImeErrorReason.MIC_BUSY
                        }
                    )
                }
                is AudioCaptureResult.Started -> {
                    runningComposingText.clear()
                    hasCommittedCurrentTurn = false
                    _imeState.value = ImeState.Recording
                    liveClient.startUtterance(
                        scope = serviceScope,
                        apiKey = key,
                        modelId = settings.selectedModelId,
                        audioFrames = capture.frames,
                        languageCodes = settings.languageCodes,
                        smartMode = settings.smartModeEnabled
                    )

                    maxDurationJob?.cancel()
                    maxDurationJob = serviceScope.launch {
                        delay(MAX_HOLD_DURATION_MS)
                        if (_imeState.value == ImeState.Recording) {
                            stopAndFinalize(reachedCap = true)
                        }
                    }
                }
            }
        }
    }

    private fun onMicReleased() {
        if (_imeState.value != ImeState.Recording) return
        stopAndFinalize(reachedCap = false)
    }

    private fun stopAndFinalize(reachedCap: Boolean) {
        maxDurationJob?.cancel()
        audioManager.stop()
        _imeState.value = ImeState.Finalizing

        finalizeJob?.cancel()
        finalizeJob = serviceScope.launch {
            liveClient.finishUtterance(serviceScope)
            delay(liveClient.finalizeTimeout())
            if (_imeState.value == ImeState.Finalizing) {
                if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
                    commitFinalText(runningComposingText.toString())
                } else {
                    currentInputConnection?.finishComposingText()
                }
                _imeState.value = if (reachedCap) {
                    ImeState.Error(ImeErrorReason.MAX_DURATION)
                } else {
                    ImeState.Warm
                }
            }
        }
    }

    private fun commitFinalText(text: String) {
        if (hasCommittedCurrentTurn) return
        hasCommittedCurrentTurn = true
        runningComposingText.clear()
        currentInputConnection?.commitText(text, 1)
    }

    private fun abortActiveSession() {
        maxDurationJob?.cancel()
        finalizeJob?.cancel()
        audioManager.stop()
        if (!hasCommittedCurrentTurn && runningComposingText.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
        }
        runningComposingText.clear()
        hasCommittedCurrentTurn = false
        if (_imeState.value == ImeState.Recording || _imeState.value == ImeState.Finalizing) {
            _imeState.value = ImeState.Idle
        }
    }

    private fun performBackspace() {
        if (_imeState.value is ImeState.Error) {
            _imeState.value = if (voiceAllowed) ImeState.Idle else ImeState.Error(ImeErrorReason.PASSWORD_FIELD)
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun performEnter() {
        val ic = currentInputConnection ?: return
        val info = currentEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (ic.performEditorAction(action)) return
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun performSwitchIme() {
        val switched = switchToNextInputMethod(false)
        if (!switched) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    private fun sendDpadKey(keyCode: Int) {
        if (_imeState.value == ImeState.Recording) return
        if (_imeState.value is ImeState.Error && voiceAllowed) {
            _imeState.value = ImeState.Idle
        }
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun openSettings() {
        mainHandler.post {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isSensitiveInput(info: EditorInfo?): Boolean {
        if (info == null) return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
        val typeClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (typeClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isMultilineInput(info: EditorInfo?): Boolean {
        if (info == null) return false
        val typeClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (typeClass != InputType.TYPE_CLASS_TEXT) return false
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
            (flags and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0 ||
            variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
    }

    companion object {
        private const val MAX_HOLD_DURATION_MS = 60_000L
    }
}

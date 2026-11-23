package com.akeyboard.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.akeyboard.R
import com.akeyboard.audio.AudioCaptureManager
import com.akeyboard.data.SettingsRepository
import com.akeyboard.live.GeminiLiveClient
import com.akeyboard.live.VoiceDictationSession
import com.akeyboard.live.VoiceSessionError
import com.akeyboard.live.VoiceSessionPhase
import com.akeyboard.settings.SettingsActivity
import com.akeyboard.ui.KeyboardScreen
import com.akeyboard.util.hasPermission
import com.akeyboard.util.isNetworkAvailable
import com.akeyboard.util.readClipboardText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private lateinit var dictationSession: VoiceDictationSession
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

    private val _inlineSuggestions = MutableStateFlow<List<InlineContentView>>(emptyList())
    val inlineSuggestions: StateFlow<List<InlineContentView>> = _inlineSuggestions.asStateFlow()
    private var inlineGeneration = 0

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        val density = resources.displayMetrics.density
        val style = InlineSuggestionUi.newStyleBuilder().build()
        val stylesBundle = UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = InlinePresentationSpec.Builder(
            Size((INLINE_MIN_WIDTH_DP * density).toInt(), (INLINE_HEIGHT_DP * density).toInt()),
            Size(resources.displayMetrics.widthPixels, (INLINE_HEIGHT_DP * density).toInt())
        ).setStyle(stylesBundle).build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(INLINE_MAX_SUGGESTIONS)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        val generation = ++inlineGeneration
        if (suggestions.isEmpty()) {
            _inlineSuggestions.value = emptyList()
            return false
        }
        val inflated = arrayOfNulls<InlineContentView>(suggestions.size)
        var pending = suggestions.size
        val wrap = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        suggestions.forEachIndexed { index, suggestion ->
            suggestion.inflate(this, wrap, mainExecutor) { view ->
                if (generation != inlineGeneration) return@inflate
                view?.setZOrderedOnTop(true)
                inflated[index] = view
                if (--pending == 0) {
                    _inlineSuggestions.value = inflated.filterNotNull()
                }
            }
        }
        return true
    }

    private fun clearInlineSuggestions() {
        inlineGeneration++
        _inlineSuggestions.value = emptyList()
    }

    override fun onCreate() {
        lifecycleDispatcher.onServicePreSuperOnCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        lifecycleDispatcher.onServicePreSuperOnStart()

        app = application as AKeyboardApp
        liveClient = GeminiLiveClient(
            okHttpClient = app.okHttpClient,
            onUsageReported = app::recordUsage
        )
        dictationSession = VoiceDictationSession(
            scope = serviceScope,
            audioManager = audioManager,
            liveClient = liveClient,
            apiKeyStore = app.apiKeyStore,
            settingsRepository = app.settingsRepository,
            hasMicPermission = { hasPermission(Manifest.permission.RECORD_AUDIO) },
            isNetworkAvailable = { isNetworkAvailable() },
            onPhaseChanged = { phase ->
                setImeKeepScreenOn(
                    voiceAllowed && (phase == VoiceSessionPhase.RECORDING || phase == VoiceSessionPhase.FINALIZING)
                )
                if (voiceAllowed) {
                    _imeState.value = when (phase) {
                        VoiceSessionPhase.IDLE -> ImeState.Idle
                        VoiceSessionPhase.WARM -> ImeState.Warm
                        VoiceSessionPhase.RECORDING -> ImeState.Recording
                        VoiceSessionPhase.FINALIZING -> ImeState.Finalizing
                    }
                }
            },
            onInterim = { text -> currentInputConnection?.setComposingText(text, 1) },
            onFinal = { text -> currentInputConnection?.commitText(text, 1) },
            onFinishComposing = { currentInputConnection?.finishComposingText() },
            onError = { err ->
                setImeKeepScreenOn(false)
                _imeState.value = ImeState.Error(
                    when (err) {
                        VoiceSessionError.NO_PERMISSION -> ImeErrorReason.NO_PERMISSION
                        VoiceSessionError.NO_KEY -> ImeErrorReason.NO_KEY
                        VoiceSessionError.NO_NETWORK -> ImeErrorReason.NO_NETWORK
                        VoiceSessionError.MIC_BUSY -> ImeErrorReason.MIC_BUSY
                        VoiceSessionError.INVALID_KEY -> ImeErrorReason.INVALID_KEY
                        VoiceSessionError.QUOTA_EXCEEDED -> ImeErrorReason.QUOTA_EXCEEDED
                        VoiceSessionError.REGION_NOT_SUPPORTED -> ImeErrorReason.REGION_NOT_SUPPORTED
                        VoiceSessionError.MAX_DURATION -> ImeErrorReason.MAX_DURATION
                        VoiceSessionError.GENERIC -> ImeErrorReason.GENERIC
                    }
                )
            }
        )

        serviceScope.launch {
            app.settingsRepository.settings.collect { s ->
                _stepXDp.value = s.touchpadStepXDp
                _stepYDp.value = s.touchpadStepYDp
                _showSwitchImeButton.value = s.showSwitchImeKey
            }
        }
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
                    inlineSuggestionsFlow = inlineSuggestions,
                    onMicPress = ::onMicPressed,
                    onMicRelease = ::onMicReleased,
                    onBackspace = ::performBackspace,
                    onEnter = ::performEnter,
                    onSwitchIme = ::performSwitchIme,
                    onDpadKey = ::sendDpadKey,
                    onPaste = ::performPaste,
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
        updateNavigationBarAppearance()
        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (restarting) {
            abortActiveSession()
        }
        currentEditorInfo = info
        clearInlineSuggestions()
        _isMultiline.value = isMultilineInput(info)
        _imeAction.value = resolveEffectiveImeAction(info?.imeOptions ?: 0)
        voiceAllowed = !isSensitiveInput(info)

        if (!voiceAllowed) {
            _imeState.value = ImeState.Error(ImeErrorReason.PASSWORD_FIELD)
            liveClient.close()
            return
        }

        _imeState.value = ImeState.Idle
        dictationSession.resetPhaseToIdle()
        dictationSession.warmUp()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInputView(finishingInput: Boolean) {
        clearInlineSuggestions()
        setImeKeepScreenOn(false)
        abortActiveSession()
        liveClient.close()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        setImeKeepScreenOn(false)
        abortActiveSession()
        liveClient.close()
        super.onWindowHidden()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        updateNavigationBarAppearance()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNavigationBarAppearance(newConfig)
    }

    private fun updateNavigationBarAppearance(config: Configuration = resources.configuration) {
        val imeWindow = window?.window ?: return
        val isDarkTheme =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        WindowCompat.setDecorFitsSystemWindows(imeWindow, false)

        @Suppress("DEPRECATION")
        imeWindow.navigationBarColor = Color.TRANSPARENT
        imeWindow.isNavigationBarContrastEnforced = false

        WindowInsetsControllerCompat(imeWindow, imeWindow.decorView).apply {
            isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    override fun onDestroy() {
        setImeKeepScreenOn(false)
        dictationSession.close()
        serviceScope.cancel()
        lifecycleDispatcher.onServicePreSuperOnDestroy()
        super.onDestroy()
        store.clear()
    }

    private fun setImeKeepScreenOn(keepOn: Boolean) {
        val imeWindow = window?.window ?: return
        if (keepOn) {
            imeWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            imeWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun onMicPressed() {
        if (!voiceAllowed) {
            _imeState.value = ImeState.Error(ImeErrorReason.PASSWORD_FIELD)
            return
        }
        dictationSession.onMicPressed()
    }

    private fun onMicReleased() {
        dictationSession.onMicReleased()
    }

    private fun abortActiveSession() {
        dictationSession.abort()
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
        if (((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            val action = resolveEffectiveImeAction(info?.imeOptions ?: 0)
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                if (ic.performEditorAction(action)) return
            }
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

    private fun performPaste() {
        if (_imeState.value == ImeState.Recording || _imeState.value == ImeState.Finalizing) return
        if (_imeState.value is ImeState.Error && voiceAllowed) {
            _imeState.value = ImeState.Idle
        }
        val text = readClipboardText()
        if (text == null) {
            Toast.makeText(this, R.string.api_key_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            for (chunk in chunkTextForCommit(text)) {
                if (!ic.commitText(chunk, 1)) break
            }
        } catch (_: RuntimeException) {
            // Guard against Binder transaction failures on oversized clipboard text.
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun openSettings() {
        mainHandler.post {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
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
        private const val INLINE_MIN_WIDTH_DP = 100
        private const val INLINE_HEIGHT_DP = 32
        private const val INLINE_MAX_SUGGESTIONS = 5
        const val MAX_COMMIT_CHUNK_CHARS = 4_096

        fun chunkTextForCommit(
            text: String,
            maxChunkChars: Int = MAX_COMMIT_CHUNK_CHARS
        ): List<String> {
            if (text.isEmpty() || maxChunkChars <= 0) return emptyList()
            if (text.length <= maxChunkChars) return listOf(text)
            val chunks = ArrayList<String>((text.length + maxChunkChars - 1) / maxChunkChars)
            var start = 0
            while (start < text.length) {
                var end = (start + maxChunkChars).coerceAtMost(text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) {
                    end = if (end - start > 1) end - 1 else (end + 1).coerceAtMost(text.length)
                }
                chunks.add(text.substring(start, end))
                start = end
            }
            return chunks
        }

        fun resolveEffectiveImeAction(imeOptions: Int): Int {
            return if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
                EditorInfo.IME_ACTION_NONE
            } else {
                imeOptions and EditorInfo.IME_MASK_ACTION
            }
        }
    }
}

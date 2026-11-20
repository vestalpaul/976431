package com.schwabquant.app.presentation.regime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.core.handoff.GeminiHandoffManager
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.DemoRegimePreset
import com.schwabquant.app.domain.usecase.EvaluateBearChecklistUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegimeUiState(
    val evaluation: BearChecklistEvaluation? = null,
    val hasLiveAuth: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedDemoPreset: DemoRegimePreset = DemoRegimePreset.CAUTION,
    val snackbarMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class RegimeViewModel @Inject constructor(
    private val evaluateBearChecklistUseCase: EvaluateBearChecklistUseCase,
    private val credentialVault: CredentialVault,
    private val geminiHandoffManager: GeminiHandoffManager
) : ViewModel() {

    private val evaluationState = MutableStateFlow<BearChecklistEvaluation?>(null)
    private val isRefreshingState = MutableStateFlow(false)
    private val selectedPresetState = MutableStateFlow(DemoRegimePreset.CAUTION)
    private val snackbarState = MutableStateFlow<String?>(null)
    private val errorState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RegimeUiState> = combine(
        evaluationState,
        credentialVault.authSnapshotFlow,
        isRefreshingState,
        selectedPresetState,
        snackbarState,
        errorState
    ) { values ->
        val evaluation = values[0] as BearChecklistEvaluation?
        val auth = values[1] as com.schwabquant.app.core.security.StoredAuthSnapshot
        val refreshing = values[2] as Boolean
        val preset = values[3] as DemoRegimePreset
        val snackbar = values[4] as String?
        val error = values[5] as String?

        RegimeUiState(
            evaluation = evaluation,
            hasLiveAuth = auth.hasAccessToken,
            isRefreshing = refreshing,
            selectedDemoPreset = preset,
            snackbarMessage = snackbar,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RegimeUiState()
    )

    init {
        if (evaluateBearChecklistUseCase.hasLiveAuth()) {
            refreshLive(forceRefresh = false)
        } else {
            selectDemoPreset(DemoRegimePreset.CAUTION)
        }
    }

    fun refresh() {
        val current = evaluationState.value
        if (evaluateBearChecklistUseCase.hasLiveAuth() && current?.isDemoData != true) {
            refreshLive(forceRefresh = true)
        } else if (evaluateBearChecklistUseCase.hasLiveAuth()) {
            refreshLive(forceRefresh = true)
        } else {
            selectDemoPreset(selectedPresetState.value)
        }
    }

    fun refreshLive(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            isRefreshingState.value = true
            errorState.value = null
            evaluateBearChecklistUseCase.evaluateLive(forceRefresh = forceRefresh)
                .onSuccess { evaluation ->
                    evaluationState.value = evaluation
                }
                .onFailure { err ->
                    errorState.value = err.message ?: "Live Bear Checklist fetch failed; showing Demo preset."
                    if (evaluationState.value == null) {
                        evaluationState.value = evaluateBearChecklistUseCase.evaluateDemo(selectedPresetState.value)
                    }
                }
            isRefreshingState.value = false
        }
    }

    fun selectDemoPreset(preset: DemoRegimePreset) {
        selectedPresetState.value = preset
        errorState.value = null
        evaluationState.value = evaluateBearChecklistUseCase.evaluateDemo(preset)
    }

    fun handoffToGemini() {
        val eval = evaluationState.value ?: return
        val markdown = evaluateBearChecklistUseCase.buildRegimeGeminiMarkdown(eval)
        val result = geminiHandoffManager.handoffPrompt(
            prompt = markdown,
            subject = "Schwab Quant — ${eval.regime.label} Regime Brief"
        )
        snackbarState.value = result.message
    }

    fun consumeSnackbar() {
        snackbarState.value = null
    }
}

package com.macroplus.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroplus.app.data.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun signIn(email: String, password: String) {
        _uiState.value = AuthUiState(isSubmitting = true)
        viewModelScope.launch {
            try {
                authRepository.signIn(email, password)
                _uiState.value = AuthUiState(isSubmitting = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = AuthUiState(isSubmitting = false, errorMessage = e.message ?: "Sign in failed")
            }
        }
    }

    fun signUp(email: String, password: String) {
        _uiState.value = AuthUiState(isSubmitting = true)
        viewModelScope.launch {
            try {
                authRepository.signUp(email, password)
                _uiState.value = AuthUiState(isSubmitting = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = AuthUiState(isSubmitting = false, errorMessage = e.message ?: "Sign up failed")
            }
        }
    }
}

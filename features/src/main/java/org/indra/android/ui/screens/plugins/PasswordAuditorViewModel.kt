package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

data class PasswordAuditResult(
    val password: String,
    val length: Int,
    val entropyBits: Double,
    val hasUpper: Boolean,
    val hasLower: Boolean,
    val hasDigit: Boolean,
    val hasSymbol: Boolean,
    val inDictionary: Boolean,
    val score: Int, // 0-4
    val verdict: String
)

data class PasswordAuditorUiState(
    val password: String = "",
    val result: PasswordAuditResult? = null
)

@HiltViewModel
class PasswordAuditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(PasswordAuditorUiState())
    val uiState: StateFlow<PasswordAuditorUiState> = _uiState.asStateFlow()

    private val commonPasswords: Set<String> by lazy {
        try {
            context.assets.open("wordlists/common-passwords.txt").bufferedReader().use { r ->
                r.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun updatePassword(v: String) { _uiState.value = _uiState.value.copy(password = v) }

    fun audit() {
        val pw = _uiState.value.password
        if (pw.isEmpty()) {
            _uiState.value = _uiState.value.copy(result = null)
            return
        }
        val hasUpper = pw.any { it.isUpperCase() }
        val hasLower = pw.any { it.isLowerCase() }
        val hasDigit = pw.any { it.isDigit() }
        val hasSymbol = pw.any { !it.isLetterOrDigit() }
        val charsetSize = (if (hasUpper) 26 else 0) + (if (hasLower) 26 else 0) +
            (if (hasDigit) 10 else 0) + (if (hasSymbol) 32 else 0)
        val entropy = if (charsetSize > 0) pw.length * kotlin.math.log2(charsetSize.toDouble()) else 0.0
        val inDict = commonPasswords.contains(pw) || commonPasswords.any { it.equals(pw, ignoreCase = true) }

        var score = 0
        if (pw.length >= 8) score++
        if (pw.length >= 12) score++
        if (hasUpper && hasLower && hasDigit) score++
        if (hasSymbol) score++
        if (inDict) score = 0
        if (pw.length < 6) score = 0

        val verdict = when (score) {
            0 -> "Very weak"
            1 -> "Weak"
            2 -> "Fair"
            3 -> "Strong"
            else -> "Very strong"
        }

        _uiState.value = _uiState.value.copy(
            result = PasswordAuditResult(pw, pw.length, entropy, hasUpper, hasLower, hasDigit, hasSymbol, inDict, score, verdict)
        )
    }
}
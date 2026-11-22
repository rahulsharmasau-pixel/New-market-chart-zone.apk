package com.marketchartzone.app.ui.auth

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.marketchartzone.app.R
import com.marketchartzone.app.data.model.UserProfile
import com.marketchartzone.app.data.repository.MarketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MarketRepository(application)
    
    private val _authState = MutableStateFlow<AuthStatus>(AuthStatus.Loading)
    val authState: StateFlow<AuthStatus> = _authState.asStateFlow()

    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        checkCurrentUser()
    }

    fun checkCurrentUser() {
        val firebaseUser = Firebase.auth.currentUser
        if (firebaseUser != null) {
            viewModelScope.launch {
                var profile = repository.getUserProfile(firebaseUser.uid)
                if (profile == null) {
                    val isAdminEmail = firebaseUser.email == "admin@marketchartzone.com" || firebaseUser.email == "rahulsharmasau@gmail.com"
                    profile = UserProfile(
                        uid = firebaseUser.uid,
                        email = firebaseUser.email ?: "",
                        displayName = firebaseUser.displayName ?: "Trader",
                        avatarUrl = firebaseUser.photoUrl?.toString() ?: "",
                        isAdmin = isAdminEmail
                    )
                    repository.saveUserProfile(profile)
                }
                _authState.value = AuthStatus.Authenticated(profile)
            }
        } else {
            _authState.value = AuthStatus.Unauthenticated
        }
    }

    fun signInWithGoogle(context: android.content.Context) {
        viewModelScope.launch {
            try {
                errorMessage = null
                val webClientId = context.getString(R.string.default_web_client_id)
                val googleIdOption = GetSignInWithGoogleOption.Builder(webClientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
                val credentialManager = CredentialManager.create(context)
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken.idToken, null)
                val authResult = Firebase.auth.signInWithCredential(firebaseCredential).await()
                val firebaseUser = authResult.user
                if (firebaseUser != null) {
                    var profile = repository.getUserProfile(firebaseUser.uid)
                    val isAdminEmail = firebaseUser.email == "admin@marketchartzone.com" || firebaseUser.email == "rahulsharmasau@gmail.com"
                    if (profile == null) {
                        profile = UserProfile(
                            uid = firebaseUser.uid,
                            email = firebaseUser.email ?: "",
                            displayName = firebaseUser.displayName ?: "Trader",
                            avatarUrl = firebaseUser.photoUrl?.toString() ?: "",
                            isAdmin = isAdminEmail
                        )
                        repository.saveUserProfile(profile)
                    } else if (isAdminEmail && !profile.isAdmin) {
                        profile = profile.copy(isAdmin = true)
                        repository.saveUserProfile(profile)
                    }
                    _authState.value = AuthStatus.Authenticated(profile)
                } else {
                    errorMessage = "Authentication failed."
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("Auth", "Sign in cancelled", e)
            } catch (e: Exception) {
                Log.e("Auth", "Sign in error", e)
                errorMessage = e.localizedMessage ?: "Sign in failed"
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            Firebase.auth.signOut()
            _authState.value = AuthStatus.Unauthenticated
        }
    }
}

sealed class AuthStatus {
    object Loading : AuthStatus()
    object Unauthenticated : AuthStatus()
    data class Authenticated(val user: UserProfile) : AuthStatus()
}

package com.ofir.syncfolder.auth

import android.content.Context
import android.content.IntentSender
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.tasks.await

class AuthManager(private val context: Context) {

    private val authorizationClient = Identity.getAuthorizationClient(context)

    fun buildSignInRequest(serverClientId: String): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(serverClientId)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
    }

    fun buildSignInRequestFresh(serverClientId: String): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
    }

    fun extractEmail(credential: Credential): String {
        val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
        return googleCred.id
    }

    /**
     * Returns (accessToken, null) if authorized silently, or (null, IntentSender) if UI needed.
     * Call from any context — does not require Activity.
     */
    suspend fun authorizeForDrive(): Pair<String?, IntentSender?> {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        val result = authorizationClient.authorize(request).await()
        return if (result.hasResolution()) {
            Pair(null, result.pendingIntent?.intentSender)
        } else {
            Pair(result.accessToken, null)
        }
    }

    suspend fun getTokenFromAuthResult(): String? {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        return try {
            authorizationClient.authorize(request).await().accessToken
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}

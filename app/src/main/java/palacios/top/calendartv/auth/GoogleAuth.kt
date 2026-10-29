package palacios.top.calendartv.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

sealed interface AuthResult {
    data class Token(val accessToken: String) : AuthResult
    /** The user must grant access once; launch [intentSender] and pass the result to [GoogleAuth.fromConsentResult]. */
    data class NeedsConsent(val intentSender: IntentSender) : AuthResult
}

/**
 * Calendar access through Google Play Services on the TV's own Google account (the "enter a code on
 * your phone" device flow does not allow Calendar scopes). Access tokens are short-lived, so every
 * sync asks again; after the first consent that call is silent.
 */
class GoogleAuth(context: Context) {

    private val client = Identity.getAuthorizationClient(context)

    private fun request(email: String?, withProfile: Boolean) = AuthorizationRequest.builder()
        .setRequestedScopes(listOfNotNull(Scope(CALENDAR_READONLY), if (withProfile) Scope(Scopes.PROFILE) else null))
        .apply { if (email != null) setAccount(Account(email, "com.google")) }
        .build()

    /** With [email] the request targets that TV account (silent once consented); without it Google shows its chooser. */
    suspend fun authorize(email: String? = null): AuthResult = toResult(client.authorize(request(email, false)).await())

    /**
     * Calendar plus the profile photo scope. Sync never uses this: an account that was connected before the
     * photo feature would otherwise look "disconnected" just because the extra scope isn't granted yet.
     */
    suspend fun authorizeWithProfile(email: String?): AuthResult =
        toResult(client.authorize(request(email, true)).await())

    fun fromConsentResult(data: Intent?): AuthResult = toResult(client.getAuthorizationResultFromIntent(data))

    private fun toResult(result: AuthorizationResult): AuthResult {
        val token = result.accessToken
        if (token != null) return AuthResult.Token(token)
        val pending = result.pendingIntent ?: error("Authorization returned neither a token nor a consent screen")
        return AuthResult.NeedsConsent(pending.intentSender)
    }

    private companion object {
        const val CALENDAR_READONLY = "https://www.googleapis.com/auth/calendar.readonly"
    }
}

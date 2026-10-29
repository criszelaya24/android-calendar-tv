package palacios.top.calendartv.ui

import android.Manifest
import android.accounts.AccountManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.launch
import palacios.top.calendartv.auth.AuthResult

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(viewModel)
            }
        }
    }
}

@Composable
private fun App(viewModel: MainViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.onConsentResult(result.data)
        else viewModel.onConsentCancelled()
    }
    val authorize: (String?) -> Unit = { email ->
        viewModel.picker = null
        scope.launch {
            when (val result = viewModel.authorize(email)) {
                is AuthResult.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(result.intentSender).build())
                is AuthResult.Token -> viewModel.onToken(result.accessToken)
                null -> Unit
            }
        }
    }
    // Android's own chooser lists every Google account on the TV and grants this app access to the one picked.
    val chooseAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == android.app.Activity.RESULT_OK && email != null) authorize(email)
    }
    val onPick: (String?) -> Unit = { email ->
        if (email != null) authorize(email)
        else chooseAccount.launch(
            AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null),
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.openPicker()
    }
    val addAccount: () -> Unit = {
        val granted = context.checkSelfPermission(Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.openPicker() else permission.launch(Manifest.permission.GET_ACCOUNTS)
    }

    // Debug builds only: `adb shell am start -n <pkg>/.ui.MainActivity --es add_account <email>`.
    LaunchedEffect(Unit) {
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val email = (context as? android.app.Activity)?.intent?.getStringExtra("add_account")
        if (debuggable && email != null) authorize(email)
    }

    // "Update account photos": one profile-permission screen per account, in order.
    val nextPhoto = viewModel.photoQueue.firstOrNull()
    LaunchedEffect(nextPhoto) {
        if (nextPhoto == null) return@LaunchedEffect
        viewModel.profileTarget = nextPhoto
        when (val result = viewModel.authorize(nextPhoto)) {
            is AuthResult.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(result.intentSender).build())
            is AuthResult.Token -> viewModel.onProfileToken(nextPhoto, result.accessToken)
            null -> viewModel.skipProfile()
        }
    }

    BackHandler(enabled = viewModel.picker != null) { viewModel.picker = null }
    BackHandler(enabled = viewModel.picker == null && settings.signedIn && viewModel.screen != Screen.Today) {
        viewModel.back()
    }

    Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
        Box(Modifier.padding(horizontal = 48.dp, vertical = 27.dp)) {
            val accounts = viewModel.picker
            if (accounts != null) {
                PickAccountScreen(viewModel, accounts, onPick = onPick)
            } else if (!settings.signedIn) {
                SignInScreen(viewModel, onSignIn = addAccount)
            } else {
                when (val screen = viewModel.screen) {
                    Screen.Today -> TodayScreen(viewModel, onReconnect = addAccount)
                    Screen.Agenda -> AgendaScreen(viewModel, onReconnect = addAccount)
                    is Screen.Detail -> DetailScreen(viewModel, screen)
                    Screen.Preferences -> SettingsScreen(viewModel, onAddAccount = addAccount)
                }
            }
        }
    }
}

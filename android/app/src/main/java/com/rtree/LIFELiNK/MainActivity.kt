package com.rtree.LIFELiNK

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Googleでログインしてください") }
    var locationText by remember { mutableStateOf("位置情報はまだ保存されていません") }

    val requestLocationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            scope.launch {
                locationText = captureLocation(context)
            }
        } else {
            locationText = "位置情報の権限が拒否されました"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("LIFELiNK", style = MaterialTheme.typography.headlineLarge)
        Text("You are never alone.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Text(status)
        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    status = signInWithGoogle(context)
                }
            },
        ) {
            Text("Googleでログイン")
        }
        Spacer(Modifier.height(24.dp))
        Text(locationText)
        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val hasLocationPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

                if (hasLocationPermission) {
                    scope.launch {
                        locationText = captureLocation(context)
                    }
                } else {
                    requestLocationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            },
        ) {
            Text("現在位置を取得")
        }
        Spacer(Modifier.height(24.dp))
        Text("Backend: ${BuildConfig.BACKEND_URL}", style = MaterialTheme.typography.bodySmall)
    }
}

private suspend fun signInWithGoogle(context: android.content.Context): String {
    val webClientId = context.getString(R.string.default_web_client_id)

    return runCatching {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val result = CredentialManager.create(context).getCredential(
            context = context,
            request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build(),
        )
        val customCredential = result.credential as? CustomCredential
            ?: error("Google credential was not returned")
        require(customCredential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val googleCredential = GoogleIdTokenCredential.createFrom(customCredential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        val user = FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).await().user
            ?: error("Firebase user was not returned")
        "ログイン済み: ${user.email ?: user.uid}"
    }.getOrElse { error ->
        "ログイン失敗: ${error.message ?: "unknown error"}"
    }
}

private suspend fun captureLocation(context: android.content.Context): String {
    val hasFine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) {
        return "位置情報の権限が必要です"
    }

    return runCatching {
        val location = LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(
                if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            )
            .await()
            ?: error("位置を取得できませんでした")
        "緯度 %.6f / 経度 %.6f / 精度 %.0fm".format(
            location.latitude,
            location.longitude,
            location.accuracy,
        )
    }.getOrElse { error ->
        "位置取得失敗: ${error.message ?: "unknown error"}"
    }
}
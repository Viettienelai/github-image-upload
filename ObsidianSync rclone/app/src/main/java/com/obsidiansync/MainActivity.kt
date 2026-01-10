package com.obsidiansync

import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.work.*
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.*
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.*
import java.io.File
import java.util.*

data class DriveItem(val id: String, val name: String)

class MainActivity : ComponentActivity() {
    private var driveService by mutableStateOf<Drive?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))

        setContent { MaterialTheme(colors = darkColors()) { Surface(Modifier.fillMaxSize(), color = Color(0xFF121212)) { MainScreen() } } }
    }

    @Composable
    fun MainScreen() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var user by remember { mutableStateOf(AppConfig.getUserEmail(ctx) ?: "") }
        var showPicker by remember { mutableStateOf(false) }

        // WorkManager Check
        val wm = WorkManager.getInstance(ctx)
        val workInfos by wm.getWorkInfosByTagLiveData("manual_sync").observeAsState(listOf())
        val workInfo = workInfos.firstOrNull { !it.state.isFinished }
        val isSyncing = workInfo != null
        val progress = workInfo?.progress?.getInt("progress", 0)?.div(100f) ?: 0f

        // Auth Init
        LaunchedEffect(Unit) {
            GoogleSignIn.getLastSignedInAccount(ctx)?.let { initDrive(it) { email -> user = email } }
        }

        val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            GoogleSignIn.getSignedInAccountFromIntent(res.data).result?.let { initDrive(it) { e -> user = e } }
        }
        val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                ctx.contentResolver.takePersistableUriPermission(it, 3)
                AppConfig.saveLocalPath(ctx, uri.path?.split("primary:")?.getOrNull(1)?.let { p -> "/storage/emulated/0/$p" } ?: "/storage/emulated/0/")
            }
        }

        Column(Modifier.padding(16.dp)) {
            Text("Obsidian Sync Pro", style = MaterialTheme.typography.h5, color = Color.White)
            Spacer(Modifier.height(16.dp))
            Card(elevation = 4.dp, backgroundColor = Color(0xFF2D2D2D)) {
                Column(Modifier.padding(12.dp)) {
                    RowItem(Icons.Default.AccountCircle, if (user.isEmpty()) "Đăng nhập Drive" else user) {
                        if (user.isEmpty()) authLauncher.launch(GoogleSignIn.getClient(ctx, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE)).build()).signInIntent)
                    }
                    Divider()
                    RowItem(Icons.Default.PhoneAndroid, AppConfig.getLocalPath(ctx).takeLast(30)) { folderLauncher.launch(null) }
                    Divider()
                    RowItem(Icons.Default.CloudQueue, AppConfig.getRemoteDisplayName(ctx)) { if (driveService != null) showPicker = true }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (isSyncing || progress > 0f) LinearProgressIndicator(progress, Modifier.fillMaxWidth().height(8.dp), Color(0xFFBB86FC))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ wm.enqueue(OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("is_upload" to true)).addTag("manual_sync").build()) }, Modifier.weight(1f), enabled = !isSyncing) { Text("Upload ⬆️") }
                Button({ wm.enqueue(OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("is_upload" to false)).addTag("manual_sync").build()) }, Modifier.weight(1f), enabled = !isSyncing, colors = ButtonDefaults.buttonColors(Color(0xFF03DAC6))) { Text("Download ⬇️") }
            }
        }
        if (showPicker && driveService != null) DrivePicker(driveService!!) { showPicker = false; if (it != null) saveRemote(ctx, it) }
    }

    private fun initDrive(acc: GoogleSignInAccount, onUser: (String) -> Unit) {
        val cred = GoogleAccountCredential.usingOAuth2(this, Collections.singleton(DriveScopes.DRIVE)).apply { selectedAccount = acc.account }
        driveService = Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), cred).setApplicationName("ObsidianSync").build()
        AppConfig.saveUserEmail(this, acc.email ?: "")
        onUser(acc.email ?: "")
        CoroutineScope(Dispatchers.IO).launch {
            val token = GoogleAuthUtil.getToken(this@MainActivity, acc.account!!, "oauth2:https://www.googleapis.com/auth/drive")
            File(filesDir, "rclone.conf").writeText("[gdrive]\ntype = drive\nscope = drive\ntoken = {\"access_token\":\"$token\",\"token_type\":\"Bearer\",\"expiry\":\"2030-01-01T00:00:00+07:00\"}")
        }
    }

    private fun saveRemote(ctx: android.content.Context, folder: DriveItem) {
        AppConfig.saveRemotePath(ctx, "gdrive:"); AppConfig.saveRemoteDisplayName(ctx, folder.name)
        val f = File(ctx.filesDir, "rclone.conf")
        if (f.exists()) f.writeText(f.readText().lines().filter { !it.startsWith("root_folder_id") }.joinToString("\n") + "\nroot_folder_id = ${folder.id}")
    }

    @Composable fun RowItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.LightGray); Spacer(Modifier.width(12.dp)); Text(text, color = Color.White)
        }
    }
}

@Composable
fun DrivePicker(service: Drive, onResult: (DriveItem?) -> Unit) {
    var stack by remember { mutableStateOf(listOf(DriveItem("root", "Root"))) }
    var list by remember { mutableStateOf(listOf<DriveItem>()) }

    LaunchedEffect(stack.last()) {
        withContext(Dispatchers.IO) {
            try {
                val res = service.files().list().setQ("'${stack.last().id}' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed=false").setFields("files(id, name)").setOrderBy("name").execute()
                list = res.files.map { DriveItem(it.id, it.name) }
            } catch (_: Exception) {}
        }
    }

    Dialog({ onResult(null) }) {
        Card(Modifier.fillMaxWidth().height(500.dp), backgroundColor = Color(0xFF1E1E1E)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stack.size > 1) IconButton({ stack = stack.dropLast(1) }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                    Text(stack.last().name, style = MaterialTheme.typography.h6, color = Color.White)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(list) { i ->
                        Row(Modifier.clickable { stack = stack + i }.padding(12.dp).fillMaxWidth()) { Icon(Icons.Default.Folder, null, tint = Color.Yellow); Spacer(Modifier.width(10.dp)); Text(i.name, color = Color.White) }
                        Divider(color = Color.DarkGray)
                    }
                }
                Button({ onResult(stack.last()) }, Modifier.align(Alignment.End)) { Text("Chọn") }
            }
        }
    }
}
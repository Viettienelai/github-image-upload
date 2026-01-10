package com.obsidiansync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.io.File as JavaFile
import java.net.URLDecoder
import java.util.UUID

data class DriveItem(val id: String, val name: String)

class MainActivity : ComponentActivity() {
    private var driveService by mutableStateOf<Drive?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
        setContent {
            MaterialTheme(colors = darkColors()) {
                Surface(color = Color(0xFF121212)) { MainScreen() }
            }
        }
    }

    private fun uriToPath(uri: Uri): String {
        return try {
            val path = uri.path ?: return ""
            if (path.contains("primary:")) {
                val split = path.split("primary:")
                if (split.size > 1) {
                    return "/storage/emulated/0/" + URLDecoder.decode(split[1], "UTF-8")
                }
            }
            "/storage/emulated/0/"
        } catch (e: Exception) { "/storage/emulated/0/" }
    }

    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val workManager = WorkManager.getInstance(context)

        // Đã xóa logText
        var isSignedIn by remember { mutableStateOf(false) }
        var userEmail by remember { mutableStateOf("") }

        var localPath by remember { mutableStateOf(AppConfig.getLocalPath(context)) }
        var remotePathDisplayName by remember { mutableStateOf(AppConfig.getRemoteDisplayName(context)) }

        var showDrivePicker by remember { mutableStateOf(false) }
        var currentWorkId by remember { mutableStateOf<UUID?>(null) }
        var syncProgress by remember { mutableStateOf(0f) }
        var isSyncing by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account != null) {
                userEmail = account.email ?: AppConfig.getUserEmail(context) ?: ""
                isSignedIn = true
                val cred = GoogleAccountCredential.usingOAuth2(context, Collections.singleton(DriveScopes.DRIVE))
                cred.selectedAccount = account.account
                driveService = Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), cred)
                    .setApplicationName("ObsidianSync").build()
            }
        }

        val workInfos = if (currentWorkId != null) {
            workManager.getWorkInfoByIdLiveData(currentWorkId!!).observeAsState()
        } else null
        val currentWorkInfo = workInfos?.value

        LaunchedEffect(currentWorkInfo) {
            if (currentWorkInfo != null) {
                isSyncing = currentWorkInfo.state == WorkInfo.State.RUNNING || currentWorkInfo.state == WorkInfo.State.ENQUEUED

                val p = currentWorkInfo.progress.getInt("progress", 0)
                if (p > 0) syncProgress = p / 100f

                if (currentWorkInfo.state == WorkInfo.State.SUCCEEDED) {
                    syncProgress = 1f
                    isSyncing = false
                } else if (currentWorkInfo.state == WorkInfo.State.FAILED) {
                    isSyncing = false
                    syncProgress = 0f
                }
            }
        }

        val localFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, flags)
                val realPath = uriToPath(it)
                localPath = realPath
                AppConfig.saveLocalPath(context, realPath)
            }
        }

        val googleSignInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    userEmail = account.email ?: ""
                    isSignedIn = true
                    AppConfig.saveUserEmail(context, userEmail)

                    val cred = GoogleAccountCredential.usingOAuth2(context, Collections.singleton(DriveScopes.DRIVE))
                    cred.selectedAccount = account.account
                    driveService = Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), cred)
                        .setApplicationName("ObsidianSync").build()

                    scope.launch(Dispatchers.IO) {
                        val token = GoogleAuthUtil.getToken(context, account.account!!, "oauth2:https://www.googleapis.com/auth/drive")
                        val configContent = """
[gdrive]
type = drive
scope = drive
token = {"access_token":"$token","token_type":"Bearer","expiry":"2030-01-01T00:00:00.000000+07:00"}
""".trimIndent()
                        JavaFile(context.filesDir, "rclone.conf").writeText(configContent)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        fun requestSignIn() {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail().requestScopes(Scope("https://www.googleapis.com/auth/drive")).build()
            googleSignInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
        }

        fun onDriveFolderSelected(folder: DriveItem) {
            remotePathDisplayName = folder.name
            AppConfig.saveRemotePath(context, "gdrive:")
            AppConfig.saveRemoteDisplayName(context, folder.name)

            scope.launch(Dispatchers.IO) {
                val configFile = JavaFile(context.filesDir, "rclone.conf")
                if (configFile.exists()) {
                    var content = configFile.readText()
                    content = content.lines().filter { !it.startsWith("root_folder_id") }.joinToString("\n")
                    content += "\nroot_folder_id = ${folder.id}"
                    configFile.writeText(content)
                }
            }
        }

        fun runSync(isUpload: Boolean) {
            if (isSyncing) return
            syncProgress = 0f

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf("is_upload" to isUpload))
                .addTag("manual_sync")
                .build()
            currentWorkId = syncRequest.id
            workManager.enqueue(syncRequest)
        }

        Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
            Text("Obsidian Sync Pro", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(16.dp))

            Card(elevation = 4.dp, backgroundColor = Color(0xFF2D2D2D), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { if(!isSignedIn) requestSignIn() }.padding(8.dp)) {
                        Icon(Icons.Default.AccountCircle, null, tint = if(isSignedIn) Color.Green else Color.Gray)
                        Spacer(Modifier.width(12.dp))
                        Text(if(isSignedIn) userEmail else "Nhấn để đăng nhập Drive", color = Color.White)
                    }
                    Divider(color = Color.Gray, thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { localFolderLauncher.launch(null) }.padding(8.dp)) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Folder trên máy", color = Color.Gray, fontSize = 12.sp)
                            Text(localPath.takeLast(30), color = Color.White)
                        }
                    }
                    Divider(color = Color.Gray, thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { if(isSignedIn && driveService != null) showDrivePicker = true }.padding(8.dp)) {
                        Icon(Icons.Default.CloudQueue, null, tint = Color(0xFFBB86FC))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Folder trên Drive", color = Color.Gray, fontSize = 12.sp)
                            Text(remotePathDisplayName, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (isSyncing || syncProgress > 0f) {
                Text(text = if(isSyncing) "Đang chạy: ${(syncProgress*100).toInt()}%" else "Hoàn tất 100%", color = Color.Cyan, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = syncProgress, modifier = Modifier.fillMaxWidth(), color = Color(0xFFBB86FC), backgroundColor = Color.DarkGray)
                Spacer(Modifier.height(16.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runSync(true) }, modifier = Modifier.weight(1f), enabled = !isSyncing, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFBB86FC))) { Text("Upload ⬆️") }
                Button(onClick = { runSync(false) }, modifier = Modifier.weight(1f), enabled = !isSyncing, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF03DAC6))) { Text("Download ⬇️") }
            }
        }

        if (showDrivePicker) {
            DriveFolderPickerDialog(driveService = driveService!!, onDismiss = { showDrivePicker = false }, onFolderSelected = { folder -> onDriveFolderSelected(folder); showDrivePicker = false })
        }
    }
}

@Composable
fun DriveFolderPickerDialog(
    driveService: Drive,
    onDismiss: () -> Unit,
    onFolderSelected: (DriveItem) -> Unit
) {
    var folderStack by remember { mutableStateOf(listOf(DriveItem("root", "Drive của tôi"))) }
    val currentFolder = folderStack.last()
    var currentChildren by remember { mutableStateOf<List<DriveItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadChildren(parentId: String) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val query = "'$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
                val result = driveService.files().list()
                    .setQ(query)
                    .setFields("files(id, name)")
                    .setOrderBy("name")
                    .execute()
                val folders = result.files.map { DriveItem(it.id, it.name) }
                withContext(Dispatchers.Main) {
                    currentChildren = folders
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(currentFolder) { loadChildren(currentFolder.id) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(500.dp),
            backgroundColor = Color(0xFF1E1E1E)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folderStack.size > 1) {
                        IconButton(onClick = { folderStack = folderStack.dropLast(1) }) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                        }
                    } else {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFFBB86FC), modifier = Modifier.padding(12.dp))
                    }
                    Text(text = currentFolder.name, style = MaterialTheme.typography.h6, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                }
                Divider(color = Color.Gray, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color(0xFFBB86FC))
                    } else {
                        LazyColumn {
                            if (currentChildren.isEmpty()) item { Text("Thư mục trống", color = Color.Gray, modifier = Modifier.padding(16.dp)) }
                            items(currentChildren) { item ->
                                Row(modifier = Modifier.fillMaxWidth().clickable { folderStack = folderStack + item }.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FolderOpen, null, tint = Color(0xFF03DAC6))
                                    Spacer(Modifier.width(16.dp))
                                    Text(item.name, color = Color.White, fontSize = 16.sp)
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, null, tint = Color.DarkGray)
                                }
                                Divider(color = Color(0xFF333333))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Hủy", color = Color.Gray) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onFolderSelected(currentFolder) }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFBB86FC))) { Text("Chọn", color = Color.White) }
                }
            }
        }
    }
}
package com.obsidiansync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

data class DrivePath(val id: String, val name: String)

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var settingsManager: SettingsManager

    private var driveService by mutableStateOf<Drive?>(null)
    private var logText by mutableStateOf("Đang khởi tạo...")
    private var isSyncing by mutableStateOf(false)

    // Các state UI được khôi phục từ settingsManager
    private var selectedLocalFolderUri by mutableStateOf<Uri?>(null)
    private var selectedDriveFolderId by mutableStateOf<String?>(null)
    private var selectedDriveFolderName by mutableStateOf("Chưa chọn Drive")

    private var showDrivePicker by mutableStateOf(false)
    private var driveFoldersList by mutableStateOf<List<File>>(emptyList())

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                task.getResult(ApiException::class.java)?.let {
                    settingsManager.accountEmail = it.email
                    initializeDriveService(it)
                }
            } catch (e: Exception) { logText = "Lỗi đăng nhập: ${e.message}" }
        }
    }

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedLocalFolderUri = it
            settingsManager.localFolderUri = it // LƯU LẠI
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = AppDatabase.getDatabase(this)
        settingsManager = SettingsManager(this)

        // Khôi phục folder cũ từ bộ nhớ
        selectedLocalFolderUri = settingsManager.localFolderUri
        selectedDriveFolderId = settingsManager.driveFolderId
        selectedDriveFolderName = settingsManager.driveFolderName ?: "Chưa chọn Drive"

        setContent {
            MaterialTheme(colors = darkColors(primary = Color(0xFFBB86FC), secondary = Color(0xFF03DAC6))) {
                Surface(color = Color(0xFF121212)) { MainScreen() }
            }
        }

        checkSilentSignIn()
    }

    private fun checkSilentSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)

        googleSignInClient.silentSignIn().addOnCompleteListener { task ->
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    initializeDriveService(account)
                } else {
                    logText = "Sẵn sàng (Yêu cầu đăng nhập)"
                }
            } catch (e: Exception) {
                logText = "Vui lòng đăng nhập lại"
            }
        }
    }

    private fun initializeDriveService(acc: GoogleSignInAccount) {
        val cred = GoogleAccountCredential.usingOAuth2(this, Collections.singleton(DriveScopes.DRIVE)).apply {
            selectedAccount = acc.account
        }
        driveService = Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), cred)
            .setApplicationName("ObsidSync")
            .build()
        logText = "Đã kết nối: ${acc.email}"
    }

    // --- LOGIC SYNC (Giữ nguyên từ code cũ của bạn) ---
    private fun runMirror(isUpload: Boolean) {
        val localUri = selectedLocalFolderUri ?: return
        val driveFolderId = selectedDriveFolderId ?: return
        val service = driveService ?: return
        isSyncing = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, localUri) ?: return@launch
                val localMap = mutableMapOf<String, DocumentFile>()
                getLocalFilesRecursive(rootDoc, "", localMap)

                val driveMap = mutableMapOf<String, File>()
                getDriveFilesRecursive(driveFolderId, "", driveMap)
                val dbFilesMap = database.syncDao().getAllFiles().associateBy { it.localPath }

                if (isUpload) {
                    localMap.forEach { (relPath, localDoc) ->
                        val driveFile = driveMap[relPath]
                        if (!localDoc.isDirectory) {
                            val db = dbFilesMap[relPath]
                            if (driveFile == null || db == null || localDoc.length() != db.lastFileSize || localDoc.lastModified() != db.lastModifiedLocal) {
                                withContext(Dispatchers.Main) { logText = "⬆️ Upload: $relPath" }
                                val parentId = ensureDrivePathExists(relPath.substringBeforeLast("/", ""), driveFolderId)
                                uploadFileByDeleteAndCreate(localDoc, relPath, parentId, driveFile?.id)
                            }
                        } else if (driveFile == null) {
                            ensureDrivePathExists(relPath, driveFolderId)
                        }
                    }
                    driveMap.forEach { (relPath, driveFile) ->
                        if (!localMap.containsKey(relPath)) {
                            withContext(Dispatchers.Main) { logText = "🗑️ Xóa Drive: $relPath" }
                            service.files().delete(driveFile.id).execute()
                            database.syncDao().deleteByPath(relPath)
                        }
                    }
                } else {
                    driveMap.forEach { (relPath, driveFile) ->
                        val localDoc = localMap[relPath]
                        if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                            val db = dbFilesMap[relPath]
                            if (localDoc == null || db == null || driveFile.md5Checksum != db.fileHash) {
                                withContext(Dispatchers.Main) { logText = "⬇️ Download: $relPath" }
                                val parentDoc = ensureLocalPathExists(relPath.substringBeforeLast("/", ""), rootDoc)
                                downloadFileByDeleteAndCreate(driveFile, relPath, parentDoc)
                            }
                        } else if (localDoc == null) {
                            ensureLocalPathExists(relPath, rootDoc)
                        }
                    }
                    localMap.forEach { (relPath, localDoc) ->
                        if (!driveMap.containsKey(relPath)) {
                            withContext(Dispatchers.Main) { logText = "🗑️ Xóa Local: $relPath" }
                            localDoc.delete()
                            database.syncDao().deleteByPath(relPath)
                        }
                    }
                }
                withContext(Dispatchers.Main) { logText = "✅ Hoàn tất!"; isSyncing = false }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { logText = "❌ Lỗi: ${e.message}"; isSyncing = false }
            }
        }
    }

    // --- Helper Methods ---
    private fun getLocalFilesRecursive(dir: DocumentFile, rel: String, map: MutableMap<String, DocumentFile>) {
        dir.listFiles().forEach { file ->
            val path = if (rel.isEmpty()) file.name!! else "$rel/${file.name}"
            map[path] = file
            if (file.isDirectory) getLocalFilesRecursive(file, path, map)
        }
    }

    private fun getDriveFilesRecursive(pid: String, rel: String, map: MutableMap<String, File>) {
        var pageToken: String? = null
        do {
            val res = driveService?.files()?.list()?.setQ("'$pid' in parents and trashed = false")
                ?.setFields("nextPageToken, files(id, name, mimeType, md5Checksum)")?.setPageToken(pageToken)?.execute()
            res?.files?.forEach { file ->
                val path = if (rel.isEmpty()) file.name else "$rel/${file.name}"
                map[path] = file
                if (file.mimeType == "application/vnd.google-apps.folder") getDriveFilesRecursive(file.id, path, map)
            }
            pageToken = res?.nextPageToken
        } while (pageToken != null)
    }

    private fun ensureDrivePathExists(rel: String, root: String): String {
        if (rel.isEmpty()) return root
        var currentId = root
        rel.split("/").forEach { segment ->
            val q = "'$currentId' in parents and name = '$segment' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val existing = driveService?.files()?.list()?.setQ(q)?.execute()?.files?.firstOrNull()
            currentId = existing?.id ?: driveService?.files()?.create(File().apply { name = segment; mimeType = "application/vnd.google-apps.folder"; parents = listOf(currentId) })?.setFields("id")?.execute()?.id!!
        }
        return currentId
    }

    private fun ensureLocalPathExists(rel: String, root: DocumentFile): DocumentFile {
        var current = root
        if (rel.isNotEmpty()) rel.split("/").forEach { current = current.findFile(it) ?: current.createDirectory(it)!! }
        return current
    }

    private suspend fun uploadFileByDeleteAndCreate(local: DocumentFile, path: String, pid: String, oldId: String?) {
        oldId?.let { try { driveService?.files()?.delete(it)?.execute() } catch (e: Exception) {} }
        val content = InputStreamContent(null, contentResolver.openInputStream(local.uri))
        val new = driveService?.files()?.create(File().apply { name = local.name; parents = listOf(pid) }, content)?.setFields("id, md5Checksum")?.execute()
        database.syncDao().insertFile(SyncFile(path, new?.id!!, local.lastModified(), local.length(), new.md5Checksum ?: ""))
    }

    private suspend fun downloadFileByDeleteAndCreate(driveFile: File, path: String, parent: DocumentFile) {
        parent.findFile(driveFile.name)?.delete()
        val new = parent.createFile("application/octet-stream", driveFile.name)!!
        driveService?.files()?.get(driveFile.id)?.executeMediaAsInputStream()?.use { input ->
            contentResolver.openOutputStream(new.uri)?.use { input.copyTo(it) }
        }
        database.syncDao().insertFile(SyncFile(path, driveFile.id, new.lastModified(), new.length(), driveFile.md5Checksum ?: ""))
    }

    // --- UI COMPONENTS ---
    @Composable
    fun MainScreen() {
        Scaffold(
            topBar = { TopAppBar(title = { Text("ObsidSync Lite", fontWeight = FontWeight.Bold) }, elevation = 8.dp) }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                SyncCard(title = "Kết nối") {
                    StatusRow(Icons.Default.AccountCircle, if (driveService != null) "Đã đăng nhập" else "Chưa đăng nhập", driveService != null) { requestSignIn() }
                    StatusRow(Icons.Default.Storage, selectedDriveFolderName, selectedDriveFolderId != null) { fetchDriveFolders("root") }
                    StatusRow(Icons.Default.FolderOpen, selectedLocalFolderUri?.lastPathSegment ?: "Chọn thư mục máy", selectedLocalFolderUri != null) { openFolderLauncher.launch(null) }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runMirror(true) }, enabled = !isSyncing && driveService != null && selectedLocalFolderUri != null && selectedDriveFolderId != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.CloudUpload, null)
                        Spacer(Modifier.width(4.dp))
                        Text("UPLOAD")
                    }
                    Button(onClick = { runMirror(false) }, enabled = !isSyncing && driveService != null && selectedLocalFolderUri != null && selectedDriveFolderId != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary)) {
                        Icon(Icons.Default.CloudDownload, null)
                        Spacer(Modifier.width(4.dp))
                        Text("DOWNLOAD")
                    }
                }

                if (isSyncing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))

                Spacer(modifier = Modifier.height(16.dp))

                Text("NHẬT KÝ", style = MaterialTheme.typography.overline, color = Color.Gray)
                Card(modifier = Modifier.fillMaxWidth().weight(1f), backgroundColor = Color(0xFF1E1E1E), shape = RoundedCornerShape(4.dp)) {
                    Box(modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                        Text(logText, color = Color(0xFF00FF00), fontSize = 12.sp, style = MaterialTheme.typography.body2)
                    }
                }
            }
        }
        if (showDrivePicker) DriveFolderPickerDialog()
    }

    @Composable
    fun SyncCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(elevation = 4.dp, shape = RoundedCornerShape(12.dp), backgroundColor = Color(0xFF252525)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary)
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }

    @Composable
    fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, isOk: Boolean, onClick: () -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (isOk) Color.Green else Color.Gray, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = Color.DarkGray)
        }
    }

    @Composable
    fun DriveFolderPickerDialog() {
        Dialog(onDismissRequest = { showDrivePicker = false }) {
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxHeight(0.7f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Duyệt Drive", style = MaterialTheme.typography.h6)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(driveFoldersList) { folder ->
                            Text("📁 ${folder.name}", modifier = Modifier.fillMaxWidth().clickable {
                                // Cập nhật state UI
                                selectedDriveFolderId = folder.id
                                selectedDriveFolderName = folder.name
                                // LƯU VÀO SETTINGS
                                settingsManager.driveFolderId = folder.id
                                settingsManager.driveFolderName = folder.name
                                fetchDriveFolders(folder.id)
                            }.padding(12.dp))
                        }
                    }
                    Button(onClick = { showDrivePicker = false }, modifier = Modifier.align(Alignment.End)) { Text("XÁC NHẬN") }
                }
            }
        }
    }

    private fun requestSignIn() {
        val opt = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(Scope(DriveScopes.DRIVE)).build()
        signInLauncher.launch(GoogleSignIn.getClient(this, opt).signInIntent)
    }

    private fun fetchDriveFolders(pid: String) {
        showDrivePicker = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val res = driveService?.files()?.list()?.setQ("'$pid' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false")?.setFields("files(id, name)")?.execute()
                withContext(Dispatchers.Main) { driveFoldersList = res?.files ?: emptyList() }
            } catch (e: Exception) {}
        }
    }
}
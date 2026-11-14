package com.hazbu.xcam.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.MediaConverter
import com.hazbu.xcam.utils.SystemUtils

class MainActivity : ComponentActivity() {

    private var service: BypassService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? BypassService)?.apply { /* direct access */ }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                copyMediaToInternal(uri)
                selectedMediaPath = uri.path ?: ""
            }
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Notification permission required", Toast.LENGTH_SHORT).show()
    }

    private var selectedMediaPath by mutableStateOf<String?>(null)
    private var mediaFileName by mutableStateOf<String?>(null)
    private var isMirrored by mutableStateOf(false)
    private var rotationAngle by mutableStateOf(0)
    private var selectedAppPackage by mutableStateOf<String?>(null)
    private var isInjecting by mutableStateOf(false)
    private var pipelineRoute by mutableStateOf("")
    private var appList by mutableStateOf<List<AppInfo>>(emptyList())
    private var isLoadingApps by mutableStateOf(false)
    private var xposedActive by mutableStateOf(false)
    private var notificationPermissionGranted by mutableStateOf(false)

    private fun copyMediaToInternal(uri: Uri) {
        try {
            val fileName = "virtual.${uri.path?.substringAfterLast('.') ?: "mp4"}"
            contentResolver.openInputStream(uri)?.use { input ->
                val dest = filesDir.resolve(fileName)
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            mediaFileName = fileName
            Toast.makeText(this, "Media loaded: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load media: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!notificationPermissionGranted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Check if Xposed is active
        xposedActive = checkXposedActive()

        // Load camera-using apps
        loadCameraApps()

        setContent {
            xCamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        appList = appList,
                        isLoadingApps = isLoadingApps,
                        selectedAppPackage = selectedAppPackage,
                        setSelectedAppPackage = { selectedAppPackage = it },
                        selectedMediaPath = selectedMediaPath,
                        mediaFileName = mediaFileName,
                        onSelectMedia = { openVideoPicker() },
                        isMirrored = isMirrored,
                        onToggleMirror = { isMirrored = !isMirrored },
                        rotationAngle = rotationAngle,
                        onRotateLeft = { rotationAngle = (rotationAngle - 90) % 360 },
                        onRotateRight = { rotationAngle = (rotationAngle + 90) % 360 },
                        isInjecting = isInjecting,
                        onToggleInjection = { toggleInjection() },
                        pipelineRoute = pipelineRoute,
                        xposedActive = xposedActive,
                        onClearPipeline = { pipelineRoute = "" }
                    )
                }
            }
        }
    }

    private fun checkXposedActive(): Boolean {
        return try {
            val intent = Intent("io.github.libxposed.IRemoteService")
            intent.setPackage("io.github.libxposed.posabeta")
            val services = packageManager.queryIntentServices(intent, 0)
            services.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun loadCameraApps() {
        isLoadingApps = true
        try {
            val cameraApps = try {
                packageManager.queryIntentActivities(
                    Intent("android.hardware.camera.action.CAPTURE"),
                    PackageManager.MATCH_ALL
                ).map { it.activityInfo.packageName }.toSet()
            } catch (_: Exception) { setOf() }

            // Also query for apps that use Camera2
            val camera2Apps = try {
                packageManager.queryIntentActivities(
                    Intent("android.hardware.camera2"),
                    PackageManager.MATCH_ALL
                ).map { it.activityInfo.packageName }.toSet()
            } catch (_: Exception) { setOf() }

            // Get all packages that use CAMERA permission
            val allPackages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { pkg ->
                    pkg.packageName != packageName &&
                    !pkg.packageName.startsWith("com.hazbu.") &&
                    !pkg.packageName.startsWith("io.github.libxposed") &&
                    pkg.applicationInfo?.let { ai -> ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 } == true &&
                    (pkg.requestedPermissions?.any {
                        it == Manifest.permission.CAMERA ||
                        it == "android.permission.CAMERA"
                    } == true)
                }
                .mapNotNull { pkg ->
                    pkg.applicationInfo?.let { ai ->
                        val label = packageManager.getResourcesForApplication(ai)?.getString(ai.labelRes) ?: ""
                        pkg to label
                    }
                }
                .filter { (_, label) -> label.isNotEmpty() }

            appList = allPackages
                .filter { (pkg, _) -> cameraApps.contains(pkg.packageName) || camera2Apps.contains(pkg.packageName) }
                .map { (pkg, label) -> AppInfo(pkg.packageName, label as String) }
                .sortedBy { it.name }

            // Add some common KYC apps manually
            val commonApps = listOf(
                "com.hyperverge.recorder" to "Hyperverge KYC",
                "com.veriff.client" to "Veriff",
                "com.onfido.platform" to "Onfido",
                "com.jumio.sdk" to "Jumio",
                "com.trulogic.mverify" to "Mverify",
                "com.synapse" to "Synapse",
                "com.yoti" to "Yoti",
                "com.truslope.ds" to "DataScan",
                "com.shere" to "Shere",
                "com.idealab" to "Idealab",
            )
            for ((pkg, name) in commonApps) {
                if (!appList.any { it.packageName == pkg }) {
                    appList = appList + AppInfo(pkg, name)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading apps: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        isLoadingApps = false
    }

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        videoPickerLauncher.launch(intent)
    }

    private fun toggleInjection() {
        if (selectedAppPackage == null || selectedMediaPath == null) {
            Toast.makeText(this, "Select an app and media first", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, BypassService::class.java).apply {
            action = if (isInjecting) "ACTION_STOP" else "ACTION_START"
            putExtra("TARGET_PACKAGE", selectedAppPackage)
            putExtra("MEDIA_PATH", selectedMediaPath)
            putExtra("MIRRORED", isMirrored)
            putExtra("ROTATION", rotationAngle)
        }

        startService(intent)
        isInjecting = !isInjecting
        Toast.makeText(
            this,
            if (isInjecting) "Injecting into ${selectedAppPackage}"
            else "Injection stopped",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onResume() {
        super.onResume()
        // Check Xposed status periodically
        xposedActive = checkXposedActive()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}
    }
}

// Data class
data class AppInfo(
    val packageName: String,
    val name: String,
    val icon: android.graphics.Bitmap? = null
)

// Theme
@Suppress("unused")
@Composable
private fun xCamTheme(content: @Composable () -> Unit) {
    val colorScheme = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF6750A4),
        secondary = Color(0xFF625B71),
        tertiary = Color(0xFF7D5260),
        background = Color(0xFF1C1B1F),
        surface = Color(0xFF1C1B1F),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onTertiary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White,
    )
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

// Main UI Screen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    appList: List<AppInfo>,
    isLoadingApps: Boolean,
    selectedAppPackage: String?,
    setSelectedAppPackage: (String?) -> Unit,
    selectedMediaPath: String?,
    mediaFileName: String?,
    onSelectMedia: () -> Unit,
    isMirrored: Boolean,
    onToggleMirror: () -> Unit,
    rotationAngle: Int,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    isInjecting: Boolean,
    onToggleInjection: () -> Unit,
    pipelineRoute: String,
    xposedActive: Boolean,
    onClearPipeline: () -> Unit,
) {
    var showAppPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "xCam Bypass",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                actions = {
                    if (pipelineRoute.isNotEmpty()) {
                        IconButton(onClick = onClearPipeline) {
                            Icon(Icons.Default.Clear, "Clear pipeline", tint = Color(0xFFB39DDB))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status bar
            StatusCard(xposedActive = xposedActive)

            // Media selection
            MediaCard(
                selectedMediaPath = selectedMediaPath,
                mediaFileName = mediaFileName,
                onSelectMedia = onSelectMedia
            )

            // App selection
            AppSelectionCard(
                isLoading = isLoadingApps,
                showAppPicker = showAppPicker,
                onShowAppPicker = { showAppPicker = true },
                selectedAppPackage = selectedAppPackage,
                appList = appList,
                setSelectedAppPackage = { pkg ->
                    setSelectedAppPackage(pkg)
                    showAppPicker = false
                }
            )

            // Transform controls
            TransformCard(
                isMirrored = isMirrored,
                onToggleMirror = onToggleMirror,
                rotationAngle = rotationAngle,
                onRotateLeft = onRotateRight,
                onRotateRight = onRotateLeft
            )

            // Pipeline monitor
            PipelineCard(
                route = pipelineRoute,
                isInjecting = isInjecting
            )

            // Inject button
            InjectButton(
                isInjecting = isInjecting,
                onToggle = onToggleInjection,
                hasApp = selectedAppPackage != null,
                hasMedia = selectedMediaPath != null
            )
        }
    }
}

@Composable
fun StatusCard(xposedActive: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (xposedActive) Color(0xFF1B5E20) else Color(0xFF4A148C)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (xposedActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFB39DDB)
            )
            Column {
                Text(
                    text = if (xposedActive) "Xposed/LSPosed: ACTIVE"
                    else "Xposed/LSPosed: NOT DETECTED",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB39DDB),
                    fontSize = 13.sp
                )
                Text(
                    text = if (xposedActive) "Module will inject into target apps"
                    else "Install LSPosed or patch with LSPatch first",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun MediaCard(
    selectedMediaPath: String?,
    mediaFileName: String?,
    onSelectMedia: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2B33))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "📹 VIRTUAL MEDIA",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB39DDB),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (selectedMediaPath != null && mediaFileName != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✓ $mediaFileName",
                        color = Color(0xFF81C784),
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Tap to change",
                        color = Color(0xFFB39DDB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onSelectMedia,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, "Add media", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Video/Image", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AppSelectionCard(
    isLoading: Boolean,
    showAppPicker: Boolean,
    onShowAppPicker: () -> Unit,
    selectedAppPackage: String?,
    appList: List<AppInfo>,
    setSelectedAppPackage: (String?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2B33)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "📱 TARGET APP",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB39DDB),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (selectedAppPackage != null) {
                val appName = getAppName(LocalContext.current, selectedAppPackage)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "✓ $appName",
                            color = Color(0xFF81C784),
                            fontSize = 13.sp
                        )
                        Text(
                            text = selectedAppPackage,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = "Change",
                        color = Color(0xFFB39DDB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onShowAppPicker,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Apps, "Select app", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Target App", color = Color.White)
                }
            }
        }
    }

    // App picker dialog
    if (showAppPicker) {
        AlertDialog(
            onDismissRequest = { onShowAppPicker() },
            title = { Text("Select Target App", fontWeight = FontWeight.Bold) },
            text = {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(appList) { appItem ->
                            AppRow(
                                app = appItem,
                                onSelect = {
                                    setSelectedAppPackage(appItem.packageName)
                                    onShowAppPicker()
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onShowAppPicker() }) {
                    Text("Cancel", color = Color(0xFFB39DDB))
                }
            }
        )
    }
}

@Composable
fun AppRow(app: AppInfo, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = app.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun TransformCard(
    isMirrored: Boolean,
    onToggleMirror: () -> Unit,
    rotationAngle: Int,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2B33)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "🔄 TRANSFORMS",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB39DDB),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rotate left
                FilledTonalButton(
                    onClick = onRotateLeft,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A148C))
                ) {
                    Icon(Icons.Default.RotateLeft, "Rotate left", tint = Color.White)
                    Text("Left", color = Color.White, fontSize = 12.sp)
                }

                // Rotation display
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "$rotationAngle°",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB39DDB)
                    )
                    Text(
                        text = "ROTATION",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }

                // Rotate right
                FilledTonalButton(
                    onClick = onRotateRight,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A148C))
                ) {
                    Icon(Icons.Default.RotateRight, "Rotate right", tint = Color.White)
                    Text("Right", color = Color.White, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mirror toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Horizontal Mirror", color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = isMirrored,
                    onCheckedChange = { onToggleMirror() }
                )
            }
        }
    }
}

@Composable
fun PipelineCard(route: String, isInjecting: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isInjecting) Color(0xFF1B5E20) else Color(0xFF2D2B33)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📊 PIPELINE MONITOR",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB39DDB),
                    fontSize = 14.sp
                )
                if (isInjecting) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF81C784))
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (route.isNotEmpty()) {
                Text(
                    text = route,
                    color = Color(0xFF81C784),
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            } else {
                Text(
                    text = if (isInjecting) "Waiting for pipeline nodes..."
                    else "No pipeline data",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun InjectButton(
    isInjecting: Boolean,
    onToggle: () -> Unit,
    hasApp: Boolean,
    hasMedia: Boolean
) {
    Button(
        onClick = onToggle,
        enabled = hasApp && hasMedia && !isInjecting,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isInjecting) Color(0xFFD32F2F) else Color(0xFF6750A4),
            disabledContainerColor = Color(0xFF4A4655)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Icon(
            if (isInjecting) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Color.White
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = if (isInjecting) "STOP INJECTION" else "START INJECTION",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

// Helper
private fun getAppName(context: android.content.Context, packageName: String): String {
    return try {
        val ai = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getResourcesForApplication(ai)?.
            getString(ai.labelRes) ?: packageName
    } catch (_: Exception) {
        packageName
    }
}

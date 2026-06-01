package com.example

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.service.ScreenStateService
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.ControlManager
import com.example.utils.PrefsManager
import com.example.utils.ShellUtils
import com.example.widget.QuickControlWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize torch listener to keep flashlight sync live
        ControlManager.initTorchListener(this)

        // Ensure background service is running if enabled in settings
        val isAutoData = PrefsManager.isAutoDataToggleEnabled(this)
        val isFlashlightCtrl = PrefsManager.isFlashlightPowerControlEnabled(this)
        val isUsb5g = PrefsManager.isUsb5gToggleEnabled(this)
        if (isAutoData || isFlashlightCtrl || isUsb5g) {
            val serviceIntent = Intent(this, ScreenStateService::class.java)
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to auto-start ScreenStateService: ${e.message}")
            }
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFFF3F4F9) // Set background color as per design
                ) { innerPadding ->
                    ControlPanelScreen(
                        modifier = Modifier.padding(innerPadding),
                        activity = this
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Trigger widget refreshing and physical states when app resumes focus
        refreshAllWidgets()
    }

    fun refreshAllWidgets() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val thisWidget = ComponentName(this, QuickControlWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
        val intent = Intent(this, QuickControlWidget::class.java).apply {
            action = QuickControlWidget.ACTION_REFRESH_WIDGET
        }
        sendBroadcast(intent)
    }
}

@Composable
fun ControlPanelScreen(modifier: Modifier = Modifier, activity: MainActivity) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Root & Live Hardware States
    var isRootAvailable by remember { mutableStateOf(false) }
    var checkingRoot by remember { mutableStateOf(false) }

    var isDataEnabled by remember { mutableStateOf(false) }
    var isWifiEnabled by remember { mutableStateOf(false) }
    var isGpsEnabled by remember { mutableStateOf(false) }
    var isFlashlightEnabled by remember { mutableStateOf(false) }
    var currentStrategy by remember { mutableStateOf(PrefsManager.getWorkingStrategy(context)) }

    // Live SIM card list
    var simCardList by remember { mutableStateOf(emptyList<com.example.utils.SimInfo>()) }

    // Timestamps for toggling events to enforce an optimistic state transition lock
    var lastDataToggleTime by remember { mutableStateOf(0L) }
    var lastWifiToggleTime by remember { mutableStateOf(0L) }
    var lastGpsToggleTime by remember { mutableStateOf(0L) }
    var lastFlashlightToggleTime by remember { mutableStateOf(0L) }

    // 2. Local Customization Preferences
    var autoDataToggle by remember { mutableStateOf(PrefsManager.isAutoDataToggleEnabled(context)) }
    var flashlightPowerControl by remember { mutableStateOf(PrefsManager.isFlashlightPowerControlEnabled(context)) }
    var usb5gToggle by remember { mutableStateOf(PrefsManager.isUsb5gToggleEnabled(context)) }
    val is5GSupportedDevice = remember { ControlManager.is5GSupported(context) }
    var activeColorHex by remember { mutableStateOf(PrefsManager.getActiveColor(context)) }
    var inactiveColorHex by remember { mutableStateOf(PrefsManager.getInactiveColor(context)) }

    fun refreshSimList() {
        coroutineScope.launch(Dispatchers.IO) {
            val list = ControlManager.getSimCardList(context)
            withContext(Dispatchers.Main) {
                simCardList = list
            }
        }
    }

    // 3. Permission launcher to satisfy fine location, camera & phone state needs
    val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        Log.d("MainActivity", "Bento permissions granted: Location=$fineLocationGranted, Camera=$cameraGranted, PhoneState=$phoneStateGranted")
        if (phoneStateGranted) {
            refreshSimList()
        }
    }

    // Palette Options
    val activeColorOptions = listOf(
        "#FF00E676" to "荧光绿",
        "#FF29B6F6" to "科技蓝",
        "#FFFF7043" to "珊瑚橙",
        "#FFFFD700" to "曜石金",
        "#FFAB47BC" to "魅力紫"
    )

    val inactiveColorOptions = listOf(
        "#FF757575" to "深邃灰",
        "#FFB0BEC5" to "冰川蓝灰",
        "#FF37474F" to "暗礁黑"
    )

    // Syncing probe with transition locks
    fun probeHardwareStates() {
        val now = System.currentTimeMillis()
        if (now - lastDataToggleTime > 2500) {
            isDataEnabled = ControlManager.isMobileDataEnabled(context)
        }
        if (now - lastWifiToggleTime > 2500) {
            isWifiEnabled = ControlManager.isWifiEnabled(context)
        }
        if (now - lastGpsToggleTime > 2500) {
            isGpsEnabled = ControlManager.isGpsEnabled(context)
        }
        if (now - lastFlashlightToggleTime > 2500) {
            isFlashlightEnabled = ControlManager.isFlashlightEnabled()
        }
        currentStrategy = PrefsManager.getWorkingStrategy(context)
    }

    // Launch probe & root check on layout start
    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
        checkingRoot = true
        coroutineScope.launch(Dispatchers.IO) {
            val rootOk = ShellUtils.isRootAvailable()
            withContext(Dispatchers.Main) {
                isRootAvailable = rootOk
                checkingRoot = false
            }
        }
        probeHardwareStates()
        refreshSimList()
    }

    // 2-second dynamic tick polling to guarantee hardware sync
    LaunchedEffect(Unit) {
        while (true) {
            probeHardwareStates()
            refreshSimList()
            delay(2000)
        }
    }

    // Color definitions corresponding to the Bento theme HTML
    val bentoBg = Color(0xFFF3F4F9)
    val bentoTextDark = Color(0xFF1B1B1F)
    val bentoTextSecondary = Color(0xFF44474E)
    val bentoBorderColor = Color(0xFFDEE2EB)
    
    val activeColor = Color(android.graphics.Color.parseColor(activeColorHex))
    val inactiveColor = Color(android.graphics.Color.parseColor(inactiveColorHex))

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(bentoBg)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {

        // 1. Bento Header Block
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "System Control",
                        color = bentoTextDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Widget Management",
                        color = bentoTextSecondary,
                        fontSize = 14.sp
                    )
                }

                // Dynamic Root status tag
                val tagBg = if (isRootAvailable) Color(0xFFE1E2EC) else Color(0xFFFFE0B2)
                val dotColor = if (isRootAvailable) Color(0xFF2EBD59) else Color(0xFFFF9100)
                val tagText = if (isRootAvailable) "ROOT ACTIVE" else "ROOT NEEDED"

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tagBg)
                        .clickable {
                            if (!isRootAvailable) {
                                checkingRoot = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    val rootOk = ShellUtils.isRootAvailable()
                                    withContext(Dispatchers.Main) {
                                        isRootAvailable = rootOk
                                        checkingRoot = false
                                        if (rootOk) {
                                            Toast.makeText(context, "Root 授权成功！", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "未获取到 Root，请在 Magisk 授权", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tagText,
                        color = bentoTextDark,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        // 2. Widget Preview Card (Bento Rounded White Card)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White)
                    .border(BorderStroke(1.dp, bentoBorderColor), RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "WIDGET PREVIEW",
                            color = Color(0xFF74777F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFD1E4FF))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "4x1 Layout",
                                color = Color(0xFF001D36),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Interactive 4x1 Widget Bar Mirror
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Data Column
                        PreviewBentoMiniTile(
                            name = if (isDataEnabled) "Data On" else "Data Off",
                            isOn = isDataEnabled,
                            iconRes = R.drawable.ic_cellular_data,
                            activeColor = activeColor,
                            inactiveColor = inactiveColor,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val target = !isDataEnabled
                                isDataEnabled = target
                                lastDataToggleTime = System.currentTimeMillis()
                                coroutineScope.launch(Dispatchers.IO) {
                                    val result = ControlManager.setMobileDataEnabled(context, target)
                                    withContext(Dispatchers.Main) {
                                        if (!result) {
                                            isDataEnabled = !target
                                            if (target) {
                                                Toast.makeText(context, "控制失败，流量控制需要Root权限授权", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        probeHardwareStates()
                                        activity.refreshAllWidgets()
                                    }
                                }
                            }
                        )

                        // WiFi Column
                        PreviewBentoMiniTile(
                            name = if (isWifiEnabled) "WiFi On" else "WiFi Off",
                            isOn = isWifiEnabled,
                            iconRes = R.drawable.ic_wifi,
                            activeColor = activeColor,
                            inactiveColor = inactiveColor,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val target = !isWifiEnabled
                                isWifiEnabled = target
                                lastWifiToggleTime = System.currentTimeMillis()
                                coroutineScope.launch(Dispatchers.IO) {
                                    val result = ControlManager.setWifiEnabled(context, target)
                                    withContext(Dispatchers.Main) {
                                        if (!result) {
                                            isWifiEnabled = !target
                                            Toast.makeText(context, "控制失败，Wi-Fi控制需要Root权限授权", Toast.LENGTH_SHORT).show()
                                        }
                                        probeHardwareStates()
                                        activity.refreshAllWidgets()
                                    }
                                }
                            }
                        )

                        // GPS Column
                        PreviewBentoMiniTile(
                            name = if (isGpsEnabled) "GPS On" else "GPS Off",
                            isOn = isGpsEnabled,
                            iconRes = R.drawable.ic_gps,
                            activeColor = activeColor,
                            inactiveColor = inactiveColor,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val target = !isGpsEnabled
                                isGpsEnabled = target
                                lastGpsToggleTime = System.currentTimeMillis()
                                coroutineScope.launch(Dispatchers.IO) {
                                    val result = ControlManager.setGpsEnabled(context, target)
                                    withContext(Dispatchers.Main) {
                                        if (!result) {
                                            isGpsEnabled = !target
                                            Toast.makeText(context, "控制失败，GPS开关控制需要Root权限授权", Toast.LENGTH_SHORT).show()
                                        }
                                        probeHardwareStates()
                                        activity.refreshAllWidgets()
                                    }
                                }
                            }
                        )

                        // Flashlight Column
                        PreviewBentoMiniTile(
                            name = if (isFlashlightEnabled) "Flash On" else "Flash Off",
                            isOn = isFlashlightEnabled,
                            iconRes = R.drawable.ic_flashlight,
                            activeColor = activeColor,
                            inactiveColor = inactiveColor,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val target = !isFlashlightEnabled
                                isFlashlightEnabled = target
                                lastFlashlightToggleTime = System.currentTimeMillis()
                                coroutineScope.launch(Dispatchers.IO) {
                                    val result = ControlManager.setFlashlightEnabled(context, target)
                                    withContext(Dispatchers.Main) {
                                        if (!result) {
                                            isFlashlightEnabled = !target
                                        }
                                        probeHardwareStates()
                                        activity.refreshAllWidgets()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // 3. Bento Multi-grid Section (Double Cards Row)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Navy Bento Block
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1.15f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF001D36))
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD1E4FF).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🔋", fontSize = 16.sp)
                        }

                        Column {
                            Text(
                                text = "Battery Optimization",
                                color = Color(0xFFD1E4FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Extreme Power-Saving",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Light Bento Block
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1.15f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White)
                        .border(BorderStroke(1.dp, bentoBorderColor), RoundedCornerShape(28.dp))
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(bentoBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚙️", fontSize = 16.sp)
                        }

                        Column {
                            Text(
                                text = "Background Thread",
                                color = bentoTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Low Memory State",
                                color = bentoTextDark,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 4. Full-Width Light Blue Auto-Switch Ribbon Block
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFD1E4FF))
                    .border(BorderStroke(1.dp, Color(0xFFBAC7DB)), RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Auto-Switch Logic",
                            color = Color(0xFF001D36),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "系统会基于当前状态，按照以下自动切换规则执行工作：",
                            color = Color(0xFF001D36).copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        val rules = listOf(
                            "① 息屏自动省电：关屏息屏时，立即自动关闭移动数据，保障设备休眠省电与降温成效。",
                            "② 亮屏延时恢复：亮屏解锁时，引入 0.8 秒 延迟缓冲开启设计，供以舒适直观的确已切断之视觉确认反馈。",
                            "③ USB 连接防护：当 USB 切换/配置为共享网络且跟 PC 数据连接时，息屏后始终维持流量在线，防止调试或热点共享因锁屏而中断网络。",
                            "④ 热点连网设备保活：只要开启无线热点且尚存在任何外联接入设备，就强制维持流量在线永不断线。"
                        )
                        rules.forEach { rule ->
                            Text(
                                text = rule,
                                color = Color(0xFF001D36).copy(alpha = 0.75f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }

                        val strategyText = when (currentStrategy) {
                            0 -> "🎯 最佳匹配：方案 A (svc data)"
                            1 -> "🎯 最佳匹配：方案 B (cmd phone data_enabled)"
                            2 -> "🎯 最佳匹配：方案 C (cmd phone)"
                            else -> "🔍 方案自适应中 (首次触发切换后自动锁定)"
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strategyText,
                                color = Color(0xFF003366),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (currentStrategy != -1) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "|  重置检测",
                                    color = Color(0xFFBA1A1A),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            PrefsManager.setWorkingStrategy(context, -1)
                                            currentStrategy = -1
                                            Toast.makeText(context, "已重置缓存，下次自动开关时将重新检测匹配", Toast.LENGTH_SHORT).show()
                                        }
                                )
                            }
                        }
                    }

                    Switch(
                        checked = autoDataToggle,
                        onCheckedChange = { checked ->
                            autoDataToggle = checked
                            PrefsManager.setAutoDataToggleEnabled(context, checked)

                            val serviceIntent = Intent(context, ScreenStateService::class.java)
                            if (checked) {
                                ContextCompat.startForegroundService(context, serviceIntent)
                                Toast.makeText(context, "熄屏/亮屏自动流量切换已启用", Toast.LENGTH_SHORT).show()
                            } else {
                                if (!flashlightPowerControl && !usb5gToggle) {
                                    context.stopService(serviceIntent)
                                    Toast.makeText(context, "自动控制服务已停止，后台服务已关闭", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "自动流量切换已停止，其它自动服务仍保持后台运行", Toast.LENGTH_SHORT).show()
                                }
                            }
                            activity.refreshAllWidgets()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF005AC1),
                            uncheckedThumbColor = Color(0xFF74777F),
                            uncheckedTrackColor = Color(0xFFE1E2EC)
                        )
                    )
                }
            }
        }

        // 4.5. Full-Width Warm Yellow Flashlight Power Control Ribbon Block
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFFFF0C2))
                    .border(BorderStroke(1.dp, Color(0xFFE2C46E)), RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "手电筒智能电源关闭",
                            color = Color(0xFF221B00),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "启用后，如果在手机上打开手电筒后按电源键熄屏。当再次按下电源键时，屏幕不会亮起（保持熄灭），而是只将手电筒关闭，省电且在夜间可以不刺眼地一键关闭手电筒。",
                            color = Color(0xFF221B00).copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }

                    Switch(
                        checked = flashlightPowerControl,
                        onCheckedChange = { checked ->
                            flashlightPowerControl = checked
                            PrefsManager.setFlashlightPowerControlEnabled(context, checked)

                            val serviceIntent = Intent(context, ScreenStateService::class.java)
                            if (checked) {
                                ContextCompat.startForegroundService(context, serviceIntent)
                                Toast.makeText(context, "手电筒按键智能控制已启用", Toast.LENGTH_SHORT).show()
                            } else {
                                if (!autoDataToggle && !usb5gToggle) {
                                    context.stopService(serviceIntent)
                                    Toast.makeText(context, "手电筒控制已停止，后台服务已关闭", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "手电筒控制已停止，其它自动服务仍保持后台运行", Toast.LENGTH_SHORT).show()
                                }
                            }
                            activity.refreshAllWidgets()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF221B00),
                            uncheckedThumbColor = Color(0xFF74777F),
                            uncheckedTrackColor = Color(0xFFE5D5AA)
                        )
                    )
                }
            }
        }

        // 4.6. Full-Width Emerald Green USB Smart 5G Switch ribbon block
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFE8F5E9)) // Light pastel green Scheme
                    .border(BorderStroke(1.dp, Color(0xFFA5D6A7)), RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "USB 外接供电智能 5G/4G 切换",
                            color = Color(0xFF1B5E20),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "在手机流量开启的情况下，如果手机有通过 USB 外接供电，那么就开启 5G 网络，否则就退回 4G 网络（不仅降温防烫，也能在大流量场景和日常省电中智能求取最优解）。",
                            color = Color(0xFF1B5E20).copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (is5GSupportedDevice) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFC8E6C9).copy(alpha = 0.7f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF1B5E20),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "已检测到本机硬件支持 5G 网络规则",
                                    color = Color(0xFF1B5E20),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFFE082).copy(alpha = 0.7f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "未检测到 5G 硬件，已启用 4G 安全降级保护",
                                    color = Color(0xFFE65100),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Switch(
                        checked = usb5gToggle,
                        onCheckedChange = { checked ->
                            usb5gToggle = checked
                            PrefsManager.setUsb5gToggleEnabled(context, checked)

                            val serviceIntent = Intent(context, ScreenStateService::class.java)
                            if (checked) {
                                ContextCompat.startForegroundService(context, serviceIntent)
                                Toast.makeText(context, "USB 供电智能网络切换已启用", Toast.LENGTH_SHORT).show()
                            } else {
                                if (!autoDataToggle && !flashlightPowerControl) {
                                    context.stopService(serviceIntent)
                                    Toast.makeText(context, "自动控制服务已停止，后台服务已关闭", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "USB 供电网络切换已停用，其它自动服务保持运行", Toast.LENGTH_SHORT).show()
                                }
                            }
                            activity.refreshAllWidgets()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF1B5E20),
                            uncheckedThumbColor = Color(0xFF74777F),
                            uncheckedTrackColor = Color(0xFFC8E6C9)
                        )
                    )
                }
            }
        }

        // 4.8. SIM Card Management Bento Card
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White)
                    .border(BorderStroke(1.dp, bentoBorderColor), RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "双卡智能控制 & 主动询问",
                                color = bentoTextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "一键启用/禁用特定的 SIM 卡插槽。在新开启 SIM 卡时，系统自带的默认拨号和短信配置将重置为“每次主动询问”模式（流量不进行询问，自动锁定由原卡默认提供），只有在通话/短信触发时，系统级别的卡槽选择器才会跳出供您主动选择，再次点击即可快速安全关闭。",
                                color = bentoTextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFE8DEF8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎴", fontSize = 20.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (simCardList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(bentoBg)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "未检测到已插入的 SIM 卡",
                                    color = bentoTextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "请确认是否授予了“电话/读取电话状态”权限，或设备已插入卡",
                                    color = bentoTextSecondary.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        permissionLauncher.launch(permissionsToRequest)
                                        refreshSimList()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF001D36)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("重新获取权限/刷新", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    } else {
                        val secondarySim = simCardList.firstOrNull { it.slotIndex > 0 }
                        val mainSim = simCardList.firstOrNull { it.slotIndex == 0 }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            var toggleLoading by remember { mutableStateOf(false) }

                            val isSimInserted = (secondarySim != null)
                            val isSimActive = (secondarySim?.isActive == true)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (isSimActive) Color(0xFFE8F5E9) else bentoBg)
                                    .border(
                                        BorderStroke(
                                            width = 1.dp,
                                            color = if (isSimActive) Color(0xFFC8E6C9) else Color(0xFFE1E2EC)
                                        ),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(if (isSimActive) Color(0xFF2EBD59) else Color(0xFFB0BEC5)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "2",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column {
                                        Text(
                                            text = if (isSimInserted) "副卡 2 (${secondarySim?.displayName ?: "SIM 2"})" else "副卡 2 (Slot 2)",
                                            color = bentoTextDark,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = when {
                                                !isSimInserted -> "检测状态: 未检测到已插卡 (控制开关失效)"
                                                isSimActive -> "运行中 | 系统拨号与短信已配置为主动询问"
                                                else -> "已检测到插卡且未启用 | 按钮生效，点击可一键重载配置并启用"
                                            },
                                            color = bentoTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                if (toggleLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = activeColor,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Switch(
                                        checked = isSimActive,
                                        enabled = isSimInserted,
                                        onCheckedChange = { checked ->
                                            if (secondarySim != null) {
                                                toggleLoading = true
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val success = ControlManager.setSubscriptionEnabled(context, secondarySim.subId, checked)
                                                    
                                                    if (success) {
                                                        if (checked) {
                                                            ControlManager.setDefaultSimsToAsk()
                                                        }
                                                        delay(1500)
                                                    }
                                                    
                                                    withContext(Dispatchers.Main) {
                                                        toggleLoading = false
                                                        refreshSimList()
                                                        if (success) {
                                                            if (checked) {
                                                                Toast.makeText(context, "副卡 ${secondarySim.displayName} 已开启，默认拨号/短信已配置为系统级主动质询询问机制！", Toast.LENGTH_LONG).show()
                                                            } else {
                                                                Toast.makeText(context, "副卡 ${secondarySim.displayName} 已成功休眠关闭！", Toast.LENGTH_SHORT).show()
                                                            }
                                                        } else {
                                                            Toast.makeText(context, "切换状态失败，请确认是否授予 Root/Shell 级配置权限。", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF2EBD59),
                                            uncheckedThumbColor = Color(0xFF74777F),
                                            uncheckedTrackColor = Color(0xFFE1E2EC),
                                            disabledCheckedThumbColor = Color.White.copy(alpha = 0.5f),
                                            disabledCheckedTrackColor = Color(0xFF2EBD59).copy(alpha = 0.4f),
                                            disabledUncheckedThumbColor = Color(0xFF74777F).copy(alpha = 0.5f),
                                            disabledUncheckedTrackColor = Color(0xFFE1E2EC).copy(alpha = 0.4f)
                                        )
                                    )
                                }
                            }

                            // Primary Resident Card Info (Always Active)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFFF3F4F6))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📌", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "主卡 (SIM 1): ${mainSim?.displayName ?: "未检测到或读取中"}",
                                            color = bentoTextDark,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "系统常驻运行状态（默认提供移动数据，免除拨号与短信质询弹窗）",
                                            color = bentoTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            // Dynamic Footer Trigger
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = { refreshSimList() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF001D36)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("点击刷新/扫描卡槽状态", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Theme Customizer Card (Modular Color Pickers)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White)
                    .border(BorderStroke(1.dp, bentoBorderColor), RoundedCornerShape(28.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "小组件磁铁配色定制",
                        color = bentoTextDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "点击实时定制通知栏小磁铁在亮/灭状态下的高亮色，与桌面一键同步。",
                        color = bentoTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Active Swatches
                    Text(
                        text = "打开状态高亮 (Active Color)",
                        color = bentoTextDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        activeColorOptions.forEach { (hex, name) ->
                            val isSelected = activeColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(hex)))
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = Color(0xFF001D36),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        activeColorHex = hex
                                        PrefsManager.setActiveColor(context, hex)
                                        activity.refreshAllWidgets()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Inactive Swatches
                    Text(
                        text = "关闭状态低亮 (Inactive Color)",
                        color = bentoTextDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        inactiveColorOptions.forEach { (hex, name) ->
                            val isSelected = inactiveColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(hex)))
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = Color(0xFF001D36),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        inactiveColorHex = hex
                                        PrefsManager.setInactiveColor(context, hex)
                                        activity.refreshAllWidgets()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Tutorial & Setup Help
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFE1E2EC))
                    .padding(20.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "💡 桌面小组件绑定教程",
                            color = bentoTextDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. 在手机桌面任意空白区域长按并保持，呼出“小部件/Widgets”列表。\n2. 搜索或滑行定位到 “System Control” 组件并拖拽放入手机桌面。\n3. 您可在此页面测试磁铁效果，所有点击触发秒级状态响应，通知栏状态同步刷新！",
                        color = bentoTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PreviewBentoMiniTile(
    name: String,
    isOn: Boolean,
    iconRes: Int,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerBg = if (isOn) activeColor else Color(0xFFE1E2EC)
    val iconColor = if (isOn) Color.White else Color(0xFF5A5F6C)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // High quality rounded tile aspect
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(containerBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = name,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isOn) activeColor else Color(0xFF44474E),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

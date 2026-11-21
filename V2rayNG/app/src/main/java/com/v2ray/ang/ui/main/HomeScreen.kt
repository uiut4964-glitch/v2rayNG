package com.v2ray.ang.ui.main

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// الألوان: خلفية كحلية داكنة، سماوي للأزرار، أخضر عند الاتصال
// ---------------------------------------------------------------------------
private object HomeColors {
    val Bg = Color(0xFF0A0F1C)
    val Card = Color(0xFF131B2E)
    val Card2 = Color(0xFF1B2540)
    val Accent = Color(0xFF22D3EE)
    val Connected = Color(0xFF34D399)
    val Idle = Color(0xFF64748B)
    val Text = Color(0xFFE5E9F5)
    val Muted = Color(0xFF8B97B5)
    val Danger = Color(0xFFF87171)
}

private val HomeColorScheme = darkColorScheme(
    primary = HomeColors.Accent,
    onPrimary = Color(0xFF00212A),
    secondary = HomeColors.Connected,
    background = HomeColors.Bg,
    onBackground = HomeColors.Text,
    surface = HomeColors.Card,
    onSurface = HomeColors.Text,
    surfaceVariant = HomeColors.Card2,
    onSurfaceVariant = HomeColors.Muted,
    outline = HomeColors.Idle,
    error = HomeColors.Danger,
)

private data class HomeServer(val guid: String, val profile: ProfileItem)

private fun loadServers(): List<HomeServer> =
    MmkvManager.decodeAllServerList()
        .distinct()
        .mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { HomeServer(guid, it) }
        }

// ---------------------------------------------------------------------------
// سجل أسماء SNI المستخدمة سابقاً (لاختيارها بضغطة)
// ---------------------------------------------------------------------------
private const val HOME_PREFS = "home_prefs"
private const val KEY_SNI_HISTORY = "sni_history"

private fun loadSniHistory(context: Context): List<String> =
    context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SNI_HISTORY, "")
        .orEmpty()
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun pushSniHistory(context: Context, sni: String) {
    val value = sni.trim()
    if (value.isEmpty()) return
    val list = (listOf(value) + loadSniHistory(context)).distinct().take(8)
    context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SNI_HISTORY, list.joinToString("\n"))
        .apply()
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

private fun serverTitle(profile: ProfileItem): String =
    profile.remarks.ifBlank { profile.server.orEmpty().ifBlank { "بدون اسم" } }

private fun serverSubtitle(profile: ProfileItem): String {
    val security = profile.security.orEmpty()
    val kind = if (security.isNotEmpty()) "${profile.configType.name} / $security" else profile.configType.name
    return "$kind  •  ${profile.getServerAddressAndPort()}"
}

// ---------------------------------------------------------------------------
// عناصر الواجهة
// ---------------------------------------------------------------------------
@Composable
private fun HomeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(HomeColors.Card)
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun ConnectButton(running: Boolean, onClick: () -> Unit) {
    val ring = if (running) HomeColors.Connected else HomeColors.Idle
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(210.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(ring.copy(alpha = 0.28f), Color.Transparent)))
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(HomeColors.Card2)
                .border(4.dp, ring, CircleShape)
        ) {
            Canvas(modifier = Modifier.size(56.dp)) {
                val w = size.width
                val h = size.height
                val strokeWidth = 6.dp.toPx()
                drawArc(
                    color = ring,
                    startAngle = -60f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(w * 0.08f, h * 0.16f),
                    size = Size(w * 0.84f, h * 0.84f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawLine(
                    color = ring,
                    start = Offset(w / 2f, 0f),
                    end = Offset(w / 2f, h * 0.5f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun StatItem(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = HomeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(title, color = HomeColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ServerRow(
    item: HomeServer,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) HomeColors.Card2 else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            (if (selected) "● " else "○ ") + serverTitle(item.profile),
            color = if (selected) HomeColors.Accent else HomeColors.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
        Text(serverSubtitle(item.profile), color = HomeColors.Muted, fontSize = 12.sp)
        Row {
            TextButton(onClick = onEdit) { Text("تعديل كامل", fontSize = 12.sp) }
            TextButton(onClick = onDelete) { Text("حذف", fontSize = 12.sp, color = HomeColors.Danger) }
        }
    }
}

// ---------------------------------------------------------------------------
// الشاشة الرئيسية
// ---------------------------------------------------------------------------
@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRunning = uiState.isRunning
    val selectedGuid = uiState.selectedGuid

    // رقم يتغير لإعادة قراءة قائمة السيرفرات
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    val servers by produceState(
        initialValue = emptyList<HomeServer>(),
        uiState.groups,
        selectedGuid,
        tick
    ) {
        value = withContext(Dispatchers.IO) { loadServers() }
    }
    val selected = servers.firstOrNull { it.guid == selectedGuid }

    fun refreshLater() {
        scope.launch {
            delay(700)
            tick++
            delay(2000)
            tick++
        }
    }

    // عدّاد الوقت والبيانات (حركة التطبيق نفسه نحو السيرفر)
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var downBytes by remember { mutableLongStateOf(0L) }
    var upBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRunning) {
        if (!isRunning) {
            elapsedSeconds = 0L
            downBytes = 0L
            upBytes = 0L
            return@LaunchedEffect
        }
        val uid = Process.myUid()
        val startTime = SystemClock.elapsedRealtime()
        val baseRx = TrafficStats.getUidRxBytes(uid)
        val baseTx = TrafficStats.getUidTxBytes(uid)
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            downBytes = if (rx < 0 || baseRx < 0) -1L else (rx - baseRx).coerceAtLeast(0L)
            upBytes = if (tx < 0 || baseTx < 0) -1L else (tx - baseTx).coerceAtLeast(0L)
            delay(1000)
        }
    }

    val statusText = when (val status = uiState.status) {
        MainStatus.Disconnected -> "غير متصل"
        MainStatus.Connected -> "متصل ✓"
        MainStatus.Testing, is MainStatus.TestProgress -> "جارٍ الاختبار..."
        is MainStatus.ConnectionTest -> mainViewModel.formatStatus(status)
    }

    var showPicker by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var customOn by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<HomeServer?>(null) }

    MaterialTheme(colorScheme = HomeColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = HomeColors.Bg,
            contentColor = HomeColors.Text
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // الشريط العلوي
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "VPN الشخصي",
                        color = HomeColors.Text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onNavigate(MainDestination.Logcat) }) {
                        Text("السجل", fontSize = 13.sp)
                    }
                    TextButton(onClick = { onNavigate(MainDestination.Settings) }) {
                        Text("الإعدادات", fontSize = 13.sp)
                    }
                    TextButton(onClick = onOpenAdvanced) {
                        Text("المتقدمة", fontSize = 13.sp)
                    }
                }

                // زر الاتصال
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ConnectButton(running = isRunning) { onAction(MainAction.ToggleService) }
                }
                Text(
                    statusText,
                    color = if (isRunning) HomeColors.Connected else HomeColors.Muted,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isRunning) {
                    OutlinedButton(
                        onClick = { onAction(MainAction.TestCurrentServer) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("اختبار الاتصال") }
                }

                // العدّادات
                HomeCard {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatItem("الوقت", formatDuration(elapsedSeconds), Modifier.weight(1f))
                        StatItem("التنزيل", formatBytes(downBytes), Modifier.weight(1f))
                        StatItem("الرفع", formatBytes(upBytes), Modifier.weight(1f))
                    }
                }

                // السيرفر
                HomeCard {
                    Text("السيرفر", color = HomeColors.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    if (selected != null) {
                        Text(
                            serverTitle(selected.profile),
                            color = HomeColors.Text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(serverSubtitle(selected.profile), color = HomeColors.Muted, fontSize = 12.sp)
                    } else {
                        Text(
                            "لا يوجد سيرفر. اضغط «إضافة» ثم «من الحافظة (رابط)».",
                            color = HomeColors.Muted,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) {
                            Text("تغيير السيرفر")
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            Button(onClick = { showAddMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("+ إضافة")
                            }
                            DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("من الحافظة (رابط vless:// ...)") },
                                    onClick = {
                                        showAddMenu = false
                                        onAction(MainAction.ImportClipboard)
                                        refreshLater()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("مسح رمز QR") },
                                    onClick = {
                                        showAddMenu = false
                                        onAction(MainAction.ImportQRcode)
                                        refreshLater()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("من ملف") },
                                    onClick = {
                                        showAddMenu = false
                                        onAction(MainAction.ImportConfigLocal)
                                        refreshLater()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("إدخال يدوي (VLESS)") },
                                    onClick = {
                                        showAddMenu = false
                                        onAction(MainAction.ImportManually(EConfigType.VLESS.value))
                                    }
                                )
                                if (selected != null) {
                                    DropdownMenuItem(
                                        text = { Text("نسخ رابط السيرفر الحالي") },
                                        onClick = {
                                            showAddMenu = false
                                            onAction(MainAction.ShareClipboard(selected.guid))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // الإعداد المخصص
                HomeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "إعداد مخصص",
                                color = HomeColors.Text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "تعديل عنوان السيرفر و SNI بسرعة",
                                color = HomeColors.Muted,
                                fontSize = 12.sp
                            )
                        }
                        Switch(checked = customOn, onCheckedChange = { customOn = it })
                    }

                    if (customOn && selected != null) {
                        val profile = selected.profile
                        var address by remember(selected.guid, profile) { mutableStateOf(profile.server.orEmpty()) }
                        var port by remember(selected.guid, profile) { mutableStateOf(profile.serverPort.orEmpty()) }
                        var sni by remember(selected.guid, profile) { mutableStateOf(profile.sni.orEmpty()) }
                        var host by remember(selected.guid, profile) { mutableStateOf(profile.host.orEmpty()) }
                        val history = remember(tick, sni) { loadSniHistory(context) }

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("عنوان السيرفر (IP أو دومين)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("المنفذ") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sni,
                            onValueChange = { sni = it },
                            label = { Text("SNI") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (history.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                history.forEach { name ->
                                    Text(
                                        name,
                                        color = HomeColors.Accent,
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(HomeColors.Card2)
                                            .clickable { sni = name }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host (اختياري، لـ WebSocket)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val updated = profile.copy(
                                    server = address.trim().ifEmpty { profile.server },
                                    serverPort = port.trim().ifEmpty { profile.serverPort },
                                    sni = sni.trim().ifEmpty { null },
                                    host = host.trim().ifEmpty { null },
                                )
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            MmkvManager.encodeServerConfig(selected.guid, updated)
                                        }
                                        pushSniHistory(context, sni)
                                        tick++
                                        Toast.makeText(context, "تم الحفظ", Toast.LENGTH_SHORT).show()
                                        if (isRunning) onAction(MainAction.RestartService)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "تعذر الحفظ", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("حفظ وتطبيق") }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        // نافذة اختيار السيرفر
        if (showPicker) {
            AlertDialog(
                onDismissRequest = { showPicker = false },
                confirmButton = { TextButton(onClick = { showPicker = false }) { Text("إغلاق") } },
                title = { Text("اختر السيرفر") },
                text = {
                    if (servers.isEmpty()) {
                        Text("لا توجد سيرفرات بعد.")
                    } else {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            servers.forEach { item ->
                                ServerRow(
                                    item = item,
                                    selected = item.guid == selectedGuid,
                                    onSelect = {
                                        showPicker = false
                                        onAction(MainAction.SelectServer(item.guid))
                                    },
                                    onEdit = {
                                        showPicker = false
                                        onAction(MainAction.EditServer(item.guid, item.profile))
                                    },
                                    onDelete = { deleteTarget = item }
                                )
                            }
                        }
                    }
                }
            )
        }

        // تأكيد الحذف
        val target = deleteTarget
        if (target != null) {
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("حذف السيرفر؟") },
                text = { Text(serverTitle(target.profile)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        onAction(MainAction.RemoveServer(target.guid))
                        refreshLater()
                    }) { Text("حذف", color = HomeColors.Danger) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") }
                }
            )
        }
    }
}

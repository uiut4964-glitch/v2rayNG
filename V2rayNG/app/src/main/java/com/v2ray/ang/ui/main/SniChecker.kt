package com.v2ray.ang.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.CertificateFingerprintManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** اسم مؤقت للملفات التي يُنشئها الفاحص أثناء الاختبار (تُحذف بعد كل اختبار) */
internal const val SNI_TEST_REMARK = "__sni_test__"

private const val CHECKER_PARALLEL = 4

private val DOMAIN_REGEX =
    Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")

/** يستخرج أسماء الدومينات من أي نص (txt أو json أو csv) دون تكرار */
internal fun parseSniList(text: String): List<String> =
    DOMAIN_REGEX.findAll(text)
        .map { it.value.lowercase() }
        .filter { name -> name.substringAfterLast('.').any { it.isLetter() } }
        .distinct()
        .take(2000)
        .toList()

private data class SniResult(val name: String, val ms: Long)

/** يختبر السيرفر نفسه مع SNI مختلف عبر ملف مؤقت يُحذف فوراً. يعيد زمن الاستجابة أو -1 */
private suspend fun testSni(context: Context, base: ProfileItem, sni: String): Long =
    withContext(Dispatchers.IO) {
        var guid = ""
        try {
            val temp = base.copy(remarks = SNI_TEST_REMARK, sni = sni, subscriptionId = "")
            guid = MmkvManager.encodeServerConfig("", temp)
            val config = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!config.status) {
                -1L
            } else {
                CoreNativeManager.measureOutboundDelay(config.content, SettingsManager.getDelayTestUrl())
            }
        } catch (e: Throwable) {
            -1L
        } finally {
            if (guid.isNotEmpty()) {
                try {
                    MmkvManager.removeServer(guid)
                } catch (e: Throwable) {
                    // ignore
                }
            }
        }
    }

@Composable
fun SniCheckerDialog(
    baseProfile: ProfileItem,
    guid: String,
    onApply: (String) -> Unit,
    onHistoryChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(baseProfile) }
    var job by remember { mutableStateOf<Job?>(null) }
    val ok = remember { mutableStateListOf<SniResult>() }
    val names = remember(input) { parseSniList(input) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }.orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                }
                if (text.isBlank()) {
                    note = "تعذر قراءة الملف"
                } else {
                    input = text
                    note = "تم تحميل الملف"
                }
            }
        }
    }

    // حذف أي ملفات اختبار مؤقتة بقيت من محاولة سابقة
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                MmkvManager.decodeAllServerList().distinct().forEach { id ->
                    if (MmkvManager.decodeServerConfig(id)?.remarks == SNI_TEST_REMARK) {
                        MmkvManager.removeServer(id)
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun stop() {
        job?.cancel()
        running = false
    }

    fun start() {
        if (names.isEmpty()) {
            note = "لا توجد أسماء صالحة في القائمة"
            return
        }
        ok.clear()
        done = 0
        total = names.size
        current = ""
        note = ""
        running = true
        job = scope.launch {
            try {
                var base = profile
                // السيرفر الذي يستخدم TLS بشهادة ذاتية يحتاج بصمة الشهادة أولاً
                if (base.security == "tls" && base.pinnedCA256.isNullOrBlank() &&
                    base.verifyPeerCertByName.isNullOrBlank() && base.insecure != true
                ) {
                    val sha = withContext(Dispatchers.IO) {
                        CertificateFingerprintManager.fetchForManualFill(base)
                    }
                    if (sha.isNullOrBlank()) {
                        note = "تعذر جلب بصمة شهادة السيرفر. تأكد أن السيرفر يعمل والاتصال متاح."
                        return@launch
                    }
                    base = base.copy(pinnedCA256 = sha)
                    profile = base
                    withContext(Dispatchers.IO) {
                        try {
                            MmkvManager.encodeServerConfig(guid, base)
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
                val semaphore = Semaphore(CHECKER_PARALLEL)
                val testBase = base
                coroutineScope {
                    names.forEach { name ->
                        launch(Dispatchers.IO) {
                            semaphore.withPermit {
                                val ms = testSni(context, testBase, name)
                                withContext(Dispatchers.Main) {
                                    done++
                                    current = name
                                    if (ms > 0) ok.add(SniResult(name, ms))
                                }
                            }
                        }
                    }
                }
                note = if (ok.isEmpty()) "انتهى الفحص: لم ينجح أي اسم" else "انتهى الفحص: نجح ${ok.size} اسم"
            } finally {
                running = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(10.dp),
            shape = RoundedCornerShape(24.dp),
            color = HomeColors.Card
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("فاحص SNI", color = HomeColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "السيرفر: " + serverTitle(profile) + "  •  يُختبر بإعداداته نفسها مع تبديل SNI فقط",
                    color = HomeColors.Muted,
                    fontSize = 12.sp
                )
                if (profile.security == "reality") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "تنبيه: هذا السيرفر بنظام REALITY ولا يقبل إلا الأسماء المضافة في إعداد الخادم، فستفشل أغلب الأسماء هنا. حوّل الخادم إلى WS+TLS ليقبل أي اسم.",
                        color = HomeColors.Danger,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("الصق القائمة هنا (اسم في كل سطر)") },
                    maxLines = 5,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 150.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { picker.launch("*/*") }, enabled = !running) { Text("استيراد ملف") }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            input = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        },
                        enabled = !running
                    ) { Text("لصق") }
                    Text("${names.size} اسم", color = HomeColors.Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (running) stop() else start() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (running) "إيقاف" else "ابدأ الفحص") }

                if (total > 0) {
                    Spacer(Modifier.height(10.dp))
                    val fraction = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(HomeColors.Card2)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(6.dp)
                                .background(HomeColors.Accent)
                        )
                    }
                    Text(
                        "$done / $total  •  ناجح: ${ok.size}" + if (running && current.isNotEmpty()) "  •  $current" else "",
                        color = HomeColors.Muted,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                if (note.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(note, color = HomeColors.Accent, fontSize = 13.sp)
                }

                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(ok.sortedBy { it.ms }) { result ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "✓ " + result.name,
                                color = HomeColors.Connected,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${result.ms} ms", color = HomeColors.Muted, fontSize = 12.sp)
                            TextButton(onClick = { onApply(result.name) }) { Text("تطبيق") }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = ok.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = { ok.minByOrNull { it.ms }?.let { onApply(it.name) } }
                    ) { Text("تطبيق الأفضل", fontSize = 13.sp, maxLines = 1) }
                    OutlinedButton(
                        enabled = ok.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ok.sortedByDescending { it.ms }.forEach { pushSniHistory(context, it.name) }
                            onHistoryChanged()
                            note = "تم حفظ الناجحة في سجل SNI"
                        }
                    ) { Text("حفظ في السجل", fontSize = 13.sp, maxLines = 1) }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = ok.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(
                                ClipData.newPlainText("sni", ok.sortedBy { it.ms }.joinToString("\n") { it.name })
                            )
                            note = "تم نسخ الناجحة"
                        }
                    ) { Text("نسخ", fontSize = 13.sp, maxLines = 1) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { stop(); onDismiss() }
                    ) { Text("إغلاق", fontSize = 13.sp, maxLines = 1) }
                }
            }
        }
    }
}

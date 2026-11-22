package com.v2ray.ang.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** نافذة النسخ الاحتياطي المشفّر: تصدير كل السيرفرات كنص مشفّر، أو استيراده بكلمة المرور. */
@Composable
fun BackupDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importMode by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun doExport() {
        if (password.length < 6) {
            message = "كلمة المرور يجب ألا تقل عن 6 أحرف"
            return
        }
        busy = true
        scope.launch {
            val result: Pair<Int, String>? = withContext(Dispatchers.IO) {
                try {
                    val profiles = MmkvManager.decodeAllServerList()
                        .distinct()
                        .mapNotNull { MmkvManager.decodeServerConfig(it) }
                    if (profiles.isEmpty()) null
                    else Pair(profiles.size, BackupCrypto.encrypt(JsonUtil.toJson(profiles), password))
                } catch (e: Exception) {
                    null
                }
            }
            busy = false
            if (result == null) {
                exported = ""
                message = "لا توجد سيرفرات لتصديرها"
            } else {
                exported = result.second
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("hook-backup", result.second))
                message = "تم نسخ النسخة المشفّرة (${result.first} سيرفر) إلى الحافظة. الصقها في أي مكان آمن، مثل رسائلك المحفوظة."
            }
        }
    }

    fun doImport() {
        if (payload.isBlank() || password.isEmpty()) {
            message = "الصق النص المشفّر واكتب كلمة المرور"
            return
        }
        busy = true
        scope.launch {
            val added: Int = withContext(Dispatchers.IO) {
                try {
                    val json = BackupCrypto.decrypt(payload, password)
                    if (json == null) {
                        -1
                    } else {
                        val items = JsonUtil.fromJson(json, Array<ProfileItem>::class.java)
                        if (items == null) {
                            -2
                        } else {
                            val existing = MmkvManager.decodeAllServerList()
                                .mapNotNull { MmkvManager.decodeServerConfig(it) }
                                .map { Triple(it.server, it.serverPort, it.password) }
                                .toSet()
                            var count = 0
                            for (p in items) {
                                if (Triple(p.server, p.serverPort, p.password) in existing) continue
                                MmkvManager.encodeServerConfig("", p.copy(subscriptionId = ""))
                                count++
                            }
                            count
                        }
                    }
                } catch (e: Exception) {
                    -3
                }
            }
            busy = false
            message = when {
                added == -1 -> "كلمة المرور خاطئة أو النص غير صالح"
                added == -2 -> "محتوى النسخة غير مفهوم"
                added < 0 -> "تعذر الاستيراد"
                added == 0 -> "لا جديد: كل السيرفرات موجودة مسبقاً"
                else -> "تم استيراد $added سيرفر"
            }
            if (added > 0) onChanged()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("نسخ احتياطي مشفّر") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!importMode) {
                        Button(onClick = { }) { Text("تصدير") }
                        OutlinedButton(onClick = { importMode = true; message = "" }) { Text("استيراد") }
                    } else {
                        OutlinedButton(onClick = { importMode = false; message = "" }) { Text("تصدير") }
                        Button(onClick = { }) { Text("استيراد") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (importMode) {
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it },
                        label = { Text("النص المشفّر (HOOK1:...)") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        payload = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    }) { Text("لصق من الحافظة") }
                } else {
                    Text(
                        "يُصدَّر كل السيرفرات في نص واحد مشفّر بكلمة مرور (AES-256).",
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("كلمة المرور") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                if (importMode) {
                    Button(onClick = { doImport() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("استيراد")
                    }
                } else {
                    Button(onClick = { doExport() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("تصدير ونسخ")
                    }
                    if (exported.isNotEmpty()) {
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, exported)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Text("مشاركة") }
                    }
                }
                if (message.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(message, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

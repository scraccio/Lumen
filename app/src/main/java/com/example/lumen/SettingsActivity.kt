package com.example.lumen

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences("lumen", MODE_PRIVATE)

            if (prefs.getBoolean("onboarding_done", false)) {
                startActivity(Intent(this, MainActivity::class.java))
            } else {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }

            finish()
        }, 1500)
    }
}

@Composable
fun SettingsScreen() {

    var notifications by remember { mutableStateOf(true) }
    var biasMeter by remember { mutableStateOf(true) }
    var autoDeduplicate by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1623))
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {

        Text(
            text = "Settings",
            fontSize = 32.sp,
            color = Color(0xFFF5C842)
        )

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider(
            color = Color(0xFFF5C842),
            thickness = 1.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "PREFERENCES",
            fontSize = 22.sp,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingsSwitchRow(
            title = "Notifications",
            checked = notifications,
            onCheckedChange = { notifications = it }
        )

        SettingsSwitchRow(
            title = "Show bias meter",
            checked = biasMeter,
            onCheckedChange = { biasMeter = it }
        )

        SettingsSwitchRow(
            title = "Auto-deduplicate",
            checked = autoDeduplicate,
            onCheckedChange = { autoDeduplicate = it }
        )

        SettingsSwitchRow(
            title = "Dark mode",
            checked = darkMode,
            onCheckedChange = { darkMode = it }
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "ARTICLES",
            fontSize = 22.sp,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingsNavigationRow("Edit topics")
        SettingsNavigationRow("Manage sources")
        SettingsNavigationRow("Reading history")
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = title,
                fontSize = 18.sp,
                color = Color.White
            )

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF0F1623),
                    checkedTrackColor = Color(0xFFF5C842),
                    checkedBorderColor = Color.White,
                    uncheckedThumbColor = Color(0xFFF5C842),
                    uncheckedTrackColor = Color(0xFF0F1623),
                    uncheckedBorderColor = Color.White
                )
            )
        }

        HorizontalDivider(
            color = Color(0xFFF5C842),
            thickness = 1.dp
        )
    }
}

@Composable
fun SettingsNavigationRow(title: String) {

    Column {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    // Navigasjon her
                }
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = title,
                fontSize = 18.sp,
                color = Color.White
            )

            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFFF5C842)
            )
        }

        HorizontalDivider(
            color = Color(0xFFF5C842),
            thickness = 1.dp
        )
    }
}
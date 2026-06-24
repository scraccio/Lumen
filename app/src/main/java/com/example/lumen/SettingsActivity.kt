package com.example.lumen

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import android.os.Bundle
import com.example.lumen.R
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SettingsActivity : ComponentActivity() {

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                SettingsScreen()
            }
        }
    }
}

@Composable
fun SettingsScreen() {

    val context = LocalContext.current
    val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)

    var notifications by remember { mutableStateOf(true) }
    var biasMeter by remember { mutableStateOf(prefs.getBoolean("bias_meter_enabled", true)) }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1623))
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { backDispatcher?.onBackPressed() },
                modifier = Modifier.offset(x = (-12).dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFFF5C842)
                )
            }

            Text(
                text = "settings",
                fontSize = 32.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFFF5C842)
            )
        }

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
            onCheckedChange = {
                biasMeter = it
                prefs.edit().putBoolean("bias_meter_enabled", it).apply()
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "ARTICLES",
            fontSize = 22.sp,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingsNavigationRow("Edit preferences") {
            val intent = Intent(context, OnboardingActivity::class.java)
                .putExtra(OnboardingActivity.EXTRA_EDIT_MODE, true)
            context.startActivity(intent)
        }
        SettingsNavigationRow("Reading history") {
            context.startActivity(
                Intent(context, com.example.lumen.ui.ReadingHistoryActivity::class.java)
            )
        }
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
                interactionSource = remember { MutableInteractionSource() },
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
fun SettingsNavigationRow(title: String, onClick: () -> Unit = {}) {

    Column {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
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

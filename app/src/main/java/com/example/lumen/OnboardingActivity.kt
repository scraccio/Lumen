package com.example.lumen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.foundation.interaction.MutableInteractionSource

class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editMode = intent.getBooleanExtra(EXTRA_EDIT_MODE, false)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                InterestsScreen(editMode = editMode)
            }
        }
    }

    companion object {
        const val EXTRA_EDIT_MODE = "extra_edit_mode"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestsScreen(editMode: Boolean = false) {
    val context = LocalContext.current
    val savedPrefs = remember {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
    }
    var selectedTopics by remember {
        mutableStateOf(savedPrefs.getStringSet("topics", emptySet())?.toSet() ?: emptySet())
    }
    var selectedSources by remember {
        mutableStateOf(savedPrefs.getStringSet("sources", emptySet())?.toSet() ?: emptySet())
    }
    var isLoading by remember { mutableStateOf(false) }

    val topics = listOf("TECHNOLOGY", "SCIENCE", "POLITICS", "WORLD", "BUSINESS", "HEALTH", "CLIMATE", "SPORT")
    val sources = listOf("The New York Times", "The Guardian", "BBC News", "Reuters", "Der Spiegel", "Politico", "Al Jazeera")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1623))
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {

        Text(
            text = if (editMode) "edit interests" else "your interests",
            fontSize = 32.sp,
            fontWeight = FontWeight.Normal,
            color = Color(0xFFF5C842)
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = Color(0xFFF5C842), thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = "Pick topics to follow (at least 1)",
                fontSize = 16.sp,
                color = Color(0xFFF5C842)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "TOPICS",
                fontSize = 22.sp,
                color = Color(0xFFFFFFFFFF),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                topics.forEach { topic ->
                    val isSelected = selectedTopics.contains(topic)
                    TopicChip(
                        text = topic,
                        isSelected = isSelected,
                        onClick = {
                            selectedTopics = if (isSelected) {
                                selectedTopics - topic
                            } else {
                                selectedTopics + topic
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "SOURCES",
                fontSize = 22.sp,
                color = Color(0xFFFFFFFFFF),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            sources.forEach { source ->
                SourceItem(
                    name = source,
                    isChecked = selectedSources.contains(source),
                    onCheckedChange = { isChecked ->
                        selectedSources = if (isChecked) {
                            selectedSources + source
                        } else {
                            selectedSources - source
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                if (!isLoading) {
                    isLoading = true
                    savePreferences(
                        context = context,
                        topics = selectedTopics,
                        sources = selectedSources,
                        editMode = editMode
                    )
                }
            },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF5C842),
                contentColor = Color(0xFF0F1623),
                disabledContainerColor = Color(0xFFF5C842),
                disabledContentColor = Color(0xFF0F1623)
            ),
            border = BorderStroke(1.dp, Color(0xFFF5C842)),
            interactionSource = remember { MutableInteractionSource() }
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color(0xFF0F1623),
                    strokeWidth = 2.5.dp
                )
            } else {
                Text(
                    text = "CONTINUE",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
fun savePreferences(
    context: Context,
    topics: Set<String>,
    sources: Set<String>,
    editMode: Boolean = false
) {
    context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("topics", topics)
        .putStringSet("sources", sources)
        .apply()

    val activity = context as android.app.Activity

    if (editMode) {
        // Editing existing prefs from Settings — overwrite and return.
        // The feed re-reads SharedPreferences the next time it loads.
        activity.finish()
        return
    }

    context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("onboarding_done", true)
        .apply()

    activity.startActivity(Intent(context, MainActivity::class.java))
    @Suppress("DEPRECATION")
    activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
}



@Composable
fun TopicChip(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Color(0xFFF5C842)),
        color = if (isSelected) Color(0xFFF5C842) else Color(0xFF0F1623)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            fontSize = 14.sp,
            color = Color(0xFFFFFFFF)
        )
    }
}


@Composable
fun SourceItem(name: String, isChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = name,
                fontSize = 18.sp,
                color = Color(0xFFFFFFFF)
            )
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                interactionSource = remember { MutableInteractionSource() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF0F1623),
                    checkedTrackColor = Color(0xFFF5C842),
                    checkedBorderColor = Color(0xFFFFFFFF),
                    uncheckedThumbColor = Color(0xFFF5C842),
                    uncheckedTrackColor = Color(0xFF0F1623),
                    uncheckedBorderColor = Color(0xFFFFFFFF)
                )
            )
        }
        HorizontalDivider(color = Color.Black, thickness = 1.dp)
    }
}


@Preview(showBackground = true, showSystemUi = true)
@Composable
fun InterestsScreenPreview() {
    InterestsScreen()
}
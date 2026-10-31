@file:OptIn(ExperimentalMaterial3Api::class)

package com.tvdeck.app

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One tap switches between light and dark mode. */
@Composable
fun ThemeButton() {
    val dark = ThemeSettings.isDark()
    IconButton(onClick = { ThemeSettings.changeMode(if (dark) "light" else "dark") }) {
        Icon(
            if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
            if (dark) "Switch to light mode" else "Switch to dark mode"
        )
    }
}

@Composable
fun AppearanceDialog(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Appearance") },
        text = {
            Column {
                listOf("system" to "Follow my phone", "light" to "Light", "dark" to "Dark").forEach { (k, l) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { ThemeSettings.changeMode(k) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = ThemeSettings.mode == k, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(l)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Material You colors")
                        Text(
                            if (Build.VERSION.SDK_INT >= 31) "Uses colors from your wallpaper" else "Needs Android 12 or newer",
                            fontSize = 12.sp, color = cs.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = ThemeSettings.dynamic && Build.VERSION.SDK_INT >= 31,
                        onCheckedChange = { ThemeSettings.changeDynamic(it) },
                        enabled = Build.VERSION.SDK_INT >= 31
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
}

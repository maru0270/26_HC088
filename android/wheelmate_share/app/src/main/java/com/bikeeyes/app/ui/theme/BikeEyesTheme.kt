package com.bikeeyes.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun BikeEyesTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme {
        content()
    }
}
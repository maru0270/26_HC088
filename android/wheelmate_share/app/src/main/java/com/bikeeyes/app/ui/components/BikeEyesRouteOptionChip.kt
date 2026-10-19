package com.bikeeyes.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen

@Composable
fun BikeEyesRouteOptionChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {

    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50.dp),
        border = BorderStroke(
            1.dp,
            if (selected) BikeEyesGreen else BikeEyesBorderGray
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor =
                if (selected) BikeEyesGreen.copy(alpha = 0.12f)
                else Color.White
        )
    ) {
        Text(
            text = text,
            color =
                if (selected) BikeEyesGreen
                else Color.DarkGray
        )
    }
}
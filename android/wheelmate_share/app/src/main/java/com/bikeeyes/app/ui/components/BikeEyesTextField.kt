package com.bikeeyes.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen



@Composable
fun BikeEyesTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,

        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),

        singleLine = true,

        placeholder = {
            Text(placeholder)
        },

        visualTransformation = visualTransformation,

        shape = RoundedCornerShape(14.dp),

        colors = OutlinedTextFieldDefaults.colors(

            focusedBorderColor = BikeEyesGreen,

            unfocusedBorderColor = BikeEyesBorderGray,

            focusedLabelColor = BikeEyesGreen,

            cursorColor = BikeEyesGreen
        )
    )
}
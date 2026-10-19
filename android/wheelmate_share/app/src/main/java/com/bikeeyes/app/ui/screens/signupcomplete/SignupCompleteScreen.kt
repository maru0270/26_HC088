package com.bikeeyes.app.ui.screens.signupcomplete

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.ui.components.BikeEyesPrimaryButton
import com.bikeeyes.app.ui.theme.BikeEyesGreen

@Composable
fun SignupCompleteScreen(
    onGoLoginClick: () -> Unit = {}
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),

        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = BikeEyesGreen,
            modifier = Modifier.height(100.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "회원가입이 완료되었습니다",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "로그인 후 안전한 라이딩을 시작하세요.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(56.dp))

        BikeEyesPrimaryButton(
            text = "로그인 화면으로 이동",
            onClick = onGoLoginClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
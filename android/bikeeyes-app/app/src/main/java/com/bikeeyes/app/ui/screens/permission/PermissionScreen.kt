package com.bikeeyes.app.ui.screens.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.ui.components.BikeEyesOutlinedButton
import com.bikeeyes.app.ui.components.BikeEyesPermissionCard
import com.bikeeyes.app.ui.components.BikeEyesPrimaryButton

@Composable
fun PermissionScreen(
    onAllowAllClick: () -> Unit = {},
    onPermissionAllowClick: () -> Unit = {},
    onLaterClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "권한 요청",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            BikeEyesPermissionCard(
                title = "위치 권한",
                description = "현재 위치와 위험구간 알림",
                onAllowClick = onPermissionAllowClick
            )

            Spacer(modifier = Modifier.height(14.dp))

            BikeEyesPermissionCard(
                title = "카메라 권한",
                description = "노면 상태 분석",
                onAllowClick = onPermissionAllowClick
            )

            Spacer(modifier = Modifier.height(14.dp))

            BikeEyesPermissionCard(
                title = "센서 사용",
                description = "충격과 기울기 감지",
                onAllowClick = onPermissionAllowClick
            )

            Spacer(modifier = Modifier.height(14.dp))

            BikeEyesPermissionCard(
                title = "알림 권한",
                description = "위험구간과 사고 확인",
                onAllowClick = onPermissionAllowClick
            )
        }

        Column {
            BikeEyesPrimaryButton(
                text = "모든 권한 허용",
                onClick = onAllowAllClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            BikeEyesOutlinedButton(
                text = "나중에 설정",
                onClick = onLaterClick
            )
        }
    }
}

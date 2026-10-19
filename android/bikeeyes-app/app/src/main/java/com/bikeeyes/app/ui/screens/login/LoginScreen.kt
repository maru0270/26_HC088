package com.bikeeyes.app.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoginClick: () -> Unit = {},
    onSignupClick: () -> Unit = {},
    onPasswordResetClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember(context) { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (repository.restoreSession()) onLoginClick() else loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("BikeEyes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("로그인", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(32.dp))
        if (loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("로그인 상태를 확인하고 있습니다.")
            return@Column
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("이메일") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("비밀번호") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = !loading,
            onClick = {
                if (!email.contains("@") || password.isBlank()) {
                    error = "이메일과 비밀번호를 입력해주세요."
                    return@Button
                }
                loading = true
                error = null
                scope.launch {
                    runCatching { repository.login(email, password) }
                        .onSuccess { onLoginClick() }
                        .onFailure {
                            error = AuthRepository.userMessage(it)
                            loading = false
                        }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("로그인") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSignupClick, modifier = Modifier.fillMaxWidth()) {
            Text("회원가입")
        }
        TextButton(onClick = onPasswordResetClick) {
            Text("비밀번호를 잊으셨나요?")
        }
    }
}

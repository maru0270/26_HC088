package com.bikeeyes.app.ui.screens.signup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun SignupScreen(
    onBackClick: () -> Unit = {},
    onSignupClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember(context) { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var requiredAgreed by remember { mutableStateOf(false) }
    var aiResearch by remember { mutableStateOf(false) }
    var longTermStorage by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("회원가입", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            nickname, { nickname = it }, label = { Text("닉네임") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(email, { email = it }, label = { Text("이메일") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("휴대전화 번호") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password, { password = it }, label = { Text("비밀번호") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            confirmation, { confirmation = it }, label = { Text("비밀번호 확인") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        ConsentRow(requiredAgreed, { requiredAgreed = it }, "[필수] 이용약관·개인정보·GPS/센서/이미지 수집 동의")
        ConsentRow(aiResearch, { aiResearch = it }, "[선택] AI 학습·연구 목적 재사용 동의")
        ConsentRow(longTermStorage, { longTermStorage = it }, "[선택] 장기 보관 및 서비스 개선 동의")
        Text("비밀번호는 8자 이상이며 특수문자를 1개 이상 포함해야 합니다.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !loading,
            onClick = {
                error = when {
                    nickname.isBlank() -> "닉네임을 입력해주세요."
                    !email.contains("@") -> "올바른 이메일을 입력해주세요."
                    !AuthRepository.isValidPassword(password) -> "비밀번호 조건을 확인해주세요."
                    password != confirmation -> "비밀번호가 일치하지 않습니다."
                    !requiredAgreed -> "필수 항목에 동의해주세요."
                    else -> null
                }
                if (error == null) {
                    loading = true
                    scope.launch {
                        runCatching {
                            repository.signup(
                                nickname, email, phone, password, aiResearch, longTermStorage
                            )
                        }.onSuccess {
                            onSignupClick()
                        }.onFailure {
                            error = AuthRepository.userMessage(it)
                            loading = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "처리 중..." else "회원가입 완료") }
        OutlinedButton(onClick = onBackClick, modifier = Modifier.fillMaxWidth()) {
            Text("로그인으로 돌아가기")
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

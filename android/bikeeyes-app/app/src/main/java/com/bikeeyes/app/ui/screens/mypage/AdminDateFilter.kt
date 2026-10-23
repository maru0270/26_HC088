package com.bikeeyes.app.ui.screens.mypage

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** 관리자 기록 화면에서 공통으로 사용하는 일 단위 조회 필터. */
@Composable
fun AdminDateFilter(
    year: Int?,
    month: Int?,
    day: Int?,
    onDateChanged: (Int?, Int?, Int?) -> Unit,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val calendar = Calendar.getInstance()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedButton(
            modifier = Modifier.weight(1f),
            enabled = enabled,
            onClick = {
                DatePickerDialog(
                    context,
                    { _, selectedYear, selectedMonth, selectedDay ->
                        onDateChanged(selectedYear, selectedMonth + 1, selectedDay)
                    },
                    year ?: calendar.get(Calendar.YEAR),
                    (month?.minus(1)) ?: calendar.get(Calendar.MONTH),
                    day ?: calendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        ) {
            Text(
                if (year == null || month == null || day == null) "달력 · 전체 기간"
                else "달력 · ${year}년 ${month}월 ${day}일"
            )
        }
        if (year != null && month != null && day != null) {
            TextButton(enabled = enabled, onClick = { onDateChanged(null, null, null) }) {
                Text("전체")
            }
        }
    }
}

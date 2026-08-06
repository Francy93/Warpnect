package io.warpnect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.warpnect.WarpnectRole

@Composable
fun MainScreen(
    role: WarpnectRole,
    onIdleSelected: () -> Unit,
    onReceiverSelected: () -> Unit,
    onTransmitterSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Warpnect",
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = "Powered by State Coherence Layer")
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Role: ${role.displayName}",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onReceiverSelected,
                enabled = role != WarpnectRole.Receiver,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "Receiver")
            }
            OutlinedButton(
                onClick = onTransmitterSelected,
                enabled = role != WarpnectRole.Transmitter,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "Transmitter")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onIdleSelected,
            enabled = role != WarpnectRole.Idle,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Idle")
        }
    }
}


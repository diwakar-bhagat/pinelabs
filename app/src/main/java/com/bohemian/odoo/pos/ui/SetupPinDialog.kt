package com.bohemian.odoo.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bohemian.odoo.pos.R

/** The supervisor PIN in front of Setup: an on-screen keypad, digits shown as dots. */
@Composable
fun SetupPinDialog(pin: PinUi, vm: PosViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissPin,
        title = { Text("Supervisor PIN") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the PIN to open Setup.", color = Zz.Muted)
                Text(if (pin.entered.isEmpty()) "– – – –" else "●".repeat(pin.entered.length).toCharArray().joinToString(" "),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                pin.message?.let { Text(it, color = Zz.Red, fontSize = 14.sp) }
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { k ->
                                Box(
                                    Modifier.size(width = 84.dp, height = 56.dp).clip(RoundedCornerShape(6.dp))
                                        .background(if (k == "OK") Zz.Black else Zz.CreamDeep)
                                        .clickable {
                                            when (k) {
                                                "⌫" -> vm.pinBackspace()
                                                "OK" -> vm.submitPin()
                                                else -> vm.pinDigit(k[0])
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (k == "⌫") MdiIcon(R.drawable.mdi_backspace_outline, "Delete", Zz.Black)
                                    else Text(k, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (k == "OK") Zz.Cream else Zz.Black)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(vm::dismissPin) { Text("Cancel") } },
    )
}

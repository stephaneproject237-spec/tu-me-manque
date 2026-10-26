package com.tumemanque.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Calendar

val MOIS_FR = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

/** « 05/03/1998 » → « 5 mars 1998 » (ou le texte tel quel s'il est incomplet). */
fun birthPretty(v: String): String {
    val p = parseBirth(v) ?: return ""
    val parts = v.split("/")
    return "${parts[0].toInt()} ${MOIS_FR[parts[1].toInt() - 1]} ${parts[2]}"
}

/** Calendrier guidé : on choisit l'année, puis le mois, puis le jour. Renvoie « jj/mm/aaaa ». */
@Composable
fun BirthPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    var step by remember { mutableIntStateOf(0) }
    var year by remember { mutableIntStateOf(0) }
    var month by remember { mutableIntStateOf(0) } // 1..12

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF281C24)).padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) Text("‹", color = Color(0xFFFF6A9C), fontSize = 30.sp, modifier = Modifier.clickable { step-- }.padding(end = 12.dp))
                Text(
                    when (step) {
                        0 -> "Choisis l'année"
                        1 -> "Année $year · choisis le mois"
                        else -> "${MOIS_FR[month - 1]} $year · choisis le jour"
                    },
                    color = Color(0xFFF5EEF2), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            when (step) {
                0 -> {
                    val years = remember { (thisYear - 5 downTo thisYear - 100).toList() }
                    LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(320.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(years) { y -> Cell(y.toString()) { year = y; step = 1 } }
                    }
                }
                1 -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(260.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((1..12).toList()) { m -> Cell(MOIS_FR[m - 1].replaceFirstChar { it.uppercase() }) { month = m; step = 2 } }
                }
                else -> {
                    val days = remember(year, month) {
                        Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
                    }
                    LazyVerticalGrid(GridCells.Fixed(7), Modifier.height(260.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items((1..days).toList()) { d ->
                            Cell(d.toString()) { onPick("%02d/%02d/%04d".format(d, month, year)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Annuler", color = Color(0xFFB7A3AE), fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(8.dp))
        }
    }
}

@Composable
private fun Cell(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A2A34)).clickable { onClick() }.padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color(0xFFF5EEF2), fontSize = 14.sp, maxLines = 1) }
}

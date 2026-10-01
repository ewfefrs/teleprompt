package com.example.teleprompter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Справочник по частым вопросам: режимы, функции, премиум, использование очков. */
@Composable
fun FaqScreen(onBack: () -> Unit) {
    val items = listOf(
        R.string.faq_q_modes to R.string.faq_a_modes,
        R.string.faq_q_glasses to R.string.faq_a_glasses,
        R.string.faq_q_auto to R.string.faq_a_auto,
        R.string.faq_q_limits to R.string.faq_a_limits,
        R.string.faq_q_premium to R.string.faq_a_premium,
        R.string.faq_q_lang to R.string.faq_a_lang,
        R.string.faq_q_battery to R.string.faq_a_battery,
        R.string.faq_q_even to R.string.faq_a_even,
        R.string.faq_q_ring to R.string.faq_a_ring,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.padding(start = 16.dp).size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.faq_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        items.forEach { (q, a) ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(q), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(a),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

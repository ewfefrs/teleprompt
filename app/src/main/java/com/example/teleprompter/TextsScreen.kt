package com.example.teleprompter

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TextsScreen(
    items: List<SavedText>,
    onNew: () -> Unit,
    onImportFile: (Uri) -> Unit,
    onRun: (SavedText) -> Unit,
    onEdit: (SavedText) -> Unit,
    onDelete: (SavedText) -> Unit,
    onBack: () -> Unit
) {
    // Системный выбор файла (SAF): .txt / .md / .docx. Разбор — офлайн (FileImport).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportFile(uri)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(
                stringResource(R.string.my_texts),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNew, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.create_text))
            }
            OutlinedButton(
                onClick = { picker.launch(FileImport.MIME_TYPES) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.import_file))
            }
        }

        if (items.isEmpty()) {
            Text(stringResource(R.string.no_texts), style = MaterialTheme.typography.bodyMedium)
        } else {
            for (item in items) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.title.ifBlank { "—" }, fontWeight = FontWeight.Bold, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onRun(item) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.run)) }
                            OutlinedButton(onClick = { onEdit(item) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.edit)) }
                            OutlinedButton(onClick = { onDelete(item) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TextEditorScreen(
    initialTitle: String,
    initialBody: String,
    wordLimit: Int = Int.MAX_VALUE,
    onSave: (String, String) -> Unit,
    onRun: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    // Уже сохранённый длинный текст (напр. с прежнего премиума) при открытии тоже режется до лимита.
    var body by remember { mutableStateOf(capWords(initialBody, wordLimit)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(
                stringResource(R.string.create_text),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.title_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body,
            // Жёсткий лимит бесплатного тарифа: сверх [wordLimit] слов ввести/вставить нельзя.
            onValueChange = { body = capWords(it, wordLimit) },
            label = { Text(stringResource(R.string.body_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
        )
        val words = ReadingTime.wordCount(body)
        if (wordLimit != Int.MAX_VALUE) {
            val atLimit = words >= wordLimit
            Text(
                "$words / $wordLimit",
                style = MaterialTheme.typography.bodySmall,
                color = if (atLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (atLimit) {
                Text(
                    stringResource(R.string.editor_over_limit, wordLimit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Text(
                "$words",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onSave(title, body) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
            Button(onClick = { onRun(title, body) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.run)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Оставить не более [limit] слов (бесплатный тариф). Лишние слова/вставка отбрасываются,
 *  ровно [limit] слов вводить можно. [Int.MAX_VALUE] (премиум) — без ограничения. */
private fun capWords(text: String, limit: Int): String {
    if (limit == Int.MAX_VALUE) return text
    var count = 0
    var limitWordEnd = -1
    for (m in Regex("\\S+").findAll(text)) {
        count++
        if (count == limit) limitWordEnd = m.range.last + 1
        if (count > limit) return text.substring(0, limitWordEnd)
    }
    return text
}

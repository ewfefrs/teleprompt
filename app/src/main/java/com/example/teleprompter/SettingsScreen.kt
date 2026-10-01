package com.example.teleprompter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    themeMode: Int,
    language: String,
    appMode: Int,
    isAdmin: Boolean,
    isPremium: Boolean,
    sessionHoursLeft: Int,
    onThemeChange: (Int) -> Unit,
    onLanguageChange: (String) -> Unit,
    onEnableRental: () -> Unit,
    onDisableRental: (String) -> Boolean,
    onActivateKey: (String) -> Boolean,
    onOpenFaq: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    security: SecurityManager
) {
    var showChangePwd by remember { mutableStateOf(false) }
    var showCreateGuest by remember { mutableStateOf(false) }
    var showEnableRental by remember { mutableStateOf(false) }
    var showDisableRental by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        // ── Премиум ──
        SectionHeader(Icons.Filled.Star, stringResource(R.string.premium_title))
        PremiumCard(isPremium, onActivateKey)

        HorizontalDivider()

        // ── Язык (интерфейс + меню на очках) ──
        SectionHeader(Icons.Filled.Language, stringResource(R.string.language))
        LocaleManager.SUPPORTED.forEach { lang ->
            RadioRow(stringResource(lang.nameRes), language == lang.code) { onLanguageChange(lang.code) }
        }

        HorizontalDivider()

        // ── Тема ──
        SectionHeader(Icons.Filled.Palette, stringResource(R.string.theme))
        RadioRow(stringResource(R.string.theme_system), themeMode == ThemeManager.SYSTEM) { onThemeChange(ThemeManager.SYSTEM) }
        RadioRow(stringResource(R.string.theme_light), themeMode == ThemeManager.LIGHT) { onThemeChange(ThemeManager.LIGHT) }
        RadioRow(stringResource(R.string.theme_dark), themeMode == ThemeManager.DARK) { onThemeChange(ThemeManager.DARK) }

        HorizontalDivider()

        // ── Показ ──
        SectionHeader(Icons.Filled.Timer, stringResource(R.string.playback_title))
        CountdownToggle()

        HorizontalDivider()

        // ── Жесты тачбара + авто-скрытие ──
        SectionHeader(Icons.Filled.TouchApp, stringResource(R.string.gestures_title))
        GesturesCard()

        HorizontalDivider()

        // ── Доступ и режим ──
        SectionHeader(Icons.Filled.Lock, stringResource(R.string.sec_access))
        AccessCard(
            appMode = appMode,
            isAdmin = isAdmin,
            isPremium = isPremium,
            sessionHoursLeft = sessionHoursLeft,
            onEnableRental = { showEnableRental = true },
            onDisableRental = { showDisableRental = true },
            onChangePwd = { showChangePwd = true },
            onCreateGuest = { showCreateGuest = true },
            onLogout = onLogout
        )

        HorizontalDivider()

        // ── Справка и контакты ──
        SectionHeader(Icons.Filled.Info, stringResource(R.string.sec_help))
        OutlinedButton(onClick = onOpenFaq, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.faq_title))
        }
        ContactCard()

        HorizontalDivider()

        // ── О приложении ──
        SectionHeader(Icons.Filled.Public, stringResource(R.string.about_title))
        AboutCard()
    }

    if (showChangePwd) ChangePasswordDialog(security) { showChangePwd = false }
    if (showCreateGuest) CreateGuestDialog(security) { showCreateGuest = false }
    if (showEnableRental) EnableRentalDialog(security, onEnabled = { onEnableRental(); showEnableRental = false }) { showEnableRental = false }
    if (showDisableRental) DisableRentalDialog(onDisableRental) { showDisableRental = false }
}

// ── Премиум-карточка ──
@Composable
private fun PremiumCard(isPremium: Boolean, onActivateKey: (String) -> Boolean) {
    var showDisclaimer by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isPremium) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.premium_active), fontWeight = FontWeight.Bold)
                }
            } else {
                Text(stringResource(R.string.premium_desc), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.premium_free_plan, PremiumManager.MAX_TEXTS_FREE, PremiumManager.MAX_WORDS_FREE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Перед активацией — дисклеймер (возврат невозможен, претензий нет).
                Button(onClick = { showDisclaimer = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.enter_key))
                }
                Text(
                    stringResource(R.string.buy_key_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TgBotLink()
            }
        }
    }
    if (showDisclaimer) DisclaimerDialog(
        onAgree = { showDisclaimer = false; showKeyDialog = true },
        onDismiss = { showDisclaimer = false }
    )
    if (showKeyDialog) ActivateKeyDialog(onActivateKey) { showKeyDialog = false }
}

/** Кликабельный хэндл бота — открывает Telegram. */
@Composable
private fun TgBotLink() {
    val context = LocalContext.current
    val handle = LicenseManager.TG_BOT.trimStart('@')
    Text(
        LicenseManager.TG_BOT,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { openUrl(context, "https://t.me/$handle") }
    )
}

/** Дисклеймер перед покупкой полного доступа. */
@Composable
private fun DisclaimerDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disclaimer_title)) },
        text = { Text(stringResource(R.string.disclaimer_text)) },
        confirmButton = { Button(onClick = onAgree) { Text(stringResource(R.string.agree)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Диалог активации: показывает Device ID (к нему привязывается ключ) + поле ввода ключа. */
@Composable
private fun ActivateKeyDialog(onActivate: (String) -> Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val deviceId = remember { LicenseManager.deviceId(context) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.premium_activation)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.buy_key_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TgBotLink()
                Text(
                    "${stringResource(R.string.device_id)}: $deviceId",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = { copyToClipboard(context, deviceId) }) {
                    Text(stringResource(R.string.copy))
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = false },
                    label = { Text(stringResource(R.string.key_hint)) },
                    isError = error,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) Text(
                    stringResource(R.string.activation_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (onActivate(key)) {
                    Toast.makeText(context, R.string.activation_ok, Toast.LENGTH_LONG).show()
                    onDismiss()
                } else error = true
            }) { Text(stringResource(R.string.activate)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

// ── Карточка доступа / режима ──
@Composable
private fun AccessCard(
    appMode: Int,
    isAdmin: Boolean,
    isPremium: Boolean,
    sessionHoursLeft: Int,
    onEnableRental: () -> Unit,
    onDisableRental: () -> Unit,
    onChangePwd: () -> Unit,
    onCreateGuest: () -> Unit,
    onLogout: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rental = appMode == AppModeManager.RENTAL
            Text(
                "${stringResource(R.string.app_mode)}: " +
                    if (rental) stringResource(R.string.mode_rental) else stringResource(R.string.mode_public),
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(if (rental) R.string.mode_rental_desc else R.string.mode_public_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!rental) {
                // Режим аренды доступен только с премиумом.
                if (isPremium) {
                    Button(onClick = onEnableRental, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.enable_rental))
                    }
                } else {
                    Text(
                        stringResource(R.string.rental_needs_premium),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                val role = if (isAdmin) stringResource(R.string.session_admin) else stringResource(R.string.session_guest)
                Text(
                    "${stringResource(R.string.session_status)}: $role · ${stringResource(R.string.session_hours_left, sessionHoursLeft)}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (isAdmin) {
                    Button(onClick = onChangePwd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.change_password)) }
                    Button(onClick = onCreateGuest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.create_guest)) }
                    OutlinedButton(onClick = onDisableRental, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.disable_rental)) }
                }
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.logout))
                }
            }
        }
    }
}

// ── Контакты ──
@Composable
private fun ContactCard() {
    val context = LocalContext.current
    val email = stringResource(R.string.contact_email)
    val subject = stringResource(R.string.contact_subject)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.contact_desc), style = MaterialTheme.typography.bodyMedium)
            Text(email, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sendEmail(context, email, subject) }, modifier = Modifier.width(200.dp)) {
                    Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.contact_write))
                }
                OutlinedButton(onClick = { copyToClipboard(context, email) }) { Text(stringResource(R.string.copy)) }
            }
        }
    }
}

private fun sendEmail(context: Context, email: String, subject: String) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$email")
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    runCatching { context.startActivity(intent) }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("email", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

// ── Общие мелочи ──
@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

/** Тумблер обратного отсчёта 3-2-1 перед авто-показом (по умолчанию включён). */
@Composable
private fun CountdownToggle() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Prefs.getBool(context, Prefs.KEY_COUNTDOWN, true)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.countdown_label), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.countdown_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = on, onCheckedChange = {
            on = it
            Prefs.putBool(context, Prefs.KEY_COUNTDOWN, it)
        })
    }
}

/** «О приложении»: зачем создано — акцент на повсеместность и независимость от интернета. */
@Composable
private fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Кастомизация жестов тачбара + авто-скрытие текста после полной прокрутки. */
@Composable
private fun GesturesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Customization.Gesture.entries.forEach { gesture -> GestureRow(gesture) }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            AutoHideRow()
        }
    }
}

/** Одна строка: жест → выпадающий выбор действия. */
@Composable
private fun GestureRow(gesture: Customization.Gesture) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf(Customization.action(context, gesture)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(gesture.titleRes), modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(stringResource(action.labelRes)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Customization.GestureAction.entries.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(stringResource(a.labelRes)) },
                        onClick = {
                            action = a
                            Customization.setAction(context, gesture, a)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** Авто-скрытие текста после конца: Никогда / N секунд. */
@Composable
private fun AutoHideRow() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var sec by remember { mutableStateOf(Customization.autoHideSec(context)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.autohide_label), modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(if (sec == 0) stringResource(R.string.autohide_never) else stringResource(R.string.time_sec, sec))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Customization.AUTOHIDE_OPTIONS.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(if (opt == 0) stringResource(R.string.autohide_never) else stringResource(R.string.time_sec, opt)) },
                        onClick = {
                            sec = opt
                            Customization.setAutoHideSec(context, opt)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Смена админ-пароля: текущий + новый. */
@Composable
private fun ChangePasswordDialog(security: SecurityManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PwdField(current, { current = it; error = false }, stringResource(R.string.current_password))
                PwdField(new, { new = it; error = false }, stringResource(R.string.new_password))
                if (error) Text(stringResource(R.string.action_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (security.changeAdminPassword(current, new)) {
                    Toast.makeText(context, R.string.password_changed, Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else error = true
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

/** Создание одноразового гостевого кода — требует ввода админ-пароля. */
@Composable
private fun CreateGuestDialog(security: SecurityManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var adminPwd by remember { mutableStateOf("") }
    var guest by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_guest)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PwdField(adminPwd, { adminPwd = it; error = false }, stringResource(R.string.admin_password))
                OutlinedTextField(
                    value = guest,
                    onValueChange = { guest = it; error = false },
                    label = { Text(stringResource(R.string.guest_code)) },
                    singleLine = true,
                    isError = error,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) Text(stringResource(R.string.action_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (security.createGuestCode(adminPwd, guest)) {
                    Toast.makeText(context, R.string.guest_created, Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else error = true
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

/** Подтверждение включения режима аренды. */
@Composable
private fun EnableRentalDialog(security: SecurityManager, onEnabled: () -> Unit, onDismiss: () -> Unit) {
    // Впервые (админ-пароль ещё дефолтный) — просим владельца задать свой пароль.
    val firstTime = remember { security.isDefaultAdmin() }
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enable_rental)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.mode_rental_desc), style = MaterialTheme.typography.bodySmall)
                if (firstTime) {
                    Text(stringResource(R.string.set_admin_hint), style = MaterialTheme.typography.bodySmall)
                    PwdField(pwd, { pwd = it; error = false }, stringResource(R.string.set_admin_title))
                    if (error) Text(stringResource(R.string.action_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (firstTime && !security.setInitialAdminPassword(pwd)) error = true
                else onEnabled()
            }) { Text(stringResource(R.string.enable_rental)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

/** Выход из режима аренды — требует админ-пароль. */
@Composable
private fun DisableRentalDialog(onDisableRental: (String) -> Boolean, onDismiss: () -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disable_rental)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.rental_disable_admin), style = MaterialTheme.typography.bodySmall)
                PwdField(pwd, { pwd = it; error = false }, stringResource(R.string.admin_password))
                if (error) Text(stringResource(R.string.action_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { if (onDisableRental(pwd)) onDismiss() else error = true }) {
                Text(stringResource(R.string.disable_rental))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

@Composable
private fun PwdField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
}

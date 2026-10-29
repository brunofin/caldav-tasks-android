package io.github.brunofin.caldavtasks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.brunofin.caldavtasks.TasksViewModel
import io.github.brunofin.caldavtasks.core.Account
import io.github.brunofin.caldavtasks.core.Snapshot

@Composable
fun SettingsScreen(model: TasksViewModel, state: Snapshot, busy: Boolean, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Passwords are intentionally NOT placed in savedInstanceState.
    var password by remember { mutableStateOf("") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var disconnect by remember { mutableStateOf<Account?>(null) }
    fun clear() { editing = null; name = ""; url = ""; username = ""; password = ""; enabled = true }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connections", style = MaterialTheme.typography.headlineSmall)
        Text("Add HTTPS CalDAV accounts using your username and app password. All enabled accounts appear together in the task list.")
        for (account in state.accounts) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (account.enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.labelSmall)
                        state.errors[account.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    Action(Icons.Outlined.Edit, "Edit ${account.name}", !busy) {
                        editing = account.id; name = account.name; url = account.url; username = account.username
                        password = ""; enabled = account.enabled
                    }
                    Action(Icons.Outlined.DeleteOutline, "Disconnect ${account.name}", !busy) { disconnect = account }
                }
            }
        }
        HorizontalDivider()
        Text(if (editing == null) "New connection" else "Edit connection", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Connection name") }, singleLine = true)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("HTTPS CalDAV URL") },
            placeholder = { Text("https://cloud.example.com/caldav/") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Username") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text(if (editing == null) "App password" else "App password (blank keeps current)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", Modifier.weight(1f))
            Switch(enabled, { enabled = it }, enabled = !busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && name.isNotBlank() && url.isNotBlank() && username.isNotBlank() && (editing != null || password.isNotEmpty()), onClick = {
                model.perform { model.repository.saveAccount(editing, name, url, username, password, enabled); clear() }
            }) { Text("Save connection") }
            TextButton(enabled = !busy, onClick = { clear() }) { Text("Clear") }
        }
        Text("Passwords and cached tasks are encrypted on this device using Android Keystore. App backup is disabled. Disconnecting only removes local data; it never deletes server accounts or tasks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Background sync runs approximately every 15 minutes when Android permits it. While open, the app refreshes every 2 minutes. No offline write queue: failed changes are not reported as successful.", style = MaterialTheme.typography.bodySmall)
        Text("The home-screen widget shows cached task titles without unlocking this app. Only add it to a trusted launcher.", style = MaterialTheme.typography.bodySmall)
    }
    disconnect?.let { account ->
        AlertDialog(onDismissRequest = { disconnect = null }, title = { Text("Disconnect ${account.name}?") },
            text = { Text("Remove this connection and its cached tasks from this device? Server data will not be changed.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                model.perform { model.repository.removeAccount(account.id); if (editing == account.id) clear(); disconnect = null }
            }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = null }) { Text("Cancel") } })
    }
}

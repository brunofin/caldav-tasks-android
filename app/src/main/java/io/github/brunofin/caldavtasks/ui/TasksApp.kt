@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.brunofin.caldavtasks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brunofin.caldavtasks.*
import io.github.brunofin.caldavtasks.core.*
import kotlinx.coroutines.launch

private data class Confirmation(val title: String, val body: String, val action: () -> Unit)
private data class ListEditor(val accountId: String, val listId: String? = null, val initial: String = "")

@Composable
fun TasksApp(model: TasksViewModel, targetList: String?, consumeTarget: () -> Unit) {
    val state by model.repository.state.collectAsStateWithLifecycle()
    val presentation by model.presentation.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var settings by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    var editor by remember { mutableStateOf<ListEditor?>(null) }
    val rows = remember(state, presentation) { buildRows(state, presentation.filter, presentation.folds) }
    val busy = model.busy || state.busy

    LaunchedEffect(model.message) {
        model.message?.let { snackbar.showSnackbar(it); model.message = null }
    }
    LaunchedEffect(targetList, state.lists) {
        if (targetList != null) {
            state.lists.find { it.id == targetList }?.let {
                model.compose(it)
                settings = false
            }
            consumeTarget()
        }
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Text("CalDAV Tasks", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
            NavigationDrawerItem(label = { Text("Tasks") }, selected = !settings, icon = { Icon(Icons.Outlined.Checklist, null) },
                onClick = { settings = false; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            NavigationDrawerItem(label = { Text("Settings") }, selected = settings, icon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { settings = true; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.weight(1f))
            Text("Direct CalDAV · On-device encrypted storage", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(24.dp))
        }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(if (settings) "Settings" else "Tasks") }, navigationIcon = {
                    Action(Icons.Outlined.Menu, "Open navigation menu") { scope.launch { drawer.open() } }
                }, actions = {
                    Action(Icons.Outlined.Refresh, "Refresh all accounts", !busy) { model.perform { model.repository.refresh() } }
                })
            }, snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (settings) SettingsScreen(model, state, busy, Modifier.padding(padding))
            else Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("${state.activeCount} active · ${state.visibleTasks.size - state.activeCount} done · ${state.enabledAccounts.size} connections · ${state.enabledAccounts.sumOf { state.listsFor(it.id).size }} lists",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.enabledAccounts, key = { it.id }) { account ->
                            AssistChip(onClick = {
                                model.presentation.expand("account:${account.id}")
                                scope.launch {
                                    val expanded = buildRows(state, presentation.filter, presentation.folds - "account:${account.id}")
                                    val index = expanded.indexOfFirst { it.key == "account:${account.id}" }
                                    if (index >= 0) listState.animateScrollToItem(index)
                                }
                            }, label = { Text("${account.name} · ${state.listsFor(account.id).size} lists") })
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        TaskFilter.entries.forEachIndexed { index, filter ->
                            val count = when (filter) { TaskFilter.Active -> state.activeCount; TaskFilter.All -> state.visibleTasks.size; TaskFilter.Done -> state.visibleTasks.size - state.activeCount }
                            SegmentedButton(selected = presentation.filter == filter,
                                onClick = { model.presentation.filter(filter) }, shape = SegmentedButtonDefaults.itemShape(index, 3),
                                label = { Text("${filter.name} ($count)") })
                        }
                    }
                }
                if (state.enabledAccounts.isEmpty()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Your tasks, together", style = MaterialTheme.typography.headlineSmall)
                        Text("Add a CalDAV connection in Settings. Every account and list appears here—no account switching required.", Modifier.padding(vertical = 12.dp))
                        FilledTonalButton(onClick = { settings = true }) { Text("Add a connection") }
                    }
                }
                state.errors["storage"]?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(rows, key = { it.key }, contentType = { it::class.simpleName }) { row ->
                        when (row) {
                            is ListRow.AccountHeader -> {
                                Column {
                                    GroupHeader(row.account.name, "DIRECT CALDAV · ${row.count} lists · ${row.open} active",
                                        row.key in presentation.folds, true, { model.presentation.toggle(row.key) }) {
                                        Action(Icons.Outlined.CreateNewFolder, "Create list in ${row.account.name}", !busy && row.account.id !in state.errors) {
                                            editor = ListEditor(row.account.id)
                                        }
                                    }
                                    state.errors[row.account.id]?.let { ErrorLabel(it) }
                                }
                            }
                            is ListRow.ListHeader -> {
                                val list = row.list
                                val available = !busy && list.writable && list.id !in state.errors && list.accountId !in state.errors
                                Column {
                                    GroupHeader(list.name, "${row.open} active${if (!list.writable) " · Read-only" else ""}",
                                        row.key in presentation.folds, false, { model.presentation.toggle(row.key) }) {
                                        Action(Icons.Outlined.Edit, "Rename ${list.name}", available) { editor = ListEditor(list.accountId, list.id, list.name) }
                                        Action(Icons.Outlined.DeleteOutline, "Delete ${list.name}", available && list.taskOnly) {
                                            confirm = Confirmation("Delete ${list.name}?", "Permanently delete this task-only list and all its tasks from the server. This cannot be undone.") {
                                                model.perform { model.repository.listAction(list.accountId, list.id, null, delete = true) }
                                            }
                                        }
                                        Action(Icons.Outlined.Add, "Add task to ${list.name}", available) { model.compose(list) }
                                    }
                                    state.errors[list.id]?.let { ErrorLabel(it) }
                                    if (row.key !in presentation.folds) model.drafts[list.id]?.let { draft ->
                                        DraftComposer(draft, busy, { model.drafts[list.id] = draft.copy(text = it) },
                                            { model.saveDraft(list.id) }, { model.drafts.remove(list.id) })
                                    }
                                }
                            }
                            is ListRow.TaskItem -> {
                                val task = row.item.task
                                val list = state.lists.first { it.id == task.listId }
                                val editable = !busy && list.writable && list.id !in state.errors && list.accountId !in state.errors && task.editable && !task.recurring
                                TaskCard(task, row.item.depth, editable,
                                    toggle = { model.perform { model.repository.editTask(task.id, completed = !task.completed) } },
                                    edit = { model.compose(list, Draft(task.title, editId = task.id)) },
                                    child = { model.compose(list, Draft(parentUid = task.uid)) },
                                    delete = {
                                        confirm = Confirmation("Delete task?", "Permanently delete “${task.title}” from the server? Its subtasks will be kept.") {
                                            model.perform { model.repository.editTask(task.id, delete = true) }
                                        }
                                    })
                            }
                            is ListRow.Empty -> Text("No ${if (presentation.filter == TaskFilter.All) "" else presentation.filter.name.lowercase() + " "}tasks",
                                Modifier.padding(start = 40.dp, top = 4.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { item ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(item.title) }, text = { Text(item.body) },
            confirmButton = { TextButton(onClick = { item.action(); confirm = null }, enabled = !busy) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
    editor?.let { item ->
        var name by remember(item) { mutableStateOf(item.initial) }
        AlertDialog(onDismissRequest = { editor = null }, title = { Text(if (item.listId == null) "New task list" else "Rename list") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("List name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                model.perform { model.repository.listAction(item.accountId, item.listId, name); editor = null }
            }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editor = null }) { Text("Cancel") } })
    }
}

@Composable
private fun GroupHeader(title: String, subtitle: String, folded: Boolean, primary: Boolean, toggle: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = if (primary) 12.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = toggle) { Icon(if (folded) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                if (folded) "Expand $title" else "Collapse $title") }
            Surface(onClick = toggle, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(title, style = if (primary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        actions()
    }
}

@Composable
private fun TaskCard(task: Task, depth: Int, editable: Boolean, toggle: () -> Unit, edit: () -> Unit, child: () -> Unit, delete: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(4) * 12).dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.completed, onCheckedChange = { toggle() }, enabled = editable)
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null)
                if (task.recurring || !task.editable) Text("Read-only · edit in another client", style = MaterialTheme.typography.labelSmall)
            }
            Action(Icons.Outlined.Edit, "Edit ${task.title}", editable, edit)
            Action(Icons.AutoMirrored.Outlined.SubdirectoryArrowRight, "Add subtask to ${task.title}", editable, child)
            Action(Icons.Outlined.DeleteOutline, "Delete ${task.title}", editable, delete)
        }
    }
}

@Composable
private fun DraftComposer(draft: Draft, busy: Boolean, change: (String) -> Unit, save: () -> Unit, cancel: () -> Unit) {
    Column(Modifier.padding(start = 16.dp, bottom = 8.dp)) {
        OutlinedTextField(value = draft.text, onValueChange = change, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (draft.editId != null) "Edit task" else if (draft.parentUid != null) "New subtask" else "New task") },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!busy && draft.text.isNotBlank()) save() }))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = save, enabled = !busy && draft.text.isNotBlank()) { Text("Save") }
            TextButton(onClick = cancel, enabled = !busy) { Text("Cancel") }
        }
    }
}

@Composable
private fun ErrorLabel(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
}

@Composable
internal fun Action(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
}

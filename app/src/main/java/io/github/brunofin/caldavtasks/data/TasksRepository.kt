package io.github.brunofin.caldavtasks.data

import android.content.Context
import io.github.brunofin.caldavtasks.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class TasksRepository(context: Context) {
    private val store = SecureStore(context)
    private val dav = CalDavClient()
    private val mutex = Mutex()
    private var storageFailed = false
    private val mutable = MutableStateFlow(try { store.read() } catch (_: Exception) {
        storageFailed = true
        Snapshot(errors = mapOf("storage" to "Encrypted storage could not be opened. No data was overwritten. Restore access to the device key or clear app data to start over."))
    })
    val state = mutable.asStateFlow()

    private suspend fun operation(block: () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!storageFailed) { "Encrypted storage is unavailable. No changes were made." }
            mutable.value = mutable.value.copy(busy = true)
            try { block() }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private fun persist(snapshot: Snapshot) {
        store.write(snapshot)
        mutable.value = snapshot.copy(busy = true)
    }
    suspend fun saveAccount(id: String?, name: String, url: String, username: String, password: String, enabled: Boolean) = operation {
        val old = mutable.value.accounts.find { it.id == id }
        val secret = password.ifEmpty { old?.password.orEmpty() }
        require(secret.isNotEmpty()) { "Enter an app password." }
        require(username.isNotBlank()) { "Enter a username." }
        val account = Account(id ?: UUID.randomUUID().toString(), validTitle(name), CalDavClient.validateUrl(url).toString(), username.trim(), secret, enabled)
        persist(mutable.value.copy(accounts = mutable.value.accounts.filterNot { it.id == account.id } + account))
        refreshLocked()
    }
    suspend fun removeAccount(id: String) = operation {
        val state = mutable.value
        val removed = state.lists.filter { it.accountId == id }.map { it.id }.toSet()
        persist(state.copy(accounts = state.accounts.filterNot { it.id == id }, lists = state.lists.filterNot { it.id in removed },
            tasks = state.tasks.filterNot { it.listId in removed }, errors = state.errors - id - removed))
    }
    suspend fun refresh() = operation { refreshLocked() }
    private fun refreshLocked() {
        val initial = mutable.value
        val lists = initial.lists.toMutableList()
        val tasks = initial.tasks.toMutableList()
        val errors = mutableMapOf<String, String>()
        for (account in initial.accounts.filter { it.enabled }) {
            try {
                val discovered = dav.discover(account).lists
                val liveIds = discovered.map { it.id }.toSet()
                val removed = lists.filter { it.accountId == account.id && it.id !in liveIds }.map { it.id }.toSet()
                lists.removeAll { it.accountId == account.id }
                lists += discovered
                tasks.removeAll { it.listId in removed }
                for (list in discovered) {
                    try {
                        val fetched = dav.tasks(account, list)
                        tasks.removeAll { it.listId == list.id }
                        tasks += fetched
                    } catch (e: Exception) { errors[list.id] = safeMessage(e) }
                }
            } catch (e: Exception) { errors[account.id] = safeMessage(e) }
        }
        persist(initial.copy(lists = lists, tasks = tasks, errors = errors, lastSync = System.currentTimeMillis()))
    }
    private fun target(listId: String): Pair<Account, TaskList> {
        val s = mutable.value
        val list = s.lists.firstOrNull { it.id == listId } ?: error("This list no longer exists. Refresh.")
        val account = s.accounts.firstOrNull { it.id == list.accountId && it.enabled } ?: error("This account is disabled or removed.")
        require(list.writable && list.id !in s.errors && account.id !in s.errors) { "This list is unavailable or read-only. Refresh before editing." }
        return account to list
    }
    suspend fun addTask(listId: String, title: String, parentUid: String?) = operation {
        val (account, list) = target(listId)
        dav.createTask(account, list, validTitle(title), parentUid)
        refreshLocked()
    }
    suspend fun editTask(id: String, title: String? = null, completed: Boolean? = null, delete: Boolean = false) = operation {
        val task = mutable.value.tasks.firstOrNull { it.id == id } ?: error("Task no longer exists. Refresh.")
        val (account, _) = target(task.listId)
        if (delete) dav.deleteTask(account, task) else dav.updateTask(account, task, title, completed)
        refreshLocked()
    }
    suspend fun listAction(accountId: String, listId: String?, name: String?, delete: Boolean = false) = operation {
        val account = mutable.value.accounts.firstOrNull { it.id == accountId && it.enabled } ?: error("Account unavailable.")
        require(account.id !in mutable.value.errors) { "Account unavailable. Refresh first." }
        if (listId == null) dav.createList(account, validTitle(name.orEmpty())) else {
            val (_, list) = target(listId)
            if (delete) dav.deleteList(account, list) else dav.renameList(account, list, validTitle(name.orEmpty()))
        }
        refreshLocked()
    }
    companion object {
        fun safeMessage(error: Exception): String = when (error) {
            is DavException -> error.message ?: "CalDAV request failed."
            is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { !it.contains("http", true) }
                ?: "Invalid operation. Check settings and refresh."
            else -> "Operation failed. Check connection and device storage, then refresh."
        }
    }
}

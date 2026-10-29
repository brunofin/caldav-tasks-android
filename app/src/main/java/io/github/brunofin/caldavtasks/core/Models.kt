package io.github.brunofin.caldavtasks.core

import java.util.Locale

data class Account(
    val id: String, val name: String, val url: String, val username: String,
    val password: String, val enabled: Boolean = true,
)

data class TaskList(
    val id: String, val accountId: String, val name: String, val url: String,
    val taskOnly: Boolean = false, val writable: Boolean = true,
)

data class Task(
    val id: String, val listId: String, val uid: String, val title: String,
    val completed: Boolean, val parentUid: String? = null, val recurring: Boolean = false,
    val url: String, val etag: String, val calendar: String, val editable: Boolean = true,
)

enum class TaskFilter { Active, All, Done;
    fun includes(task: Task) = this == All || (this == Done) == task.completed
}

data class Snapshot(
    val accounts: List<Account> = emptyList(), val lists: List<TaskList> = emptyList(),
    val tasks: List<Task> = emptyList(), val errors: Map<String, String> = emptyMap(),
    val busy: Boolean = false, val lastSync: Long = 0,
) {
    val enabledAccounts get() = accounts.filter { it.enabled }.sortedWith(
        compareByDescending<Account> { account ->
            val ids = lists.filter { it.accountId == account.id }.map { it.id }.toSet()
            tasks.count { it.listId in ids && !it.completed }
        }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
    )
    fun listsFor(accountId: String) = lists.filter { it.accountId == accountId }.sortedWith(
        compareByDescending<TaskList> { list -> tasks.count { it.listId == list.id && !it.completed } }
            .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
    )
    val visibleTasks get(): List<Task> {
        val enabled = enabledAccounts.map { it.id }.toSet()
        val ids = lists.filter { it.accountId in enabled }.map { it.id }.toSet()
        return tasks.filter { it.listId in ids }
    }
    val activeCount get() = visibleTasks.count { !it.completed }
}

data class TaskDepth(val task: Task, val depth: Int)

/** Filter first, then walk a deterministic, cycle-safe forest. Hidden parents do not hide children. */
fun taskTree(tasks: List<Task>, filter: TaskFilter): List<TaskDepth> {
    val visible = tasks.filter(filter::includes).sortedWith(
        compareBy<Task> { it.completed }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    )
    val byUid = visible.associateBy { it.uid }
    val children = visible.groupBy { it.parentUid }
    val visited = mutableSetOf<String>()
    val result = mutableListOf<TaskDepth>()
    fun visit(task: Task) {
        val stack = java.util.ArrayDeque<Pair<Task, Int>>()
        stack.addLast(task to 0)
        while (stack.isNotEmpty()) {
            val (current, depth) = stack.removeLast()
            if (!visited.add(current.id)) continue
            result += TaskDepth(current, depth.coerceAtMost(8))
            children[current.uid].orEmpty().asReversed().forEach { stack.addLast(it to depth + 1) }
        }
    }
    visible.filter { it.parentUid !in byUid || it.parentUid == it.uid }.forEach(::visit)
    visible.forEach(::visit)
    return result
}

fun validTitle(value: String): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= 2000) { "Enter a title between 1 and 2000 characters." }
    require(!it.contains('\r') && !it.contains('\n')) { "Titles cannot contain line breaks." }
}

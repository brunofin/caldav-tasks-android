package io.github.brunofin.caldavtasks.core

sealed interface ListRow {
    val key: String
    data class AccountHeader(val account: Account, val open: Int, val count: Int) : ListRow {
        override val key = "account:${account.id}"
    }
    data class ListHeader(val list: TaskList, val open: Int) : ListRow {
        override val key = "list:${list.id}"
    }
    data class TaskItem(val item: TaskDepth) : ListRow { override val key = "task:${item.task.id}" }
    data class Empty(val listId: String) : ListRow { override val key = "empty:$listId" }
}

fun buildRows(state: Snapshot, filter: TaskFilter, folds: Set<String>): List<ListRow> = buildList {
    for (account in state.enabledAccounts) {
        val lists = state.listsFor(account.id)
        val ids = lists.map { it.id }.toSet()
        add(ListRow.AccountHeader(account, state.tasks.count { it.listId in ids && !it.completed }, lists.size))
        if ("account:${account.id}" in folds) continue
        for (list in lists) {
            val tasks = state.tasks.filter { it.listId == list.id }
            add(ListRow.ListHeader(list, tasks.count { !it.completed }))
            if ("list:${list.id}" in folds) continue
            val tree = taskTree(tasks, filter)
            if (tree.isEmpty()) add(ListRow.Empty(list.id))
            else tree.forEach { add(ListRow.TaskItem(it)) }
        }
    }
}

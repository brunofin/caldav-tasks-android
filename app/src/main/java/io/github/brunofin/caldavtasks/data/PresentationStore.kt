package io.github.brunofin.caldavtasks.data

import android.content.Context
import io.github.brunofin.caldavtasks.core.TaskFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Presentation(val filter: TaskFilter = TaskFilter.Active, val folds: Set<String> = emptySet())

/** Shared between the activity and every widget instance; folds never affect synchronization. */
class PresentationStore(context: Context) {
    private val preferences = context.getSharedPreferences("presentation", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(Presentation(
        runCatching { TaskFilter.valueOf(preferences.getString("filter", "Active")!!) }.getOrDefault(TaskFilter.Active),
        preferences.getStringSet("folds", emptySet())!!.toSet(),
    ))
    val state = mutable.asStateFlow()
    @Synchronized fun filter(value: TaskFilter) = save(mutable.value.copy(filter = value))
    @Synchronized fun toggle(id: String) {
        val folds = mutable.value.folds
        save(mutable.value.copy(folds = if (id in folds) folds - id else folds + id))
    }
    @Synchronized fun expand(vararg ids: String) = save(mutable.value.copy(folds = mutable.value.folds - ids.toSet()))
    private fun save(value: Presentation) {
        preferences.edit().putString("filter", value.filter.name).putStringSet("folds", value.folds).apply()
        mutable.value = value
    }
}

package io.github.brunofin.caldavtasks.widget

import android.content.Intent
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.brunofin.caldavtasks.R
import io.github.brunofin.caldavtasks.TasksApplication
import io.github.brunofin.caldavtasks.core.*
import io.github.brunofin.caldavtasks.data.Presentation

class TasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(application as TasksApplication)

    private class Factory(private val app: TasksApplication) : RemoteViewsFactory {
        private var state = Snapshot()
        private var presentation = Presentation()
        private var rows = emptyList<ListRow>()
        override fun onCreate() { onDataSetChanged() }
        override fun onDataSetChanged() {
            state = app.repository.state.value
            presentation = app.presentation.state.value
            rows = buildRows(state, presentation.filter, presentation.folds)
        }
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 1
        override fun hasStableIds() = false
        override fun getItemId(position: Int) = position.toLong()
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewAt(position: Int): RemoteViews? {
            val row = rows.getOrNull(position) ?: return null
            val views = RemoteViews(app.packageName, R.layout.widget_row)
            views.setViewVisibility(R.id.row_add, View.GONE)
            views.setViewVisibility(R.id.row_subtitle, View.VISIBLE)
            views.setViewPadding(R.id.row_root, 0, 0, 0, 0)
            views.setTextColor(R.id.row_title, app.getColor(R.color.widget_text))
            views.setInt(R.id.row_title, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
            fun fill(op: String, id: String? = null, listId: String? = null) = Intent().apply {
                putExtra("op", op); id?.let { putExtra("id", it) }; listId?.let { putExtra("listId", it) }
            }
            fun fold() {
                views.setTextViewText(R.id.row_check, if (row.key in presentation.folds) "›" else "⌄")
                views.setContentDescription(R.id.row_check, if (row.key in presentation.folds) "Expand" else "Collapse")
                views.setOnClickFillInIntent(R.id.row_check, fill("fold", row.key))
                views.setOnClickFillInIntent(R.id.row_label, fill("fold", row.key))
            }
            when (row) {
                is ListRow.AccountHeader -> {
                    fold()
                    views.setTextViewText(R.id.row_title, row.account.name)
                    views.setTextColor(R.id.row_title, app.getColor(R.color.widget_primary))
                    views.setTextViewText(R.id.row_subtitle, "${row.count} lists · ${row.open} active")
                }
                is ListRow.ListHeader -> {
                    fold()
                    views.setTextColor(R.id.row_title, app.getColor(R.color.widget_text))
                    views.setTextViewText(R.id.row_title, row.list.name)
                    views.setTextViewText(R.id.row_subtitle, "${row.open} active")
                    if (row.list.writable && row.list.id !in state.errors && row.list.accountId !in state.errors) {
                        views.setViewVisibility(R.id.row_add, View.VISIBLE)
                        views.setOnClickFillInIntent(R.id.row_add, fill("open", listId = row.list.id))
                    }
                }
                is ListRow.TaskItem -> {
                    val task = row.item.task
                    val list = state.lists.first { it.id == task.listId }
                    val writable = list.writable && task.editable && !task.recurring && list.id !in state.errors && list.accountId !in state.errors
                    views.setTextViewText(R.id.row_check, if (task.completed) "☑" else "○")
                    views.setContentDescription(R.id.row_check, if (task.completed) "Reopen ${task.title}" else "Complete ${task.title}")
                    views.setOnClickFillInIntent(R.id.row_check, if (writable) fill("toggle", task.id) else fill("open"))
                    views.setTextViewText(R.id.row_title, task.title)
                    views.setTextColor(R.id.row_title, app.getColor(R.color.widget_text))
                    views.setViewPadding(R.id.row_root, (row.item.depth.coerceAtMost(4) * 12 * app.resources.displayMetrics.density).toInt(), 0, 0, 0)
                    views.setInt(R.id.row_title, "setPaintFlags", Paint.ANTI_ALIAS_FLAG or if (task.completed) Paint.STRIKE_THRU_TEXT_FLAG else 0)
                    views.setViewVisibility(R.id.row_subtitle, if (task.recurring || !task.editable) View.VISIBLE else View.GONE)
                    views.setTextViewText(R.id.row_subtitle, "Read-only")
                    views.setOnClickFillInIntent(R.id.row_label, fill("open"))
                }
                is ListRow.Empty -> {
                    views.setTextViewText(R.id.row_check, "")
                    views.setViewVisibility(R.id.row_subtitle, View.GONE)
                    views.setTextViewText(R.id.row_title, "No ${if (presentation.filter == TaskFilter.All) "" else presentation.filter.name.lowercase() + " "}tasks")
                    views.setOnClickFillInIntent(R.id.row_label, fill("open", listId = row.listId))
                }
            }
            return views
        }
    }
}

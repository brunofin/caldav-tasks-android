package io.github.brunofin.caldavtasks.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*
import io.github.brunofin.caldavtasks.*
import io.github.brunofin.caldavtasks.core.TaskFilter

/** RemoteViews collection: native launcher scrolling, no embedded WebView or second task database. */
class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { update(context, manager, it) } }
    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java)).forEach { update(context, manager, it) }
        }
        private fun action(context: Context, widgetId: Int, op: String): PendingIntent {
            val intent = Intent(context, WidgetActionActivity::class.java).apply {
                data = Uri.parse("caldavtasks://widget/$widgetId/$op")
                putExtra("op", op)
            }
            return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        @Suppress("DEPRECATION")
        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val app = context.applicationContext as TasksApplication
            val state = app.repository.state.value
            val filter = app.presentation.state.value.filter
            val views = RemoteViews(context.packageName, R.layout.widget_tasks)
            val error = context.getSharedPreferences("widget-status", 0).getString("error", null)
                ?: state.errors.values.firstOrNull()
            views.setTextViewText(R.id.widget_summary, "${state.activeCount} active · ${state.visibleTasks.size - state.activeCount} done · ${state.enabledAccounts.size} connections" +
                if (error != null) "\n$error" else if (state.busy) "\nRefreshing…" else "")
            views.setOnClickPendingIntent(R.id.widget_title, action(context, id, "open"))
            views.setOnClickPendingIntent(R.id.widget_empty, action(context, id, "open"))
            views.setOnClickPendingIntent(R.id.widget_refresh, action(context, id, "refresh"))
            listOf(R.id.widget_active to TaskFilter.Active, R.id.widget_all to TaskFilter.All, R.id.widget_done to TaskFilter.Done).forEach { (viewId, choice) ->
                val count = when (choice) { TaskFilter.Active -> state.activeCount; TaskFilter.All -> state.visibleTasks.size; TaskFilter.Done -> state.visibleTasks.size - state.activeCount }
                views.setTextViewText(viewId, "${if (choice == filter) "• " else ""}${choice.name} ($count)")
                views.setOnClickPendingIntent(viewId, action(context, id, "filter:${choice.name}"))
            }
            val service = Intent(context, TasksWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("caldavtasks://collection/$id")
            }
            views.setRemoteAdapter(R.id.widget_list, service)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            val template = Intent(context, WidgetActionActivity::class.java).apply { data = Uri.parse("caldavtasks://widget/$id/row") }
            views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(context, id, template,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }
}

/** Activity PendingIntents preserve Android's user-initiated launch rules; all network work goes to WorkManager. */
class WidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TasksApplication
        val op = intent.getStringExtra("op").orEmpty()
        when {
            op.startsWith("filter:") -> runCatching { TaskFilter.valueOf(op.substringAfter(':')) }.getOrNull()?.let { app.presentation.filter(it) }
            op == "fold" -> intent.getStringExtra("id")?.let { app.presentation.toggle(it) }
            op == "refresh" || op == "toggle" -> {
                val id = intent.getStringExtra("id").orEmpty()
                val work = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(workDataOf("action" to op, "id" to id)).build()
                WorkManager.getInstance(this).enqueueUniqueWork("widget-$op-$id", ExistingWorkPolicy.KEEP, work)
            }
            else -> startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                intent.getStringExtra("listId")?.let { putExtra("listId", it) }
            })
        }
        TasksWidget.updateAll(app)
        finish()
    }
}

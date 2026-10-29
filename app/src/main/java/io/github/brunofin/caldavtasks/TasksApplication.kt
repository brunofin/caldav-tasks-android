package io.github.brunofin.caldavtasks

import android.app.Application
import androidx.work.*
import io.github.brunofin.caldavtasks.data.PresentationStore
import io.github.brunofin.caldavtasks.data.TasksRepository
import io.github.brunofin.caldavtasks.widget.TasksWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import java.util.concurrent.TimeUnit

class TasksApplication : Application() {
    val repository by lazy { TasksRepository(this) }
    val presentation by lazy { PresentationStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("caldav-sync", ExistingPeriodicWorkPolicy.KEEP, periodic)
        scope.launch { repository.state.collect { if (!it.busy) TasksWidget.updateAll(this@TasksApplication) } }
        scope.launch { presentation.state.drop(1).collect { TasksWidget.updateAll(this@TasksApplication) } }
    }
}

class SyncWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TasksApplication
        return try {
            when (inputData.getString("action")) {
                "toggle" -> {
                    val id = inputData.getString("id") ?: return Result.failure()
                    val task = app.repository.state.value.tasks.find { it.id == id } ?: return Result.failure()
                    app.repository.editTask(id, completed = !task.completed)
                }
                else -> app.repository.refresh()
            }
            app.getSharedPreferences("widget-status", 0).edit().remove("error").apply()
            TasksWidget.updateAll(app)
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            app.getSharedPreferences("widget-status", 0).edit().putString("error", TasksRepository.safeMessage(e)).apply()
            TasksWidget.updateAll(app)
            Result.failure()
        }
    }
}

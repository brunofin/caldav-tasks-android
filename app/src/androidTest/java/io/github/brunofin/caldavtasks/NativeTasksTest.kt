package io.github.brunofin.caldavtasks

import android.content.ContextWrapper
import android.content.ComponentName
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.brunofin.caldavtasks.core.TaskFilter
import io.github.brunofin.caldavtasks.data.SecureStore
import io.github.brunofin.caldavtasks.widget.TasksWidget
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class NativeTasksTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<FixtureApplication>()
    @Before fun resetFixture() {
        app.server.reset()
        runBlocking { app.repository.refresh() }
        app.presentation.filter(TaskFilter.Active)
        app.presentation.expand(*app.presentation.state.value.folds.toTypedArray())
        compose.waitUntil(10_000) { !app.repository.state.value.busy }
        compose.waitForIdle()
    }
    @After fun removeTestTask() {
        // Freeform Waydroid can leave a finished activity PAUSED until its task is removed.
        // Remove only this test activity's task, never restart/change the container.
        compose.runOnUiThread { compose.activity.finishAndRemoveTask() }
    }
    @Test fun drawerOpensConnectionSettings() {
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Connections").assertIsDisplayed()
        compose.onNodeWithText("HTTPS CalDAV URL").assertExists()
        compose.onNodeWithText("Save connection").assertIsNotEnabled()
    }
    @Test fun allAccountsRemainTogetherAndFoldPreservesCounts() {
        assertEquals(4, app.repository.state.value.activeCount)
        compose.onNodeWithText("Test the Android app").assertExists()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Test the Android app").assertDoesNotExist()
        assertEquals(4, app.repository.state.value.activeCount)
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Test the Android app").assertExists()
        compose.onNodeWithTag("task-list").performScrollToNode(hasText("Work"))
        compose.onNodeWithText("Work").assertExists()
    }
    @Test fun inlineDraftSurvivesFoldingThenWritesConditionally() {
        compose.onNodeWithContentDescription("Add task to Home").performClick()
        compose.onNodeWithText("New task").performTextInput("A native fixture task")
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("New task").assertDoesNotExist()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("A native fixture task").assertExists()
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { app.repository.state.value.tasks.any { it.title == "A native fixture task" } }
        assertEquals(1, app.server.writes)
    }
    @Test fun taskCompletionIsConfirmedAndGlobalDoneFilterWorks() {
        compose.onNodeWithContentDescription("Complete Test the Android app").performClick()
        compose.waitUntil(10_000) { app.repository.state.value.tasks.any { it.title == "Test the Android app" && it.completed } }
        compose.onNodeWithText("Test the Android app").assertDoesNotExist()
        compose.onNode(hasText("Done (1)")).performClick()
        compose.onNodeWithText("Test the Android app").assertExists()
        assertEquals(3, app.repository.state.value.activeCount)
        assertEquals(1, app.server.writes)
    }
    @Test fun encryptedStoreAndWidgetLayoutsWorkOnAndroid() {
        val source = app.repository.state.value.copy(busy = false)
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(cacheDir, "secure-store-check").apply { mkdirs() }
        }
        val store = SecureStore(context)
        store.write(source)
        assertEquals(source, store.read())
        val bytes = File(context.noBackupFilesDir, "accounts-and-cache.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("synthetic-password"))
        assertFalse(bytes.contains("Test the Android app"))
        for (layout in listOf(R.layout.widget_tasks, R.layout.widget_row)) {
            assertNotNull(RemoteViews(app.packageName, layout).apply(app, FrameLayout(app)))
        }
    }
    @Test fun widgetCollectionCanFoldFilterAndCompleteTasks() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 20261029)
        var id = -1
        var widget: AppWidgetHostView? = null
        automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, TasksWidget::class.java)))
            host.startListening()
            compose.runOnUiThread {
                widget = host.createView(compose.activity, id, manager.getAppWidgetInfo(id))
                compose.activity.addContentView(widget, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 800))
            }
            TasksWidget.updateAll(app)
            fun title(view: View?, value: String): TextView? {
                if (view is TextView && view.text.toString() == value) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    title(view.getChildAt(index), value)?.let { return it }
                }
                return null
            }
            fun loaded(value: String): Boolean {
                var found = false
                compose.runOnUiThread { found = title(widget, value) != null }
                return found
            }
            compose.waitUntil(15_000) { loaded("Test the Android app") }
            // Real collection PendingIntent -> WidgetActionActivity -> shared presentation.
            compose.runOnUiThread {
                val header = title(widget, "Home")!!.parent.parent as View
                header.findViewById<View>(R.id.row_check).performClick()
            }
            val listId = app.repository.state.value.lists.single { it.name == "Home" }.id
            compose.waitUntil(10_000) { "list:$listId" in app.presentation.state.value.folds }
            app.presentation.expand("list:$listId")
            compose.waitUntil(15_000) { loaded("Test the Android app") }
            // Real widget task click -> WorkManager -> conditional fixture PUT -> refreshed cache.
            compose.runOnUiThread {
                val row = title(widget, "Test the Android app")!!.parent.parent as View
                row.findViewById<View>(R.id.row_check).performClick()
            }
            compose.waitUntil(15_000) { app.repository.state.value.tasks.any { it.title == "Test the Android app" && it.completed } }
            compose.runOnUiThread { widget!!.findViewById<View>(R.id.widget_done).performClick() }
            compose.waitUntil(10_000) { app.presentation.state.value.filter == TaskFilter.Done }
            compose.waitUntil(15_000) { loaded("Test the Android app") }
        } finally {
            compose.runOnUiThread { (widget?.parent as? ViewGroup)?.removeView(widget) }
            if (id != -1) host.deleteAppWidgetId(id)
            host.stopListening()
            automation.dropShellPermissionIdentity()
        }
    }
}

package io.github.brunofin.caldavtasks

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.brunofin.caldavtasks.core.*
import io.github.brunofin.caldavtasks.data.TasksRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class Draft(val text: String = "", val editId: String? = null, val parentUid: String? = null)

class TasksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TasksApplication
    val repository = app.repository
    val presentation = app.presentation
    val drafts = mutableStateMapOf<String, Draft>()
    var message by mutableStateOf<String?>(null)
    private var submitting by mutableStateOf(false)
    val busy get() = submitting || repository.state.value.busy
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        submitting = true
        viewModelScope.launch {
            try { action() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                message = TasksRepository.safeMessage(e)
            } finally { submitting = false }
        }
    }
    fun compose(list: TaskList, draft: Draft = Draft()) {
        presentation.expand("account:${list.accountId}", "list:${list.id}")
        drafts[list.id] = draft
    }
    fun saveDraft(listId: String) {
        val draft = drafts[listId] ?: return
        perform {
            if (draft.editId != null) repository.editTask(draft.editId, title = validTitle(draft.text))
            else repository.addTask(listId, validTitle(draft.text), draft.parentUid)
            drafts.remove(listId)
        }
    }
}

package io.github.brunofin.caldavtasks

import io.github.brunofin.caldavtasks.core.*

/** Runs without Android or third-party libraries; also invoked by JUnit. */
object CoreChecks {
    fun runAll() {
        var count = 0
        fun test(name: String, body: () -> Unit) { body(); println("PASS $name"); count++ }
        fun task(uid: String, list: String = "l1", parent: String? = null, done: Boolean = false) =
            Task("$list|$uid", list, uid, uid, done, parent, url = "https://example.test/$uid.ics", etag = "\"1\"", calendar = "")
        fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) }
        fun parse(raw: String) = ICalendar.parse(raw, "l1", "https://example.test/t.ics", "\"1\"")
        val raw = """
            BEGIN:VCALENDAR
            VERSION:2.0
            X-CUSTOM-CALENDAR:keep me
            BEGIN:VTODO
            UID:one
            SUMMARY;LANGUAGE=en:Original
            STATUS:NEEDS-ACTION
            DUE;TZID=Europe/London:20261101T090000
            X-CUSTOM-TASK:keep this too
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder
            TRIGGER:-PT15M
            END:VALARM
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        test("parse VTODO only") {
            check(parse(raw).single().title == "Original")
            check(parse(raw.replace("VTODO", "VEVENT")).isEmpty())
        }
        test("title edit preserves dates, calendar metadata and alarms") {
            val edited = ICalendar.edit(parse(raw).single(), title = "Updated; title, with \\ slash")
            check(parse(edited).single().title == "Updated; title, with \\ slash")
            listOf("DUE;TZID=Europe/London:20261101T090000", "X-CUSTOM-CALENDAR:keep me", "X-CUSTOM-TASK:keep this too",
                "BEGIN:VALARM", "DESCRIPTION:Reminder", "TRIGGER:-PT15M").forEach { check(it in edited) }
        }
        test("complete and reopen round trip") {
            val done = ICalendar.edit(parse(raw).single(), completed = true)
            check(parse(done).single().completed)
            check("COMPLETED:" in done && "PERCENT-COMPLETE:100" in done)
            val reopened = ICalendar.edit(parse(done).single(), completed = false)
            check(!parse(reopened).single().completed && "\r\nCOMPLETED:" !in reopened)
        }
        test("UTF-8 folding never splits a code point or exceeds 75 octets") {
            val title = "é🙂中".repeat(120)
            val created = ICalendar.create(title, uid = "unicode")
            check(created.split("\r\n").all { it.toByteArray().size <= 75 })
            check(parse(created).single().title == title)
        }
        test("folded properties and text escapes") {
            val folded = raw.replace("SUMMARY;LANGUAGE=en:Original", "SUMMARY:Hello\\, world\\nSecond\r\n  line")
            check(parse(folded).single().title == "Hello, world\nSecond line")
        }
        test("parent relationship round trip") {
            check(parse(ICalendar.create("Child", "parent-id", "child-id")).single().parentUid == "parent-id")
        }
        test("recurring tasks and override resources refuse mutations") {
            val recurring = raw.replace("UID:one", "UID:one\r\nRRULE:FREQ=DAILY")
            rejects { ICalendar.edit(parse(recurring).single(), completed = true) }
            val override = raw.substringBefore("END:VCALENDAR") + "BEGIN:VTODO\r\nUID:one\r\nRECURRENCE-ID:20261101T090000Z\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
            check(parse(override).size == 1 && !parse(override).single().editable)
            rejects { ICalendar.edit(parse(override).single(), title = "x") }
        }
        test("mixed resources and missing ETags refuse mutations") {
            val mixed = raw.replace("END:VCALENDAR", "BEGIN:VEVENT\r\nUID:event\r\nEND:VEVENT\r\nEND:VCALENDAR")
            check(!parse(mixed).single().editable)
            rejects { ICalendar.edit(parse(mixed).single(), title = "x") }
            check(!ICalendar.parse(raw, "l1", "url", "").single().editable)
        }
        test("malformed components and title injection rejected") {
            rejects { parse(raw.replace("END:VTODO", "END:VEVENT")) }
            rejects { ICalendar.create(" ") }
            rejects { ICalendar.create("Title\r\nBEGIN:VEVENT") }
            rejects { ICalendar.create("a".repeat(2001)) }
        }
        val a = Account("a", "Alpha", "https://example.test/", "user", "secret")
        val b = a.copy(id = "b", name = "Beta")
        val lists = listOf(TaskList("l1", "a", "First", "url"), TaskList("l2", "a", "Second", "url"), TaskList("l3", "b", "Third", "url"))
        val tasks = listOf(task("one"), task("two", "l2"), task("three", "l2"), task("four", "l3"), task("five", "l3"))
        val state = Snapshot(listOf(b, a), lists, tasks)
        test("accounts sort by aggregate open counts, lists by own count") {
            check(state.enabledAccounts.map { it.id } == listOf("a", "b"))
            check(state.listsFor("a").map { it.id } == listOf("l2", "l1"))
        }
        test("alphabetical ties and disabled account counts") {
            check(state.copy(tasks = emptyList()).enabledAccounts.first().id == "a")
            val disabled = state.copy(accounts = listOf(a.copy(enabled = false), b))
            check(disabled.activeCount == 2 && disabled.visibleTasks.size == 2)
        }
        test("folds preserve global counts and empty groups under Done filter") {
            val rows = buildRows(state, TaskFilter.Done, setOf("account:a", "list:l3"))
            check(rows.filterIsInstance<ListRow.AccountHeader>().size == 2)
            check(rows.filterIsInstance<ListRow.TaskItem>().isEmpty())
            check(state.activeCount == 5)
            check(buildRows(state, TaskFilter.Done, emptySet()).filterIsInstance<ListRow.Empty>().size == 3)
        }
        test("filtered parents leave children visible at top level") {
            val tree = taskTree(listOf(task("parent", done = true), task("child", parent = "parent")), TaskFilter.Active)
            check(tree.single().depth == 0 && tree.single().task.uid == "child")
        }
        test("subtrees stay contiguous and cycles/orphans do not lose tasks") {
            val tree = taskTree(listOf(task("A"), task("Z", parent = "A"), task("B"), task("C", parent = "D"), task("D", parent = "C"), task("E", parent = "missing")), TaskFilter.All)
            check(tree.map { it.task.uid }.take(2) == listOf("A", "Z"))
            check(tree.size == 6 && tree.map { it.task.id }.toSet().size == 6)
        }
        test("deep trees cannot overflow the call stack") {
            val tree = taskTree((0 until 10_000).map { task("n$it", parent = if (it == 0) null else "n${it - 1}") }, TaskFilter.All)
            check(tree.size == 10_000 && tree.maxOf { it.depth } == 8)
        }
        test("account-scoped list and task identities remain independent") {
            val one = task("same", "a|https://example.test/tasks/")
            val two = task("same", "b|https://example.test/tasks/")
            check(one.id != two.id)
        }
        println("$count core checks passed")
    }
}

fun main() = CoreChecks.runAll()

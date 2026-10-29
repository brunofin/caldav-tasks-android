package io.github.brunofin.caldavtasks

import io.github.brunofin.caldavtasks.core.*
import io.github.brunofin.caldavtasks.data.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

object DavChecks {
    private val account = Account("account", "Test", "https://example.test/dav/", "user", "app-secret")
    private fun response(href: String, props: String, status: String = "200 OK") = """<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 $status</d:status></d:propstat></d:response>"""
    private fun multi(vararg responses: String) = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">${responses.joinToString("")}</d:multistatus>"""
    private val collection = """<d:displayname>Tasks &amp; reminders</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>"""
    private fun fake(vararg bodies: Pair<Int, String>): Pair<CalDavClient, MutableList<Request>> {
        val queue = java.util.ArrayDeque(bodies.toList())
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (code, text) = queue.removeFirst()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(text.toResponseBody("application/xml".toMediaType())).build()
        }.build()
        return CalDavClient(http) to requests
    }
    fun runAll() {
        var count = 0
        fun test(name: String, body: () -> Unit) { body(); println("PASS $name"); count++ }
        fun rejected(block: () -> Unit): Throwable = runCatching(block).exceptionOrNull() ?: error("Expected failure")
        test("HTTPS-only, no URL credentials or query tokens") {
            listOf("http://example.test/", "https://user:secret@example.test/", "https://example.test/?token=x", "https://example.test/#x").forEach { url ->
                rejected { CalDavClient.validateUrl(url) }
            }
        }
        test("principal discovery, namespaced XML, VTODO-only listing and account scoping") {
            val (dav, requests) = fake(
                207 to multi(response("/dav/", "<d:current-user-principal><d:href>/principal/</d:href></d:current-user-principal>")),
                207 to multi(response("/principal/", "<c:calendar-home-set><d:href>/calendars/</d:href></c:calendar-home-set>")),
                207 to multi(response("/calendars/tasks/", collection), response("/calendars/events/", collection.replace("VTODO", "VEVENT"))),
            )
            val found = dav.discover(account)
            check(found.home == "https://example.test/calendars/")
            check(found.lists.single().id == "account|https://example.test/calendars/tasks/")
            check(found.lists.single().name == "Tasks & reminders" && found.lists.single().taskOnly)
            check(requests.last().header("Depth") == "1")
            check(requests.all { it.header("Authorization")?.startsWith("Basic ") == true })
        }
        test("cross-origin discovery refused before credentials can leave origin") {
            val (dav, requests) = fake(207 to multi(response("/dav/", "<c:calendar-home-set><d:href>https://evil.test/</d:href></c:calendar-home-set>")))
            rejected { dav.discover(account) }
            check(requests.size == 1)
        }
        test("DOCTYPE and external entity payload refused") {
            val payload = "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" + multi(response("/dav/", "<d:displayname>&secret;</d:displayname>"))
            val (dav, _) = fake(207 to payload)
            check(rejected { dav.discover(account) } is DavException)
        }
        test("failed property statuses do not advertise writable task collections") {
            val (dav, _) = fake(207 to multi(response("/dav/", "<d:displayname>Root</d:displayname>")),
                207 to multi(response("/dav/tasks/", collection, "403 Forbidden")))
            check(dav.discover(account).lists.isEmpty())
        }
        test("REPORT parses CDATA, etags and VTODOs") {
            val calendar = ICalendar.create("Read <this> & that", uid = "one")
            val (dav, requests) = fake(207 to multi(response("/dav/tasks/one.ics", "<d:getetag>&quot;v1&quot;</d:getetag><c:calendar-data><![CDATA[$calendar]]></c:calendar-data>")))
            val list = TaskList("l", "account", "Tasks", "https://example.test/dav/tasks/")
            val task = dav.tasks(account, list).single()
            check(task.title == "Read <this> & that" && task.etag == "\"v1\"")
            check(requests.single().method == "REPORT")
        }
        test("conditional task PUT and 412 conflict are not silently accepted") {
            val task = ICalendar.parse(ICalendar.create("Original", uid = "one"), "l", "https://example.test/dav/tasks/one.ics", "\"v1\"").single()
            val (dav, requests) = fake(412 to "ignored")
            check(rejected { dav.updateTask(account, task, title = "New") }.message!!.contains("changed"))
            check(requests.single().header("If-Match") == "\"v1\"")
        }
        test("new resources use If-None-Match and remain inside their collection") {
            val (dav, requests) = fake(201 to "")
            dav.createTask(account, TaskList("l", "account", "Tasks", "https://example.test/dav/tasks"), "New task", null)
            check(requests.single().header("If-None-Match") == "*")
            check(requests.single().url.encodedPath.startsWith("/dav/tasks/"))
        }
        test("mixed collection deletion is blocked before DELETE") {
            val mixed = collection.replace("<c:comp name=\"VTODO\"/>", "<c:comp name=\"VTODO\"/><c:comp name=\"VEVENT\"/>")
            val (dav, requests) = fake(207 to multi(response("/dav/tasks/", mixed)))
            rejected { dav.deleteList(account, TaskList("l", "account", "Tasks", "https://example.test/dav/tasks/", taskOnly = true)) }
            check(requests.none { it.method == "DELETE" })
        }
        test("task-only collection still checks for VEVENT resources before deletion") {
            val (dav, requests) = fake(207 to multi(response("/dav/tasks/", collection)),
                207 to multi(response("/dav/tasks/event.ics", "<c:calendar-data>BEGIN:VEVENT</c:calendar-data>")))
            rejected { dav.deleteList(account, TaskList("l", "account", "Tasks", "https://example.test/dav/tasks/", taskOnly = true)) }
            check(requests.none { it.method == "DELETE" })
        }
        test("PROPPATCH per-property failure is reported") {
            val (dav, _) = fake(207 to multi(response("/dav/tasks/", "<d:displayname/>", "403 Forbidden")))
            rejected { dav.renameList(account, TaskList("l", "account", "Tasks", "https://example.test/dav/tasks/"), "New") }
        }
        test("request errors do not expose credentials or response bodies") {
            val (dav, _) = fake(401 to "app-secret server diagnostic")
            val error = rejected { dav.discover(account) }
            check("app-secret" !in error.message.orEmpty())
        }
        println("$count CalDAV checks passed")
    }
}

fun main() = DavChecks.runAll()

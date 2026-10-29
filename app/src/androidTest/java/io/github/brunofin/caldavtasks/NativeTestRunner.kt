package io.github.brunofin.caldavtasks

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.runner.AndroidJUnitRunner
import io.github.brunofin.caldavtasks.core.*
import io.github.brunofin.caldavtasks.data.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.util.UUID

/** Test APK only. Intercepts EVERY request and uses a fresh encrypted store, never real accounts. */
class NativeTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, FixtureApplication::class.java.name, context)
}

class FixtureApplication : TasksApplication() {
    val server = FixtureServer()
    private val session = UUID.randomUUID().toString()
    private val isolated by lazy {
        object : ContextWrapper(this) {
            override fun getNoBackupFilesDir() = File(cacheDir, "native-test-$session").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("native-test-$session-$name", mode)
        }
    }
    override val repository by lazy {
        SecureStore(isolated).write(Snapshot(accounts = listOf(
            Account("personal", "OpenCloud", "https://personal.test/dav/", "fixture", "synthetic-password"),
            Account("work", "Work", "https://work.test/dav/", "fixture", "synthetic-password"),
        )))
        TasksRepository(isolated, CalDavClient(OkHttpClient.Builder().addInterceptor(server).build()))
    }
    override val presentation by lazy { PresentationStore(isolated) }
}

class FixtureServer : Interceptor {
    private val calendars = linkedMapOf<String, String>()
    var writes = 0
        private set
    init { reset() }
    @Synchronized fun reset() {
        writes = 0
        calendars.clear()
        calendars["https://personal.test/dav/home/one.ics"] = ICalendar.create("Test the Android app", uid = "one")
        calendars["https://personal.test/dav/home/two.ics"] = ICalendar.create("Pick up groceries", uid = "two")
        calendars["https://personal.test/dav/projects/three.ics"] = ICalendar.create("Ship a native widget", uid = "three")
        calendars["https://work.test/dav/inbox/four.ics"] = ICalendar.create("Review the plan", uid = "four")
    }
    private fun response(href: String, props: String) = """<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
    private fun multi(responses: String) = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">$responses</d:multistatus>"""
    @Synchronized override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url.toString()
        val host = req.url.host
        check(host in setOf("personal.test", "work.test")) { "Non-fixture host refused" }
        var code = 207
        val result = when (req.method) {
            "PROPFIND" -> {
                if (req.header("Depth") == "0") multi(response("/dav/", "<c:calendar-home-set><d:href>/dav/</d:href></c:calendar-home-set>"))
                else {
                    val lists = if (host == "personal.test") listOf("home" to "Home", "projects" to "Projects") else listOf("inbox" to "Inbox")
                    multi(lists.joinToString("") { (path, name) -> response("/dav/$path/", """<d:displayname>$name</d:displayname><d:resourcetype><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>""") })
                }
            }
            "REPORT" -> multi(calendars.filterKeys { it.startsWith(url) }.entries.joinToString("") { (resource, data) ->
                response(resource, "<d:getetag>&quot;fixture-$writes&quot;</d:getetag><c:calendar-data><![CDATA[$data]]></c:calendar-data>")
            })
            "PUT" -> {
                check(req.header("If-Match") != null || req.header("If-None-Match") == "*")
                val buffer = Buffer(); req.body!!.writeTo(buffer)
                calendars[url] = buffer.readUtf8(); writes++; code = 201; ""
            }
            "DELETE" -> { check(req.header("If-Match") != null); calendars.remove(url); writes++; code = 204; "" }
            else -> error("Unexpected fixture request: ${req.method}")
        }
        return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(result.toResponseBody("application/xml".toMediaType())).build()
    }
}

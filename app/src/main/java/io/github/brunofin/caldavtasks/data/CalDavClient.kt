package io.github.brunofin.caldavtasks.data

import io.github.brunofin.caldavtasks.core.*
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class DavException(message: String) : Exception(message)

/** No redirects, cross-origin discovery, URL passwords, insecure TLS or request logging. */
class CalDavClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(35, TimeUnit.SECONDS).build()) {
    data class Discovery(val home: String, val lists: List<TaskList>)
    private data class Node(val namespaceURI: String, val localName: String, val attributes: Map<String, String>,
                            val nodes: MutableList<Node> = mutableListOf(), var text: String = "") {
        val textContent: String get() = text + nodes.joinToString("") { it.textContent }
        fun descendants(ns: String, name: String): List<Node> = nodes.flatMap { child ->
            (if (child.namespaceURI == ns && child.localName == name) listOf(child) else emptyList()) + child.descendants(ns, name)
        }
    }
    private data class DavResponse(val url: HttpUrl, val props: List<Node>) {
        fun prop(ns: String, name: String) = props.firstOrNull { it.namespaceURI == ns && it.localName == name }
    }
    companion object {
        private const val D = "DAV:"
        private const val C = "urn:ietf:params:xml:ns:caldav"
        fun validateUrl(value: String): HttpUrl = value.trim().toHttpUrl().also {
            require(it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) {
                "Use an HTTPS CalDAV URL without embedded credentials, query or fragment."
            }
        }
        private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        private const val PROPS = "<d:displayname/><d:resourcetype/><c:supported-calendar-component-set/><d:current-user-privilege-set/><d:current-user-principal/><c:calendar-home-set/>"
        private fun children(parent: Node) = parent.nodes.toList()
    }
    private fun resolve(account: Account, base: HttpUrl, href: String): HttpUrl {
        val url = base.resolve(href) ?: throw DavException("Server returned an invalid resource URL.")
        val origin = validateUrl(account.url)
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw DavException("Cross-origin or credential-bearing discovery URL refused. Use the server's final CalDAV URL.")
        }
        return url
    }
    private fun request(account: Account, url: HttpUrl, method: String, body: String? = null,
                        depth: String? = null, headers: Map<String, String> = emptyMap()): String {
        resolve(account, validateUrl(account.url), url.toString())
        val request = Request.Builder().url(url).header("Authorization", Credentials.basic(account.username, account.password, Charsets.UTF_8))
        depth?.let { request.header("Depth", it) }
        headers.forEach { (key, value) -> request.header(key, value) }
        val type = if (method == "PUT") "text/calendar; charset=utf-8" else "application/xml; charset=utf-8"
        request.method(method, body?.toRequestBody(type.toMediaType()))
        try {
            http.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) throw DavException(when (response.code) {
                    401 -> "Authentication failed. Check your username and app password."
                    403 -> "The server denied this operation. The collection may be read-only."
                    409 -> "The server rejected the resource or collection location."
                    412 -> "This task changed on the server. Refresh before trying again."
                    in 300..399 -> "The server redirected the request. Configure its final HTTPS CalDAV URL."
                    else -> "CalDAV request failed (HTTP ${response.code})."
                })
                val stream = response.body?.byteStream() ?: return ""
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 16_000_000) throw DavException("Server response exceeds the 16 MB safety limit.")
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                return bytes.toString(Charsets.UTF_8)
            }
        } catch (e: DavException) { throw e }
        catch (_: Exception) { throw DavException("Cannot reach the CalDAV server securely. Check network, URL and certificate.") }
    }
    private fun multistatus(account: Account, base: HttpUrl, document: String): List<DavResponse> {
        try {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(StringReader(document))
            val stack = java.util.ArrayDeque<Node>()
            var root: Node? = null
            var event = parser.eventType
            var count = 0
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.DOCDECL -> throw DavException("Document types and external entities are not allowed.")
                    XmlPullParser.START_TAG -> {
                        require(stack.size < 32 && count++ < 100_000) { "XML nesting or element limit exceeded." }
                        val node = Node(parser.namespace.orEmpty(), parser.name,
                            (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
                        if (stack.isEmpty()) { require(root == null); root = node } else stack.last.nodes += node
                        stack.addLast(node)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        if (stack.isNotEmpty()) stack.last.text += parser.text.orEmpty()
                    }
                    XmlPullParser.END_TAG -> stack.removeLast()
                }
                event = parser.nextToken()
            }
            require(root != null)
            require(root.namespaceURI == D && root.localName == "multistatus")
            return children(root).filter { it.namespaceURI == D && it.localName == "response" }.mapNotNull { response ->
                val href = children(response).firstOrNull { it.namespaceURI == D && it.localName == "href" }?.textContent ?: return@mapNotNull null
                val props = children(response).filter { it.namespaceURI == D && it.localName == "propstat" }.flatMap { stat ->
                    val status = children(stat).firstOrNull { it.localName == "status" }?.textContent.orEmpty()
                    if (!Regex("HTTP/\\S+ 200(?: .*)?").matches(status.trim())) emptyList()
                    else children(stat).filter { it.namespaceURI == D && it.localName == "prop" }.flatMap(::children)
                }
                DavResponse(resolve(account, base, href), props)
            }
        } catch (e: DavException) { throw e }
        catch (_: Exception) { throw DavException("The server returned invalid or unsafe CalDAV XML.") }
    }
    private fun propfind(account: Account, url: HttpUrl, depth: String = "0") = multistatus(account, url,
        request(account, url, "PROPFIND", "<d:propfind xmlns:d=\"DAV:\" xmlns:c=\"$C\"><d:prop>$PROPS</d:prop></d:propfind>", depth))
    private fun href(prop: Node?) = prop?.descendants(D, "href")?.firstOrNull()?.textContent
    private fun components(response: DavResponse): Set<String>? = response.prop(C, "supported-calendar-component-set")?.let { prop ->
        prop.descendants(C, "comp").map { it.attributes["name"].orEmpty().uppercase() }.toSet()
    }
    private fun toList(account: Account, response: DavResponse): TaskList? {
        val type = response.prop(D, "resourcetype") ?: return null
        if (type.descendants(C, "calendar").isEmpty()) return null
        val components = components(response)
        if (components != null && "VTODO" !in components) return null
        val privileges = response.prop(D, "current-user-privilege-set")
        val writable = privileges == null || listOf("all", "write", "write-content").any {
            privileges.descendants(D, it).isNotEmpty()
        }
        return TaskList("${account.id}|${response.url}", account.id, response.prop(D, "displayname")?.textContent
            ?.takeIf { it.isNotBlank() } ?: response.url.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty(),
            response.url.toString(), components == setOf("VTODO"), writable)
    }
    fun discover(account: Account): Discovery {
        val root = validateUrl(account.url)
        val initial = propfind(account, root).firstOrNull() ?: throw DavException("CalDAV discovery returned no resource.")
        var home = href(initial.prop(C, "calendar-home-set"))?.let { resolve(account, root, it) }
        if (home == null) {
            val principal = href(initial.prop(D, "current-user-principal"))?.let { resolve(account, root, it) }
            if (principal != null) home = propfind(account, principal).firstOrNull()?.let { response ->
                href(response.prop(C, "calendar-home-set"))?.let { resolve(account, principal, it) }
            }
        }
        toList(account, initial)?.let { return Discovery((home ?: root).toString(), listOf(it)) }
        val target = home ?: root
        val lists = propfind(account, target, "1").mapNotNull { toList(account, it) }.distinctBy { it.id }
        return Discovery(target.toString(), lists)
    }
    private fun report(account: Account, list: TaskList, component: String): List<DavResponse> {
        val url = list.url.toHttpUrl()
        val body = """<c:calendar-query xmlns:d="DAV:" xmlns:c="$C"><d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="$component"/></c:comp-filter></c:filter></c:calendar-query>"""
        return multistatus(account, url, request(account, url, "REPORT", body, "1"))
    }
    fun tasks(account: Account, list: TaskList): List<Task> = report(account, list, "VTODO").flatMap { response ->
        val calendar = response.prop(C, "calendar-data")?.textContent ?: return@flatMap emptyList()
        try { ICalendar.parse(calendar, list.id, response.url.toString(), response.prop(D, "getetag")?.textContent.orEmpty()) }
        catch (_: IllegalArgumentException) { throw DavException("A task contains malformed calendar data. Previous data was retained.") }
    }
    fun createTask(account: Account, list: TaskList, title: String, parentUid: String?) {
        val uid = UUID.randomUUID().toString()
        val base = list.url.toHttpUrl().newBuilder().apply { if (!list.url.endsWith('/')) addPathSegment("") }.build()
        val url = base.resolve("$uid.ics") ?: throw DavException("Invalid task collection URL.")
        request(account, url, "PUT", ICalendar.create(title, parentUid, uid), headers = mapOf("If-None-Match" to "*"))
    }
    fun updateTask(account: Account, task: Task, title: String? = null, completed: Boolean? = null) {
        request(account, task.url.toHttpUrl(), "PUT", ICalendar.edit(task, title, completed), headers = mapOf("If-Match" to task.etag))
    }
    fun deleteTask(account: Account, task: Task) {
        require(task.editable && !task.recurring) { "Recurring or shared resources cannot be deleted here." }
        request(account, task.url.toHttpUrl(), "DELETE", headers = mapOf("If-Match" to task.etag))
    }
    fun createList(account: Account, name: String) {
        val discovery = discover(account)
        val home = discovery.home.toHttpUrl().newBuilder().apply { if (!discovery.home.endsWith('/')) addPathSegment("") }.build()
        val url = home.resolve("${UUID.randomUUID()}/")!!
        request(account, url, "MKCALENDAR", """<c:mkcalendar xmlns:d="DAV:" xmlns:c="$C"><d:set><d:prop><d:displayname>${xml(validTitle(name))}</d:displayname><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:set></c:mkcalendar>""")
    }
    fun renameList(account: Account, list: TaskList, name: String) {
        val result = request(account, list.url.toHttpUrl(), "PROPPATCH", """<d:propertyupdate xmlns:d="DAV:"><d:set><d:prop><d:displayname>${xml(validTitle(name))}</d:displayname></d:prop></d:set></d:propertyupdate>""")
        val confirmed = multistatus(account, list.url.toHttpUrl(), result).any { it.prop(D, "displayname") != null }
        if (!confirmed) throw DavException("The server did not confirm the list rename.")
    }
    fun deleteList(account: Account, list: TaskList) {
        val fresh = propfind(account, list.url.toHttpUrl()).firstOrNull()?.let { toList(account, it) }
        require(fresh?.taskOnly == true) { "Only collections explicitly supporting VTODO alone can be deleted." }
        require(report(account, list, "VEVENT").none { it.prop(C, "calendar-data") != null }) {
            "This collection contains events and cannot be deleted."
        }
        request(account, list.url.toHttpUrl(), "DELETE")
    }
}

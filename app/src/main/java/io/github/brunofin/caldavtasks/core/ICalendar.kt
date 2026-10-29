package io.github.brunofin.caldavtasks.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Lossless at the property level: unknown properties, parameters and nested alarms survive edits.
 * Shared/multi-component and recurring resources are deliberately not mutated.
 */
object ICalendar {
    private fun lines(text: String): List<String> {
        require(text.length <= 2_000_000) { "Calendar resource is too large." }
        val result = mutableListOf<String>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if ((line.startsWith(' ') || line.startsWith('\t')) && result.isNotEmpty()) {
                result[result.lastIndex] += line.drop(1)
            } else if (line.isNotEmpty()) result += line
        }
        return result
    }
    private fun name(line: String) = line.substringBefore(':').substringBefore(';').uppercase()
    private fun value(line: String) = line.substringAfter(':', "")
    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                i++
                out.append(if (value[i] == 'n' || value[i] == 'N') '\n' else value[i])
            } else out.append(value[i])
            i++
        }
        return out.toString()
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
        .replace(";", "\\;").replace(",", "\\,")

    private fun blocks(lines: List<String>): List<IntRange> {
        val stack = mutableListOf<String>()
        var start = -1
        val result = mutableListOf<IntRange>()
        for ((i, line) in lines.withIndex()) {
            when (name(line)) {
                "BEGIN" -> {
                    if (value(line).equals("VTODO", true)) {
                        require(stack.lastOrNull() == "VCALENDAR") { "Invalid task nesting." }
                        start = i
                    }
                    stack += value(line).uppercase()
                }
                "END" -> {
                    val type = value(line).uppercase()
                    require(stack.lastOrNull() == type) { "Malformed calendar component." }
                    stack.removeAt(stack.lastIndex)
                    if (type == "VTODO") { result += start..i; start = -1 }
                }
            }
        }
        require(stack.isEmpty()) { "Unclosed calendar component." }
        return result
    }
    private fun properties(lines: List<String>, range: IntRange): List<String> {
        var depth = 0
        return lines.subList(range.first + 1, range.last).filter { line ->
            when (name(line)) {
                "BEGIN" -> { depth++; false }
                "END" -> { depth--; false }
                else -> depth == 0
            }
        }
    }

    fun parse(calendar: String, listId: String, url: String, etag: String): List<Task> {
        val lines = lines(calendar)
        val ranges = blocks(lines)
        val eventPresent = lines.any { it.equals("BEGIN:VEVENT", true) }
        return ranges.mapNotNull { range ->
            val props = properties(lines, range)
            fun prop(key: String) = props.firstOrNull { name(it) == key }
            val uid = prop("UID")?.let(::value)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (prop("RECURRENCE-ID") != null) return@mapNotNull null
            val parent = props.firstOrNull {
                name(it) == "RELATED-TO" && (!it.substringBefore(':').contains("RELTYPE=", true) ||
                    it.substringBefore(':').contains("RELTYPE=PARENT", true))
            }?.let(::value)
            Task("$listId|$uid", listId, uid, prop("SUMMARY")?.let { unescape(value(it)) } ?: "(Untitled)",
                prop("STATUS")?.let(::value).equals("COMPLETED", true), parent,
                props.any { name(it) in setOf("RRULE", "RDATE", "EXDATE") }, url, etag, calendar,
                ranges.size == 1 && !eventPresent && etag.isNotBlank())
        }
    }

    fun create(title: String, parentUid: String? = null, uid: String = UUID.randomUUID().toString()): String {
        val now = stamp()
        return render(listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Bruno Finger//CalDAV Tasks Android//EN",
            "BEGIN:VTODO", "UID:$uid", "DTSTAMP:$now", "CREATED:$now", "SUMMARY:${escape(validTitle(title))}",
            "STATUS:NEEDS-ACTION", "PERCENT-COMPLETE:0") +
            listOfNotNull(parentUid?.let { "RELATED-TO;RELTYPE=PARENT:${escape(it)}" }) +
            listOf("END:VTODO", "END:VCALENDAR"))
    }

    fun edit(task: Task, title: String? = null, completed: Boolean? = null): String {
        require(task.editable && !task.recurring) { "Recurring or shared resources are read-only. Edit them in another client." }
        val input = lines(task.calendar)
        val range = blocks(input).single()
        val changes = linkedMapOf<String, String?>("LAST-MODIFIED" to stamp(), "DTSTAMP" to stamp())
        if (title != null) changes["SUMMARY"] = escape(validTitle(title))
        if (completed != null) {
            changes["STATUS"] = if (completed) "COMPLETED" else "NEEDS-ACTION"
            changes["PERCENT-COMPLETE"] = if (completed) "100" else "0"
            changes["COMPLETED"] = if (completed) stamp() else null
        }
        val out = mutableListOf<String>()
        var nested = 0
        for ((i, line) in input.withIndex()) {
            if (i == range.last) changes.forEach { (key, value) -> if (value != null) out += "$key:$value" }
            val inside = i > range.first && i < range.last
            if (inside && name(line) == "END") nested--
            if (!inside || nested != 0 || name(line) !in changes) out += line
            if (inside && name(line) == "BEGIN") nested++
        }
        return render(out)
    }
    private fun stamp() = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
    private fun render(lines: List<String>): String = lines.joinToString("\r\n", postfix = "\r\n") { line ->
        // RFC 5545: fold at 75 OCTETS, never in the middle of a UTF-8 code point.
        val out = StringBuilder()
        var bytes = 0
        val points = line.codePoints().iterator()
        while (points.hasNext()) {
            val chars = String(Character.toChars(points.nextInt()))
            val size = chars.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 75) { out.append("\r\n "); bytes = 1 }
            out.append(chars); bytes += size
        }
        out.toString()
    }
}

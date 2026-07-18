package com.example.fold.data.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WebDavHandler(private val rootDir: File) {

    fun handle(session: IHTTPSession): Response? {
        val uri = session.uri
        val method = session.method

        if (!uri.startsWith("/dav")) return null

        val relPath = uri.removePrefix("/dav").ifEmpty { "/" }
        val file = File(rootDir, relPath.removePrefix("/"))

        return when (method) {
            NanoHTTPD.Method.OPTIONS -> handleOptions()
            NanoHTTPD.Method.GET -> handleGet(file)
            NanoHTTPD.Method.HEAD -> handleHead(file)
            NanoHTTPD.Method.PUT -> handlePut(session, file)
            NanoHTTPD.Method.DELETE -> handleDelete(file)
            NanoHTTPD.Method.MKCOL -> handleMkcol(file)
            NanoHTTPD.Method.MOVE -> handleMove(session, file)
            NanoHTTPD.Method.COPY -> handleCopy(session, file)
            NanoHTTPD.Method.PROPFIND -> handlePropfind(session, file)
            NanoHTTPD.Method.PROPPATCH -> handleProppatch()
            NanoHTTPD.Method.LOCK -> handleLock()
            NanoHTTPD.Method.UNLOCK -> handleUnlock()
            else -> NanoHTTPD.newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                "text/plain",
                "Method not allowed"
            )
        }
    }

    private fun handleOptions(): Response {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
            addHeader("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY, PROPFIND, PROPPATCH, LOCK, UNLOCK")
            addHeader("DAV", "1, 2")
            addHeader("MS-Author-Via", "DAV")
        }
    }

    private fun handleGet(file: File): Response {
        if (!file.exists()) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        if (file.isDirectory) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot download directory")
        }
        val mimeType = getMimeType(file.name)
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, mimeType,
            file.inputStream(), file.length()
        )
    }

    private fun handleHead(file: File): Response {
        if (!file.exists()) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, getMimeType(file.name), "").apply {
            addHeader("Content-Length", file.length().toString())
            addHeader("Last-Modified", formatHttpDate(file.lastModified()))
        }
    }

    private fun handlePut(session: IHTTPSession, file: File): Response {
        return try {
            file.parentFile?.mkdirs()

            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            val inputStream = session.inputStream

            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var remaining = contentLength
                while (remaining > 0 || contentLength == 0L) {
                    val toRead = if (contentLength > 0) minOf(buffer.size.toLong(), remaining).toInt() else buffer.size
                    val read = inputStream.read(buffer, 0, toRead)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    if (contentLength > 0) remaining -= read
                }
            }

            NanoHTTPD.newFixedLengthResponse(
                if (session.headers["content-length"] == null) Response.Status.NO_CONTENT else Response.Status.CREATED,
                "text/plain", "OK"
            )
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain",
                "Upload failed: ${e.message}"
            )
        }
    }

    private fun handleDelete(file: File): Response {
        if (!file.exists()) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        return if (file.deleteRecursively()) {
            NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        } else {
            NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot delete")
        }
    }

    private fun handleMkcol(file: File): Response {
        return if (file.mkdirs()) {
            NanoHTTPD.newFixedLengthResponse(Response.Status.CREATED, "text/plain", "Created")
        } else {
            NanoHTTPD.newFixedLengthResponse(
                if (file.exists()) Response.Status.METHOD_NOT_ALLOWED else Response.Status.FORBIDDEN,
                "text/plain",
                if (file.exists()) "Already exists" else "Cannot create directory"
            )
        }
    }

    private fun handleMove(session: IHTTPSession, file: File): Response {
        val destUri = session.headers["destination"]
            ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing Destination header")

        val destPath = decodeDestinationUri(destUri)
        val dest = File(rootDir, destPath.removePrefix("/"))

        if (!file.exists()) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Source not found")
        }

        return if (file.renameTo(dest)) {
            NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        } else {
            NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot move")
        }
    }

    private fun handleCopy(session: IHTTPSession, file: File): Response {
        val destUri = session.headers["destination"]
            ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing Destination header")

        val destPath = decodeDestinationUri(destUri)
        val dest = File(rootDir, destPath.removePrefix("/"))

        if (!file.exists()) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Source not found")
        }

        return try {
            if (file.isDirectory) {
                copyDirectory(file, dest)
            } else {
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
            }
            NanoHTTPD.newFixedLengthResponse(Response.Status.CREATED, "text/plain", "Copied")
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot copy: ${e.message}")
        }
    }

    private fun copyDirectory(src: File, dest: File) {
        dest.mkdirs()
        src.listFiles()?.forEach { child ->
            val childDest = File(dest, child.name)
            if (child.isDirectory) {
                copyDirectory(child, childDest)
            } else {
                child.copyTo(childDest, overwrite = true)
            }
        }
    }

    private fun handlePropfind(session: IHTTPSession, file: File): Response {
        if (!file.exists()) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }

        val depth = session.headers["depth"]?.toIntOrNull() ?: 1
        val xml = buildPropfindXml(file, depth)

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", xml).apply {
            addHeader("DAV", "1, 2")
        }
    }

    private fun buildPropfindXml(file: File, depth: Int): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("""<D:multistatus xmlns:D="DAV:">""")

        addResourceXml(sb, file, includeChildren = true)

        if (depth > 0 && file.isDirectory) {
            file.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            )?.forEach { child ->
                addResourceXml(sb, child, includeChildren = false)
            }
        }

        sb.appendLine("</D:multistatus>")
        return sb.toString()
    }

    private fun addResourceXml(sb: StringBuilder, file: File, includeChildren: Boolean) {
        val href = if (file.isDirectory) {
            "/dav${file.absolutePath.removePrefix(rootDir.absolutePath)}/"
        } else {
            "/dav${file.absolutePath.removePrefix(rootDir.absolutePath)}"
        }

        sb.appendLine("  <D:response>")
        sb.appendLine("    <D:href>${escapeXml(href)}</D:href>")
        sb.appendLine("    <D:propstat>")
        sb.appendLine("      <D:prop>")
        sb.appendLine("        <D:resourcetype>${if (file.isDirectory) "<D:collection/>" else ""}</D:resourcetype>")
        sb.appendLine("        <D:getcontentlength>${file.length()}</D:getcontentlength>")
        sb.appendLine("        <D:getlastmodified>${formatHttpDate(file.lastModified())}</D:getlastmodified>")
        if (file.isFile) {
            sb.appendLine("        <D:getcontenttype>${getMimeType(file.name)}</D:getcontenttype>")
        }
        sb.appendLine("      </D:prop>")
        sb.appendLine("      <D:status>HTTP/1.1 200 OK</D:status>")
        sb.appendLine("    </D:propstat>")
        sb.appendLine("  </D:response>")
    }

    private fun handleProppatch(): Response {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:propstat>
      <D:prop/>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", xml)
    }

    private fun handleLock(): Response {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:prop xmlns:D="DAV:">
  <D:lockdiscovery>
    <D:activelock>
      <D:locktype><D:write/></D:locktype>
      <D:lockscope><D:exclusive/></D:lockscope>
      <D:timeout>Second-3600</D:timeout>
      <D:locktoken><D:opaque LockeToken:1/></D:locktoken>
    </D:activelock>
  </D:lockdiscovery>
</D:prop>"""
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", xml).apply {
            addHeader("Lock-Token", "<LockToken:1>")
        }
    }

    private fun handleUnlock(): Response {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
    }

    private fun decodeDestinationUri(uri: String): String {
        val decoded = java.net.URLDecoder.decode(uri, "UTF-8")
        return decoded.removePrefix("http://").let { rest ->
            val pathStart = rest.indexOf('/')
            if (pathStart >= 0) rest.substring(pathStart) else decoded
        }
    }

    private fun formatHttpDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss GMT", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(timestamp)
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun getMimeType(fileName: String): String = MimeTypes.getMimeType(fileName)
}

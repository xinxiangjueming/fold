package com.example.fold.data.server

object WebResources {

    fun getLoginPage(pairingCode: String): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Fold - Pairing</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #f5f5f5; display: flex; justify-content: center; align-items: center; min-height: 100vh; }
        .card { background: white; border-radius: 20px; padding: 40px; box-shadow: 0 4px 20px rgba(0,0,0,0.1); text-align: center; max-width: 400px; width: 90%; }
        h1 { color: #1565C0; margin-bottom: 8px; font-size: 24px; }
        .subtitle { color: #666; margin-bottom: 24px; }
        .code { font-size: 48px; font-weight: bold; color: #7B1FA2; letter-spacing: 8px; margin: 24px 0; }
        .input-group { margin: 20px 0; }
        input { width: 100%; padding: 14px; border: 2px solid #e0e0e0; border-radius: 12px; font-size: 18px; text-align: center; letter-spacing: 4px; }
        input:focus { outline: none; border-color: #1565C0; }
        button { width: 100%; padding: 14px; background: linear-gradient(135deg, #1565C0, #7B1FA2); color: white; border: none; border-radius: 12px; font-size: 16px; font-weight: 600; cursor: pointer; margin-top: 12px; }
        button:hover { opacity: 0.9; }
        .hint { color: #999; font-size: 12px; margin-top: 16px; }
    </style>
</head>
<body>
    <div class="card">
        <h1>Fold</h1>
        <p class="subtitle">Enter pairing code to connect</p>
        <div class="code">$pairingCode</div>
        <form action="/pair" method="POST">
            <div class="input-group">
                <input type="text" name="code" placeholder="Enter code" maxlength="6" pattern="[0-9]{6}" required>
            </div>
            <button type="submit">Connect</button>
        </form>
        <p class="hint">Ask the phone owner for the code</p>
    </div>
</body>
</html>
        """.trimIndent()
    }

    fun getLoginPageWithError(pairingCode: String, error: String): String {
        return getLoginPage(pairingCode).replace(
            "</form>",
            "<p style='color:#FF3B30;margin-top:12px'>$error</p></form>"
        )
    }

    fun getBrowserPage(path: String, files: List<FileInfo>): String {
        val fileListHtml = files.joinToString("\n") { file ->
            val icon = if (file.isDirectory) "📁" else getFileIcon(file.name)
            val size = if (file.isDirectory) "" else formatSize(file.size)
            val link = if (file.isDirectory) "/browse${file.path}" else "/download${file.path}"
            """
            <tr>
                <td><a href="$link">$icon ${file.name}</a></td>
                <td>$size</td>
            </tr>
            """.trimIndent()
        }

        val parentLink = if (path != "/") {
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            "<a href=\"/browse$parent\" class=\"back\">← Back</a>"
        } else ""

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Fold - $path</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #f5f5f5; }
        .header { background: linear-gradient(135deg, #1565C0, #7B1FA2); color: white; padding: 20px; }
        .header h1 { font-size: 20px; margin-bottom: 8px; }
        .path { font-size: 14px; opacity: 0.9; word-break: break-all; }
        .back { color: white; text-decoration: none; display: inline-block; margin-bottom: 12px; opacity: 0.9; }
        .back:hover { opacity: 1; }
        .content { padding: 16px; }
        table { width: 100%; background: white; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.05); }
        td { padding: 14px 16px; border-bottom: 1px solid #f0f0f0; }
        tr:last-child td { border-bottom: none; }
        a { color: #1565C0; text-decoration: none; }
        a:hover { text-decoration: underline; }
        td:last-child { color: #999; font-size: 13px; text-align: right; width: 80px; }
        .info { margin-top: 16px; background: white; padding: 16px; border-radius: 12px; box-shadow: 0 2px 10px rgba(0,0,0,0.05); }
        .info h3 { color: #333; font-size: 14px; margin-bottom: 8px; }
        .info code { background: #f0f0f0; padding: 2px 6px; border-radius: 4px; font-size: 13px; word-break: break-all; }
        .upload { margin-top: 16px; background: white; padding: 16px; border-radius: 12px; box-shadow: 0 2px 10px rgba(0,0,0,0.05); }
        .upload form { display: flex; gap: 8px; }
        .upload input[type="file"] { flex: 1; }
        .upload button { padding: 10px 20px; background: #1565C0; color: white; border: none; border-radius: 8px; cursor: pointer; }
    </style>
</head>
<body>
    <div class="header">
        $parentLink
        <h1>Fold</h1>
        <div class="path">$path</div>
    </div>
    <div class="content">
        <div class="info">
            <h3>WebDAV 地址</h3>
            <code id="davUrl"></code>
        </div>
        <table>
            $fileListHtml
        </table>
        <div class="upload">
            <form action="/upload$path" method="POST" enctype="multipart/form-data">
                <input type="file" name="file" multiple>
                <button type="submit">Upload</button>
            </form>
        </div>
    </div>
    <script>
        document.getElementById('davUrl').textContent = location.origin + '/dav';
    </script>
</body>
</html>
        """.trimIndent()
    }

    fun getUploadSuccessPage(path: String): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta http-equiv="refresh" content="2;url=/browse$path">
    <title>Upload Success</title>
    <style>
        body { font-family: -apple-system, sans-serif; display: flex; justify-content: center; align-items: center; min-height: 100vh; background: #f5f5f5; }
        .msg { background: white; padding: 40px; border-radius: 20px; text-align: center; box-shadow: 0 4px 20px rgba(0,0,0,0.1); }
        h2 { color: #34C759; margin-bottom: 8px; }
    </style>
</head>
<body>
    <div class="msg">
        <h2>✓ Upload Complete</h2>
        <p>Redirecting...</p>
    </div>
</body>
</html>
        """.trimIndent()
    }

    private fun getFileIcon(name: String): String {
        return when {
            name.endsWith(".txt", true) -> "📄"
            name.endsWith(".pdf", true) -> "📕"
            name.endsWith(".epub", true) -> "📘"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true) -> "🖼️"
            name.endsWith(".mp4", true) || name.endsWith(".mkv", true) -> "🎬"
            name.endsWith(".mp3", true) || name.endsWith(".wav", true) -> "🎵"
            name.endsWith(".zip", true) || name.endsWith(".rar", true) -> "📦"
            else -> "📄"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.1f GB".format(gb)
    }
}

data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long
)
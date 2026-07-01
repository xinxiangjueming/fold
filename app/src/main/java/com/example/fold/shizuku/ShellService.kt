package com.example.fold.shizuku

import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku 进程中的 Shell 服务（ADB 权限）
 * 通过 AIDL 被主进程调用，由 Shizuku UserService 机制管理，不需要在 Manifest 注册
 */
class ShellService : IShellService.Stub() {

    override fun exec(command: String): String {
        if (!isCommandSafe(command)) {
            return "ERROR:-1:command rejected: contains dangerous pattern"
        }
        return try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "ERROR:-1:timeout"
            } else {
                val exitCode = process.exitValue()
                if (exitCode == 0) {
                    output
                } else {
                    "ERROR:$exitCode:${output.trim()}"
                }
            }
        } catch (e: Exception) {
            "ERROR:-1:${e.message}"
        }
    }

    private fun isCommandSafe(command: String): Boolean {
        val dangerous = listOf(";", "|", "$(", "`", "\n", "&&", "||")
        for (pattern in dangerous) {
            if (command.contains(pattern)) return false
        }
        // 允许 2>/dev/null (stderr 重定向)，禁止 stdout 重定向 (> 或 >>)
        val stripped = command.replace(Regex("""\d>/dev/\w+"""), "")
        if (stripped.contains(">>") || stripped.contains(">")) return false
        return true
    }
}

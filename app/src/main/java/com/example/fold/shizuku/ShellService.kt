package com.example.fold.shizuku

import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku 进程中的 Shell 服务（ADB 权限）
 * 通过 AIDL 被主进程调用，由 Shizuku UserService 机制管理，不需要在 Manifest 注册
 */
class ShellService : IShellService.Stub() {

    override fun exec(command: String): String {
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
}

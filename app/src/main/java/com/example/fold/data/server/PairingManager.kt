package com.example.fold.data.server

import android.content.Context
import android.content.SharedPreferences

class PairingManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val pendingDevices = mutableMapOf<String, Pair<String, Long>>()
    private val pendingTimeoutMs = 5 * 60 * 1000L

    fun generatePairingCode(ip: String): String {
        val code = (100000..999999).random().toString()
        pendingDevices[ip] = Pair(code, System.currentTimeMillis())
        cleanupExpiredPending()
        return code
    }

    fun verifyPairingCode(ip: String, code: String): Boolean {
        val entry = pendingDevices[ip] ?: return false
        if (System.currentTimeMillis() - entry.second > pendingTimeoutMs) {
            pendingDevices.remove(ip)
            return false
        }
        return if (entry.first == code) {
            pendingDevices.remove(ip)
            markPaired(ip)
            true
        } else {
            false
        }
    }

    private fun cleanupExpiredPending() {
        val now = System.currentTimeMillis()
        pendingDevices.entries.removeIf { now - it.value.second > pendingTimeoutMs }
    }

    fun isPaired(ip: String): Boolean {
        return prefs.getBoolean("paired_$ip", false)
    }

    private fun markPaired(ip: String) {
        prefs.edit().putBoolean("paired_$ip", true).apply()
    }

    fun removePairing(ip: String) {
        prefs.edit().remove("paired_$ip").apply()
    }

    fun getPairedDevices(): List<String> {
        return prefs.all.keys.filter { it.startsWith("paired_") }.map { it.removePrefix("paired_") }
    }
}

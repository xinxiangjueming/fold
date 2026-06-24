package com.example.fold.data.reader

import com.example.fold.util.FoldLogger
import java.lang.reflect.Method

private const val TAG = "ChineseConv"

object ChineseConverter {

    private var s2tMethod: Method? = null
    private var t2sMethod: Method? = null
    private var s2tInstance: Any? = null
    private var t2sInstance: Any? = null
    private var available = false

    init {
        try {
            val clazz = Class.forName("android.icu.text.Transliterator")
            FoldLogger.i(TAG, "Found class: ${clazz.name}")

            val getInstance = clazz.getMethod("getInstance", String::class.java)

            val s2t = getInstance.invoke(null, "Simplified-Traditional")
            FoldLogger.d(TAG, "s2t instance: ${s2t?.javaClass?.name}, isNull=${s2t == null}")

            val t2s = getInstance.invoke(null, "Traditional-Simplified")
            FoldLogger.d(TAG, "t2s instance: ${t2s?.javaClass?.name}, isNull=${t2s == null}")

            val method = clazz.getMethod("transliterate", String::class.java)
            FoldLogger.d(TAG, "transliterate method: ${method.name}, returnType=${method.returnType.name}")

            s2tMethod = method
            t2sMethod = method
            s2tInstance = s2t
            t2sInstance = t2s
            available = true
            FoldLogger.i(TAG, "ICU Transliterator initialized OK")

            // 验证测试
            val testResult = toTraditional("简体中文测试")
            FoldLogger.d(TAG, "verify test: '简体中文测试' -> '$testResult'")
        } catch (e: Exception) {
            FoldLogger.e(TAG, "ICU Transliterator init FAILED: ${e.javaClass.simpleName}: ${e.message}")
            available = false
        }
    }

    fun toTraditional(simplified: String): String {
        if (!available || s2tInstance == null) {
            FoldLogger.w(TAG, "toTraditional: not available=$available, instance=${s2tInstance != null}")
            return simplified
        }
        if (simplified.isEmpty()) return simplified
        return try {
            val result = s2tMethod?.invoke(s2tInstance, simplified)
            FoldLogger.v(TAG, "toTraditional: inLen=${simplified.length}, outLen=${(result as? String)?.length}, resultIsNull=${result == null}")
            (result as? String) ?: run {
                FoldLogger.w(TAG, "toTraditional: result is not String, type=${result?.javaClass?.name}")
                simplified
            }
        } catch (e: Exception) {
            FoldLogger.e(TAG, "toTraditional FAILED: ${e.javaClass.simpleName}: ${e.message}")
            simplified
        }
    }

    fun toSimplified(traditional: String): String {
        if (!available || t2sInstance == null) {
            FoldLogger.w(TAG, "toSimplified: not available=$available, instance=${t2sInstance != null}")
            return traditional
        }
        if (traditional.isEmpty()) return traditional
        return try {
            val result = t2sMethod?.invoke(t2sInstance, traditional)
            FoldLogger.v(TAG, "toSimplified: inLen=${traditional.length}, outLen=${(result as? String)?.length}")
            (result as? String) ?: run {
                FoldLogger.w(TAG, "toSimplified: result is not String, type=${result?.javaClass?.name}")
                traditional
            }
        } catch (e: Exception) {
            FoldLogger.e(TAG, "toSimplified FAILED: ${e.javaClass.simpleName}: ${e.message}")
            traditional
        }
    }

    fun isAvailable(): Boolean = available
}

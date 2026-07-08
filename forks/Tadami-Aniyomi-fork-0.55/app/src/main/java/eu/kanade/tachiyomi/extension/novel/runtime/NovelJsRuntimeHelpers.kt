package eu.kanade.tachiyomi.extension.novel.runtime

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Value

fun V8Array.stringArg(index: Int): String {
    return stringArgOrNull(index).orEmpty()
}

@Suppress("DEPRECATION")
fun V8Array.stringArgOrNull(index: Int): String? {
    if (index >= length()) return null
    val value = runCatching { get(index) }.getOrNull()
    return when (value) {
        null -> null
        is String -> value
        is V8Value -> {
            value.release()
            null
        }
        else -> value.toString()
    }
}

@Suppress("DEPRECATION")
fun V8Array.intArg(index: Int): Int {
    if (index >= length()) return 0
    val value = runCatching { get(index) }.getOrNull()
    return when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        is V8Value -> {
            value.release()
            0
        }
        else -> 0
    }
}

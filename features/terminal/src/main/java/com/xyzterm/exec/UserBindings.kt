package com.xyzterm.exec

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined proot bindings persisted in Settings ("custom_bindings").
 *
 * Each entry exposes a host folder inside the guest filesystem. proot has no per-binding read-only flag, so every bind
 * is writable from the guest; the settings screen warns about this.
 */
object UserBindings {
    fun decode(json: String): List<Binding> = runCatching {
        val array = JSONArray(json)
        val seenGuests = mutableSetOf<String>()
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val outside = obj.optString("outside").trim()
            val inside = obj.optString("inside").trim().ifEmpty { null }

            // Shape check only: a host path that is currently missing
            // (storage unmounted, permission revoked) must survive the
            // roundtrip, otherwise saving the list would silently delete
            // it. attachTo() skips missing paths at launch time anyway.
            if (!isWellFormedHost(outside)) {
                return@mapNotNull null
            }
            if (inside != null && !isValidGuestPath(inside)) {
                return@mapNotNull null
            }
            val binding = Binding(outside, inside)
            // Two binds to one guest target would silently shadow each other in proot.
            if (seenGuests.add(binding.inside ?: binding.outside)) binding else null
        }
    }
        .getOrDefault(emptyList())

    fun encode(bindings: List<Binding>): String {
        val array = JSONArray()
        bindings.forEach { binding ->
            val obj = JSONObject().put("outside", binding.outside)
            binding.inside?.let { obj.put("inside", it) }
            array.put(obj)
        }
        return array.toString()
    }

    fun isValid(outside: String, inside: String?): Boolean =
        isValidHostPath(outside) && (inside == null || isValidGuestPath(inside))

    /** Add-time validation: the host folder must exist right now. */
    fun isValidHostPath(path: String): Boolean =
        isWellFormedHost(path) && java.io.File(path).exists()

    fun isValidGuestPath(path: String): Boolean =
        isWellFormedHost(path) && path.trim('/').isNotEmpty() && path.split('/').none { it == ".." }

    // proot splits "-b source:target" on ':' and sandbox.sh reads newline-separated binds;
    // a path containing either would silently bind the wrong directory.
    internal fun isWellFormedHost(path: String): Boolean =
        path.startsWith("/") && path.length > 1 && ':' !in path && '\\' !in path && path.none { it.isISOControl() }
}

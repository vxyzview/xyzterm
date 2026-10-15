package com.rk.exec

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined proot bindings persisted in Settings ("custom_bindings").
 *
 * Each entry exposes a host folder inside the guest filesystem. proot has no per-binding read-only flag, so every bind
 * is writable from the guest; the settings screen warns about this.
 */
object UserBindings {
    private const val TAG = "UserBindings"

    fun decode(json: String): List<Binding> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val outside = obj.optString("outside").trim()
            val inside = obj.optString("inside").trim().ifEmpty { null }

            // Shape check only: a host path that is currently missing
            // (storage unmounted, permission revoked) must survive the
            // roundtrip, otherwise saving the list would silently delete
            // it. attachTo() skips missing paths at launch time anyway.
            if (!isWellFormedHost(outside)) {
                Log.w(TAG, "dropping custom bind '${outside.take(64)}': ${rejectionReason(outside)}")
                return@mapNotNull null
            }
            if (inside != null && !isValidGuestPath(inside)) {
                Log.w(TAG, "dropping guest path '${inside.take(64)}': ${rejectionReason(inside)}")
                return@mapNotNull null
            }
            Binding(outside, inside)
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
    fun isValidHostPath(path: String): Boolean = isWellFormedHost(path) && java.io.File(path).exists()

    fun isValidGuestPath(path: String): Boolean = path.startsWith("/") && path.length > 1 && !UNSAFE.containsMatchIn(path)

    internal fun isWellFormedHost(path: String): Boolean =
        path.startsWith("/") && path.length > 1 && !UNSAFE.containsMatchIn(path)

    /** Why a path was rejected, for the settings screen. Null when it is acceptable. */
    fun rejectionReason(path: String): String? {
        if (!path.startsWith("/")) return "must be an absolute path"
        if (path.length <= 1) return "must not be the root directory"
        if (UNSAFE.containsMatchIn(path)) {
            val bad =
                path.firstOrNull { UNSAFE.containsMatchIn(it.toString()) }
                    ?.let { "whitespace, ':' and glob characters" }
                    ?: "invalid characters"
            return "must not contain $bad"
        }
        return null
    }

    /**
     * Characters that break proot's argument handling.
     *
     * `sandbox.sh` builds every bind into one string and expands it unquoted
     * (`ARGS="$ARGS -b $1"`), so whitespace word-splits the path in two and proot dies
     * before bash starts. `/storage/ABCD-1234/My Drive` is the stock Android SD-card
     * label, so this was reachable from a completely reasonable tap. `:` is separate —
     * proot's `handle_option_b` splits the bind spec on it — and the glob characters
     * would be re-expanded by any shell in the path.
     */
    private val UNSAFE = Regex("""[\s:*?\[\]]""")
}

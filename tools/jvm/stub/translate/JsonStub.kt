package org.json
class JSONObject(s: String) {
    fun optInt(k: String): Int = 0
    fun optBoolean(k: String): Boolean = false
    fun optJSONObject(k: String): JSONObject? = null
    fun optString(k: String): String = ""
}

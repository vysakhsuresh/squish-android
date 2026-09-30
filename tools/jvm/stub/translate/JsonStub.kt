package org.json
class JSONObject(s: String) {
    fun optInt(k: String): Int = 0
    fun optBoolean(k: String): Boolean = false
    fun optJSONObject(k: String): JSONObject? = null
    fun optJSONArray(k: String): JSONArray? = null
    fun optString(k: String): String = ""
    fun optDouble(k: String, fallback: Double): Double = fallback
}
class JSONArray {
    fun length(): Int = 0
    fun optJSONObject(i: Int): JSONObject? = null
}

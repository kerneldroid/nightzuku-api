package kerneldroid.nightzuku.api

/**
 * Typed system property accessors backed by [NightClient].
 */
object NightSystemProperties {

    @JvmStatic
    fun get(key: String): String? = NightClient.getSystemProperty(key, null)

    @JvmStatic
    fun get(key: String, def: String?): String? = NightClient.getSystemProperty(key, def)

    @JvmStatic
    fun getInt(key: String, def: Int): Int {
        val raw = NightClient.getSystemProperty(key, def.toString())
        return if (raw.isNullOrEmpty()) def else runCatching { Integer.decode(raw) }.getOrDefault(def)
    }

    @JvmStatic
    fun getLong(key: String, def: Long): Long {
        val raw = NightClient.getSystemProperty(key, def.toString())
        return if (raw.isNullOrEmpty()) def else runCatching { java.lang.Long.decode(raw) }.getOrDefault(def)
    }

    @JvmStatic
    fun getBoolean(key: String, def: Boolean): Boolean {
        val raw = NightClient.getSystemProperty(key, def.toString())
        return if (raw.isNullOrEmpty()) def else raw.toBoolean()
    }

    @JvmStatic
    fun set(key: String, value: String) {
        NightClient.setSystemProperty(key, value)
    }
}

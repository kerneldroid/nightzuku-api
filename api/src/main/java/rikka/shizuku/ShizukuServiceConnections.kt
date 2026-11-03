package rikka.shizuku

import java.util.Collections
import java.util.HashMap

internal object ShizukuServiceConnections {

    private val CACHE: MutableMap<String, ShizukuServiceConnection> =
        Collections.synchronizedMap(HashMap())

    @JvmStatic
    fun get(args: Shizuku.UserServiceArgs): ShizukuServiceConnection {
        val key = "${args.componentName.packageName}:${args.tag ?: args.componentName.className}"
        synchronized(CACHE) {
            var connection = CACHE[key]
            if (connection == null) {
                connection = ShizukuServiceConnection(args)
                CACHE[key] = connection
            }
            return connection
        }
    }

    @JvmStatic
    fun remove(connection: ShizukuServiceConnection) {
        synchronized(CACHE) {
            CACHE.values.removeIf { it === connection }
        }
    }
}

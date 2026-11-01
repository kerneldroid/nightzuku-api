package rikka.shizuku

import android.os.RemoteException
import kerneldroid.nightzuku.api.NightClient

object ShizukuSystemProperties {

    @JvmStatic
    @Throws(RemoteException::class)
    fun get(key: String): String? {
        if (!Shizuku.pingBinder() && NightClient.pingBinder()) {
            return NightClient.getSystemProperty(key, null)
        }
        return Shizuku.requireService().getSystemProperty(key, null)
    }

    @JvmStatic
    @Throws(RemoteException::class)
    fun get(key: String, def: String?): String? {
        if (!Shizuku.pingBinder() && NightClient.pingBinder()) {
            return NightClient.getSystemProperty(key, def)
        }
        return Shizuku.requireService().getSystemProperty(key, def)
    }

    @JvmStatic
    @Throws(RemoteException::class)
    fun getInt(key: String, def: Int): Int {
        val raw = get(key, def.toString())
        return if (raw.isNullOrEmpty()) def else Integer.decode(raw)
    }

    @JvmStatic
    @Throws(RemoteException::class)
    fun getLong(key: String, def: Long): Long {
        val raw = get(key, def.toString())
        return if (raw.isNullOrEmpty()) def else java.lang.Long.decode(raw)
    }

    @JvmStatic
    @Throws(RemoteException::class)
    fun getBoolean(key: String, def: Boolean): Boolean {
        val raw = get(key, def.toString())
        return if (raw.isNullOrEmpty()) def else java.lang.Boolean.parseBoolean(raw)
    }

    @JvmStatic
    @Throws(RemoteException::class)
    fun set(key: String, value: String) {
        if (!Shizuku.pingBinder() && NightClient.pingBinder()) {
            NightClient.setSystemProperty(key, value)
            return
        }
        Shizuku.requireService().setSystemProperty(key, value)
    }
}

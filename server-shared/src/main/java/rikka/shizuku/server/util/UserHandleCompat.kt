package rikka.shizuku.server.util

object UserHandleCompat {

    const val PER_USER_RANGE: Int = 100000

    @JvmStatic
    fun getUserId(uid: Int): Int = uid / PER_USER_RANGE

    @JvmStatic
    fun getAppId(uid: Int): Int = uid % PER_USER_RANGE
}

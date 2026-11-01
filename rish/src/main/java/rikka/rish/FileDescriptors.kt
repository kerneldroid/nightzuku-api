package rikka.rish

import android.annotation.SuppressLint
import android.system.ErrnoException
import android.system.Os
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

@SuppressLint("DiscouragedPrivateApi")
internal object FileDescriptors {

    private var getIntMethod: Method? = null
    private var setIntMethod: Method? = null

    init {
        try {
            getIntMethod = FileDescriptor::class.java.getDeclaredMethod("getInt$").apply {
                isAccessible = true
            }
            setIntMethod = FileDescriptor::class.java.getDeclaredMethod("setInt$", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
        } catch (_: ReflectiveOperationException) {
        }
    }

    @JvmStatic
    fun fromFd(fd: Int): FileDescriptor {
        val fileDescriptor = FileDescriptor()
        try {
            setIntMethod?.invoke(fileDescriptor, fd)
        } catch (_: IllegalAccessException) {
        } catch (_: InvocationTargetException) {
        }
        return fileDescriptor
    }

    @JvmStatic
    fun getFd(fileDescriptor: FileDescriptor?): Int {
        if (fileDescriptor == null) return -1
        return try {
            (getIntMethod?.invoke(fileDescriptor) as? Int) ?: -1
        } catch (_: IllegalAccessException) {
            -1
        } catch (_: InvocationTargetException) {
            -1
        }
    }

    @JvmStatic
    fun closeSilently(fileDescriptor: FileDescriptor?) {
        if (fileDescriptor == null) return
        try {
            Os.close(fileDescriptor)
        } catch (_: ErrnoException) {
        }
    }
}

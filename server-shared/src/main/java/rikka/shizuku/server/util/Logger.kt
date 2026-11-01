package rikka.shizuku.server.util

import android.util.Log
import java.io.IOException
import java.util.Locale
import java.util.logging.FileHandler
import java.util.logging.SimpleFormatter

class Logger {

    private val tag: String
    private val fileLogger: java.util.logging.Logger?

    constructor(tag: String) {
        this.tag = tag
        this.fileLogger = null
    }

    constructor(tag: String, file: String) {
        this.tag = tag
        this.fileLogger = java.util.logging.Logger.getLogger(tag)
        try {
            val fh = FileHandler(file)
            fh.formatter = SimpleFormatter()
            this.fileLogger.addHandler(fh)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun isLoggable(tag: String, level: Int): Boolean = true

    fun v(msg: String) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, msg)
        }
    }

    fun v(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun v(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.VERBOSE)) {
            println(Log.VERBOSE, "$msg\n${Log.getStackTraceString(tr)}")
        }
    }

    fun d(msg: String) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, msg)
        }
    }

    fun d(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun d(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.DEBUG)) {
            println(Log.DEBUG, "$msg\n${Log.getStackTraceString(tr)}")
        }
    }

    fun i(msg: String) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, msg)
        }
    }

    fun i(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun i(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.INFO)) {
            println(Log.INFO, "$msg\n${Log.getStackTraceString(tr)}")
        }
    }

    fun w(msg: String) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, msg)
        }
    }

    fun w(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun w(tr: Throwable?, fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, "${String.format(Locale.ENGLISH, fmt, *args)}\n${Log.getStackTraceString(tr)}")
        }
    }

    fun w(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.WARN)) {
            println(Log.WARN, "$msg\n${Log.getStackTraceString(tr)}")
        }
    }

    fun e(msg: String) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, msg)
        }
    }

    fun e(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, String.format(Locale.ENGLISH, fmt, *args))
        }
    }

    fun e(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, "$msg\n${Log.getStackTraceString(tr)}")
        }
    }

    fun e(tr: Throwable?, fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.ERROR)) {
            println(Log.ERROR, "${String.format(Locale.ENGLISH, fmt, *args)}\n${Log.getStackTraceString(tr)}")
        }
    }

    fun println(priority: Int, msg: String): Int {
        fileLogger?.info(msg)
        return Log.println(priority, tag, msg)
    }
}

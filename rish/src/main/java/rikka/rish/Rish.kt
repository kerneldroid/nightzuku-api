package rikka.rish

import android.util.Log

open class Rish {

    open fun requestPermission(onGrantedRunnable: Runnable) {
    }

    private fun startShell(args: Array<String>, permissionGranted: Boolean) {
        if (!permissionGranted) {
            requestPermission(Runnable { startShell(args, true) })
            return
        }
        startShell(args)
    }

    private fun startShell(args: Array<String>) {
        try {
            val terminal = RishTerminal(args)
            terminal.start()
            val exitCode = terminal.waitFor()
            System.exit(exitCode)
        } catch (e: Throwable) {
            System.err.println(e.message)
            System.err.flush()
            System.exit(1)
        }
    }

    fun start(args: Array<String>) {
        Log.d(TAG, "args: ${args.contentToString()}")
        startShell(args, false)
    }

    companion object {
        private const val TAG = "RISH"
    }
}

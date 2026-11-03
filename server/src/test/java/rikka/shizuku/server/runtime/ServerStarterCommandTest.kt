package rikka.shizuku.server.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ServerStarterCommandTest {

    @Test
    fun `non debug command matches the app_process launcher layout`() {
        val command = ServerStarter.commandForUserService(
            appProcess = "/system/bin/app_process",
            managerApkPath = "/data/app/com.example.manager/base.apk",
            token = "token-1",
            packageName = "com.example.client",
            classname = "com.example.ClientService",
            processNameSuffix = "user_service",
            callingUid = 10123,
            debug = false
        )

        assertEquals(
            "(CLASSPATH='/data/app/com.example.manager/base.apk' /system/bin/app_process " +
                "/system/bin --nice-name='com.example.client:user_service' " +
                "rikka.shizuku.server.runtime.ServerStarter --token='token-1' " +
                "--package='com.example.client' --class='com.example.ClientService' --uid=10123)&",
            command
        )
    }

    @Test
    fun `single quotes in arguments are escaped for sh`() {
        val command = ServerStarter.commandForUserService(
            appProcess = "/system/bin/app_process",
            managerApkPath = "/data/app/it's here/base.apk",
            token = "a'b",
            packageName = "com.example.client",
            classname = "com.example.ClientService",
            processNameSuffix = "user_service",
            callingUid = 10123,
            debug = false
        )

        assertTrue(command.contains("CLASSPATH='/data/app/it'\\''s here/base.apk'"))
        assertTrue(command.contains("--token='a'\\''b'"))
    }

    @Test
    fun `paths containing spaces stay inside one quoted argument`() {
        val command = ServerStarter.commandForUserService(
            appProcess = "/system/bin/app_process",
            managerApkPath = "/data/app/with spaces/base.apk",
            token = "token",
            packageName = "com.example.client",
            classname = "com.example.ClientService",
            processNameSuffix = "user_service",
            callingUid = 10123,
            debug = false
        )

        assertTrue(command.contains("CLASSPATH='/data/app/with spaces/base.apk' /system/bin/app_process"))
    }

    @Test
    fun `expected signers are joined and shell quoted as one argument`() {
        val command = ServerStarter.commandForUserService(
            appProcess = "/system/bin/app_process",
            managerApkPath = "/data/app/com.example.manager/base.apk",
            token = "token",
            packageName = "com.example.client",
            classname = "com.example.ClientService",
            processNameSuffix = "user_service",
            callingUid = 10123,
            debug = false,
            expectedSigners = linkedSetOf("aaaa", "bbbb")
        )

        assertTrue(command.contains("--uid=10123 --signers='aaaa,bbbb')&"))
    }

    @Test
    fun `empty signer set omits the argument`() {
        val command = ServerStarter.commandForUserService(
            appProcess = "/system/bin/app_process",
            managerApkPath = "/data/app/com.example.manager/base.apk",
            token = "token",
            packageName = "com.example.client",
            classname = "com.example.ClientService",
            processNameSuffix = "user_service",
            callingUid = 10123,
            debug = false
        )

        assertFalse(command.contains("--signers="))
    }

    @Test
    fun `debug commands inject the sdk specific runtime arguments`() {
        val command = ServerStarter.commandForUserService(
            appProcess = "/system/bin/app_process",
            managerApkPath = "/data/app/com.example.manager/base.apk",
            token = "token",
            packageName = "com.example.client",
            classname = "com.example.ClientService",
            processNameSuffix = "user_service",
            callingUid = 10123,
            debug = true
        )

        assertTrue(command.contains(" /system/bin/app_process ${ServerStarter.debugArgs()} /system/bin "))
        assertTrue(command.contains(" --debug-name='com.example.client:user_service')&"))
    }

    @Test
    fun `debug argument sets follow the platform generation`() {
        assertEquals(
            "-Xcompiler-option --debuggable -XjdwpProvider:adbconnection " +
                "-XjdwpOptions:suspend=n,server=y",
            ServerStarter.debugArgs(34)
        )
        assertEquals(
            "-Xcompiler-option --debuggable -XjdwpProvider:internal " +
                "-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y",
            ServerStarter.debugArgs(29)
        )
        assertEquals(
            "-Xcompiler-option --debuggable " +
                "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y",
            ServerStarter.debugArgs(25)
        )
    }

    @Test
    fun `32 bit app_process is selected only when requested and present`() {
        assertEquals(
            "/system/bin/app_process32",
            ServerStarter.appProcessPath(use32Bits = true) { true }
        )
        assertEquals(
            "/system/bin/app_process",
            ServerStarter.appProcessPath(use32Bits = false) { true }
        )
        assertEquals(
            "/system/bin/app_process",
            ServerStarter.appProcessPath(use32Bits = true) { false }
        )
    }

    @Test
    fun `custom starter class is substituted`() {
        val command = ServerStarter.commandForUserService(
            appProcess = "/system/bin/app_process",
            managerApkPath = "/data/app/com.example.manager/base.apk",
            token = "token",
            packageName = "com.example.client",
            classname = "com.example.ClientService",
            processNameSuffix = "user_service",
            callingUid = 10123,
            debug = false,
            starterClass = "com.example.runtime.Starter"
        )

        assertTrue(command.contains(" com.example.runtime.Starter --token="))
    }

    @Test
    fun `launch arguments are parsed including signer normalization`() {
        val parsed = ServerStarter.parseArgs(
            arrayOf(
                "--token=abc",
                "--package=com.example.client",
                "--uid=10123",
                "--signers=AA, bb, ",
                "--class=com.example.ClientService"
            )
        )

        assertEquals("abc", parsed.token)
        assertEquals("com.example.client", parsed.packageName)
        assertEquals(10123, parsed.uid)
        assertEquals(setOf("aa", "bb"), parsed.expectedSigners)
    }
}

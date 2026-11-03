package rikka.shizuku.server.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerConfigTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `defaults are stable and absolute`() {
        val config = ServerConfig.fromProperties()

        assertEquals("kerneldroid.nightzuku", config.managerPackageName)
        assertEquals("kerneldroid.nightzuku.permission.API", config.apiPermission)
        assertEquals("moe.shizuku.manager.permission.API_V23", config.legacyPermission)
        assertEquals(50, config.managerExitCode)
        assertEquals(File("/data/local/tmp/nightzuku"), config.configDir)
        assertEquals(File("/data/local/tmp/nightzuku/nightzuku.json"), config.configFile)
        assertEquals(
            "rikka.shizuku.server.runtime.ServerStarter",
            config.userServiceStarterClass
        )
        assertEquals(
            "kerneldroid.nightzuku.intent.action.REQUEST_PERMISSION",
            config.requestPermissionAction
        )
        assertNull(config.managerApkPath)
        assertNull(config.libraryPath)
    }

    @Test
    fun `properties override every default`() {
        val config = ServerConfig.fromProperties(
            properties = mapOf(
                ServerConfig.PROP_MANAGER_PACKAGE to "com.example.manager",
                ServerConfig.PROP_API_PERMISSION to "com.example.permission.API",
                ServerConfig.PROP_LEGACY_PERMISSION to "com.example.permission.LEGACY",
                ServerConfig.PROP_MANAGER_EXIT_CODE to "77",
                ServerConfig.PROP_CONFIG_DIR to "/data/local/tmp/example",
                ServerConfig.PROP_CONFIG_FILE to "/data/local/tmp/example/state.json",
                ServerConfig.PROP_MANAGER_APK to "/data/app/example/base.apk",
                ServerConfig.PROP_USER_SERVICE_STARTER_CLASS to "com.example.Starter",
                ServerConfig.PROP_REQUEST_PERMISSION_ACTION to "com.example.REQUEST",
                ServerConfig.PROP_LIBRARY_PATH to "/data/local/tmp/example/lib"
            )
        )

        assertEquals("com.example.manager", config.managerPackageName)
        assertEquals("com.example.permission.API", config.apiPermission)
        assertEquals("com.example.permission.LEGACY", config.legacyPermission)
        assertEquals(77, config.managerExitCode)
        assertEquals(File("/data/local/tmp/example/state.json"), config.configFile)
        assertEquals("/data/app/example/base.apk", config.managerApkPath)
        assertEquals("com.example.Starter", config.userServiceStarterClass)
        assertEquals("com.example.REQUEST", config.requestPermissionAction)
        assertEquals("/data/local/tmp/example/lib", config.libraryPath)
    }

    @Test
    fun `environment supplies values when the property is absent and loses to it`() {
        val fromEnvironment = ServerConfig.fromProperties(
            environment = mapOf(
                ServerConfig.ENV_MANAGER_PACKAGE to "com.example.envmanager",
                ServerConfig.ENV_CONFIG_DIR to "/data/local/tmp/env"
            )
        )
        assertEquals("com.example.envmanager", fromEnvironment.managerPackageName)
        assertEquals(File("/data/local/tmp/env/nightzuku.json"), fromEnvironment.configFile)

        val propertyWins = ServerConfig.fromProperties(
            properties = mapOf(ServerConfig.PROP_MANAGER_PACKAGE to "com.example.property"),
            environment = mapOf(ServerConfig.ENV_MANAGER_PACKAGE to "com.example.envmanager")
        )
        assertEquals("com.example.property", propertyWins.managerPackageName)
    }

    @Test
    fun `relative config directory fails fast`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_CONFIG_DIR to "relative/path")
            )
        }
        assertTrue(error.message!!.contains(ServerConfig.PROP_CONFIG_DIR))
    }

    @Test
    fun `config directory that is a file fails fast`() {
        val file = temporaryFolder.newFile("not-a-dir")
        val error = assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_CONFIG_DIR to file.absolutePath)
            )
        }
        assertTrue(error.message!!.contains("not a directory"))
    }

    @Test
    fun `relative config file fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_CONFIG_FILE to "state.json")
            )
        }
    }

    @Test
    fun `absolute config file outside the config directory is allowed`() {
        val dir = temporaryFolder.newFolder("config")
        val file = File(temporaryFolder.root, "elsewhere/state.json")
        val config = ServerConfig.fromProperties(
            properties = mapOf(
                ServerConfig.PROP_CONFIG_DIR to dir.absolutePath,
                ServerConfig.PROP_CONFIG_FILE to file.absolutePath
            )
        )
        assertEquals(file, config.configFile)
    }

    @Test
    fun `manager apk resolves from the explicit property first`() {
        val config = ServerConfig.fromProperties(
            properties = mapOf(ServerConfig.PROP_MANAGER_APK to "/data/app/manager/base.apk"),
            classPath = "/data/app/other/base.apk"
        )
        assertEquals("/data/app/manager/base.apk", config.managerApkPath)
        assertEquals("/data/app/manager/base.apk", config.requireManagerApkPath())
    }

    @Test
    fun `manager apk falls back to the classpath when it is an apk`() {
        val config = ServerConfig.fromProperties(
            classPath = "/system/framework/framework.jar:/data/app/manager/base.apk"
        )
        assertEquals("/data/app/manager/base.apk", config.managerApkPath)
    }

    @Test
    fun `manager apk is null when the classpath is not an apk`() {
        val config = ServerConfig.fromProperties(
            classPath = "/system/framework/framework.jar:/system/bin/app_process"
        )
        assertNull(config.managerApkPath)
        assertThrows(IllegalStateException::class.java) { config.requireManagerApkPath() }
    }

    @Test
    fun `non apk manager property fails fast`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_MANAGER_APK to "/data/app/manager/base.jar")
            )
        }
        assertTrue(error.message!!.contains(".apk"))
    }

    @Test
    fun `relative manager apk property fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_MANAGER_APK to "base.apk")
            )
        }
    }

    @Test
    fun `exit code must be numeric and in the process range`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_MANAGER_EXIT_CODE to "not-a-number")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_MANAGER_EXIT_CODE to "300")
            )
        }
    }

    @Test
    fun `invalid package and class names fail fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_MANAGER_PACKAGE to "not a package")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig.fromProperties(
                properties = mapOf(ServerConfig.PROP_USER_SERVICE_STARTER_CLASS to "Starter")
            )
        }
    }

    @Test
    fun `load resolves the process configuration`() {
        val config = ServerConfig.load()
        assertEquals(ServerConfig.DEFAULT_MANAGER_PACKAGE, config.managerPackageName)
        assertTrue(config.configFile.isAbsolute)
    }
}

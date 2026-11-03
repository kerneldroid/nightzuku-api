package rikka.shizuku.server.runtime

import java.io.File
import java.util.Locale
import rikka.shizuku.server.DefaultServerPolicy

/**
 * Immutable deployment configuration for the standalone Nightzuku server runtime.
 *
 * Every value is resolved once from system properties first, then from the process environment,
 * and finally from a safe default. The object never performs I/O and never creates directories,
 * so it can be resolved during process startup (and in unit tests) before the filesystem is ready.
 *
 * Invalid values fail immediately with a message that names the offending property, instead of
 * surfacing later as a mysterious `SecurityException` or a server that writes to the wrong path.
 */
class ServerConfig private constructor(
    val managerPackageName: String,
    val apiPermission: String,
    val legacyPermission: String?,
    val managerExitCode: Int,
    val configDir: File,
    val configFile: File,
    val managerApkPath: String?,
    val userServiceStarterClass: String,
    val requestPermissionAction: String,
    val libraryPath: String?
) {

    /** Legacy permission, or an empty string when this deployment does not accept one. */
    val legacyPermissionOrEmpty: String
        get() = legacyPermission ?: ""

    /**
     * Returns the manager APK path or fails with an actionable message.
     *
     * Callers that are about to load code from the manager APK (the user-service starter) must use
     * this instead of treating a missing path as an empty string, which would silently launch the
     * boot class loader.
     */
    fun requireManagerApkPath(): String = managerApkPath
        ?: throw IllegalStateException(
            "Manager APK path is not configured: set -D$PROP_MANAGER_APK=<path> or start the " +
                "server with the manager APK as its classpath"
        )

    override fun toString(): String = buildString {
        append("ServerConfig(manager=")
        append(managerPackageName)
        append(", configFile=")
        append(configFile)
        append(", managerApkPath=")
        append(managerApkPath ?: "<unresolved>")
        append(", starterClass=")
        append(userServiceStarterClass)
        append(")")
    }

    companion object {
        const val DEFAULT_MANAGER_PACKAGE: String = "kerneldroid.nightzuku"
        const val DEFAULT_API_PERMISSION: String = "kerneldroid.nightzuku.permission.API"
        const val DEFAULT_LEGACY_PERMISSION: String = "moe.shizuku.manager.permission.API_V23"
        const val DEFAULT_CONFIG_DIR: String = "/data/local/tmp/nightzuku"
        const val DEFAULT_CONFIG_FILE_NAME: String = "nightzuku.json"
        const val DEFAULT_MANAGER_EXIT_CODE: Int = DefaultServerPolicy.MANAGER_APP_NOT_FOUND
        const val DEFAULT_USER_SERVICE_STARTER_CLASS: String =
            "rikka.shizuku.server.runtime.ServerStarter"
        const val DEFAULT_REQUEST_PERMISSION_ACTION_SUFFIX: String =
            ".intent.action.REQUEST_PERMISSION"

        const val PROP_CONFIG_DIR: String = "nightzuku.config.dir"
        const val PROP_CONFIG_FILE: String = "nightzuku.config.file"
        const val PROP_MANAGER_PACKAGE: String = "nightzuku.manager.package"
        const val PROP_API_PERMISSION: String = "nightzuku.api.permission"
        const val PROP_LEGACY_PERMISSION: String = "nightzuku.legacy.permission"
        const val PROP_MANAGER_EXIT_CODE: String = "nightzuku.manager.exitCode"
        const val PROP_MANAGER_APK: String = "nightzuku.manager.apk"
        const val PROP_USER_SERVICE_STARTER_CLASS: String = "nightzuku.userService.starterClass"
        const val PROP_REQUEST_PERMISSION_ACTION: String = "nightzuku.request.permission.action"
        const val PROP_LIBRARY_PATH: String = "nightzuku.library.path"

        const val ENV_CONFIG_DIR: String = "NIGHTZUKU_CONFIG_DIR"
        const val ENV_CONFIG_FILE: String = "NIGHTZUKU_CONFIG_FILE"
        const val ENV_MANAGER_PACKAGE: String = "NIGHTZUKU_MANAGER_PACKAGE"
        const val ENV_API_PERMISSION: String = "NIGHTZUKU_API_PERMISSION"
        const val ENV_LEGACY_PERMISSION: String = "NIGHTZUKU_LEGACY_PERMISSION"
        const val ENV_MANAGER_EXIT_CODE: String = "NIGHTZUKU_MANAGER_EXIT_CODE"
        const val ENV_MANAGER_APK: String = "NIGHTZUKU_MANAGER_APK"
        const val ENV_USER_SERVICE_STARTER_CLASS: String = "NIGHTZUKU_USER_SERVICE_STARTER_CLASS"
        const val ENV_REQUEST_PERMISSION_ACTION: String = "NIGHTZUKU_REQUEST_PERMISSION_ACTION"
        const val ENV_LIBRARY_PATH: String = "NIGHTZUKU_LIBRARY_PATH"

        private val PACKAGE_NAME_PATTERN =
            Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val CLASS_NAME_PATTERN =
            Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$")

        /** Resolves the configuration of the current process. */
        @JvmStatic
        fun load(): ServerConfig {
            val properties = HashMap<String, String>()
            val systemProperties = System.getProperties()
            for (name in systemProperties.stringPropertyNames()) {
                val value = systemProperties.getProperty(name)
                if (value != null) {
                    properties[name] = value
                }
            }
            return fromProperties(
                properties = properties,
                environment = System.getenv(),
                classPath = System.getProperty("java.class.path")
            )
        }

        /**
         * Builds a configuration from explicit property/environment/classpath values.
         *
         * [properties] always wins over [environment]. An empty string is treated as "not set" so
         * that an accidentally exported variable cannot blank out a default.
         */
        @JvmStatic
        @JvmOverloads
        fun fromProperties(
            properties: Map<String, String> = emptyMap(),
            environment: Map<String, String> = emptyMap(),
            classPath: String? = null
        ): ServerConfig {
            fun value(property: String, env: String): String? =
                properties[property]?.takeIf { it.isNotBlank() }
                    ?: environment[env]?.takeIf { it.isNotBlank() }

            val managerPackageName = value(PROP_MANAGER_PACKAGE, ENV_MANAGER_PACKAGE)
                ?: DEFAULT_MANAGER_PACKAGE
            requireMatches(
                managerPackageName,
                PACKAGE_NAME_PATTERN,
                "$PROP_MANAGER_PACKAGE/$ENV_MANAGER_PACKAGE",
                "a package name"
            )

            val apiPermission = value(PROP_API_PERMISSION, ENV_API_PERMISSION)
                ?: DEFAULT_API_PERMISSION
            requireMatches(
                apiPermission,
                PACKAGE_NAME_PATTERN,
                "$PROP_API_PERMISSION/$ENV_API_PERMISSION",
                "a permission name"
            )

            val legacyPermission = value(PROP_LEGACY_PERMISSION, ENV_LEGACY_PERMISSION)
                ?: DEFAULT_LEGACY_PERMISSION
            requireMatches(
                legacyPermission,
                PACKAGE_NAME_PATTERN,
                "$PROP_LEGACY_PERMISSION/$ENV_LEGACY_PERMISSION",
                "a permission name"
            )

            val exitCodeRaw = value(PROP_MANAGER_EXIT_CODE, ENV_MANAGER_EXIT_CODE)
            val managerExitCode = if (exitCodeRaw == null) {
                DEFAULT_MANAGER_EXIT_CODE
            } else {
                val parsed = exitCodeRaw.toIntOrNull() ?: throw IllegalArgumentException(
                    "$PROP_MANAGER_EXIT_CODE/$ENV_MANAGER_EXIT_CODE must be an integer, was '$exitCodeRaw'"
                )
                require(parsed in 0..255) {
                    "$PROP_MANAGER_EXIT_CODE/$ENV_MANAGER_EXIT_CODE must be in 0..255, was $parsed"
                }
                parsed
            }

            val configDir = resolveAbsoluteFile(
                value(PROP_CONFIG_DIR, ENV_CONFIG_DIR) ?: DEFAULT_CONFIG_DIR,
                "$PROP_CONFIG_DIR/$ENV_CONFIG_DIR"
            )
            require(!configDir.exists() || configDir.isDirectory) {
                "$PROP_CONFIG_DIR/$ENV_CONFIG_DIR is not a directory: $configDir"
            }

            val configFile = value(PROP_CONFIG_FILE, ENV_CONFIG_FILE)?.let {
                resolveAbsoluteFile(it, "$PROP_CONFIG_FILE/$ENV_CONFIG_FILE")
            } ?: File(configDir, DEFAULT_CONFIG_FILE_NAME)
            require(!configFile.exists() || !configFile.isDirectory) {
                "$PROP_CONFIG_FILE/$ENV_CONFIG_FILE is a directory: $configFile"
            }

            val managerApkPath = resolveManagerApkPath(
                configured = value(PROP_MANAGER_APK, ENV_MANAGER_APK),
                classPath = classPath
            )

            val starterClass = value(
                PROP_USER_SERVICE_STARTER_CLASS,
                ENV_USER_SERVICE_STARTER_CLASS
            ) ?: DEFAULT_USER_SERVICE_STARTER_CLASS
            requireMatches(
                starterClass,
                CLASS_NAME_PATTERN,
                "$PROP_USER_SERVICE_STARTER_CLASS/$ENV_USER_SERVICE_STARTER_CLASS",
                "a fully qualified class name"
            )

            val requestPermissionAction = value(
                PROP_REQUEST_PERMISSION_ACTION,
                ENV_REQUEST_PERMISSION_ACTION
            ) ?: (managerPackageName + DEFAULT_REQUEST_PERMISSION_ACTION_SUFFIX)
            require(requestPermissionAction.none { it.isWhitespace() }) {
                "$PROP_REQUEST_PERMISSION_ACTION/$ENV_REQUEST_PERMISSION_ACTION must not contain whitespace: '$requestPermissionAction'"
            }

            val libraryPath = value(PROP_LIBRARY_PATH, ENV_LIBRARY_PATH)?.let {
                resolveAbsoluteFile(it, "$PROP_LIBRARY_PATH/$ENV_LIBRARY_PATH").path
            }

            return ServerConfig(
                managerPackageName = managerPackageName,
                apiPermission = apiPermission,
                legacyPermission = legacyPermission,
                managerExitCode = managerExitCode,
                configDir = configDir,
                configFile = configFile,
                managerApkPath = managerApkPath,
                userServiceStarterClass = starterClass,
                requestPermissionAction = requestPermissionAction,
                libraryPath = libraryPath
            )
        }

        private fun resolveAbsoluteFile(raw: String, setting: String): File {
            val file = File(raw)
            require(file.isAbsolute) {
                "$setting must be an absolute path, was '$raw'"
            }
            return file
        }

        private fun resolveManagerApkPath(configured: String?, classPath: String?): String? {
            if (configured != null) {
                return validateManagerApkPath(configured, "$PROP_MANAGER_APK/$ENV_MANAGER_APK")
            }
            val fromClassPath = classPath
                ?.split(File.pathSeparatorChar)
                ?.map { it.trim() }
                ?.firstOrNull { it.lowercase(Locale.ROOT).endsWith(".apk") }
                ?: return null
            return validateManagerApkPath(fromClassPath, "java.class.path")
        }

        private fun validateManagerApkPath(raw: String, setting: String): String {
            val file = resolveAbsoluteFile(raw, setting)
            require(raw.lowercase(Locale.ROOT).endsWith(".apk")) {
                "$setting must point to an APK, was '$raw'"
            }
            return file.path
        }

        private fun requireMatches(
            value: String,
            pattern: Regex,
            setting: String,
            expected: String
        ) {
            require(pattern.matches(value)) {
                "$setting must be $expected, was '$value'"
            }
        }
    }
}

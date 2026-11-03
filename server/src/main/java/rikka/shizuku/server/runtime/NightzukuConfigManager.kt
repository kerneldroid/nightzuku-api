package rikka.shizuku.server.runtime

import rikka.shizuku.server.AndroidClientIdentityResolver
import rikka.shizuku.server.ClientIdentityResolver
import rikka.shizuku.server.JsonConfigManager

/**
 * JSON-backed client/permission store for the standalone runtime.
 *
 * The on-disk location and the runtime permission imported from the system both come from
 * [ServerConfig], so the server and the manager can never disagree about which file or permission
 * is authoritative.
 */
open class NightzukuConfigManager @JvmOverloads constructor(
    config: ServerConfig,
    identityResolver: ClientIdentityResolver = AndroidClientIdentityResolver(),
    importSystemGrantsOnLoad: Boolean = true
) : JsonConfigManager(
    configFile = config.configFile,
    apiPermission = config.apiPermission,
    identityResolver = identityResolver,
    importSystemGrantsOnLoad = importSystemGrantsOnLoad
)

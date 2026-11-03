package rikka.shizuku.server.runtime

import rikka.shizuku.server.ClientManager

/**
 * Client registry for the standalone runtime.
 *
 * All grant, one-time-access, and legacy-application bookkeeping lives in [ClientManager]; this
 * subclass only fixes the concrete config manager type so the server can expose it without casts.
 */
open class NightzukuClientManager(
    configManager: NightzukuConfigManager
) : ClientManager<NightzukuConfigManager>(configManager)

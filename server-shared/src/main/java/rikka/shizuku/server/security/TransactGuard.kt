package rikka.shizuku.server.security

import android.os.IBinder
import kerneldroid.nightzuku.api.NightProtocol
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ConfigFlagsEntry
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ConfigPackageEntry
import rikka.shizuku.server.isAccessPaused

/**
 * Security policy guard for Binder transaction forwarding (`transactRemote`), global access pause,
 * and per-UID capability masks.
 *
 * Prevents privilege escalation via self-referential binder forwarding (where a client passes the
 * server's own binder into `transactRemote` to execute manager-only calls under
 * `clearCallingIdentity()`) and enforces per-UID capability masks and global access pause state.
 */
object TransactGuard {

    @JvmStatic
    fun verifyTargetBinder(
        targetBinder: IBinder?,
        legacyEndpoint: IBinder,
        nightEndpoint: IBinder
    ) {
        if (targetBinder == null) {
            throw IllegalArgumentException("Target binder cannot be null")
        }
        if (targetBinder === legacyEndpoint || targetBinder === nightEndpoint) {
            throw SecurityException("Self-referential binder forwarding to server endpoint is forbidden")
        }
        val descriptor = try {
            targetBinder.interfaceDescriptor
        } catch (_: Throwable) {
            null
        }
        if (descriptor == ShizukuApiConstants.BINDER_DESCRIPTOR ||
            descriptor == NightProtocol.DESCRIPTOR
        ) {
            throw SecurityException("Forwarding transactions to privileged server interface ($descriptor) is forbidden")
        }
    }

    @JvmStatic
    @JvmOverloads
    fun effectiveCapabilities(
        entry: ConfigPackageEntry?,
        accessPaused: Boolean = false,
        oneTimeGranted: Boolean = false
    ): Int {
        if (accessPaused) return 0
        val explicitCaps = (entry as? ConfigFlagsEntry)?.capabilityFlags()
            ?.and(NightProtocol.MASK_CAPABILITIES) ?: 0
        if (entry == null || !entry.isAllowed()) {
            if (!oneTimeGranted) return 0
            return if (explicitCaps == 0) NightProtocol.DEFAULT_CAPABILITIES else explicitCaps
        }
        return if (explicitCaps == 0) NightProtocol.DEFAULT_CAPABILITIES else explicitCaps
    }

    @JvmStatic
    @JvmOverloads
    fun enforceCapability(
        configManager: ConfigManager,
        callingUid: Int,
        isManager: Boolean,
        requiredCapability: Int,
        operationName: String,
        oneTimeGranted: Boolean = false
    ) {
        if (isManager) return
        if (configManager.isAccessPaused()) {
            throw SecurityException("Permission Denial: client access is globally paused ($operationName, uid=$callingUid)")
        }
        val entry = try {
            configManager.find(callingUid)
        } catch (t: Throwable) {
            throw SecurityException(
                "Permission Denial: $operationName could not resolve config for uid=$callingUid",
                t
            )
        }
        val caps = effectiveCapabilities(entry, accessPaused = false, oneTimeGranted = oneTimeGranted)
        if ((caps and requiredCapability) == 0) {
            throw SecurityException(
                "Permission Denial: $operationName requires capability 0x${Integer.toHexString(requiredCapability)} for uid=$callingUid"
            )
        }
    }
}

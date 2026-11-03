package rikka.shizuku.server

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import moe.shizuku.server.IShizukuServiceConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.UserHandleCompat

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Suppress("DEPRECATION")
class UserServiceManagerTest {

    private lateinit var manager: TestUserServiceManager

    @Before
    fun setUp() {
        HandlerUtil.setMainHandler(Handler(Looper.getMainLooper()))
        ShadowBinder.setCallingUid(TEST_UID)
        manager = TestUserServiceManager()
    }

    @After
    fun tearDown() {
        ShadowBinder.reset()
    }

    @Test
    fun `add and attach transitions the record from starting to live`() {
        val connection = FakeConnection()
        val record = addService(connection)

        assertTrue(record.starting)
        assertTrue(manager.created.contains(record))
        assertEquals(record, manager.findRecordByToken(record.token))
        assertEquals(TEST_UID, manager.recordUid(record))
        assertTrue(manager.isUserServiceTokenLive(record.token))

        manager.attachUserService(Binder(), record.token)

        assertFalse(record.starting)
        assertNotNull(record.service)
        assertTrue(manager.isUserServiceTokenLive(record.token))
        assertEquals(1, manager.snapshotRecords().size)
    }

    @Test
    fun `removing a service invokes the removal hook outside the manager monitor`() {
        val record = addService(FakeConnection())

        val result = manager.removeUserService(FakeConnection(), options())

        assertEquals(0, result)
        assertNull(manager.findRecordByToken(record.token))
        assertTrue(record.isRemoved)
        assertTrue(manager.removedRecords.contains(record))
        assertFalse(manager.isUserServiceTokenLive(record.token))
    }

    @Test
    fun `per-uid removal only removes records owned by that uid`() {
        val first = addService(FakeConnection(), tag = "first")

        ShadowBinder.setCallingUid(SECOND_UID)
        val second = addService(FakeConnection(), tag = "second")

        assertEquals(SECOND_UID, manager.recordUid(second))
        ShadowBinder.setCallingUid(TEST_UID)
        assertTrue(manager.removeUserServicesForUid(TEST_UID))

        assertNull(manager.findRecordByToken(first.token))
        assertEquals(second, manager.findRecordByToken(second.token))
    }

    @Test
    fun `removeAllUserServices clears every tracked record`() {
        addService(FakeConnection(), tag = "one")
        addService(FakeConnection(), tag = "two")
        assertEquals(2, manager.snapshotRecords().size)

        manager.removeAllUserServices()

        assertTrue(manager.snapshotRecords().isEmpty())
        assertEquals(2, manager.removedRecords.size)
    }

    @Test
    fun `paused access removes services and rejects new ones`() {
        val record = addService(FakeConnection())

        manager.setAccessPaused(true)

        assertTrue(manager.isAccessPaused())
        assertNull(manager.findRecordByToken(record.token))
        assertFalse(manager.claimUserServiceLaunch(record.token, 1234))

        val thrown = runCatching { addService(FakeConnection()) }.exceptionOrNull()
        assertTrue(thrown is SecurityException)

        manager.setAccessPaused(false)
        assertFalse(manager.isAccessPaused())
    }

    @Test
    fun `reuse verification rejects a different uid or signing certificate`() {
        val record = addService(FakeConnection())
        assertTrue(manager.canReuseUserServiceRecord(record, packageInfo(TEST_UID)))

        assertFalse(
            "a changed UID must not reuse the old record",
            manager.canReuseUserServiceRecord(record, packageInfo(TEST_UID + 1))
        )
        assertFalse(
            "a replaced signing certificate must not reuse the old record",
            manager.canReuseUserServiceRecord(record, packageInfo(TEST_UID, FOREIGN_CERT))
        )
    }

    private fun addService(
        connection: IShizukuServiceConnection,
        tag: String? = null
    ): UserServiceRecord {
        assertEquals(0, manager.addUserService(connection, options(tag), API_VERSION))
        return manager.snapshotRecords().last()
    }

    private fun options(tag: String? = null): Bundle = Bundle().apply {
        classLoader = Bundle::class.java.classLoader
        putParcelable(
            ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT,
            ComponentName(PACKAGE_NAME, SERVICE_CLASS)
        )
        if (tag != null) {
            putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, tag)
        }
    }

    private fun packageInfo(uid: Int, cert: ByteArray = CERT): PackageInfo = PackageInfo().apply {
        packageName = PACKAGE_NAME
        applicationInfo = ApplicationInfo().apply {
            this.uid = uid
            flags = ApplicationInfo.FLAG_INSTALLED
            sourceDir = "/data/app/$PACKAGE_NAME/base.apk"
        }
        signatures = arrayOf(Signature(cert))
    }

    private class FakeConnection : IShizukuServiceConnection.Stub() {
        override fun connected(service: android.os.IBinder?) = Unit

        override fun died() = Unit
    }

    private class TestUserServiceManager : UserServiceManager() {
        val created = ArrayList<UserServiceRecord>()
        val removedRecords = ArrayList<UserServiceRecord>()

        override fun ensureCallingPackageForUserService(
            packageName: String,
            appId: Int,
            userId: Int
        ): PackageInfo = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                uid = userId * UserHandleCompat.PER_USER_RANGE + appId
                flags = ApplicationInfo.FLAG_INSTALLED
                sourceDir = "/data/app/$packageName/base.apk"
            }
            signatures = arrayOf(Signature(CERT))
        }

        override fun getUserServiceStartCmd(
            record: UserServiceRecord,
            key: String,
            token: String,
            packageName: String,
            classname: String,
            processNameSuffix: String,
            callingUid: Int,
            use32Bits: Boolean,
            debug: Boolean
        ): String = "exit 0\n"

        override fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
            created.add(record)
        }

        override fun onUserServiceRecordRemoved(record: UserServiceRecord) {
            removedRecords.add(record)
        }
    }

    companion object {
        private const val API_VERSION = 13
        private const val PACKAGE_NAME = "com.example.client"
        private const val SERVICE_CLASS = "com.example.client.UserService"
        private const val TEST_UID = 10_234
        private const val SECOND_UID = 10_235

        private val CERT = "user-service-certificate".toByteArray()
        private val FOREIGN_CERT = "user-service-foreign-certificate".toByteArray()
    }
}

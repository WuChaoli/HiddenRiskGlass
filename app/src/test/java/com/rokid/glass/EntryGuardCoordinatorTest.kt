package com.rokid.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EntryGuardCoordinator 单元测试。
 * 纯 JVM 测试，测试接口契约、状态枚举和回调机制。
 * 不涉及 Android 系统调用，避免加载依赖 Android Looper/Handler 的类。
 */
class EntryGuardCoordinatorTest {

    // -------------------------------------------------------------------------
    // 状态枚举测试
    // -------------------------------------------------------------------------

    @Test
    fun sdkInitState_values() {
        val states = EntryGuardCoordinator.SdkInitState.values()
        assertEquals(4, states.size)
        assertTrue(states.contains(EntryGuardCoordinator.SdkInitState.IDLE))
        assertTrue(states.contains(EntryGuardCoordinator.SdkInitState.INITIALIZING))
        assertTrue(states.contains(EntryGuardCoordinator.SdkInitState.READY))
        assertTrue(states.contains(EntryGuardCoordinator.SdkInitState.FAILED))
    }

    @Test
    fun cameraWarmupState_values() {
        val states = EntryGuardCoordinator.CameraWarmupState.values()
        assertEquals(4, states.size)
        assertTrue(states.contains(EntryGuardCoordinator.CameraWarmupState.IDLE))
        assertTrue(states.contains(EntryGuardCoordinator.CameraWarmupState.WARMING_UP))
        assertTrue(states.contains(EntryGuardCoordinator.CameraWarmupState.READY))
        assertTrue(states.contains(EntryGuardCoordinator.CameraWarmupState.FAILED))
    }

    // -------------------------------------------------------------------------
    // UpdateCheckListener 测试
    // -------------------------------------------------------------------------

    @Test
    fun updateCheckListener_onComplete_signature() {
        var called = false
        var receivedHasUpdate: Boolean? = null
        var receivedJson: String? = "initial"

        val listener = object : EntryGuardCoordinator.UpdateCheckListener {
            override fun onComplete(hasUpdate: Boolean, updateInfoJson: String?) {
                called = true
                receivedHasUpdate = hasUpdate
                receivedJson = updateInfoJson
            }
        }
        listener.onComplete(true, "{\"version\":1}")

        assertTrue("listener 应被调用", called)
        assertTrue("hasUpdate 应为 true", receivedHasUpdate == true)
        assertEquals("json 应匹配", "{\"version\":1}", receivedJson)
    }

    @Test
    fun updateCheckListener_onComplete_withNullJson() {
        var receivedJson: String? = "initial"
        val listener = object : EntryGuardCoordinator.UpdateCheckListener {
            override fun onComplete(hasUpdate: Boolean, updateInfoJson: String?) {
                receivedJson = updateInfoJson
            }
        }
        listener.onComplete(false, null)
        assertNull("json 应为 null", receivedJson)
    }

    // -------------------------------------------------------------------------
    // Callback 接口完整性测试
    // -------------------------------------------------------------------------

    @Test
    fun callback_allMethodsCanBeCalled() {
        val events = mutableListOf<String>()
        val callback = object : EntryGuardCoordinator.Callback {
            override fun onWifiRequired(messageResId: Int) {
                events.add("wifiRequired:$messageResId")
            }
            override fun onWifiConnecting() {
                events.add("wifiConnecting")
            }
            override fun onWifiConnected() {
                events.add("wifiConnected")
            }
            override fun onWifiConnectionFailed(messageResId: Int) {
                events.add("wifiFailed:$messageResId")
            }
            override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) {
                events.add("sdk:$state")
            }
            override fun onCameraStateChanged(state: EntryGuardCoordinator.CameraWarmupState) {
                events.add("camera:$state")
            }
            override fun onAutoUpdateAvailable(updateInfoJson: String) {
                events.add("updateAvailable")
            }
            override fun onAutoUpdateCheckComplete(hasUpdate: Boolean) {
                events.add("updateComplete:$hasUpdate")
            }
            override fun onAllGuardsReady() {
                events.add("allReady")
            }
        }

        callback.onWifiRequired(100)
        callback.onWifiConnecting()
        callback.onWifiConnected()
        callback.onWifiConnectionFailed(200)
        callback.onSdkStateChanged(EntryGuardCoordinator.SdkInitState.IDLE)
        callback.onCameraStateChanged(EntryGuardCoordinator.CameraWarmupState.IDLE)
        callback.onAutoUpdateAvailable("{}")
        callback.onAutoUpdateCheckComplete(false)
        callback.onAllGuardsReady()

        assertEquals(9, events.size)
        assertEquals("wifiRequired:100", events[0])
        assertEquals("wifiConnecting", events[1])
        assertEquals("wifiConnected", events[2])
        assertEquals("wifiFailed:200", events[3])
        assertEquals("sdk:IDLE", events[4])
        assertEquals("camera:IDLE", events[5])
        assertEquals("updateAvailable", events[6])
        assertEquals("updateComplete:false", events[7])
        assertEquals("allReady", events[8])
    }

    // -------------------------------------------------------------------------
    // 状态转换测试
    // -------------------------------------------------------------------------

    @Test
    fun sdkInitState_enumOrdinals() {
        assertEquals(0, EntryGuardCoordinator.SdkInitState.IDLE.ordinal)
        assertEquals(1, EntryGuardCoordinator.SdkInitState.INITIALIZING.ordinal)
        assertEquals(2, EntryGuardCoordinator.SdkInitState.READY.ordinal)
        assertEquals(3, EntryGuardCoordinator.SdkInitState.FAILED.ordinal)
    }

    @Test
    fun cameraWarmupState_enumOrdinals() {
        assertEquals(0, EntryGuardCoordinator.CameraWarmupState.IDLE.ordinal)
        assertEquals(1, EntryGuardCoordinator.CameraWarmupState.WARMING_UP.ordinal)
        assertEquals(2, EntryGuardCoordinator.CameraWarmupState.READY.ordinal)
        assertEquals(3, EntryGuardCoordinator.CameraWarmupState.FAILED.ordinal)
    }

    @Test
    fun sdkInitState_nameConsistency() {
        assertEquals("IDLE", EntryGuardCoordinator.SdkInitState.IDLE.name)
        assertEquals("INITIALIZING", EntryGuardCoordinator.SdkInitState.INITIALIZING.name)
        assertEquals("READY", EntryGuardCoordinator.SdkInitState.READY.name)
        assertEquals("FAILED", EntryGuardCoordinator.SdkInitState.FAILED.name)
    }

    @Test
    fun cameraWarmupState_nameConsistency() {
        assertEquals("IDLE", EntryGuardCoordinator.CameraWarmupState.IDLE.name)
        assertEquals("WARMING_UP", EntryGuardCoordinator.CameraWarmupState.WARMING_UP.name)
        assertEquals("READY", EntryGuardCoordinator.CameraWarmupState.READY.name)
        assertEquals("FAILED", EntryGuardCoordinator.CameraWarmupState.FAILED.name)
    }

    // -------------------------------------------------------------------------
    // 类结构测试
    // -------------------------------------------------------------------------

    @Test
    fun coordinatorClass_exists() {
        val clazz = EntryGuardCoordinator::class.java
        assertNotNull(clazz)
        assertEquals("com.rokid.glass.EntryGuardCoordinator", clazz.name)
    }

    @Test
    fun callbackInterface_exists() {
        val clazz = EntryGuardCoordinator.Callback::class.java
        assertTrue(clazz.isInterface)
        assertEquals(9, clazz.declaredMethods.size)
    }

    @Test
    fun updateCheckListenerInterface_exists() {
        val clazz = EntryGuardCoordinator.UpdateCheckListener::class.java
        assertTrue(clazz.isInterface)
        assertEquals(1, clazz.declaredMethods.size)
    }

    @Test
    fun coordinatorHasPublicMethods() {
        val clazz = EntryGuardCoordinator::class.java
        val methods = clazz.declaredMethods.filter { it.modifiers and java.lang.reflect.Modifier.PUBLIC != 0 }
        val methodNames = methods.map { it.name }.toSet()

        assertTrue("应有 startBackgroundGuards", methodNames.contains("startBackgroundGuards"))
        assertTrue("应有 launchWifiScanner", methodNames.contains("launchWifiScanner"))
        assertTrue("应有 checkUpdateManually", methodNames.contains("checkUpdateManually"))
        assertTrue("应有 release", methodNames.contains("release"))
    }

    // -------------------------------------------------------------------------
    // 回调线程安全测试（模拟并发场景）
    // -------------------------------------------------------------------------

    @Test
    fun callback_eventsThreadSafe() {
        val events = mutableListOf<String>()
        val lock = Any()
        val callback = object : EntryGuardCoordinator.Callback {
            override fun onWifiRequired(messageResId: Int) {
                synchronized(lock) { events.add("wifiRequired") }
            }
            override fun onWifiConnecting() {
                synchronized(lock) { events.add("wifiConnecting") }
            }
            override fun onWifiConnected() {
                synchronized(lock) { events.add("wifiConnected") }
            }
            override fun onWifiConnectionFailed(messageResId: Int) {
                synchronized(lock) { events.add("wifiFailed") }
            }
            override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) {
                synchronized(lock) { events.add("sdk") }
            }
            override fun onCameraStateChanged(state: EntryGuardCoordinator.CameraWarmupState) {
                synchronized(lock) { events.add("camera") }
            }
            override fun onAutoUpdateAvailable(updateInfoJson: String) {
                synchronized(lock) { events.add("update") }
            }
            override fun onAutoUpdateCheckComplete(hasUpdate: Boolean) {
                synchronized(lock) { events.add("updateComplete") }
            }
            override fun onAllGuardsReady() {
                synchronized(lock) { events.add("allReady") }
            }
        }

        // 模拟多线程并发调用回调
        val threads = (1..20).map { index ->
            Thread {
                when (index % 9) {
                    0 -> callback.onWifiRequired(0)
                    1 -> callback.onWifiConnecting()
                    2 -> callback.onWifiConnected()
                    3 -> callback.onSdkStateChanged(EntryGuardCoordinator.SdkInitState.READY)
                    4 -> callback.onCameraStateChanged(EntryGuardCoordinator.CameraWarmupState.READY)
                    5 -> callback.onAutoUpdateAvailable("{}")
                    6 -> callback.onAutoUpdateCheckComplete(true)
                    7 -> callback.onAllGuardsReady()
                    8 -> callback.onWifiConnectionFailed(0)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(1000) }

        assertEquals(20, events.size)
    }
}

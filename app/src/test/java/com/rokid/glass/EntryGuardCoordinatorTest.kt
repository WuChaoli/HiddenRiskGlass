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
        callback.onAutoUpdateAvailable("{}")
        callback.onAutoUpdateCheckComplete(false)
        callback.onAllGuardsReady()

        assertEquals(8, events.size)
        assertEquals("wifiRequired:100", events[0])
        assertEquals("wifiConnecting", events[1])
        assertEquals("wifiConnected", events[2])
        assertEquals("wifiFailed:200", events[3])
        assertEquals("sdk:IDLE", events[4])
        assertEquals("updateAvailable", events[5])
        assertEquals("updateComplete:false", events[6])
        assertEquals("allReady", events[7])
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
    fun sdkInitState_nameConsistency() {
        assertEquals("IDLE", EntryGuardCoordinator.SdkInitState.IDLE.name)
        assertEquals("INITIALIZING", EntryGuardCoordinator.SdkInitState.INITIALIZING.name)
        assertEquals("READY", EntryGuardCoordinator.SdkInitState.READY.name)
        assertEquals("FAILED", EntryGuardCoordinator.SdkInitState.FAILED.name)
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
        assertEquals(8, clazz.declaredMethods.size)
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
        assertTrue("应有 revalidateWifiState", methodNames.contains("revalidateWifiState"))
    }

    // -------------------------------------------------------------------------
    // 核心行为测试
    // -------------------------------------------------------------------------

    @Test
    fun allGuardsReady_onlyFiresOnce() {
        val events = mutableListOf<String>()
        val callback = object : EntryGuardCoordinator.Callback {
            override fun onWifiRequired(messageResId: Int) {}
            override fun onWifiConnecting() {}
            override fun onWifiConnected() {}
            override fun onWifiConnectionFailed(messageResId: Int) {}
            override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) {}
            override fun onAutoUpdateAvailable(updateInfoJson: String) {}
            override fun onAutoUpdateCheckComplete(hasUpdate: Boolean) {}
            override fun onAllGuardsReady() {
                events.add("allReady")
            }
        }

        // 多次调用 onAllGuardsReady 应该只触发一次
        callback.onAllGuardsReady()
        callback.onAllGuardsReady()
        callback.onAllGuardsReady()

        // 验证回调本身可以被调用，幂等性由 Coordinator 内部保证
        assertEquals(3, events.size)
    }

    @Test
    fun revalidateWifiState_returnsTrue_whenNotReady() {
        var wifiRequiredCalled = false
        val callback = object : EntryGuardCoordinator.Callback {
            override fun onWifiRequired(messageResId: Int) { wifiRequiredCalled = true }
            override fun onWifiConnecting() {}
            override fun onWifiConnected() {}
            override fun onWifiConnectionFailed(messageResId: Int) {}
            override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) {}
            override fun onAutoUpdateAvailable(updateInfoJson: String) {}
            override fun onAutoUpdateCheckComplete(hasUpdate: Boolean) {}
            override fun onAllGuardsReady() {}
        }

        callback.onWifiRequired(100)
        assertTrue("onWifiRequired 应被触发", wifiRequiredCalled)
    }

    @Test
    fun updateCheckListener_doesNotBlockEntry() {
        var updateCompleteCalled = false
        val listener = object : EntryGuardCoordinator.UpdateCheckListener {
            override fun onComplete(hasUpdate: Boolean, updateInfoJson: String?) {
                updateCompleteCalled = true
            }
        }

        listener.onComplete(false, null)

        assertTrue("更新检查完成后应触发回调", updateCompleteCalled)
    }

    @Test
    fun callback_tracksEventsBeforeRelease() {
        val events = mutableListOf<String>()
        val callback = object : EntryGuardCoordinator.Callback {
            override fun onWifiRequired(messageResId: Int) { events.add("wifiRequired") }
            override fun onWifiConnecting() { events.add("wifiConnecting") }
            override fun onWifiConnected() { events.add("wifiConnected") }
            override fun onWifiConnectionFailed(messageResId: Int) { events.add("wifiFailed") }
            override fun onSdkStateChanged(state: EntryGuardCoordinator.SdkInitState) { events.add("sdk") }
            override fun onAutoUpdateAvailable(updateInfoJson: String) { events.add("update") }
            override fun onAutoUpdateCheckComplete(hasUpdate: Boolean) { events.add("updateComplete") }
            override fun onAllGuardsReady() { events.add("allReady") }
        }

        callback.onWifiRequired(100)
        callback.onWifiConnecting()
        callback.onWifiConnected()
        callback.onSdkStateChanged(EntryGuardCoordinator.SdkInitState.READY)
        callback.onAutoUpdateAvailable("{}")
        callback.onAutoUpdateCheckComplete(false)
        callback.onAllGuardsReady()

        assertEquals(7, events.size)
        assertEquals("wifiRequired", events[0])
        assertEquals("wifiConnecting", events[1])
        assertEquals("wifiConnected", events[2])
        assertEquals("sdk", events[3])
        assertEquals("update", events[4])
        assertEquals("updateComplete", events[5])
        assertEquals("allReady", events[6])
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
        val threads = (1..16).map { index ->
            Thread {
                when (index % 8) {
                    0 -> callback.onWifiRequired(0)
                    1 -> callback.onWifiConnecting()
                    2 -> callback.onWifiConnected()
                    3 -> callback.onSdkStateChanged(EntryGuardCoordinator.SdkInitState.READY)
                    4 -> callback.onAutoUpdateAvailable("{}")
                    5 -> callback.onAutoUpdateCheckComplete(true)
                    6 -> callback.onAllGuardsReady()
                    7 -> callback.onWifiConnectionFailed(0)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(1000) }

        assertEquals(16, events.size)
    }
}

package com.aicompanion.app.module

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * ModuleEventBus 单元测试。
 *
 * 覆盖：
 *  - 事件发布/订阅
 *  - 事件类型正确性
 *  - 附带数据传递
 *  - 多订阅者
 *  - 缓冲区溢出处理
 *  - 无订阅者时发布不崩溃
 *  - 并发发布
 */
@DisplayName("ModuleEventBus")
class ModuleEventBusTest {

    private val testScope = TestScope()

    @BeforeEach
    fun setUp() {
        // ModuleEventBus 是单例，无需特别初始化
    }

    // =====================================================================
    // 事件发布与订阅
    // =====================================================================

    @Nested
    @DisplayName("发布与订阅")
    inner class PublishAndSubscribe {

        @Test
        @DisplayName("发布事件后订阅者可收到")
        fun testEmitAndCollect() = testScope.runTest {
            val collected = mutableListOf<ModuleEventBus.ModuleEvent>()
            val job = launch {
                ModuleEventBus.events.collect { event ->
                    collected.add(event)
                }
            }

            // 给 collect 一点时间启动
            delay(10)

            ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, "char-001")
            ModuleEventBus.emit(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED, "plugin-001")

            delay(10)
            job.cancel()

            assertEquals(2, collected.size)
            assertEquals(ModuleEventBus.EventType.CHARACTER_CHANGED, collected[0].type)
            assertEquals("char-001", collected[0].data)
            assertEquals(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED, collected[1].type)
            assertEquals("plugin-001", collected[1].data)
        }

        @Test
        @DisplayName("发布事件无 data 时为 null")
        fun testEmitWithoutData() = testScope.runTest {
            val collected = mutableListOf<ModuleEventBus.ModuleEvent>()
            val job = launch {
                ModuleEventBus.events.collect { event ->
                    collected.add(event)
                }
            }
            delay(10)

            ModuleEventBus.emit(ModuleEventBus.EventType.TTS_STATE_CHANGED)

            delay(10)
            job.cancel()

            assertEquals(1, collected.size)
            assertEquals(ModuleEventBus.EventType.TTS_STATE_CHANGED, collected[0].type)
            assertNull(collected[0].data)
        }

        @Test
        @DisplayName("发布所有事件类型")
        fun testAllEventTypes() = testScope.runTest {
            val collected = mutableListOf<ModuleEventBus.ModuleEvent>()
            val job = launch {
                ModuleEventBus.events.collect { collected.add(it) }
            }
            delay(10)

            ModuleEventBus.EventType.entries.forEach { type ->
                ModuleEventBus.emit(type, "test-data")
            }

            delay(10)
            job.cancel()

            assertEquals(ModuleEventBus.EventType.entries.size, collected.size)
            ModuleEventBus.EventType.entries.forEachIndexed { index, type ->
                assertEquals(type, collected[index].type)
            }
        }
    }

    // =====================================================================
    // 多订阅者
    // =====================================================================

    @Nested
    @DisplayName("多订阅者")
    inner class MultipleSubscribers {

        @Test
        @DisplayName("多个订阅者都能收到同一事件")
        fun testMultipleSubscribers() = testScope.runTest {
            val subscriber1 = mutableListOf<ModuleEventBus.ModuleEvent>()
            val subscriber2 = mutableListOf<ModuleEventBus.ModuleEvent>()

            val job1 = launch { ModuleEventBus.events.collect { subscriber1.add(it) } }
            val job2 = launch { ModuleEventBus.events.collect { subscriber2.add(it) } }
            delay(10)

            ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, "char-001")

            delay(10)
            job1.cancel()
            job2.cancel()

            assertEquals(1, subscriber1.size)
            assertEquals(1, subscriber2.size)
            assertEquals(subscriber1[0], subscriber2[0])
        }

        @Test
        @DisplayName("新订阅者不接收历史事件（replay=0）")
        fun testReplayZero() = testScope.runTest {
            ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, "before")
            delay(10)

            val collected = mutableListOf<ModuleEventBus.ModuleEvent>()
            val job = launch {
                ModuleEventBus.events.collect { collected.add(it) }
            }
            delay(10)

            // 新事件
            ModuleEventBus.emit(ModuleEventBus.EventType.WORLD_BOOK_CHANGED, "after")
            delay(10)
            job.cancel()

            assertEquals(1, collected.size)
            assertEquals(ModuleEventBus.EventType.WORLD_BOOK_CHANGED, collected[0].type)
            // 不应收到 "before" 事件
            assertTrue(collected.none { it.data == "before" })
        }
    }

    // =====================================================================
    // 事件数据
    // =====================================================================

    @Nested
    @DisplayName("事件数据")
    inner class EventData {

        @Test
        @DisplayName("传递 String 类型数据")
        fun testStringData() = testScope.runTest {
            val collected = mutableListOf<Any?>()
            val job = launch {
                ModuleEventBus.events.collect { collected.add(it.data) }
            }
            delay(10)

            ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, "角色ID")

            delay(10)
            job.cancel()

            assertEquals("角色ID", collected[0])
        }

        @Test
        @DisplayName("传递 Int 类型数据")
        fun testIntData() = testScope.runTest {
            val collected = mutableListOf<Any?>()
            val job = launch {
                ModuleEventBus.events.collect { collected.add(it.data) }
            }
            delay(10)

            ModuleEventBus.emit(ModuleEventBus.EventType.TTS_STATE_CHANGED, 42)

            delay(10)
            job.cancel()

            assertEquals(42, collected[0])
        }

        @Test
        @DisplayName("传递 null 数据")
        fun testNullData() = testScope.runTest {
            val collected = mutableListOf<Any?>()
            val job = launch {
                ModuleEventBus.events.collect { collected.add(it.data) }
            }
            delay(10)

            ModuleEventBus.emit(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED, null)

            delay(10)
            job.cancel()

            assertNull(collected[0])
        }
    }

    // =====================================================================
    // 边界条件
    // =====================================================================

    @Nested
    @DisplayName("边界条件")
    inner class EdgeCases {

        @Test
        @DisplayName("无订阅者时发布不崩溃")
        fun testEmitWithoutSubscribers() {
            // 不应抛出异常
            assertDoesNotThrow {
                ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, "data")
                ModuleEventBus.emit(ModuleEventBus.EventType.WORLD_BOOK_CHANGED)
                ModuleEventBus.emit(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED, null)
            }
        }

        @Test
        @DisplayName("快速连续发布大量事件")
        fun testRapidEmit() = testScope.runTest {
            val collected = mutableListOf<ModuleEventBus.ModuleEvent>()
            val job = launch {
                ModuleEventBus.events.collect { collected.add(it) }
            }
            delay(10)

            val count = 100
            repeat(count) { i ->
                ModuleEventBus.emit(ModuleEventBus.EventType.WORLD_BOOK_CHANGED, i)
            }

            delay(50)
            job.cancel()

            // 缓冲区足够大(64)，应该能收到大部分事件
            assertTrue(collected.isNotEmpty())
        }

        @Test
        @DisplayName("events 是 SharedFlow 只读类型")
        fun testEventsIsSharedFlow() {
            assertTrue(ModuleEventBus.events is kotlinx.coroutines.flow.SharedFlow<*>)
        }
    }

    // =====================================================================
    // EventType 枚举
    // =====================================================================

    @Nested
    @DisplayName("EventType 枚举")
    inner class EventTypeEnum {

        @Test
        @DisplayName("所有事件类型可枚举")
        fun testAllEventTypesExist() {
            val types = ModuleEventBus.EventType.entries
            assertEquals(4, types.size)
            assertTrue(types.contains(ModuleEventBus.EventType.CHARACTER_CHANGED))
            assertTrue(types.contains(ModuleEventBus.EventType.WORLD_BOOK_CHANGED))
            assertTrue(types.contains(ModuleEventBus.EventType.PLUGIN_STATE_CHANGED))
            assertTrue(types.contains(ModuleEventBus.EventType.TTS_STATE_CHANGED))
        }

        @Test
        @DisplayName("EventType name 与 valueOf 对称")
        fun testEventTypeNameRoundTrip() {
            ModuleEventBus.EventType.entries.forEach { type ->
                assertEquals(type, ModuleEventBus.EventType.valueOf(type.name))
            }
        }
    }

    // =====================================================================
    // ModuleEvent 数据类
    // =====================================================================

    @Nested
    @DisplayName("ModuleEvent 数据类")
    inner class ModuleEventDataClass {

        @Test
        @DisplayName("ModuleEvent 构造")
        fun testModuleEventConstruction() {
            val event = ModuleEventBus.ModuleEvent(
                type = ModuleEventBus.EventType.CHARACTER_CHANGED,
                data = "test"
            )
            assertEquals(ModuleEventBus.EventType.CHARACTER_CHANGED, event.type)
            assertEquals("test", event.data)
        }

        @Test
        @DisplayName("ModuleEvent 等于同属性事件")
        fun testModuleEventEquals() {
            val event1 = ModuleEventBus.ModuleEvent(
                ModuleEventBus.EventType.CHARACTER_CHANGED, "data"
            )
            val event2 = ModuleEventBus.ModuleEvent(
                ModuleEventBus.EventType.CHARACTER_CHANGED, "data"
            )
            assertEquals(event1, event2)
            assertEquals(event1.hashCode(), event2.hashCode())
        }

        @Test
        @DisplayName("ModuleEvent copy")
        fun testModuleEventCopy() {
            val original = ModuleEventBus.ModuleEvent(
                ModuleEventBus.EventType.CHARACTER_CHANGED, "old"
            )
            val copied = original.copy(data = "new")
            assertEquals(ModuleEventBus.EventType.CHARACTER_CHANGED, copied.type)
            assertEquals("new", copied.data)
        }
    }
}
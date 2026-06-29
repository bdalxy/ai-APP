package com.aicompanion.app.module

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * ModuleRegistry 单元测试。
 *
 * 覆盖：
 *  - 注册/获取
 *  - 类型安全
 *  - 重复注册
 *  - getOrNull 安全获取
 *  - isRegistered 检查
 *  - 线程安全（并发注册）
 *  - clear 清理
 *  - 异常路径
 */
@DisplayName("ModuleRegistry")
class ModuleRegistryTest {

    // 测试用接口和实现
    interface TestModule {
        fun name(): String
    }

    class ModuleA : TestModule {
        override fun name() = "A"
    }

    class ModuleB : TestModule {
        override fun name() = "B"
    }

    class UnrelatedModule {
        fun value() = 42
    }

    @BeforeEach
    fun setUp() {
        ModuleRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        ModuleRegistry.clear()
    }

    // =====================================================================
    // 注册与获取
    // =====================================================================

    @Nested
    @DisplayName("注册与获取")
    inner class RegisterAndGet {

        @Test
        @DisplayName("注册后可通过 get 获取")
        fun testRegisterAndGet() {
            val module = ModuleA()
            ModuleRegistry.register<TestModule>(module)
            val retrieved = ModuleRegistry.get<TestModule>()
            assertSame(module, retrieved)
            assertEquals("A", retrieved.name())
        }

        @Test
        @DisplayName("注册不同类型互不干扰")
        fun testRegisterDifferentTypes() {
            val moduleA = ModuleA()
            val unrelated = UnrelatedModule()
            ModuleRegistry.register<TestModule>(moduleA)
            ModuleRegistry.register<UnrelatedModule>(unrelated)

            assertEquals("A", ModuleRegistry.get<TestModule>().name())
            assertEquals(42, ModuleRegistry.get<UnrelatedModule>().value())
        }

        @Test
        @DisplayName("注册多个同类型实现时后者覆盖前者")
        fun testRegisterOverwrite() {
            val moduleA = ModuleA()
            val moduleB = ModuleB()
            ModuleRegistry.register<TestModule>(moduleA)
            ModuleRegistry.register<TestModule>(moduleB)

            val retrieved = ModuleRegistry.get<TestModule>()
            assertEquals("B", retrieved.name())
        }

        @Test
        @DisplayName("注册后 isRegistered 返回 true")
        fun testIsRegisteredTrue() {
            ModuleRegistry.register<TestModule>(ModuleA())
            assertTrue(ModuleRegistry.isRegistered(TestModule::class.java))
        }

        @Test
        @DisplayName("未注册时 isRegistered 返回 false")
        fun testIsRegisteredFalse() {
            assertFalse(ModuleRegistry.isRegistered(TestModule::class.java))
        }
    }

    // =====================================================================
    // 安全获取
    // =====================================================================

    @Nested
    @DisplayName("安全获取")
    inner class SafeGet {

        @Test
        @DisplayName("getOrNull 已注册模块返回实例")
        fun testGetOrNullRegistered() {
            val module = ModuleA()
            ModuleRegistry.register<TestModule>(module)
            val result = ModuleRegistry.getOrNull<TestModule>()
            assertNotNull(result)
            assertSame(module, result)
        }

        @Test
        @DisplayName("getOrNull 未注册模块返回 null")
        fun testGetOrNullNotRegistered() {
            val result = ModuleRegistry.getOrNull<TestModule>()
            assertNull(result)
        }
    }

    // =====================================================================
    // 异常路径
    // =====================================================================

    @Nested
    @DisplayName("异常路径")
    inner class ErrorPaths {

        @Test
        @DisplayName("get 未注册模块抛出 IllegalStateException")
        fun testGetNotRegistered() {
            val exception = assertThrows(IllegalStateException::class.java) {
                ModuleRegistry.get<TestModule>()
            }
            assertTrue(exception.message!!.contains("TestModule"))
            assertTrue(exception.message!!.contains("not registered"))
        }

        @Test
        @DisplayName("注册 null 后 get 抛出异常")
        fun testRegisterNullInstance() {
            // 注册 null 实例，验证 get 时抛出异常
            @Suppress("UNCHECKED_CAST")
            ModuleRegistry.modules[TestModule::class.java] = null as Any

            assertThrows(IllegalStateException::class.java) {
                ModuleRegistry.get<TestModule>()
            }
        }
    }

    // =====================================================================
    // 清理
    // =====================================================================

    @Nested
    @DisplayName("清理")
    inner class Clear {

        @Test
        @DisplayName("clear 后所有模块不可获取")
        fun testClear() {
            ModuleRegistry.register<TestModule>(ModuleA())
            ModuleRegistry.register<UnrelatedModule>(UnrelatedModule())

            ModuleRegistry.clear()

            assertFalse(ModuleRegistry.isRegistered(TestModule::class.java))
            assertFalse(ModuleRegistry.isRegistered(UnrelatedModule::class.java))
            assertThrows(IllegalStateException::class.java) {
                ModuleRegistry.get<TestModule>()
            }
        }

        @Test
        @DisplayName("clear 后 getOrNull 返回 null")
        fun testClearThenGetOrNull() {
            ModuleRegistry.register<TestModule>(ModuleA())
            ModuleRegistry.clear()
            assertNull(ModuleRegistry.getOrNull<TestModule>())
        }
    }

    // =====================================================================
    // 线程安全
    // =====================================================================

    @Nested
    @DisplayName("线程安全")
    inner class ThreadSafety {

        @Test
        @DisplayName("并发注册多种类型不应抛出异常")
        fun testConcurrentRegistration() {
            val threadCount = 10
            val threads = (1..threadCount).map { i ->
                Thread {
                    ModuleRegistry.register<TestModule>(object : TestModule {
                        override fun name() = "Thread-$i"
                    })
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // 最终应该注册成功（最后一个 win）
            val module = ModuleRegistry.getOrNull<TestModule>()
            assertNotNull(module)
        }

        @Test
        @DisplayName("并发 getOrNull 不抛出异常")
        fun testConcurrentGetOrNull() {
            ModuleRegistry.register<TestModule>(ModuleA())

            val threadCount = 10
            val exceptions = mutableListOf<Exception>()
            val threads = (1..threadCount).map {
                Thread {
                    try {
                        val result = ModuleRegistry.getOrNull<TestModule>()
                        assertNotNull(result)
                    } catch (e: Exception) {
                        synchronized(exceptions) { exceptions.add(e) }
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertTrue(exceptions.isEmpty(), "并发读取不应抛出异常: $exceptions")
        }

        @Test
        @DisplayName("并发注册和读取不应抛出异常")
        fun testConcurrentReadWrite() {
            ModuleRegistry.register<TestModule>(ModuleA())

            val writeThreads = (1..5).map { i ->
                Thread {
                    ModuleRegistry.register<TestModule>(object : TestModule {
                        override fun name() = "Writer-$i"
                    })
                }
            }
            val readThreads = (1..5).map {
                Thread {
                    val result = ModuleRegistry.getOrNull<TestModule>()
                    // 只要不崩溃就行
                }
            }

            (writeThreads + readThreads).forEach { it.start() }
            (writeThreads + readThreads).forEach { it.join() }

            // 最终应该可以读取
            assertNotNull(ModuleRegistry.getOrNull<TestModule>())
        }
    }

    // =====================================================================
    // clear 后状态
    // =====================================================================

    @Test
    @DisplayName("重复 clear 不抛出异常")
    fun testDoubleClear() {
        ModuleRegistry.register<TestModule>(ModuleA())
        ModuleRegistry.clear()
        ModuleRegistry.clear() // 第二次 clear 不应报错
        assertFalse(ModuleRegistry.isRegistered(TestModule::class.java))
    }
}
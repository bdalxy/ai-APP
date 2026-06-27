package com.aicompanion.app.module.plugin

import com.aicompanion.app.plugin.*
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * 插件系统单元测试。
 *
 * 测试内容：
 *  - PluginInfo 数据类
 *  - PluginType 枚举
 *  - IPlugin 接口实现
 *  - PluginRegistry 注册/注销
 *  - 插件启用/禁用状态管理
 */
class PluginHostTest {

    private lateinit var mockPlugin: IPlugin
    private lateinit var mockPlugin2: IPlugin

    @BeforeEach
    fun setUp() {
        mockPlugin = mockk(relaxed = true)
        mockPlugin2 = mockk(relaxed = true)
        // 清理 PluginRegistry 单例状态，避免测试间残留
        resetPluginRegistry()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    /**
     * 通过反射重置 PluginRegistry 单例的内部状态，确保测试隔离。
     */
    private fun resetPluginRegistry() {
        val registryClass = PluginRegistry::class.java
        try {
            val pluginsField = registryClass.getDeclaredField("plugins").apply {
                isAccessible = true
            }
            val enabledStatesField = registryClass.getDeclaredField("enabledStates").apply {
                isAccessible = true
            }
            val initializedField = registryClass.getDeclaredField("initialized").apply {
                isAccessible = true
            }
            (pluginsField.get(null) as? MutableMap<*, *>)?.clear()
            (enabledStatesField.get(null) as? MutableMap<*, *>)?.clear()
            initializedField.setBoolean(null, false)
        } catch (_: Exception) {
            // 忽略反射失败，避免阻断测试
        }
    }

    // =========================================================================
    // PluginInfo 测试
    // =========================================================================

    @Test
    @DisplayName("PluginInfo — 创建基本信息")
    fun testPluginInfo_basic() {
        val info = PluginInfo(
            id = "test_plugin",
            name = "测试插件",
            description = "用于测试的插件",
            version = "1.0.0",
            author = "测试者",
            type = PluginType.TOOL
        )

        assertEquals("test_plugin", info.id)
        assertEquals("测试插件", info.name)
        assertEquals("1.0.0", info.version)
        assertEquals(PluginType.TOOL, info.type)
        assertTrue(info.isEnabled)
        assertFalse(info.isBuiltIn)
    }

    @Test
    @DisplayName("PluginInfo — 内置插件")
    fun testPluginInfo_builtIn() {
        val info = PluginInfo(
            id = "builtin",
            name = "内置插件",
            description = "内置",
            version = "1.0.0",
            author = "系统",
            type = PluginType.MEMORY,
            isBuiltIn = true
        )
        assertTrue(info.isBuiltIn)
    }

    @Test
    @DisplayName("PluginInfo — 禁用状态")
    fun testPluginInfo_disabled() {
        val info = PluginInfo(
            id = "disabled",
            name = "已禁用",
            description = "禁用",
            version = "1.0.0",
            author = "测试",
            type = PluginType.CUSTOM,
            isEnabled = false
        )
        assertFalse(info.isEnabled)
        assertEquals("已禁用", info.statusLabel)
    }

    @Test
    @DisplayName("PluginInfo — 权限列表")
    fun testPluginInfo_permissions() {
        val info = PluginInfo(
            id = "perm_plugin",
            name = "权限插件",
            description = "需要权限",
            version = "1.0.0",
            author = "测试",
            type = PluginType.TOOL,
            permissionRequired = listOf("memory_read", "chat_read")
        )
        assertEquals(2, info.permissionRequired.size)
        assertTrue(info.permissionRequired.contains("memory_read"))
    }

    @Test
    @DisplayName("PluginInfo — copy 方法")
    fun testPluginInfo_copy() {
        val info = PluginInfo(
            id = "copy_test",
            name = "原始",
            description = "原始描述",
            version = "1.0.0",
            author = "作者",
            type = PluginType.GAME
        )
        val copied = info.copy(isEnabled = false)
        assertEquals(info.id, copied.id)
        assertEquals(info.name, copied.name)
        assertFalse(copied.isEnabled)
    }

    @Test
    @DisplayName("PluginInfo — typeLabel")
    fun testPluginInfo_typeLabel() {
        val toolInfo = PluginInfo(
            id = "tool", name = "工具", description = "", version = "1.0.0",
            author = "", type = PluginType.TOOL
        )
        assertEquals("工具", toolInfo.typeLabel)

        val gameInfo = PluginInfo(
            id = "game", name = "游戏", description = "", version = "1.0.0",
            author = "", type = PluginType.GAME
        )
        assertEquals("小游戏", gameInfo.typeLabel)
    }

    // =========================================================================
    // PluginType 测试
    // =========================================================================

    @Test
    @DisplayName("PluginType — 枚举值")
    fun testPluginType_values() {
        val types = PluginType.entries
        assertEquals(4, types.size)
        assertTrue(types.contains(PluginType.TOOL))
        assertTrue(types.contains(PluginType.GAME))
        assertTrue(types.contains(PluginType.MEMORY))
        assertTrue(types.contains(PluginType.CUSTOM))
    }

    @Test
    @DisplayName("PluginType — 分类值")
    fun testPluginType_categories() {
        assertEquals("tool", PluginType.TOOL.category)
        assertEquals("game", PluginType.GAME.category)
        assertEquals("memory", PluginType.MEMORY.category)
        assertEquals("custom", PluginType.CUSTOM.category)
    }

    // =========================================================================
    // IPlugin 接口测试
    // =========================================================================

    @Test
    @DisplayName("IPlugin — 接口方法调用")
    fun testPlugin_interface_calls() {
        val info = PluginInfo(
            id = "mock_plugin",
            name = "模拟插件",
            description = "测试用",
            version = "1.0.0",
            author = "测试",
            type = PluginType.TOOL
        )

        every { mockPlugin.getPluginInfo() } returns info

        assertEquals(info, mockPlugin.getPluginInfo())
        assertEquals("mock_plugin", mockPlugin.getPluginInfo().id)
    }

    @Test
    @DisplayName("BuiltinPlugins — 内置插件验证")
    fun testBuiltinPlugins_info() {
        val memoryStats = MemoryStatsPlugin()
        val info = memoryStats.getPluginInfo()

        assertEquals("memory_stats", info.id)
        assertEquals("记忆统计", info.name)
        assertEquals(PluginType.MEMORY, info.type)
        assertTrue(info.isBuiltIn)
        assertTrue(info.isEnabled)
        assertTrue(info.permissionRequired.contains("memory_read"))
    }

    @Test
    @DisplayName("BuiltinPlugins — DailyGreeting")
    fun testBuiltinPlugins_dailyGreeting() {
        val plugin = DailyGreetingPlugin()
        val info = plugin.getPluginInfo()

        assertEquals("daily_greeting", info.id)
        assertEquals(PluginType.TOOL, info.type)
        assertTrue(info.isBuiltIn)
    }

    @Test
    @DisplayName("BuiltinPlugins — ConversationSummary")
    fun testBuiltinPlugins_conversationSummary() {
        val plugin = ConversationSummaryPlugin()
        val info = plugin.getPluginInfo()

        assertEquals("conversation_summary", info.id)
        assertTrue(info.permissionRequired.contains("chat_read"))
        assertTrue(info.isBuiltIn)
    }

    // =========================================================================
    // PluginRegistry 测试（使用 mock Context）
    // =========================================================================

    @Test
    @DisplayName("PluginRegistry — 单例非空")
    fun testPluginRegistry_singleton() {
        assertNotNull(PluginRegistry)
    }

    @Test
    @DisplayName("PluginRegistry — 注册插件")
    fun testPluginRegistry_registerPlugin() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info = PluginInfo(
            id = "test_register",
            name = "注册测试",
            description = "测试注册",
            version = "1.0.0",
            author = "测试",
            type = PluginType.TOOL
        )
        every { mockPlugin.getPluginInfo() } returns info

        // 模拟 SharedPreferences
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        val result = PluginRegistry.registerPlugin(context, mockPlugin)
        assertTrue(result)

        // 验证 onInstall 被调用
        verify(exactly = 1) { mockPlugin.onInstall(context) }
        // 验证 onEnable 被调用（因为 isEnabled=true）
        verify(exactly = 1) { mockPlugin.onEnable(context) }
    }

    @Test
    @DisplayName("PluginRegistry — 重复注册应失败")
    fun testPluginRegistry_duplicateRegistration() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info = PluginInfo(
            id = "test_dup",
            name = "重复测试",
            description = "测试",
            version = "1.0.0",
            author = "测试",
            type = PluginType.TOOL
        )
        every { mockPlugin.getPluginInfo() } returns info
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        val first = PluginRegistry.registerPlugin(context, mockPlugin)
        assertTrue(first)

        val second = PluginRegistry.registerPlugin(context, mockPlugin)
        assertFalse(second)
    }

    @Test
    @DisplayName("PluginRegistry — 注销插件")
    fun testPluginRegistry_unregisterPlugin() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info = PluginInfo(
            id = "test_unregister",
            name = "注销测试",
            description = "测试",
            version = "1.0.0",
            author = "测试",
            type = PluginType.TOOL,
            isBuiltIn = false
        )
        every { mockPlugin.getPluginInfo() } returns info
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        PluginRegistry.registerPlugin(context, mockPlugin)
        assertEquals(1, PluginRegistry.getPluginCount())

        val result = PluginRegistry.unregisterPlugin(context, "test_unregister")
        assertTrue(result)
        assertEquals(0, PluginRegistry.getPluginCount())

        // 验证回调
        verify(exactly = 1) { mockPlugin.onDisable(context) }
        verify(exactly = 1) { mockPlugin.onUninstall(context) }
    }

    @Test
    @DisplayName("PluginRegistry — 内置插件不可注销")
    fun testPluginRegistry_builtinCannotUnregister() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info = PluginInfo(
            id = "test_builtin",
            name = "内置测试",
            description = "测试",
            version = "1.0.0",
            author = "测试",
            type = PluginType.TOOL,
            isBuiltIn = true
        )
        every { mockPlugin.getPluginInfo() } returns info
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        PluginRegistry.registerPlugin(context, mockPlugin)

        val result = PluginRegistry.unregisterPlugin(context, "test_builtin")
        assertFalse(result)
    }

    @Test
    @DisplayName("PluginRegistry — 注销不存在的插件")
    fun testPluginRegistry_unregisterNonexistent() {
        val context = mockk<android.content.Context>(relaxed = true)
        mockSharedPreferences(context)
        PluginRegistry.init(context)

        val result = PluginRegistry.unregisterPlugin(context, "nonexistent")
        assertFalse(result)
    }

    @Test
    @DisplayName("PluginRegistry — 启用/禁用插件")
    fun testPluginRegistry_enableDisable() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info = PluginInfo(
            id = "test_toggle",
            name = "切换测试",
            description = "测试",
            version = "1.0.0",
            author = "测试",
            type = PluginType.TOOL
        )
        every { mockPlugin.getPluginInfo() } returns info
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        PluginRegistry.registerPlugin(context, mockPlugin)

        // 禁用
        val disabled = PluginRegistry.disablePlugin(context, "test_toggle")
        assertTrue(disabled)
        verify(exactly = 1) { mockPlugin.onDisable(context) }

        // 再次禁用应返回 true（幂等）
        val disabledAgain = PluginRegistry.disablePlugin(context, "test_toggle")
        assertTrue(disabledAgain)

        // 启用
        val enabled = PluginRegistry.enablePlugin(context, "test_toggle")
        assertTrue(enabled)
        verify(exactly = 2) { mockPlugin.onEnable(context) } // 注册时1次 + 启用1次
    }

    @Test
    @DisplayName("PluginRegistry — 启用不存在的插件")
    fun testPluginRegistry_enableNonexistent() {
        val context = mockk<android.content.Context>(relaxed = true)
        mockSharedPreferences(context)
        PluginRegistry.init(context)

        val result = PluginRegistry.enablePlugin(context, "nonexistent")
        assertFalse(result)
    }

    @Test
    @DisplayName("PluginRegistry — 获取所有插件")
    fun testPluginRegistry_getAllPlugins() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info1 = PluginInfo(
            id = "p1", name = "插件1", description = "", version = "1.0.0",
            author = "", type = PluginType.TOOL
        )
        val info2 = PluginInfo(
            id = "p2", name = "插件2", description = "", version = "1.0.0",
            author = "", type = PluginType.GAME
        )
        every { mockPlugin.getPluginInfo() } returns info1
        every { mockPlugin2.getPluginInfo() } returns info2
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        PluginRegistry.registerPlugin(context, mockPlugin)
        PluginRegistry.registerPlugin(context, mockPlugin2)

        val all = PluginRegistry.getAllPlugins()
        assertEquals(2, all.size)
    }

    @Test
    @DisplayName("PluginRegistry — 获取已启用插件")
    fun testPluginRegistry_getEnabledPlugins() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info1 = PluginInfo(
            id = "p1", name = "插件1", description = "", version = "1.0.0",
            author = "", type = PluginType.TOOL
        )
        val info2 = PluginInfo(
            id = "p2", name = "插件2", description = "", version = "1.0.0",
            author = "", type = PluginType.GAME
        )
        every { mockPlugin.getPluginInfo() } returns info1
        every { mockPlugin2.getPluginInfo() } returns info2
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        PluginRegistry.registerPlugin(context, mockPlugin)
        PluginRegistry.registerPlugin(context, mockPlugin2)
        PluginRegistry.disablePlugin(context, "p2")

        val enabled = PluginRegistry.getEnabledPlugins()
        assertEquals(1, enabled.size)
        assertEquals("p1", enabled[0].id)
    }

    @Test
    @DisplayName("PluginRegistry — 获取插件实例")
    fun testPluginRegistry_getPlugin() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info = PluginInfo(
            id = "test_get", name = "获取测试", description = "", version = "1.0.0",
            author = "", type = PluginType.TOOL
        )
        every { mockPlugin.getPluginInfo() } returns info
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        PluginRegistry.registerPlugin(context, mockPlugin)

        val retrieved = PluginRegistry.getPlugin("test_get")
        assertNotNull(retrieved)
        assertEquals(info, retrieved?.getPluginInfo())
    }

    @Test
    @DisplayName("PluginRegistry — 获取不存在的插件")
    fun testPluginRegistry_getNonexistent() {
        val context = mockk<android.content.Context>(relaxed = true)
        mockSharedPreferences(context)
        PluginRegistry.init(context)

        val result = PluginRegistry.getPlugin("nonexistent")
        assertNull(result)
    }

    @Test
    @DisplayName("PluginRegistry — getPluginCount 和 getEnabledPluginCount")
    fun testPluginRegistry_counts() {
        val context = mockk<android.content.Context>(relaxed = true)
        val info = PluginInfo(
            id = "count_test", name = "计数测试", description = "", version = "1.0.0",
            author = "", type = PluginType.TOOL
        )
        every { mockPlugin.getPluginInfo() } returns info
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        PluginRegistry.registerPlugin(context, mockPlugin)

        assertEquals(1, PluginRegistry.getPluginCount())
        assertEquals(1, PluginRegistry.getEnabledPluginCount())

        PluginRegistry.disablePlugin(context, "count_test")
        assertEquals(1, PluginRegistry.getPluginCount())
        assertEquals(0, PluginRegistry.getEnabledPluginCount())
    }

    @Test
    @DisplayName("PluginRegistry — 重复初始化")
    fun testPluginRegistry_repeatInit() {
        val context = mockk<android.content.Context>(relaxed = true)
        mockSharedPreferences(context)

        PluginRegistry.init(context)
        PluginRegistry.init(context) // 不应崩溃
        // 验证没有异常
        assertTrue(true)
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    /**
     * 模拟 SharedPreferences 用于 PluginRegistry 的持久化。
     */
    private fun mockSharedPreferences(context: android.content.Context) {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } returns null
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit
    }
}
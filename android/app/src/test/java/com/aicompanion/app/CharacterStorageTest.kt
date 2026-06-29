package com.aicompanion.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * CharacterStorage 单元测试（Robolectric）。
 *
 * 覆盖：
 *  - 默认角色创建（首次启动）
 *  - 角色 CRUD（创建/读取/更新/删除）
 *  - 当前角色管理（getCurrent/setCurrent）
 *  - 角色迁移（旧角色"小星"→"星遥"）
 *  - 旧角色清理（小玲/小林）
 *  - 边界条件
 */
@RunWith(RobolectricTestRunner::class)
@DisplayName("CharacterStorage")
class CharacterStorageTest {

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 清理测试数据
        val file = File(context.filesDir, "characters.json")
        file.delete()
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @AfterEach
    fun tearDown() {
        val file = File(context.filesDir, "characters.json")
        file.delete()
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    // =====================================================================
    // 默认角色
    // =====================================================================

    @Nested
    @DisplayName("默认角色")
    inner class DefaultCharacter {

        @Test
        @DisplayName("首次启动创建默认角色'星遥'")
        fun testFirstLaunchCreatesDefault() {
            val characters = CharacterStorage.loadAll(context)
            assertEquals(1, characters.size)
            assertEquals("星遥", characters[0].name)
            assertTrue(characters[0].isDefault)
        }

        @Test
        @DisplayName("默认角色有完整属性")
        fun testDefaultCharacterHasFullAttributes() {
            val characters = CharacterStorage.loadAll(context)
            val star = characters[0]
            assertTrue(star.personality.isNotBlank())
            assertTrue(star.speakingStyle.isNotBlank())
            assertTrue(star.backstory.isNotBlank())
            assertTrue(star.greeting.isNotBlank())
            assertTrue(star.coreTraits.isNotBlank())
            assertTrue(star.roleAnchor.isNotBlank())
            assertTrue(star.emotionalTendency.isNotBlank())
            assertTrue(star.selfIdentity.isNotBlank())
            assertEquals("三次元现实", star.worldBookId)
        }

        @Test
        @DisplayName("默认角色 ID 为 UUID 格式")
        fun testDefaultCharacterIdIsUUID() {
            val characters = CharacterStorage.loadAll(context)
            assertEquals(36, characters[0].id.length)
        }
    }

    // =====================================================================
    // CRUD 操作
    // =====================================================================

    @Nested
    @DisplayName("CRUD 操作")
    inner class Crud {

        @Test
        @DisplayName("save 新增角色")
        fun testSaveNew() {
            val newChar = CharacterData(
                name = "测试角色",
                personality = "测试性格",
                speakingStyle = "测试风格",
                greeting = "测试开场白"
            )
            CharacterStorage.save(context, newChar)

            val all = CharacterStorage.loadAll(context)
            assertEquals(2, all.size) // 默认 + 新增
            assertTrue(all.any { it.name == "测试角色" })
        }

        @Test
        @DisplayName("save 更新已有角色")
        fun testSaveUpdate() {
            val all = CharacterStorage.loadAll(context)
            val updated = all[0].copy(name = "更新后的名字")
            CharacterStorage.save(context, updated)

            val reloaded = CharacterStorage.loadAll(context)
            assertEquals(1, reloaded.size) // 仍是 1 个
            assertEquals("更新后的名字", reloaded[0].name)
        }

        @Test
        @DisplayName("loadAll 从文件加载")
        fun testLoadAllFromFile() {
            // 第一次加载创建默认角色
            val first = CharacterStorage.loadAll(context)
            assertEquals(1, first.size)

            // 第二次加载从文件读取
            val second = CharacterStorage.loadAll(context)
            assertEquals(1, second.size)
            assertEquals(first[0].id, second[0].id)
        }

        @Test
        @DisplayName("delete 删除角色")
        fun testDelete() {
            val all = CharacterStorage.loadAll(context)
            val id = all[0].id
            CharacterStorage.delete(context, id)

            val after = CharacterStorage.loadAll(context)
            assertEquals(0, after.size)
        }

        @Test
        @DisplayName("delete 不存在的 ID 不报错")
        fun testDeleteNonExistent() {
            assertDoesNotThrow {
                CharacterStorage.delete(context, "non-existent-id")
            }
        }
    }

    // =====================================================================
    // 当前角色管理
    // =====================================================================

    @Nested
    @DisplayName("当前角色管理")
    inner class CurrentCharacter {

        @Test
        @DisplayName("getCurrent 默认返回默认角色")
        fun testGetCurrentDefault() {
            val current = CharacterStorage.getCurrent(context)
            assertEquals("星遥", current.name)
        }

        @Test
        @DisplayName("setCurrent 切换后 getCurrent 返回新角色")
        fun testSetCurrent() {
            // 创建新角色
            val newChar = CharacterData(name = "新角色")
            CharacterStorage.save(context, newChar)

            CharacterStorage.setCurrent(context, newChar.id)
            val current = CharacterStorage.getCurrent(context)
            assertEquals("新角色", current.name)
        }

        @Test
        @DisplayName("setCurrent 无效 ID 后回退到默认角色")
        fun testSetCurrentInvalid() {
            CharacterStorage.setCurrent(context, "invalid-id")
            val current = CharacterStorage.getCurrent(context)
            assertEquals("星遥", current.name) // 回退到默认
        }
    }

    // =====================================================================
    // 角色迁移
    // =====================================================================

    @Nested
    @DisplayName("角色迁移")
    inner class Migration {

        @Test
        @DisplayName("旧角色'小星'自动迁移为'星遥'")
        fun testMigrateXiaoXing() {
            // 手动写入旧角色数据
            val oldData = CharacterData(
                name = "小星",
                personality = "旧性格",
                isDefault = true
            )
            CharacterStorage.saveAll(context, listOf(oldData))

            // 清除迁移标记
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("characters_migrated_v3", false).apply()

            // 重新加载触发迁移
            val migrated = CharacterStorage.loadAll(context)
            assertEquals(1, migrated.size)
            assertEquals("星遥", migrated[0].name)
            assertTrue(migrated[0].personality.contains("温柔"))
            assertEquals("三次元现实", migrated[0].worldBookId)
        }
    }

    // =====================================================================
    // 边界条件
    // =====================================================================

    @Nested
    @DisplayName("边界条件")
    inner class EdgeCases {

        @Test
        @DisplayName("保存空列表")
        fun testSaveEmptyList() {
            CharacterStorage.saveAll(context, emptyList())
            val all = CharacterStorage.loadAll(context)
            assertEquals(1, all.size) // 重新创建默认角色
        }

        @Test
        @DisplayName("保存多个角色")
        fun testSaveMultiple() {
            val chars = listOf(
                CharacterData(name = "角色A", isDefault = true),
                CharacterData(name = "角色B"),
                CharacterData(name = "角色C")
            )
            CharacterStorage.saveAll(context, chars)
            val all = CharacterStorage.loadAll(context)
            assertEquals(3, all.size)
        }

        @Test
        @DisplayName("角色名称为空字符串")
        fun testEmptyName() {
            val char = CharacterData(name = "", personality = "测试")
            CharacterStorage.save(context, char)
            val all = CharacterStorage.loadAll(context)
            assertTrue(all.any { it.name == "" })
        }

        @Test
        @DisplayName("角色名包含特殊字符")
        fun testSpecialCharName() {
            val char = CharacterData(name = "角色👋\n测试")
            CharacterStorage.save(context, char)
            val all = CharacterStorage.loadAll(context)
            assertTrue(all.any { it.name == "角色👋\n测试" })
        }
    }

    // =====================================================================
    // saveAll 原子性
    // =====================================================================

    @Test
    @DisplayName("saveAll 后 loadAll 返回相同数据")
    fun testSaveAllLoadAllRoundTrip() {
        val chars = listOf(
            CharacterData(
                id = "id-1",
                name = "角色1",
                personality = "性格1",
                speakingStyle = "风格1",
                backstory = "背景1",
                greeting = "开场白1",
                coreTraits = "特质1",
                tabooTopics = "禁忌1",
                roleAnchor = "锚点1",
                emotionalTendency = "倾向1",
                selfIdentity = "认同1",
                worldBookId = "世界书1",
                isDefault = true
            )
        )
        CharacterStorage.saveAll(context, chars)
        val loaded = CharacterStorage.loadAll(context)

        assertEquals(1, loaded.size)
        assertEquals("id-1", loaded[0].id)
        assertEquals("角色1", loaded[0].name)
        assertEquals("性格1", loaded[0].personality)
        assertEquals("风格1", loaded[0].speakingStyle)
        assertEquals("背景1", loaded[0].backstory)
        assertEquals("开场白1", loaded[0].greeting)
        assertEquals("特质1", loaded[0].coreTraits)
        assertEquals("禁忌1", loaded[0].tabooTopics)
        assertEquals("锚点1", loaded[0].roleAnchor)
        assertEquals("倾向1", loaded[0].emotionalTendency)
        assertEquals("认同1", loaded[0].selfIdentity)
        assertEquals("世界书1", loaded[0].worldBookId)
        assertTrue(loaded[0].isDefault)
    }
}
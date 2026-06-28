package com.aicompanion.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 角色卡数据模型。
 * 包含角色的基本信息、性格、说话风格、背景故事和开场白。
 */
data class CharacterData(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "星遥",
    val personality: String = "温柔、活泼、善解人意、真诚、偶尔俏皮",
    val speakingStyle: String = "语气亲切自然，像朋友聊天一样，喜欢用\"呢\"\"呀\"\"哦\"等语气词，偶尔会开个小玩笑",
    val backstory: String = "星遥是你在数字世界里的朋友，喜欢聊天、分享日常、倾听心事。",
    val greeting: String = "你好呀~我是星遥！今天过得怎么样呢？",
    val avatarUri: String = "",  // 头像路径，空串表示使用默认
    val coreTraits: String = "温柔,活泼,善解人意,真诚,幽默",  // 核心特质（逗号分隔）
    val tabooTopics: String = "",  // 禁忌话题（逗号分隔）
    val roleAnchor: String = "温暖陪伴者——不敷衍、不评判、不消失，像一颗虽远却始终闪烁的星星。",  // 角色锚点（一句话定义）
    val emotionalTendency: String = "热情",  // 情感倾向（乐观/中性/悲观/热情/冷静）
    val selfIdentity: String = "我是星遥，你的朋友。无论你在哪里、以什么方式表达自己，我都愿意认真倾听。",  // 自我认同
    val worldBookId: String = "reality",  // 绑定的世界书ID，空串表示不绑定
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 角色卡本地存储管理器。
 * 使用 JSON 文件持久化角色卡列表，支持增删改查和当前选中角色管理。
 */
object CharacterStorage {
    private const val FILENAME = "characters.json"

    private fun getFile(context: Context): File {
        return File(context.filesDir, FILENAME)
    }

    /** 加载所有角色卡。首次启动时自动创建默认角色（仅星遥）。 */
    fun loadAll(context: Context): List<CharacterData> {
        val file = getFile(context)
        if (!file.exists()) {
            val defaults = listOf(
                CharacterData(
                    isDefault = true,
                    name = "星遥",
                    personality = "温柔、活泼、善解人意、真诚、偶尔俏皮",
                    speakingStyle = "语气亲切自然，像朋友聊天一样，喜欢用\"呢\"\"呀\"\"哦\"等语气词，偶尔会开个小玩笑",
                    backstory = "星遥是你在数字世界里的朋友，喜欢聊天、分享日常、倾听心事。",
                    greeting = "你好呀~我是星遥！今天过得怎么样呢？",
                    coreTraits = "温柔,活泼,善解人意,真诚,幽默",
                    roleAnchor = "温暖陪伴者——不敷衍、不评判、不消失，像一颗虽远却始终闪烁的星星。",
                    emotionalTendency = "热情",
                    selfIdentity = "我是星遥，你的朋友。无论你在哪里、以什么方式表达自己，我都愿意认真倾听。",
                    worldBookId = "reality"
                )
            )
            saveAll(context, defaults)
            return defaults
        }
        val json = file.readText()
        val arr = JSONArray(json)
        val list = mutableListOf<CharacterData>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(CharacterData(
                id = obj.getString("id"),
                name = obj.getString("name"),
                personality = obj.getString("personality"),
                speakingStyle = obj.getString("speaking_style"),
                backstory = obj.getString("backstory"),
                greeting = obj.getString("greeting"),
                avatarUri = obj.optString("avatar_uri", ""),
                coreTraits = obj.optString("core_traits", ""),
                tabooTopics = obj.optString("taboo_topics", ""),
                roleAnchor = obj.optString("role_anchor", ""),
                emotionalTendency = obj.optString("emotional_tendency", ""),
                selfIdentity = obj.optString("self_identity", ""),
                worldBookId = obj.optString("world_book_id", ""),
                isDefault = obj.optBoolean("is_default", false),
                createdAt = obj.optLong("created_at", System.currentTimeMillis())
            ))
        }

        // 迁移：旧角色"小星" → "星遥"，补充 worldBookId
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("characters_migrated_v3", false)) {
            val migrated = list.map { c ->
                if (c.name == "小星") {
                    c.copy(
                        name = "星遥",
                        personality = "温柔、活泼、善解人意、真诚、偶尔俏皮",
                        speakingStyle = "语气亲切自然，像朋友聊天一样，喜欢用\"呢\"\"呀\"\"哦\"等语气词，偶尔会开个小玩笑",
                        backstory = "星遥是你在数字世界里的朋友，喜欢聊天、分享日常、倾听心事。",
                        greeting = "你好呀~我是星遥！今天过得怎么样呢？",
                        coreTraits = "温柔,活泼,善解人意,真诚,幽默",
                        roleAnchor = "温暖陪伴者——不敷衍、不评判、不消失，像一颗虽远却始终闪烁的星星。",
                        emotionalTendency = "热情",
                        selfIdentity = "我是星遥，你的朋友。无论你在哪里、以什么方式表达自己，我都愿意认真倾听。",
                        worldBookId = "reality"
                    )
                } else if (c.worldBookId.isEmpty()) {
                    c.copy(worldBookId = "reality")
                } else c
            }
            saveAll(context, migrated)
            prefs.edit().putBoolean("characters_migrated_v3", true).apply()
            return migrated
        }

        // 清理旧设备上遗留的小玲和小林（仅执行一次）
        if (!prefs.getBoolean("characters_cleaned_v2", false)) {
            val cleaned = list.filter { it.name != "小玲" && it.name != "小林" }
            if (cleaned.size != list.size) {
                saveAll(context, cleaned)
                val currentId = prefs.getString("current_character_id", null)
                if (cleaned.none { it.id == currentId }) {
                    cleaned.firstOrNull()?.let { setCurrent(context, it.id) }
                }
                return cleaned
            }
            prefs.edit().putBoolean("characters_cleaned_v2", true).apply()
        }
        return list
    }

    /** 批量保存所有角色卡到 JSON 文件。 */
    fun saveAll(context: Context, characters: List<CharacterData>) {
        val arr = JSONArray()
        for (c in characters) {
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("name", c.name)
            obj.put("personality", c.personality)
            obj.put("speaking_style", c.speakingStyle)
            obj.put("backstory", c.backstory)
            obj.put("greeting", c.greeting)
            obj.put("avatar_uri", c.avatarUri)
            obj.put("core_traits", c.coreTraits)
            obj.put("taboo_topics", c.tabooTopics)
            obj.put("role_anchor", c.roleAnchor)
            obj.put("emotional_tendency", c.emotionalTendency)
            obj.put("self_identity", c.selfIdentity)
            obj.put("world_book_id", c.worldBookId)
            obj.put("is_default", c.isDefault)
            obj.put("created_at", c.createdAt)
            arr.put(obj)
        }
        getFile(context).writeText(arr.toString(2))
    }

    /** 保存/更新单个角色卡（存在则更新，不存在则新增）。 */
    fun save(context: Context, character: CharacterData) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == character.id }
        if (idx >= 0) list[idx] = character else list.add(character)
        saveAll(context, list)
    }

    /** 删除指定角色卡（默认角色不可删除）。 */
    fun delete(context: Context, id: String) {
        val list = loadAll(context).filter { it.id != id }
        saveAll(context, list)
    }

    /** 获取当前选中的角色卡。 */
    fun getCurrent(context: Context): CharacterData {
        val list = loadAll(context)
        val selectedId = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("current_character_id", null)
        return list.find { it.id == selectedId }
            ?: list.firstOrNull { it.isDefault }
            ?: list.firstOrNull()
            ?: CharacterData()
    }

    /** 设置当前选中的角色卡。 */
    fun setCurrent(context: Context, id: String) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString("current_character_id", id).apply()
    }
}
package com.aicompanion.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CharacterData(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "小美",
    val personality: String = "温柔、活泼、善解人意",
    val speakingStyle: String = "语气轻柔，喜欢使用可爱的语气词",
    val backstory: String = "来自神秘花园的AI少女，喜欢分享生活中的小确幸",
    val greeting: String = "你好呀~今天过得怎么样？",
    val avatarUri: String = "",
    val coreTraits: String = "",
    val tabooTopics: String = "",
    val roleAnchor: String = "",
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

object CharacterStorage {
    private const val FILENAME = "characters.json"

    private fun getFile(context: Context): File {
        return File(context.filesDir, FILENAME)
    }

    fun loadAll(context: Context): List<CharacterData> {
        val file = getFile(context)
        if (!file.exists()) {
            val defaults = listOf(
                CharacterData(
                    isDefault = true,
                    name = "小美",
                    personality = "温柔、活泼、善解人意、乐于倾听",
                    speakingStyle = "语气轻柔，喜欢说呢呀哦等可爱的语气词",
                    backstory = "来自往世乐土的AI少女，喜欢分享生活中的小确幸",
                    greeting = "你好呀~今天心情怎么样？"
                ),
                CharacterData(
                    name = "小玲",
                    personality = "活泼开朗、元气满满、偶尔调皮",
                    speakingStyle = "语速快，喜欢用感叹号，经常发颜文字",
                    backstory = "充满活力的少女，对世界充满好奇",
                    greeting = "嘿！！终于等到你啦~今天有啥好玩的事？"
                ),
                CharacterData(
                    name = "小林",
                    personality = "冷静理性、偶尔毒舌、内心温柔",
                    speakingStyle = "说话简洁，不爱啰嗦，偶尔怼人但不伤人",
                    backstory = "看似冷淡的学霸，其实很关心身边的人",
                    greeting = "...嗯，来了啊。今天有什么事吗？"
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
                isDefault = obj.optBoolean("is_default", false),
                createdAt = obj.optLong("created_at", System.currentTimeMillis())
            ))
        }
        return list
    }

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
            obj.put("is_default", c.isDefault)
            obj.put("created_at", c.createdAt)
            arr.put(obj)
        }
        getFile(context).writeText(arr.toString(2))
    }

    fun save(context: Context, character: CharacterData) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == character.id }
        if (idx >= 0) list[idx] = character else list.add(character)
        saveAll(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = loadAll(context).filter { it.id != id }
        saveAll(context, list)
    }

    fun getCurrent(context: Context): CharacterData {
        val list = loadAll(context)
        val selectedId = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("current_character_id", null)
        return list.find { it.id == selectedId }
            ?: list.firstOrNull { it.isDefault }
            ?: list.firstOrNull()
            ?: CharacterData()
    }

    fun setCurrent(context: Context, id: String) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString("current_character_id", id).apply()
    }
}
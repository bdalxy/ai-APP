package com.aicompanion.app.module.worldbook

import android.util.Log
import com.aicompanion.app.module.ModuleEventBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书模块实现。
 *
 * 桥接 Python chat_bridge._world_book 模块，提供世界书条目的增删改查。
 * 接口中的 [WorldBookEntry.key] 映射到 Python 端的条目 ID。
 *
 * 注意：世界书数据存储在 Python 端（data/world_books/ 目录），
 * Kotlin 端此模块作为薄封装层，所有操作委托给 Python 引擎。
 *
 * 默认世界书名称为 "_common_sense"，与 WorldBookActivity 保持一致。
 */
class WorldBookModuleImpl : WorldBookModule {

    companion object {
        private const val TAG = "WorldBookModule"
        /** 默认世界书名称，与 WorldBookActivity 保持一致 */
        const val DEFAULT_BOOK_NAME = "_common_sense"
    }

    // ======================== Python 模块获取 ========================

    /**
     * 获取 Python chat_bridge 模块引用。
     * 每次调用时动态获取，避免持有过期引用。
     *
     * @return PyObject 或 null（Python 未就绪时）
     */
    private fun getPythonModule(): com.chaquo.python.PyObject? {
        return try {
            com.chaquo.python.Python.getInstance().getModule("chat_bridge")
        } catch (e: Exception) {
            Log.w(TAG, "Python chat_bridge 模块未就绪: ${e.message}")
            null
        }
    }

    // ======================== WorldBookEntry 转换 ========================

    /**
     * 将 Python 端返回的条目 JSON 转换为接口 [WorldBookEntry]。
     * Python 端字段: id, keys, content, comment, priority, probability, constant
     */
    private fun jsonToEntry(json: JSONObject): WorldBookEntry {
        // keys 数组取第一个作为 tags，comment 作为辅助标签
        val keysArray = json.optJSONArray("keys")
        val tags = mutableListOf<String>()
        if (keysArray != null) {
            for (i in 0 until keysArray.length()) {
                tags.add(keysArray.getString(i))
            }
        }
        val comment = json.optString("comment", "")
        if (comment.isNotBlank()) {
            tags.add(comment)
        }

        return WorldBookEntry(
            key = json.optString("id", ""),
            content = json.optString("content", ""),
            tags = tags,
            priority = json.optInt("priority", 0)
        )
    }

    /**
     * 将接口 [WorldBookEntry] 转换为 Python 端条目 JSON。
     * key 映射到 Python 的 id，tags 的第一个元素映射到 comment。
     */
    private fun entryToJson(entry: WorldBookEntry): JSONObject {
        val keysArray = JSONArray()
        // tags 中非空元素放入 keys
        for (tag in entry.tags) {
            if (tag.isNotBlank()) {
                keysArray.put(tag)
            }
        }

        return JSONObject().apply {
            put("id", entry.key)
            put("content", entry.content)
            put("keys", keysArray)
            put("comment", if (entry.tags.isNotEmpty()) entry.tags.first() else "")
            put("constant", false)
            put("probability", 100)
            put("priority", entry.priority)
        }
    }

    // ======================== WorldBookModule 接口实现 ========================

    override fun getEntry(key: String): WorldBookEntry? {
        return try {
            val module = getPythonModule() ?: return null
            val result = module.callAttr("get_world_book", DEFAULT_BOOK_NAME).toString()
            val json = JSONObject(result)

            if (json.optString("status") != "ok") return null

            val entries = json.optJSONObject("book")?.optJSONArray("entries") ?: return null
            for (i in 0 until entries.length()) {
                val entryJson = entries.getJSONObject(i)
                if (entryJson.optString("id") == key) {
                    return jsonToEntry(entryJson)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取条目失败: key=$key", e)
            null
        }
    }

    override fun setEntry(entry: WorldBookEntry) {
        try {
            val module = getPythonModule() ?: return
            val entryJson = entryToJson(entry).toString()

            // 检查条目是否已存在，存在则更新，不存在则新增
            val existing = getEntry(entry.key)
            if (existing != null) {
                module.callAttr("update_world_book_entry", DEFAULT_BOOK_NAME, entry.key, entryJson)
                Log.d(TAG, "条目已更新: key=${entry.key}")
            } else {
                module.callAttr("add_world_book_entry", DEFAULT_BOOK_NAME, entryJson)
                Log.d(TAG, "条目已新增: key=${entry.key}")
            }

            // 发布世界书变更事件
            ModuleEventBus.emit(ModuleEventBus.EventType.WORLD_BOOK_CHANGED, entry.key)
        } catch (e: Exception) {
            Log.e(TAG, "设置条目失败: key=${entry.key}", e)
        }
    }

    override fun listEntries(): List<WorldBookEntry> {
        return try {
            val module = getPythonModule() ?: return emptyList()
            val result = module.callAttr("get_world_book", DEFAULT_BOOK_NAME).toString()
            val json = JSONObject(result)

            if (json.optString("status") != "ok") return emptyList()

            val entries = json.optJSONObject("book")?.optJSONArray("entries") ?: return emptyList()
            val list = mutableListOf<WorldBookEntry>()
            for (i in 0 until entries.length()) {
                list.add(jsonToEntry(entries.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "列出条目失败", e)
            emptyList()
        }
    }

    override fun deleteEntry(key: String): Boolean {
        return try {
            val module = getPythonModule() ?: return false
            module.callAttr("delete_world_book_entry", DEFAULT_BOOK_NAME, key)
            Log.d(TAG, "条目已删除: key=$key")
            // 发布世界书变更事件
            ModuleEventBus.emit(ModuleEventBus.EventType.WORLD_BOOK_CHANGED, key)
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除条目失败: key=$key", e)
            false
        }
    }

    override fun searchEntries(keyword: String): List<WorldBookEntry> {
        // 在已加载的条目中按内容搜索
        return try {
            listEntries().filter { entry ->
                entry.content.contains(keyword, ignoreCase = true) ||
                entry.tags.any { it.contains(keyword, ignoreCase = true) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "搜索条目失败: keyword=$keyword", e)
            emptyList()
        }
    }
}
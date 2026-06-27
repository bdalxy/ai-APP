package com.aicompanion.app.module.worldbook

data class WorldBookEntry(
    val key: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val priority: Int = 0
)

interface WorldBookModule {

    /** 获取指定键的条目 */
    fun getEntry(key: String): WorldBookEntry?

    /** 设置条目（新增或更新） */
    fun setEntry(entry: WorldBookEntry)

    /** 列出所有条目 */
    fun listEntries(): List<WorldBookEntry>

    /** 删除条目 */
    fun deleteEntry(key: String): Boolean

    /** 按关键词搜索条目 */
    fun searchEntries(keyword: String): List<WorldBookEntry>
}
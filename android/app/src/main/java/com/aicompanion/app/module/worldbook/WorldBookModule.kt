package com.aicompanion.app.module.worldbook

/**
 * 世界书条目类型别名，统一使用 [com.aicompanion.app.WorldBookEntry]。
 * 原 module 层定义的 WorldBookEntry(key, content, tags, priority) 已废弃，
 * 请使用 app.WorldBookEntry(id, category, content, tags, priority, createdAt, updatedAt)。
 *
 * 迁移说明：
 * - 原 key 字段 → 改用 id
 * - 原 category 字段由 UI 层维护（对应 Python 端 comment）
 */
typealias WorldBookEntry = com.aicompanion.app.WorldBookEntry

interface WorldBookModule {

    /** 获取指定键的条目（按 id 匹配） */
    fun getEntry(key: String): WorldBookEntry?

    /** 设置条目（新增或更新） */
    fun setEntry(entry: WorldBookEntry)

    /** 列出所有条目 */
    fun listEntries(): List<WorldBookEntry>

    /** 删除条目（按 id 匹配） */
    fun deleteEntry(key: String): Boolean

    /** 按关键词搜索条目 */
    fun searchEntries(keyword: String): List<WorldBookEntry>
}
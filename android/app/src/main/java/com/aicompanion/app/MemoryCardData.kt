package com.aicompanion.app

/**
 * 记忆档案馆卡片数据类。
 *
 * 用于瀑布流展示，每条记忆包含：
 * - 日期（格式化后的创建时间）
 * - 诗意摘要（从 content 提取的前 3 行，模拟 AI 诗意摘要）
 * - 情感标签列表（根据记忆类型和内容关键词推断）
 * - 原始数据引用（用于删除操作）
 *
 * @param rowid      SQLite 行 ID，用于删除操作。
 * @param id         记忆唯一标识。
 * @param type       记忆类型（episodic / semantic / user_fact）。
 * @param content    记忆内容原始文本。
 * @param createdAt  创建时间原始字符串（ISO 格式）。
 * @param importance 重要性评分（0.0 ~ 1.0）。
 * @param dateLabel  格式化后的日期标签（如 "6月15日"）。
 * @param summary    3 行诗意摘要。
 * @param emotionTags 情感标签列表。
 */
data class MemoryCardData(
    val rowid: Int,
    val id: String,
    val type: String,
    val content: String,
    val createdAt: String,
    val importance: Double,
    val dateLabel: String,
    val summary: String,
    val emotionTags: List<String>
) {
    companion object {
        /**
         * 从原始 MemoryItem（Python 返回的 JSON 解析结果）转换为 MemoryCardData。
         *
         * 转换逻辑：
         * - 日期：截取 MM-DD 部分，显示为 "X月X日"
         * - 摘要：取 content 前 60 个字符，按句子分割为最多 3 行
         * - 情感标签：根据记忆类型和内容关键词生成
         */
        fun fromMemoryItem(item: MemoryItem): MemoryCardData {
            val dateLabel = formatDateLabel(item.createdAt)
            val summary = extractSummary(item.content)
            val emotionTags = inferEmotionTags(item.type, item.content)

            return MemoryCardData(
                rowid = item.rowid,
                id = item.id,
                type = item.type,
                content = item.content,
                createdAt = item.createdAt,
                importance = item.importance,
                dateLabel = dateLabel,
                summary = summary,
                emotionTags = emotionTags
            )
        }

        /**
         * 格式化日期标签。
         * 输入：ISO 格式时间字符串（如 "2026-06-15T14:30:00"）
         * 输出：中文日期标签（如 "6月15日"）
         */
        private fun formatDateLabel(isoTime: String): String {
            return try {
                // 提取 MM-DD 部分
                if (isoTime.length >= 10) {
                    val parts = isoTime.substring(0, 10).split("-")
                    if (parts.size >= 3) {
                        val month = parts[1].toIntOrNull() ?: return isoTime.take(10)
                        val day = parts[2].toIntOrNull() ?: return isoTime.take(10)
                        "${month}月${day}日"
                    } else {
                        isoTime.take(10)
                    }
                } else {
                    isoTime
                }
            } catch (e: Exception) {
                isoTime.take(10)
            }
        }

        /**
         * 提取 3 行诗意摘要。
         * 策略：将 content 按中英文句号/换行分割，取前 3 个句子。
         * 如果不足 3 句，则按长度截断为 3 行。
         */
        private fun extractSummary(content: String): String {
            if (content.isBlank()) return "..."

            // 按句号、问号、感叹号、换行分割
            val sentences = content
                .split(Regex("""[。！？\n]+"""))
                .filter { it.trim().isNotEmpty() }
                .map { it.trim() }

            if (sentences.size >= 3) {
                return sentences.take(3).joinToString("\n")
            }

            // 按字符数均分 3 行
            val maxChars = 60
            val trimmed = if (content.length > maxChars) {
                content.take(maxChars) + "..."
            } else {
                content
            }

            val lineLen = (trimmed.length + 2) / 3
            val lines = mutableListOf<String>()
            var remaining = trimmed
            while (remaining.isNotEmpty() && lines.size < 3) {
                if (remaining.length <= lineLen) {
                    lines.add(remaining)
                    break
                }
                // 尝试在标点处断行
                val breakAt = findBreakPoint(remaining, lineLen)
                lines.add(remaining.substring(0, breakAt).trim())
                remaining = remaining.substring(breakAt).trim()
            }

            return lines.joinToString("\n")
        }

        /**
         * 在目标位置附近查找合适的断行点。
         */
        private fun findBreakPoint(text: String, target: Int): Int {
            if (target >= text.length) return text.length
            // 向后查找标点
            for (i in target downTo target / 2) {
                if (i < text.length && text[i] in "，,、；; 　") {
                    return i + 1
                }
            }
            return target
        }

        /**
         * 根据记忆类型和内容推断情感标签。
         */
        private fun inferEmotionTags(type: String, content: String): List<String> {
            val tags = mutableListOf<String>()

            // 根据类型添加基础标签
            when (type) {
                "episodic" -> {
                    tags.add("回忆")
                    // 内容情感分析
                    if (containsAny(content, listOf("开心", "快乐", "高兴", "笑", "幸福", "美好"))) {
                        tags.add("快乐")
                    } else if (containsAny(content, listOf("难过", "悲伤", "伤心", "哭", "遗憾"))) {
                        tags.add("感伤")
                    } else if (containsAny(content, listOf("感动", "温暖", "温柔"))) {
                        tags.add("温暖")
                    } else {
                        tags.add("经历")
                    }
                }
                "semantic" -> {
                    tags.add("认知")
                    if (containsAny(content, listOf("喜欢", "爱", "爱好"))) {
                        tags.add("偏好")
                    } else {
                        tags.add("理解")
                    }
                }
                "user_fact" -> {
                    tags.add("关于你")
                    if (containsAny(content, listOf("喜欢", "爱", "爱好"))) {
                        tags.add("喜好")
                    } else if (containsAny(content, listOf("工作", "职业", "学习"))) {
                        tags.add("身份")
                    } else {
                        tags.add("事实")
                    }
                }
                else -> {
                    tags.add("记忆")
                }
            }

            return tags.distinct().take(3) // 最多 3 个标签
        }

        /**
         * 检查文本是否包含任意关键词。
         */
        private fun containsAny(text: String, keywords: List<String>): Boolean {
            return keywords.any { text.contains(it) }
        }
    }
}
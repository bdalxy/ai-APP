"""记忆统计插件 — 查看记忆系统统计信息"""

import re
from datetime import datetime, timedelta, timezone
from typing import Optional

from .plugin_base import BasePlugin


class MemoryStatsPlugin(BasePlugin):
    """记忆统计插件。使用 /memstats 触发。"""

    name = "memory_stats"
    version = "1.0.0"
    description = "记忆统计 — 查看记忆系统的统计信息"
    category = "script"
    icon = "chart"

    def pre_process(self, user_input: str) -> Optional[str]:
        if not re.match(r"^/memstats\s*$", user_input.strip()):
            return None
        try:
            from chat_bridge._state import _ctx
            o = _ctx.orchestrator
            if o is None:
                return "[记忆统计] 记忆系统未初始化，请先开始对话。"
            stats = o.get_stats()
            total = stats.get("total", 0)
            active = stats.get("active", 0)
            by_type = stats.get("by_type", {})
            all_mem = o.vector_store.get_all()
            high = sum(1 for m in all_mem if m.importance >= 0.7)
            med = sum(1 for m in all_mem if 0.3 <= m.importance < 0.7)
            low = sum(1 for m in all_mem if m.importance < 0.3)
            now = datetime.now(timezone.utc)
            cutoff = now - timedelta(days=7)
            recent = 0
            for m in all_mem:
                try:
                    created = datetime.fromisoformat(m.created_at)
                    if created.tzinfo is None:
                        created = created.replace(tzinfo=timezone.utc)
                    if created >= cutoff:
                        recent += 1
                except (ValueError, TypeError):
                    pass
            names = {
                "episodic": "情景记忆", "semantic": "语义记忆",
                "user_fact": "用户事实", "emotional": "情绪状态",
                "summary": "摘要记忆",
            }
            lines = [
                "📊 记忆统计报告", "━━━━━━━━━━━━━━━━━",
                f"总记忆: {total}（活跃: {active}，归档: {total - active}）",
                f"对话轮次: {stats.get('turn_count', 0)}", "",
                "📂 按分类:",
            ]
            for k, v in names.items():
                lines.append(f"  {v}: {by_type.get(k, 0)}")
            lines += [
                "",
                f"⭐ 重要性: 高({high}) 中({med}) 低({low})",
                f"🕐 最近7天新增: {recent}",
            ]
            return "\n".join(lines)
        except Exception as e:
            return f"[记忆统计] 获取失败: {e}"
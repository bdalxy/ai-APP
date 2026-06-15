# 「AI伙伴」Python 后端代码审核报告

**审核日期**: 2026-06-15 | **审核智能体**: 后端工程师 | **综合评分**: B+

---

## 一、chat_bridge/ 桥接层

### 严重问题

| # | 问题 | 位置 |
|---|------|------|
| S1 | 流式对话 stream 无超时清理，内存泄漏 | [_core.py:L226-L279](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L226-L279) |
| S2 | 后台线程异常时 queue 不完整 | [_core.py:L235-L279](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L235-L279) |
| S3 | chat_stream_poll() TOCTOU 竞态 | [_core.py:L300-L303](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L300-L303) |

### 中等问题

| # | 问题 | 位置 |
|---|------|------|
| M1 | chat_stream_start() 返回类型不一致 | [_core.py:L194](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L194) |
| M2 | export_history() 动态导入 | [_core.py:L529-L539](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L529-L539) |
| M3 | _build_custom_preset 比例之和 > 1.0 | [_core.py:L47-L51](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L47-L51) |
| M4 | 日志模块不一致（logging vs loguru） | [_plugins.py:L5-L9](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_plugins.py#L5-L9) |
| M5 | shutdown_executor() wait=False 丢失任务 | [_state.py:L27-L40](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_state.py#L27-L40) |
| M6 | XOR 加密强度不足（应统一 Fernet） | [_memory.py:L340-L345](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py#L340-L345) |
| M7 | 世界书 _save_book_to_file() 非原子写入 | [_world_book.py:L530-L560](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_world_book.py#L530-L560) |
| M8 | get_enabled_world_books() set 迭代非线程安全 | [_world_book.py:L143-L152](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_world_book.py#L143-L152) |

---

## 二、chat_engine/ 对话引擎

### 严重问题

| # | 问题 | 位置 |
|---|------|------|
| S4 | chat_stream() 没有 @retry 重试 | [deepseek.py:L219-L322](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/api_client/deepseek.py#L219-L322) |

### 中等问题

| # | 问题 | 位置 |
|---|------|------|
| M9 | chat_stream() memories 残留（finally 缺失） | [role_player.py:L384-L411](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/chat_engine/role_player.py#L384-L411) |
| M10 | token 生成中断时 context 已添加不完整回复 | [role_player.py:L396-L398](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/chat_engine/role_player.py#L396-L398) |
| M11 | PRICING 价格硬编码 | [deepseek.py:L56-L61](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/api_client/deepseek.py#L56-L61) |

---

## 三、memory/ 记忆系统

### 严重问题

| # | 问题 | 位置 |
|---|------|------|
| S5 | search_by_keyword 回退全量加载 OOM 风险 | [vector_store.py:L535-L542](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/memory/vector_store.py#L535-L542) |
| S6 | _deduplicate() 全量加载性能下降 | [extractor.py:L404-L406](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/memory/extractor.py#L404-L406) |

### 中等问题

| # | 问题 | 位置 |
|---|------|------|
| M13 | OFFSET 分页大数据量性能差 | [vector_store.py:L626](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/memory/vector_store.py#L626) |
| M14 | _fallback_full_search 候选缓冲区无上限 | [retriever.py:L156-L224](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/memory/retriever.py#L156-L224) |
| M15 | retrieve_by_type() 不使用分页 | [retriever.py:L226-L249](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/memory/retriever.py#L226-L249) |
| M16 | _extract_by_rule() 只从 user 消息提取 | [extractor.py:L213-L217](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/memory/extractor.py#L213-L217) |
| M17 | 归档器锁内调用 LLM API 阻塞 | [archiver.py:L99-L148](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/memory/archiver.py#L99-L148) |

---

## 四、world_book/ 世界书

| # | 问题 | 位置 |
|---|------|------|
| M18 | WorldBookEngine 非线程安全 | [world_book.py:L249-L406](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/world_book/world_book.py#L249-L406) |
| M19 | set_active() 与多书启用架构冲突 | [world_book.py:L346-L369](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/world_book/world_book.py#L346-L369) |

---

## 五、plugins/ 插件系统

| # | 问题 | 位置 |
|---|------|------|
| M20 | 插件类属性多实例共享计数器 | [plugin_base.py:L51-L55](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/plugins/plugin_base.py#L51-L55) |

---

## 六、模块评分

| 模块 | 代码质量 | 线程安全 | 异常处理 | 资源管理 | 综合 |
|------|----------|----------|----------|----------|------|
| chat_bridge | B+ | B | A- | B | B+ |
| chat_engine | A- | B+ | B+ | A- | B+ |
| memory | A- | A- | A- | B+ | B+ |
| world_book | B+ | C+ | B+ | B | B |
| plugins | A- | A- | A | A | A- |
| **整体** | **B+** | **B+** | **A-** | **B+** | **B+** |
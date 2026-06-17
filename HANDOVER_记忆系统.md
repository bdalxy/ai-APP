# 记忆系统开发交接报告

> 日期：2026-06-17  
> 阶段：记忆系统从无到有完整搭建  
> 状态：APK已构建、已安装到设备(1dafb92f)、已推送到GitHub

---

## 一、项目背景

本项目为独立 Android 原生 APP（Kotlin + Chaquopy Python），AI 角色扮演类应用。记忆系统是核心模块，负责对话记忆的提取、向量化存储、混合检索、衰减管理、上下文构建、备份恢复等功能。

---

## 二、记忆系统完整架构

```
MemoryOrchestrator (编排器) — 统一入口
├── 提取层: MemoryExtractor       — 规则模式 + LLM模式混合提取，支持冲突检测、实体提取
├── 检索层: MemoryRetriever       — 向量余弦相似度 + BM25关键词 + 多策略降级
├── 存储层: VectorStore           — SQLite向量存储 + 倒排索引 + 关系图 + 标签系统 + 变更日志
├── 类型系统: memory_types.py     — 5大类15子类记忆分类、冲突检测、情感分析
├── 衰减: decay.py               — 时间衰减加权、半衰期计算
├── 生命周期: MemoryLifecycle     — 衰减更新、重要性重校准、智能清理、健康检查
├── 合并层: MemoryConsolidator    — 相似记忆合并、冲突解决
├── 分析层: MemoryAnalyzer        — 趋势分析、主题聚类、用户画像、质量评估
├── 上下文构建: ContextBuilder    — Token预算管理、分层记忆注入(核心/扩展/最近)、三维排序
├── 备份层: MemoryBackup          — 完整备份(SQLite API) + JSON导出 + 增量备份 + 自动备份 + 校验恢复
├── 缓存层: MemoryCache           — LRU热点缓存 + TTL检索缓存 + 统计/画像/标签缓存
└── 归档层: MemoryArchiver        — 记忆归档(冷数据迁移)
```

---

## 三、文件清单（15个文件）

### 新增文件（8个）

| 文件路径 | 说明 | 关键类/函数 |
|----------|------|-------------|
| `src/memory/memory_types.py` | 扩展类型系统 | `MemoryCategory`(5大类15子类), `MemoryRelation`, `ConflictResult`, `EntityInfo`, `MemoryTag`, `MemoryChangeLog`, `detect_conflict()`, `extract_entities()`, `analyze_sentiment()`, `estimate_importance()` |
| `src/memory/bm25.py` | BM25关键词检索 | `BM25Scorer`, `BM25Config` |
| `src/memory/consolidator.py` | 记忆合并器 | `MemoryConsolidator`, `CONSOLIDATION_INTERVAL=20` |
| `src/memory/lifecycle.py` | 生命周期管理 | `MemoryLifecycle` — 衰减更新、重要性重校准、智能清理、健康检查 |
| `src/memory/analyzer.py` | 记忆分析器 | `MemoryAnalyzer` — 趋势分析、主题聚类、用户画像、质量评估 |
| `src/memory/context_builder.py` | 上下文构建器 | `ContextBuilder`, `ContextConfig`, `MemoryContext` — Token预算(默认800)、分层注入(核心50%/扩展30%/最近20%)、三维排序(相关性0.5/重要性0.3/时效性0.2) |
| `src/memory/backup.py` | 备份管理器 | `MemoryBackup`, `BackupMetadata` — 完整备份(SQLite API)、JSON导出、增量备份、自动备份(24h或100条)、校验恢复 |
| `src/memory/memory_cache.py` | 多层缓存 | `MemoryCache`, `LRUCache`, `TTLCache` — 热点缓存(LRU,200条)、检索缓存(TTL,60s)、统计缓存(TTL,30s)、画像缓存(TTL,300s) |

### 修改文件（7个）

| 文件路径 | 变更量 | 关键变更 |
|----------|--------|----------|
| `src/memory/orchestrator.py` | +565行 | 集成ContextBuilder/MemoryCache/MemoryBackup；新增`build_context()`, `build_context_compact()`, `backup_full()`, `backup_json()`, `restore_backup()`, `list_backups()`, `delete_backup()`, `verify_backup()`, `get_backup_stats()`, `get_cache_stats()`, `invalidate_cache()`, `invalidate_cache_all()`, `cache_cleanup()`；`remember()`增加缓存失效+自动备份检查；`recall()`增加缓存查询；`run_maintenance()`增加缓存清理 |
| `src/memory/__init__.py` | +91行 | 导出所有新模块类 |
| `src/memory/extractor.py` | +143行 | 扩展提取能力（前一阶段） |
| `src/memory/retriever.py` | +150行 | 混合检索增强（前一阶段） |
| `src/memory/vector_store.py` | +475行 | 扩展存储能力（前一阶段） |
| `chat_bridge/_memory.py` | +490行 | 新增13个桥接函数 |
| `chat_bridge/__init__.py` | +55行 | 导出所有新桥接函数 |

---

## 四、桥接接口清单（Kotlin可调用）

### 核心接口（已有）
- `init_memory(db_path)` — 初始化记忆系统
- `get_memory_stats()` — 获取统计
- `inject_memories(query_text)` — 检索并注入记忆到System Prompt
- `remember_turn(turn_id, user_msg, ai_reply)` — 手动存储一轮对话
- `list_memories(type_filter, page, page_size)` — 分页列表
- `get_memory(memory_id)` / `update_memory(memory_id, content)` / `delete_memory(memory_id)` — CRUD
- `search_memories(keyword)` — 关键字搜索
- `export_memories(password)` / `import_memories(json)` — 导出导入
- `reset_memories()` / `clear_memories()` — 清空
- `set_extract_interval(n)` / `set_memory_extract_mode(mode)` — 提取策略

### 维护接口（已有）
- `run_maintenance()` — 衰减+合并+清理+健康检查+缓存清理

### 分析接口（已有）
- `analyze_trends(days)` — 记忆趋势分析
- `analyze_topics(num_clusters)` — 主题聚类
- `generate_user_profile()` — 用户画像
- `analyze_quality()` — 记忆库质量评估

### 标签接口（已有）
- `add_tag(name, color)` / `tag_memory(memory_id, tag_name)` / `untag_memory(...)` / `get_memory_tags(...)` / `list_all_tags()`

### 关系接口（已有）
- `add_relation(source_id, target_id, type, confidence)` / `get_relations(memory_id)`

### 日志接口（已有）
- `get_changelog(memory_id, limit)` — 变更日志

### 上下文构建接口（新增）
- `build_context(query_text, conversation_history_json, user_profile_json)` — 构建优化上下文
- `build_context_compact(query_text, max_memories)` — 快速构建

### 备份管理接口（新增）
- `backup_full()` — 完整备份
- `backup_json()` — JSON备份
- `restore_backup(backup_id)` — 恢复备份
- `list_backups()` — 列出备份
- `delete_backup(backup_id)` — 删除备份
- `verify_backup(backup_id)` — 校验备份
- `get_backup_stats()` — 备份统计

### 缓存管理接口（新增）
- `get_cache_stats()` — 缓存统计
- `invalidate_cache()` — 部分失效（检索+统计）
- `invalidate_cache_all()` — 全部失效
- `cache_cleanup()` — 清理过期条目

---

## 五、关键设计决策

### 1. 懒加载策略
所有扩展模块（ContextBuilder、MemoryCache、MemoryBackup、MemoryLifecycle、MemoryConsolidator、MemoryAnalyzer、MemoryArchiver）均采用懒加载，首次调用时才初始化，减少内存占用。

### 2. 缓存集成
- `remember()` 存储新记忆后自动调用 `cache.on_memory_change()` 使检索缓存失效
- `recall()` 检索前先查缓存，命中则直接返回，未命中则检索后写入缓存
- `run_maintenance()` 包含缓存过期清理
- `restore_backup()` 恢复后自动清除全部缓存
- 自动备份检查在 `remember()` 中触发

### 3. 备份目录
备份目录自动推导：数据库文件同级的 `backups/` 子目录。内存数据库使用当前目录的 `backups/`。

### 4. Token预算管理
ContextBuilder 默认配置：
- 总预算：800 tokens
- 核心记忆：50%（~400 tokens）
- 扩展记忆：30%（~240 tokens）
- 最近记忆：20%（~160 tokens）
- 单条记忆上限：200字符

### 5. 提取节流
默认每5轮对话触发一次LLM提取，其余使用规则模式（零API开销）。可通过`set_extract_interval()`调整。

---

## 六、当前状态

| 项目 | 状态 |
|------|------|
| 代码编译 | ✅ 通过 |
| APK构建 | ✅ 成功 |
| 设备安装 | ✅ 设备 `1dafb92f` |
| APP启动 | ✅ 正常 |
| GitHub推送 | ✅ commit `4e63a10`，已推送至 `main` 分支 |
| 功能测试 | ⏳ 待人工验证 |

---

## 七、测试验证指南

### 基础流程测试
1. 打开APP → 初始化 → 发送消息对话
2. 检查记忆是否自动存储（日志中查看 `[记忆存储]` 关键字）
3. 发送相关话题，检查记忆是否被检索注入（日志中查看 `[记忆检索]`）

### 记忆管理测试
```python
# 通过Kotlin调用以下桥接函数验证：
get_memory_stats()        # 查看记忆统计（总数、各类型数量、生命周期状态）
list_memories()           # 查看记忆列表
search_memories("关键词")  # 搜索记忆
get_memory(rowid)         # 查看单条记忆详情
update_memory(rowid, "新内容")  # 更新记忆
delete_memory(rowid)      # 删除记忆
```

### 维护功能测试
```python
run_maintenance()  # 手动触发维护（衰减+合并+清理+缓存清理）
```

### 分析功能测试
```python
analyze_trends(30)        # 30天趋势分析
analyze_topics(5)         # 主题聚类
generate_user_profile()   # 用户画像
analyze_quality()         # 质量评估
```

### 备份功能测试
```python
backup_full()             # 完整备份
backup_json()             # JSON备份
list_backups()            # 查看备份列表
verify_backup("backup_id") # 校验备份
restore_backup("backup_id") # 恢复备份
```

### 缓存功能测试
```python
get_cache_stats()         # 查看各层缓存命中率
invalidate_cache()        # 部分失效
invalidate_cache_all()    # 全部失效
```

### 合格标准
- [ ] 对话后记忆自动存储，日志无异常
- [ ] 重复相关话题时记忆被检索注入
- [ ] 记忆统计信息正确（总数、各类型数量）
- [ ] CRUD操作正常
- [ ] 备份/恢复功能正常
- [ ] 维护功能正常（衰减、合并、清理）
- [ ] 缓存命中率 > 0%（重复查询时）
- [ ] 无崩溃、无内存泄漏

---

## 八、后续待办

| 优先级 | 任务 | 说明 |
|--------|------|------|
| 高 | 真机功能验证 | 按上述测试指南逐项验证 |
| 高 | 记忆系统UI | Android端管理界面（记忆列表、搜索、编辑、备份管理） |
| 中 | 上下文构建接入对话 | 将`build_context()`接入`chat()`流程，替代当前简单的`inject_memories()` |
| 中 | 性能测试 | 大量记忆(1000+)场景下的检索延迟、缓存效果 |
| 低 | 记忆导出格式优化 | 支持更多导出格式（Markdown等） |
| 低 | 从繁到简 | 在功能完善后，审视代码精简空间 |
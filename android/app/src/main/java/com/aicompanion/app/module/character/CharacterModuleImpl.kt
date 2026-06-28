package com.aicompanion.app.module.character

import android.content.Context
import android.util.Log
import com.aicompanion.app.CharacterData
import com.aicompanion.app.CharacterStorage
import com.aicompanion.app.module.ModuleEventBus

/**
 * 角色卡模块实现。
 *
 * 桥接 [CharacterStorage]（Kotlin 本地存储）和 Python chat_bridge._character 模块，
 * 通过 ModuleRegistry 注册后供全局访问。
 *
 * 数据流：
 *   CharacterInfo  <-->  CharacterData  <-->  CharacterStorage (JSON 文件)
 *   CharacterInfo  -->  JSON 字符串  -->  Python set_character_card() / reload_card()
 */
class CharacterModuleImpl(private val context: Context) : CharacterModule {

    companion object {
        private const val TAG = "CharacterModule"
    }

    // ======================== CharacterInfo <-> CharacterData 映射 ========================

    /**
     * 将领域模型 [CharacterData] 转换为接口数据类 [CharacterInfo]。
     * 使用 description 字段聚合 personality 和 backstory 的摘要。
     */
    private fun CharacterData.toInfo(): CharacterInfo {
        return CharacterInfo(
            id = this.id,
            name = this.name,
            avatar = this.avatarUri,
            description = "${this.personality}; ${this.backstory}".take(100),
            isActive = this.id == getActiveCharacterId()
        )
    }

    /**
     * 将接口数据类 [CharacterInfo] 转换为领域模型 [CharacterData]。
     * 对于新建角色，使用默认值填充 personality/backstory 等字段。
     * 对于更新已有角色，保留原有字段（通过 loadCharacter 加载再合并）。
     */
    private fun CharacterInfo.toData(): CharacterData {
        // 先尝试加载已有角色，保留其完整字段
        val existing = CharacterStorage.loadAll(context).find { it.id == this.id }
        return CharacterData(
            id = this.id,
            name = this.name,
            avatarUri = this.avatar,
            personality = existing?.personality ?: "友好、乐于助人",
            speakingStyle = existing?.speakingStyle ?: "语气亲切自然",
            backstory = existing?.backstory ?: this.description,
            greeting = existing?.greeting ?: "你好呀~",
            isDefault = existing?.isDefault ?: false,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
    }

    /** 获取当前活跃角色 ID（从 SharedPreferences 读取） */
    private fun getActiveCharacterId(): String? {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("current_character_id", null)
    }

    // ======================== CharacterModule 接口实现 ========================

    override fun loadCharacter(characterId: String): CharacterInfo? {
        return try {
            val all = CharacterStorage.loadAll(context)
            all.find { it.id == characterId }?.toInfo()
        } catch (e: Exception) {
            Log.e(TAG, "加载角色卡失败: id=$characterId", e)
            null
        }
    }

    override fun saveCharacter(character: CharacterInfo) {
        try {
            CharacterStorage.save(context, character.toData())
            Log.d(TAG, "角色卡已保存: id=${character.id}, name=${character.name}")
            // 发布角色变更事件
            ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, character.id)
        } catch (e: Exception) {
            Log.e(TAG, "保存角色卡失败: id=${character.id}", e)
        }
    }

    override fun getActiveCharacter(): CharacterInfo? {
        return try {
            CharacterStorage.getCurrent(context).toInfo()
        } catch (e: Exception) {
            Log.e(TAG, "获取活跃角色失败", e)
            null
        }
    }

    override fun setActiveCharacter(characterId: String) {
        try {
            // 1. 持久化到 SharedPreferences
            CharacterStorage.setCurrent(context, characterId)
            Log.d(TAG, "活跃角色已切换: id=$characterId")

            // 2. 同步到 Python 引擎（如果已初始化）
            syncToPython(characterId)

            // 3. 发布角色变更事件
            ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, characterId)
        } catch (e: Exception) {
            Log.e(TAG, "设置活跃角色失败: id=$characterId", e)
        }
    }

    override fun listCharacters(): List<CharacterInfo> {
        return try {
            CharacterStorage.loadAll(context).map { it.toInfo() }
        } catch (e: Exception) {
            Log.e(TAG, "列出角色卡失败", e)
            emptyList()
        }
    }

    override fun deleteCharacter(characterId: String): Boolean {
        return try {
            val character = loadCharacter(characterId)
            if (character == null) {
                Log.w(TAG, "要删除的角色卡不存在: id=$characterId")
                return false
            }
            CharacterStorage.delete(context, characterId)
            Log.d(TAG, "角色卡已删除: id=$characterId, name=${character.name}")
            // 发布角色变更事件
            ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, characterId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除角色卡失败: id=$characterId", e)
            false
        }
    }

    // ======================== Python 桥接 ========================

    /**
     * 将当前角色卡同步到 Python chat_bridge._character 模块。
     *
     * 调用 Python 端 set_character_card() 和 reload_card()，
     * 确保 AI 引擎使用最新的角色设定。
     *
     * @param characterId 要同步的角色 ID，null 表示使用当前活跃角色
     */
    fun syncToPython(characterId: String? = null) {
        try {
            val python = com.chaquo.python.Python.getInstance()
            val module = python.getModule("chat_bridge") ?: run {
                Log.w(TAG, "Python chat_bridge 模块未就绪，跳过角色卡同步")
                return
            }

            // 获取角色数据
            val targetId = characterId ?: getActiveCharacterId() ?: return
            val character = CharacterStorage.loadAll(context).find { it.id == targetId } ?: return

            // 构建 JSON（与 MainActivity.syncCharacterToPython() 保持一致）
            val charJson = org.json.JSONObject().apply {
                put("name", character.name)
                put("personality", character.personality)
                put("speaking_style", character.speakingStyle)
                put("backstory", character.backstory)
                put("greeting", character.greeting)
                put("emotional_tendency", character.emotionalTendency)
                put("self_identity", character.selfIdentity)
                put("world_book_id", character.worldBookId)
                put("core_traits", character.coreTraits)
                put("taboo_topics", character.tabooTopics)
                put("role_anchor", character.roleAnchor)
            }.toString()

            // 调用 Python 设置角色卡
            module.callAttr("set_character_card", charJson)
            module.callAttr("reload_card")
            Log.d(TAG, "角色卡已同步到 Python: ${character.name}")

            // 自动启用角色绑定的世界书
            if (character.worldBookId.isNotBlank()) {
                try {
                    module.callAttr("enable_world_book", character.worldBookId)
                    Log.d(TAG, "已自动启用角色绑定的世界书: ${character.worldBookId}")
                } catch (e: Exception) {
                    Log.w(TAG, "自动启用世界书失败: ${character.worldBookId}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "角色卡同步到 Python 失败（可能 Python 未就绪）: ${e.message}")
        }
    }
}
package com.aicompanion.app.module.character

data class CharacterInfo(
    val id: String,
    val name: String,
    val avatar: String = "",
    val description: String = "",
    val isActive: Boolean = false
)

interface CharacterModule {

    /** 加载指定角色卡 */
    fun loadCharacter(characterId: String): CharacterInfo?

    /** 保存角色卡 */
    fun saveCharacter(character: CharacterInfo)

    /** 获取当前活跃角色 */
    fun getActiveCharacter(): CharacterInfo?

    /** 设置当前活跃角色（切换角色时调用） */
    fun setActiveCharacter(characterId: String)

    /** 列出所有角色卡 */
    fun listCharacters(): List<CharacterInfo>

    /** 删除角色卡 */
    fun deleteCharacter(characterId: String): Boolean
}
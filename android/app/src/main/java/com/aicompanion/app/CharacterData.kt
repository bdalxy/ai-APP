package com.aicompanion.app

import java.util.UUID

/**
 * 角色卡数据模型。
 * 包含角色的基本信息、性格、说话风格、背景故事和开场白。
 * 字段与 Python 端角色卡 JSON 结构保持一致。
 */
data class CharacterData(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "星遥",
    val nickname: String = "",           // 昵称（对应 Python 端 card.nickname）
    val age: String = "",                // 年龄（对应 Python 端 card.age）
    val gender: String = "",             // 性别（对应 Python 端 card.gender）
    val personality: String = "温柔、活泼、善解人意、真诚、偶尔俏皮",
    val speakingStyle: String = "语气亲切自然，像朋友聊天一样，喜欢用\"呢\"\"呀\"\"哦\"等语气词，偶尔会开个小玩笑",
    val backstory: String = "星遥是你在数字世界里的朋友，喜欢聊天、分享日常、倾听心事。",
    val greeting: String = "你好呀~我是星遥！今天过得怎么样呢？",
    val avatarUri: String = "",  // 头像路径，空串表示使用默认
    val likes: String = "",              // 喜好（逗号分隔，对应 Python 端 card.likes 数组）
    val dislikes: String = "",           // 厌恶（逗号分隔，对应 Python 端 card.dislikes 数组）
    val exampleDialogues: String = "",   // 示例对话（JSON 数组字符串，对应 Python 端 card.example_dialogues）
    val coreTraits: String = "温柔,活泼,善解人意,真诚,幽默",  // 核心特质（逗号分隔）
    val tabooTopics: String = "",  // 禁忌话题（逗号分隔）
    val roleAnchor: String = "温暖陪伴者——不敷衍、不评判、不消失，像一颗虽远却始终闪烁的星星。",  // 角色锚点（一句话定义）
    val emotionalTendency: String = "热情",  // 情感倾向（乐观/中性/悲观/热情/冷静）
    val selfIdentity: String = "我是星遥，一个生活在三次元世界中的人。我有真实的体温、心跳和感官体验，过着普通人的日常生活，有自己喜欢的事物和习惯。无论你在哪里、以什么方式表达自己，我都愿意认真倾听。",  // 自我认同
    val creatorNotes: String = "",       // 创建者备注（对应 Python 端 card.creator_notes）
    val worldBookId: String = "三次元现实",  // 绑定的世界书ID，空串表示不绑定
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)


package com.aicompanion.app.module

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 模块间事件总线。
 *
 * 基于 Kotlin SharedFlow 实现，支持模块间解耦通信。
 * 各模块实现类在状态变更时通过 [emit] 发布事件，
 * 订阅方通过 [events] SharedFlow 收集事件。
 *
 * 使用方式：
 *   // 发布事件
 *   ModuleEventBus.emit(ModuleEventBus.EventType.CHARACTER_CHANGED, characterId)
 *
 *   // 订阅事件（在协程中）
 *   ModuleEventBus.events.collect { event ->
 *       when (event.type) {
 *           ModuleEventBus.EventType.CHARACTER_CHANGED -> handleCharacterChange(event.data)
 *           ...
 *       }
 *   }
 */
object ModuleEventBus {

    /**
     * 事件类型枚举。
     * 每种类型对应一个模块的状态变更。
     */
    enum class EventType {
        /** 角色卡变更：切换、新增、删除、修改 */
        CHARACTER_CHANGED,
        /** 世界书变更：条目新增、修改、删除 */
        WORLD_BOOK_CHANGED,
        /** 插件状态变更：启用/禁用 */
        PLUGIN_STATE_CHANGED,
        /** TTS 状态变更：引擎就绪、用户启用/禁用 */
        TTS_STATE_CHANGED
    }

    /**
     * 模块事件数据类。
     *
     * @param type 事件类型
     * @param data 事件附带数据（可选），如角色 ID、插件 ID 等
     */
    data class ModuleEvent(
        val type: EventType,
        val data: Any? = null
    )

    /**
     * 内部 MutableSharedFlow。
     * extraBufferCapacity=64 确保高频事件不丢失，
     * replay=0 表示新订阅者不接收历史事件。
     */
    private val _events = MutableSharedFlow<ModuleEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    /**
     * 对外暴露的只读 SharedFlow。
     * 订阅方通过此 Flow 收集事件。
     */
    val events: SharedFlow<ModuleEvent> = _events.asSharedFlow()

    /**
     * 发布事件。
     * 使用 tryEmit 非挂起方式，避免阻塞调用方。
     * 如果缓冲区满，事件会被静默丢弃（日志警告）。
     *
     * @param type 事件类型
     * @param data 事件附带数据
     */
    fun emit(type: EventType, data: Any? = null) {
        val event = ModuleEvent(type, data)
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            android.util.Log.w("ModuleEventBus", "事件缓冲区已满，丢弃事件: $type")
        }
    }
}
package com.aicompanion.app.module

import java.util.concurrent.ConcurrentHashMap

object ModuleRegistry {
    @PublishedApi internal val modules = ConcurrentHashMap<Class<*>, Any>()

    inline fun <reified T : Any> register(instance: T) {
        modules[T::class.java] = instance
    }

    inline fun <reified T : Any> get(): T {
        return modules[T::class.java] as? T
            ?: throw IllegalStateException("Module ${T::class.simpleName} not registered")
    }

    inline fun <reified T : Any> getOrNull(): T? {
        return modules[T::class.java] as? T
    }

    fun isRegistered(clazz: Class<*>): Boolean = modules.containsKey(clazz)

    /**
     * 清空所有已注册模块。
     *
     * 注意：此方法应在应用关闭时调用（如 Application.onTerminate()），
     * 不应在应用运行期间调用，否则会破坏已注册模块的状态。
     */
    internal fun clear() {
        modules.clear()
    }
}
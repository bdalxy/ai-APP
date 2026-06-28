package com.aicompanion.app.module

object ModuleRegistry {
    private val modules = mutableMapOf<Class<*>, Any>()

    @Synchronized
    inline fun <reified T : Any> register(instance: T) {
        modules[T::class.java] = instance
    }

    @Synchronized
    inline fun <reified T : Any> get(): T {
        return modules[T::class.java] as? T
            ?: throw IllegalStateException("Module ${T::class.simpleName} not registered")
    }

    @Synchronized
    inline fun <reified T : Any> getOrNull(): T? {
        return modules[T::class.java] as? T
    }

    @Synchronized
    fun isRegistered(clazz: Class<*>): Boolean = modules.containsKey(clazz)

    @Synchronized
    internal fun clear() {
        modules.clear()
    }
}
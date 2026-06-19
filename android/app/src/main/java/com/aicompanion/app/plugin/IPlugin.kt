package com.aicompanion.app.plugin

import android.content.Context

interface IPlugin {
    fun onInstall(context: Context)
    fun onUninstall(context: Context)
    fun onEnable(context: Context)
    fun onDisable(context: Context)
    fun getPluginInfo(): PluginInfo
}
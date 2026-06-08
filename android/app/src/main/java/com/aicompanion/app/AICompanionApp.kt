package com.aicompanion.app

import com.chaquo.python.android.PyApplication

/**
 * AI Companion 应用入口。
 * 必须继承 PyApplication 以自动初始化 Chaquopy AndroidPlatform，
 * 否则调用 Python.getInstance() 会报 GenericPlatform 错误。
 */
class AICompanionApp : PyApplication()
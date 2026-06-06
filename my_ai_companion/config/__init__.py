# -*- coding: utf-8 -*-
"""
配置管理模块
提供安全的配置加载和管理
"""

from .settings import Settings, load_settings

__all__ = ["Settings", "load_settings"]

# -*- coding: utf-8 -*-
"""
安全配置管理
提供安全的环境变量加载和管理
"""

import os
import logging
from typing import Optional, Dict, Any
from pathlib import Path
from dataclasses import dataclass

logger = logging.getLogger(__name__)

@dataclass
class Settings:
    """应用配置"""
    
    # AI 配置
    AI_MODE: str = "cloud"
    DEEPSEEK_API_KEY: str = ""
    DEEPSEEK_API_URL: str = "https://api.deepseek.com/v1/chat/completions"
    DEEPSEEK_EMBEDDING_URL: str = "https://api.deepseek.com/v1/embeddings"
    MODEL_NAME: str = "deepseek-v4-flash"
    EMBEDDING_MODEL: str = "deepseek-embedding-v2"
    
    # 搜索配置
    SEARCH_PROVIDER: str = "duckduckgo"
    SEARCH_API_KEY: str = ""
    BING_SEARCH_ENDPOINT: str = "https://api.bing.microsoft.com/v7.0/search"
    GOOGLE_CSE_ID: str = ""
    
    # 平台配置
    PLATFORM: str = "wechat"
    MEMORY_LEVEL: str = "standard"
    
    # 本地模型配置
    LOCAL_MODEL_PATH: str = ""
    
    # 日志配置
    LOG_LEVEL: str = "INFO"
    
    # API 服务配置
    API_PORT: int = 8000
    API_HOST: str = "127.0.0.1"
    
    def validate(self) -> Dict[str, Any]:
        """验证配置"""
        issues = []
        warnings = []
        
        if self.AI_MODE == "cloud":
            if not self.DEEPSEEK_API_KEY:
                issues.append("DEEPSEEK_API_KEY 未配置，请在 .env 文件中设置")
            else:
                if len(self.DEEPSEEK_API_KEY) < 10:
                    warnings.append("DEEPSEEK_API_KEY 长度不足，可能无效")
                if not self.DEEPSEEK_API_URL.startswith('https://'):
                    warnings.append("DEEPSEEK_API_URL 应该使用 HTTPS 协议")
        
        if self.AI_MODE == "local":
            if not self.LOCAL_MODEL_PATH:
                issues.append("LOCAL_MODEL_PATH 未配置，请在 .env 文件中设置")
            else:
                if not os.path.exists(self.LOCAL_MODEL_PATH):
                    warnings.append(f"LOCAL_MODEL_PATH 路径不存在: {self.LOCAL_MODEL_PATH}")
        
        if self.MEMORY_LEVEL not in ["basic", "standard", "advanced"]:
            issues.append(f"MEMORY_LEVEL 值无效: {self.MEMORY_LEVEL}，有效值为 basic/standard/advanced")
        
        try:
            if self.API_PORT < 1 or self.API_PORT > 65535:
                issues.append(f"API_PORT 超出有效范围: {self.API_PORT}")
        except ValueError:
            issues.append(f"API_PORT 不是有效数字: {self.API_PORT}")
        
        return {"valid": len(issues) == 0, "issues": issues, "warnings": warnings}
    
    def ensure_valid(self) -> None:
        """确保配置有效，否则抛出异常"""
        result = self.validate()
        if not result["valid"]:
            error_msg = "配置验证失败:\n" + "\n".join(f"  - {issue}" for issue in result["issues"])
            raise ValueError(error_msg)
    
    def to_safe_dict(self) -> Dict[str, Any]:
        """转换为安全字典（隐藏敏感信息）"""
        safe_dict = {}
        for key, value in self.__dict__.items():
            if not key.startswith('_'):
                if "KEY" in key or "PASSWORD" in key or "SECRET" in key:
                    if value and len(value) > 8:
                        safe_dict[key] = f"{value[:4]}****{value[-4:]}"
                    elif value:
                        # 对于较短的密钥，只显示前2个字符
                        safe_dict[key] = f"{value[:2]}****"
                    else:
                        safe_dict[key] = "****"
                else:
                    safe_dict[key] = value
        return safe_dict

def load_settings(env_file: Optional[str] = None) -> Settings:
    """加载配置"""
    if env_file is None:
        env_file = Path.cwd() / ".env"
    
    if Path(env_file).exists():
        with open(env_file, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    key, value = line.split('=', 1)
                    os.environ[key.strip()] = value.strip()
    
    return Settings(
        AI_MODE=os.getenv('AI_MODE', 'cloud'),
        DEEPSEEK_API_KEY=os.getenv('DEEPSEEK_API_KEY', ''),
        DEEPSEEK_API_URL=os.getenv('DEEPSEEK_API_URL', 'https://api.deepseek.com/v1/chat/completions'),
        DEEPSEEK_EMBEDDING_URL=os.getenv('DEEPSEEK_EMBEDDING_URL', 'https://api.deepseek.com/v1/embeddings'),
        MODEL_NAME=os.getenv('MODEL_NAME', 'deepseek-v4-flash'),
        EMBEDDING_MODEL=os.getenv('EMBEDDING_MODEL', 'deepseek-embedding-v2'),
        SEARCH_PROVIDER=os.getenv('SEARCH_PROVIDER', 'duckduckgo'),
        SEARCH_API_KEY=os.getenv('SEARCH_API_KEY', ''),
        BING_SEARCH_ENDPOINT=os.getenv('BING_SEARCH_ENDPOINT', 'https://api.bing.microsoft.com/v7.0/search'),
        GOOGLE_CSE_ID=os.getenv('GOOGLE_CSE_ID', ''),
        PLATFORM=os.getenv('PLATFORM', 'wechat'),
        MEMORY_LEVEL=os.getenv('MEMORY_LEVEL', 'standard'),
        LOCAL_MODEL_PATH=os.getenv('LOCAL_MODEL_PATH', ''),
        LOG_LEVEL=os.getenv('LOG_LEVEL', 'INFO'),
        API_PORT=int(os.getenv('API_PORT', '8000')),
        API_HOST=os.getenv('API_HOST', '0.0.0.0')
    )

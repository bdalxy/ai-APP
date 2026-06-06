"""基础配置类 - 从 .env 文件加载环境变量，提供 Settings 单例。"""
import os
from pathlib import Path
from typing import ClassVar
from dotenv import load_dotenv
from loguru import logger

class Settings:
    _instance: ClassVar["Settings | None"] = None
    _initialized: ClassVar[bool] = False

    def __new__(cls) -> "Settings":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self) -> None:
        if Settings._initialized:
            return
        self.PROJECT_ROOT: Path = Path(__file__).resolve().parent.parent.parent
        env_path = self.PROJECT_ROOT / ".env"
        if env_path.exists():
            load_dotenv(dotenv_path=env_path)
        self._load_env()
        Settings._initialized = True

    def _load_env(self) -> None:
        self.DEEPSEEK_API_KEY: str = os.getenv("DEEPSEEK_API_KEY", "")
        self.DEEPSEEK_MODEL: str = os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")
        self.DEEPSEEK_EMBEDDING_MODEL: str = os.getenv("DEEPSEEK_EMBEDDING_MODEL", "deepseek-embedding-v2")
        self.DEEPSEEK_BASE_URL: str = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
        self.LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
        data_dir_raw = os.getenv("DATA_DIR", "./data")
        self.DATA_DIR: Path = (self.PROJECT_ROOT / data_dir_raw).resolve()
        self.DATA_DIR.mkdir(parents=True, exist_ok=True)

    def validate(self) -> bool:
        if not self.DEEPSEEK_API_KEY:
            return False
        return True

settings = Settings()

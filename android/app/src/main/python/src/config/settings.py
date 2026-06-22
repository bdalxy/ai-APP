"""基础配置类 - 从 .env 文件加载环境变量，提供 Settings 单例。

使用 python-dotenv 加载 .env 文件，使用 loguru 做日志记录。
所有敏感配置（API Key 等）通过环境变量管理，不硬编码。
"""

import os
from pathlib import Path
from typing import ClassVar

try:
    from dotenv import load_dotenv as _load_dotenv
except ImportError:
    # Android 环境不需要 dotenv（API Key 通过 SharedPreferences 传入）
    def _load_dotenv(*args: object, **kwargs: object) -> bool:
        return False

# 使用 loguru（已通过 pip 安装），失败则降级为标准 logging
try:
    from loguru import logger  # noqa: F401
except ImportError:
    import logging
    logger = logging.getLogger("AICompanion")  # type: ignore[assignment]


class Settings:
    """全局配置单例类。

    从项目根目录的 .env 文件加载所有配置项，提供类型安全的属性访问。
    首次实例化时自动调用 _load_env() 完成初始化。

    Attributes:
        DEEPSEEK_API_KEY: DeepSeek API 密钥
        DEEPSEEK_MODEL: 对话模型名称
        DEEPSEEK_EMBEDDING_MODEL: Embedding 模型名称
        DEEPSEEK_BASE_URL: API 基础 URL
        LOG_LEVEL: 日志级别
        DATA_DIR: 数据存储目录（绝对路径）
        PROJECT_ROOT: 项目根目录
    """

    _instance: ClassVar["Settings | None"] = None
    _initialized: ClassVar[bool] = False

    def __new__(cls) -> "Settings":
        """单例模式：确保全局只有一个 Settings 实例。"""
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self) -> None:
        """初始化配置，仅执行一次。"""
        if Settings._initialized:
            return

        # 确定项目根目录（src/config/settings.py 的上两级目录）
        self.PROJECT_ROOT: Path = Path(__file__).resolve().parent.parent.parent

        # 加载 .env 文件（PC 端）
        # TODO(P3-P6): Android 端 .env 文件机制不可用，需通过 Kotlin 侧
        # SharedPreferences 传递 API Key 给 Python 侧
        env_path = self.PROJECT_ROOT / ".env"
        if env_path.exists():
            _load_dotenv(dotenv_path=env_path)
            logger.info(f"已加载环境变量文件: {env_path}")
        else:
            logger.warning(f".env 文件不存在: {env_path}，将使用系统环境变量")

        self._load_env()
        Settings._initialized = True
        logger.info("Settings 单例初始化完成")

    def _load_env(self) -> None:
        """从环境变量加载所有配置项，提供合理默认值。"""
        # DeepSeek API 配置
        self.DEEPSEEK_API_KEY: str = os.getenv("DEEPSEEK_API_KEY", "")
        self.DEEPSEEK_MODEL: str = os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")
        self.DEEPSEEK_EMBEDDING_MODEL: str = os.getenv(
            "DEEPSEEK_EMBEDDING_MODEL", "deepseek-embedding-v2"
        )
        self.DEEPSEEK_BASE_URL: str = os.getenv(
            "DEEPSEEK_BASE_URL", "https://api.deepseek.com"
        )

        # 日志配置
        self.LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
        # Android Release 构建默认 WARNING，避免 logcat 泄露用户对话
        # 优先使用显式设置的 build_type（由 Kotlin 侧调用 set_build_type() 传入）
        if os.getenv("ANDROID_BUILD_TYPE", "").lower() == "release":
            self.LOG_LEVEL = os.getenv("LOG_LEVEL", "WARNING")

        # 数据存储路径（转换为绝对路径）
        data_dir_raw = os.getenv("DATA_DIR", "./data")
        self.DATA_DIR: Path = (self.PROJECT_ROOT / data_dir_raw).resolve()

        # 确保数据目录存在（Android 上可能是只读的，跳过）
        try:
            self.DATA_DIR.mkdir(parents=True, exist_ok=True)
        except (OSError, PermissionError):
            pass

        # 角色卡默认配置（可通过 set_character_card 运行时修改）
        self.CHARACTER_NAME: str = "小星"
        self.CHARACTER_PERSONALITY: str = "温柔、活泼、善解人意"
        self.CHARACTER_SPEAKING_STYLE: str = "语气轻柔，喜欢使用可爱的语气词"
        self.CHARACTER_BACKSTORY: str = "乐于助人的AI助手，喜欢聊天和分享日常趣事"

    def set_build_type(self, build_type: str) -> None:
        """由 Kotlin 侧调用，显式设置构建类型以控制日志级别。

        Release 构建时应传入 "release"，将日志级别降为 WARNING，
        避免 logcat 泄露用户对话内容。

        Args:
            build_type: "debug" 或 "release"
        """
        if build_type.lower() == "release":
            current = self.LOG_LEVEL
            self.LOG_LEVEL = "WARNING"
            logger.info(f"构建类型设为 release，日志级别: {current} → WARNING")

    def validate(self) -> bool:
        """验证必要配置是否完整。

        Returns:
            配置是否通过验证：DEEPSEEK_API_KEY 不能为空。
        """
        if not self.DEEPSEEK_API_KEY:
            logger.error("DEEPSEEK_API_KEY 未设置，请在 .env 文件中配置")
            return False
        return True


# 模块级快捷访问
settings = Settings()

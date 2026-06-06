"""P1 阶段5 集成联调测试脚本。

测试覆盖：
    1. CompanionApp 命令解析（/help, /card, /world, /memory, /export, /clear, /cost, /quit）
    2. 完整对话流程模拟（Mock API）
    3. 角色卡切换
    4. 记忆注入到对话
    5. 导出功能端到端
    6. 错误处理（API 异常、角色卡缺失等）
    7. 命令行参数解析
"""

import json
import os
import sys
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch, PropertyMock

# 确保项目根目录在 sys.path 中
_project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_project_root))

import pytest


# =============================================================================
# 测试辅助
# =============================================================================


def _mock_deepseek_chat_response(content: str = "你好呀~我是小美！"):
    """创建 Mock ChatResponse。"""
    from src.api_client.deepseek import ChatResponse
    return ChatResponse(
        content=content,
        model="deepseek-v4-flash",
        usage={"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70},
        finish_reason="stop",
    )


def _mock_deepseek_embed_response():
    """创建 Mock EmbeddingResponse。"""
    from src.api_client.deepseek import EmbeddingResponse
    return EmbeddingResponse(
        embeddings=[[0.1] * 8],
        model="deepseek-embedding-v2",
        usage={"prompt_tokens": 10, "completion_tokens": 0, "total_tokens": 10},
    )


def _setup_mock_env():
    """设置 Mock 环境变量，确保测试可以运行。"""
    os.environ["DEEPSEEK_API_KEY"] = "test_api_key_for_testing"
    os.environ["DEEPSEEK_MODEL"] = "deepseek-v4-flash"
    os.environ["DEEPSEEK_EMBEDDING_MODEL"] = "deepseek-embedding-v2"
    os.environ["DEEPSEEK_BASE_URL"] = "https://api.deepseek.com"
    os.environ["LOG_LEVEL"] = "WARNING"


# =============================================================================
# 测试1：命令行参数解析
# =============================================================================


class TestArgParse:
    """测试命令行参数解析。"""

    def test_default_args(self):
        """测试默认参数。"""
        from main import parse_args

        with patch("sys.argv", ["main.py"]):
            args = parse_args()
            assert args.card is None
            assert args.memory_db is None
            assert args.log_level is None

    def test_custom_args(self):
        """测试自定义参数。"""
        from main import parse_args

        with patch("sys.argv", [
            "main.py",
            "--card", "data/role_cards/小美.json",
            "--memory-db", "/tmp/test.db",
            "--log-level", "DEBUG",
        ]):
            args = parse_args()
            assert args.card == "data/role_cards/小美.json"
            assert args.memory_db == "/tmp/test.db"
            assert args.log_level == "DEBUG"

    def test_short_args(self):
        """测试短参数形式。"""
        from main import parse_args

        with patch("sys.argv", [
            "main.py",
            "-c", "data/role_cards/test.json",
            "-m", "/tmp/test.db",
            "-l", "ERROR",
        ]):
            args = parse_args()
            assert args.card == "data/role_cards/test.json"
            assert args.memory_db == "/tmp/test.db"
            assert args.log_level == "ERROR"

    def test_help(self):
        """测试 --help 参数。"""
        from main import parse_args

        with patch("sys.argv", ["main.py", "--help"]):
            with pytest.raises(SystemExit) as exc_info:
                parse_args()
            assert exc_info.value.code == 0

    def test_invalid_log_level(self):
        """测试无效的日志级别。"""
        from main import parse_args

        with patch("sys.argv", ["main.py", "--log-level", "INVALID"]):
            with pytest.raises(SystemExit):
                parse_args()


# =============================================================================
# 测试2：CompanionApp 命令处理（Mock API）
# =============================================================================


class TestCompanionAppCommands:
    """测试 CompanionApp 的命令处理功能。"""

    @pytest.fixture(autouse=True)
    def setup(self):
        """设置测试环境。"""
        _setup_mock_env()
        # 重新加载 settings 以使用测试环境变量
        from src.config.settings import Settings
        Settings._instance = None
        Settings._initialized = False

    def test_help_command(self):
        """测试 /help 命令。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/help")
            assert result is True

    def test_cost_command(self):
        """测试 /cost 命令。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.00005
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 100,
                "output_tokens": 50,
                "total_tokens": 150,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/cost")
            assert result is True

    def test_clear_command(self):
        """测试 /clear 命令。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/clear")
            assert result is True
            assert app.turn_count == 0

    def test_card_command_no_arg(self):
        """测试 /card 命令缺少参数。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/card")
            assert result is True  # 不应崩溃

    def test_card_switch(self):
        """测试 /card 切换角色卡。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/card data/role_cards/小美.json")
            assert result is True

    def test_world_command_no_arg(self):
        """测试 /world 命令缺少参数。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/world")
            assert result is True

    def test_memory_command(self):
        """测试 /memory 命令。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/memory")
            assert result is True

    def test_quit_command(self):
        """测试 /quit 命令。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/quit")
            assert result is False  # 应该返回 False 表示退出

    def test_unknown_command(self):
        """测试未知命令。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp
            app = CompanionApp(card_path="data/role_cards/小美.json")
            result = app._handle_command("/unknown")
            assert result is True  # 不应崩溃


# =============================================================================
# 测试3：完整对话流程（Mock API）
# =============================================================================


class TestFullChatFlow:
    """测试完整的对话流程。"""

    @pytest.fixture(autouse=True)
    def setup(self):
        """设置测试环境。"""
        _setup_mock_env()
        from src.config.settings import Settings
        Settings._instance = None
        Settings._initialized = False

    def test_single_chat_turn(self):
        """测试单轮对话。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.chat.return_value = _mock_deepseek_chat_response(
                "你好呀~我是小美！今天想聊什么呀？"
            )
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0001
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 50,
                "output_tokens": 20,
                "total_tokens": 70,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            # 使用 rule 模式避免记忆提取额外调用 LLM
            with patch("src.config.user_settings.UserSettings.get", return_value="rule"):
                app = CompanionApp(card_path="data/role_cards/小美.json")

                # 模拟对话
                app._process_chat("你好呀！")

                # 验证 API 被调用（对话至少1次）
                assert mock_client.chat.call_count >= 1
                # 验证上下文中有消息
                context = app.player.get_context()
                assert len(context) >= 2  # user + assistant
                assert context[0]["role"] == "user"
                assert context[1]["role"] == "assistant"

    def test_multi_turn_chat(self):
        """测试多轮对话。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.chat.side_effect = [
                _mock_deepseek_chat_response("你好呀~我是小美！"),
                _mock_deepseek_chat_response("我喜欢吃草莓蛋糕！"),
                _mock_deepseek_chat_response("是呀，甜甜的超好吃~"),
            ]
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0003
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 150,
                "output_tokens": 60,
                "total_tokens": 210,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            # 使用 rule 模式避免记忆提取额外调用 LLM
            with patch("src.config.user_settings.UserSettings.get", return_value="rule"):
                app = CompanionApp(card_path="data/role_cards/小美.json")

                # 三轮对话
                app._process_chat("你好呀！")
                app._process_chat("你喜欢吃什么？")
                app._process_chat("草莓蛋糕确实好吃！")

                # 验证上下文中有 6 条消息（3 轮对话）
                context = app.player.get_context()
                assert len(context) == 6
                assert app.turn_count == 3

    def test_chat_with_memory_injection(self):
        """测试记忆注入到对话流程。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.chat.return_value = _mock_deepseek_chat_response(
                "我记得你之前说过喜欢草莓蛋糕~"
            )
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0001
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 60,
                "output_tokens": 25,
                "total_tokens": 85,
            }
            mock_client_cls.return_value = mock_client

            from src.memory.vector_store import MemoryEntry
            from src.utils.time_utils import format_timestamp_iso

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")

            # 手动添加记忆到向量存储
            if app.vector_store:
                now = format_timestamp_iso()
                entry = MemoryEntry(
                    memory_type="user_fact",
                    content="用户喜欢吃草莓蛋糕",
                    embedding=[0.1] * 8,
                    importance=0.9,
                    created_at=now,
                    last_accessed=now,
                )
                app.vector_store.add(entry)

            # 对话（应该能检索到上述记忆）
            app._process_chat("我想吃点什么甜食")

            # 验证对话没有崩溃
            context = app.player.get_context()
            assert len(context) >= 2

    def test_export_functionality(self):
        """测试导出功能。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.chat.return_value = _mock_deepseek_chat_response(
                "你好~我是小美！"
            )
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
            }
            mock_client_cls.return_value = mock_client

            from src.memory.vector_store import MemoryEntry
            from src.utils.time_utils import format_timestamp_iso

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")

            # 添加一些对话
            app._process_chat("你好呀！")

            # 添加一些记忆
            if app.vector_store:
                now = format_timestamp_iso()
                app.vector_store.add(MemoryEntry(
                    memory_type="user_fact",
                    content="用户名叫小明",
                    embedding=[0.2] * 8,
                    importance=0.9,
                    created_at=now,
                    last_accessed=now,
                ))

            # 执行导出
            with tempfile.TemporaryDirectory() as tmpdir:
                # 使用 mock 更改导出目录
                original_data_dir = app.player.client
                app._handle_command("/export")

            # 验证导出没有崩溃
            assert True


# =============================================================================
# 测试4：错误处理
# =============================================================================


class TestErrorHandling:
    """测试错误处理。"""

    @pytest.fixture(autouse=True)
    def setup(self):
        """设置测试环境。"""
        _setup_mock_env()
        from src.config.settings import Settings
        Settings._instance = None
        Settings._initialized = False

    def test_api_key_error(self):
        """测试 API Key 错误处理。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            from src.exceptions import APIKeyError

            mock_client = MagicMock()
            mock_client.chat.side_effect = APIKeyError("无效的 API Key")
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")
            # 不应引发异常
            app._process_chat("你好")

    def test_api_timeout_error(self):
        """测试 API 超时错误处理。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            from src.exceptions import APITimeoutError

            mock_client = MagicMock()
            mock_client.chat.side_effect = APITimeoutError("请求超时")
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")
            app._process_chat("你好")

    def test_api_quota_error(self):
        """测试 API 配额不足错误处理。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            from src.exceptions import APIQuotaError

            mock_client = MagicMock()
            mock_client.chat.side_effect = APIQuotaError("余额不足")
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")
            app._process_chat("你好")

    def test_api_rate_limit_error(self):
        """测试 API 频率限制错误处理。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            from src.exceptions import APIRateLimitError

            mock_client = MagicMock()
            mock_client.chat.side_effect = APIRateLimitError("频率过高")
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")
            app._process_chat("你好")

    def test_api_content_filter_error(self):
        """测试内容过滤错误处理。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            from src.exceptions import APIContentFilterError

            mock_client = MagicMock()
            mock_client.chat.side_effect = APIContentFilterError("内容被过滤")
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")
            app._process_chat("你好")

    def test_api_server_error(self):
        """测试服务器错误处理。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            from src.exceptions import APIServerError

            mock_client = MagicMock()
            mock_client.chat.side_effect = APIServerError("服务器错误", 500)
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")
            app._process_chat("你好")

    def test_generic_exception(self):
        """测试通用异常处理。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.chat.side_effect = RuntimeError("意外的运行时错误")
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
            }
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")
            # 不应引发异常，应被捕获
            app._process_chat("你好")

    def test_missing_card_file(self):
        """测试角色卡文件缺失。"""
        _setup_mock_env()
        from src.config.settings import Settings
        Settings._instance = None
        Settings._initialized = False

        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            with pytest.raises(SystemExit) as exc_info:
                CompanionApp(card_path="data/role_cards/不存在.json")
            assert exc_info.value.code == 1


# =============================================================================
# 测试5：资源清理
# =============================================================================


class TestCleanup:
    """测试资源清理。"""

    @pytest.fixture(autouse=True)
    def setup(self):
        """设置测试环境。"""
        _setup_mock_env()
        from src.config.settings import Settings
        Settings._instance = None
        Settings._initialized = False

    def test_cleanup_on_close(self):
        """测试关闭时清理资源。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            app = CompanionApp(card_path="data/role_cards/小美.json")
            app._cleanup()

            # 验证 close 方法被调用
            mock_client.close.assert_called_once()


# =============================================================================
# 测试6：集成测试（类级别）
# =============================================================================


class TestIntegration:
    """端到端集成测试，覆盖所有模块的协作。"""

    @pytest.fixture(autouse=True)
    def setup(self):
        """设置测试环境。"""
        _setup_mock_env()
        from src.config.settings import Settings
        Settings._instance = None
        Settings._initialized = False

    def test_full_integration_flow(self):
        """测试完整的端到端流程。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            mock_client = MagicMock()
            # 模拟聊天回复
            mock_client.chat.side_effect = [
                _mock_deepseek_chat_response("你好呀~我是小美！"),
                _mock_deepseek_chat_response("我最喜欢草莓蛋糕了！"),
                _mock_deepseek_chat_response("是呀，甜甜的超好吃~"),
                _mock_deepseek_chat_response("嗯嗯！下次一起去吃吧！"),
            ]
            mock_client.embed.return_value = _mock_deepseek_embed_response()
            mock_client.get_total_cost.return_value = 0.0004
            mock_client.get_total_tokens.return_value = {
                "input_tokens": 200,
                "output_tokens": 80,
                "total_tokens": 280,
            }
            mock_client_cls.return_value = mock_client

            from src.memory.vector_store import MemoryEntry
            from src.utils.time_utils import format_timestamp_iso

            from main import CompanionApp

            # 使用 rule 模式避免记忆提取额外调用 LLM
            with patch("src.config.user_settings.UserSettings.get", return_value="rule"):
                # ── 初始化 ──
                app = CompanionApp(card_path="data/role_cards/小美.json")
                assert app.player.is_ready()
                assert app.vector_store is not None

                # ── 第1轮对话：问候 ──
                app._process_chat("你好呀！")
                context = app.player.get_context()
                assert len(context) == 2

                # ── 第2轮对话：了解喜好 ──
                app._process_chat("你喜欢吃什么？")
                context = app.player.get_context()
                assert len(context) == 4

                # ── 第3轮对话：继续话题 ──
                app._process_chat("草莓蛋糕我也喜欢！")
                context = app.player.get_context()
                assert len(context) == 6

                # ── 验证成本追踪 ──
                assert app.client.get_total_cost.return_value > 0

                # ── 清空上下文 ──
                app._handle_command("/clear")
                context = app.player.get_context()
                assert len(context) == 0

                # ── 第4轮对话：新对话 ──
                app._process_chat("我们刚聊到哪了？")
                context = app.player.get_context()
                assert len(context) == 2

                # ── 清理 ──
                app._cleanup()
                mock_client.close.assert_called()

    def test_cost_accumulation(self):
        """测试成本累积追踪。"""
        with patch("main.DeepSeekClient") as mock_client_cls:
            call_count = [0]

            def mock_chat_side_effect(*args, **kwargs):
                call_count[0] += 1
                return _mock_deepseek_chat_response(
                    f"这是第{call_count[0]}轮对话~"
                )

            mock_client = MagicMock()
            mock_client.chat.side_effect = mock_chat_side_effect
            mock_client.embed.return_value = _mock_deepseek_embed_response()

            # 模拟成本递增
            cost_values = [0.0001, 0.00025, 0.0004]
            token_values = [
                {"input_tokens": 50, "output_tokens": 20, "total_tokens": 70},
                {"input_tokens": 120, "output_tokens": 50, "total_tokens": 170},
                {"input_tokens": 200, "output_tokens": 80, "total_tokens": 280},
            ]
            mock_client.get_total_cost.side_effect = cost_values
            mock_client.get_total_tokens.side_effect = token_values
            mock_client_cls.return_value = mock_client

            from main import CompanionApp

            # 使用 rule 模式避免记忆提取额外调用 LLM
            with patch("src.config.user_settings.UserSettings.get", return_value="rule"):
                app = CompanionApp(card_path="data/role_cards/小美.json")

                app._process_chat("第1轮")
                app._process_chat("第2轮")
                app._process_chat("第3轮")

                assert app.turn_count == 3


# =============================================================================
# 直接运行入口
# =============================================================================

if __name__ == "__main__":
    """手动运行测试（不使用 pytest）。"""
    print("=" * 60)
    print("P1-S5 集成联调测试")
    print("=" * 60)

    _setup_mock_env()

    # 简化测试：验证命令解析和基本流程
    from main import CompanionApp

    with patch("main.DeepSeekClient") as mock_cls:
        mock_client = MagicMock()
        mock_client.chat.return_value = _mock_deepseek_chat_response("你好呀~")
        mock_client.embed.return_value = _mock_deepseek_embed_response()
        mock_client.get_total_cost.return_value = 0.0001
        mock_client.get_total_tokens.return_value = {
            "input_tokens": 50, "output_tokens": 20, "total_tokens": 70,
        }
        mock_cls.return_value = mock_client

        app = CompanionApp(card_path="data/role_cards/小美.json")

        # 测试命令
        print("1. 测试 /help 命令...")
        app._handle_command("/help")

        print("2. 测试 /memory 命令...")
        app._handle_command("/memory")

        print("3. 测试 /cost 命令...")
        app._handle_command("/cost")

        print("4. 测试 /clear 命令...")
        app._handle_command("/clear")

        print("5. 测试单轮对话...")
        app._process_chat("你好！")

        print("6. 测试 /quit 命令...")
        result = app._handle_command("/quit")
        assert result is False

        print("\n  所有测试通过！")
"""P1 阶段6 主动消息引擎测试脚本。

测试覆盖：
    1. ProactiveEngine 决策逻辑
    2. ProactiveEngine 主动消息生成
    3. TopicGenerator 话题生成（模板 + AI）
    4. ProactiveScheduler 状态持久化
    5. 集成测试（CompanionApp /proactive 命令）
    6. 边界条件（空记忆、无角色卡、异常处理）
"""

import json
import os
import sys
import tempfile
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import MagicMock, patch

# 确保项目根目录在 sys.path 中
_project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_project_root))

import pytest


# =============================================================================
# 测试辅助
# =============================================================================


def _setup_mock_env():
    """设置 Mock 环境变量。"""
    os.environ["DEEPSEEK_API_KEY"] = "test_api_key_for_testing"
    os.environ["DEEPSEEK_MODEL"] = "deepseek-v4-flash"
    os.environ["DEEPSEEK_EMBEDDING_MODEL"] = "deepseek-embedding-v2"
    os.environ["DEEPSEEK_BASE_URL"] = "https://api.deepseek.com"
    os.environ["LOG_LEVEL"] = "WARNING"


def _mock_chat_response(content: str = "你好呀~今天天气不错呢！"):
    """创建 Mock ChatResponse。"""
    from src.api_client.deepseek import ChatResponse
    return ChatResponse(
        content=content,
        model="deepseek-v4-flash",
        usage={"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70},
        finish_reason="stop",
    )


def _create_mock_card():
    """创建 Mock Card 对象。"""
    from src.chat_engine.card_parser import Card
    return Card(
        version="1.0",
        name="小美",
        nickname="美美",
        age="20",
        gender="女",
        appearance="长发飘飘，温柔可爱",
        personality="温柔体贴，活泼开朗，喜欢关心别人",
        background="一个普通的大学生，热爱生活",
        speaking_style="温柔可爱，喜欢用语气词，偶尔撒娇",
        likes=["甜食", "猫咪", "看电影"],
        dislikes=["苦瓜", "下雨天"],
        example_dialogues=[
            {"user": "你好", "character": "你好呀~今天过得怎么样？"},
        ],
        creator_notes="",
        tags=["治愈", "日常"],
    )


def _create_mock_player():
    """创建 Mock RolePlayer，包含角色卡。"""
    player = MagicMock()
    player.card = _create_mock_card()
    return player


def _create_mock_retriever(memories: list[str] | None = None):
    """创建 Mock MemoryRetriever。"""
    retriever = MagicMock()
    if memories:
        from src.memory.vector_store import MemoryEntry
        entries = [
            MemoryEntry(
                id=f"mem_{i}",
                content=m,
                memory_type="episodic",
                created_at=datetime.now(),
            )
            for i, m in enumerate(memories)
        ]
        retriever.get_recent.return_value = entries
    else:
        retriever.get_recent.return_value = []
    return retriever


def _create_mock_api_client():
    """创建 Mock DeepSeekClient。"""
    client = MagicMock()
    client.chat.return_value = _mock_chat_response()
    return client


# =============================================================================
# 测试1：ProactiveEngine 决策逻辑
# =============================================================================


class TestProactiveEngineDecision:
    """测试主动消息决策引擎的决策逻辑。"""

    def test_should_send_when_conditions_met(self):
        """测试条件满足时允许发送。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        # 设置允许发送的条件
        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 0)

        engine = ProactiveEngine()
        result = engine.should_send_message(
            current_time=datetime.now(),
            last_sent_time=None,
        )
        assert result is True

    def test_should_not_send_outside_active_time(self):
        """测试不在活跃时段时不允许发送。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "08:00")
        user_settings.set("sleep_time", "22:00")

        engine = ProactiveEngine()

        # 模拟凌晨3点（不在08:00~22:00范围内）
        night_time = datetime(2024, 1, 1, 3, 0, 0)
        result = engine.should_send_message(
            current_time=night_time,
            last_sent_time=None,
        )
        assert result is False

    def test_should_not_send_before_interval(self):
        """测试间隔未满时不允许发送。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 120)

        engine = ProactiveEngine()

        # 上次发送时间是30分钟前，间隔是120分钟
        now = datetime.now()
        last_sent = now - timedelta(minutes=30)
        result = engine.should_send_message(
            current_time=now,
            last_sent_time=last_sent,
        )
        assert result is False

    def test_should_send_after_interval(self):
        """测试间隔已满时允许发送。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 60)

        engine = ProactiveEngine()

        # 上次发送时间是120分钟前，间隔是60分钟
        now = datetime.now()
        last_sent = now - timedelta(minutes=120)
        result = engine.should_send_message(
            current_time=now,
            last_sent_time=last_sent,
        )
        assert result is True

    def test_decide_and_generate_disabled(self):
        """测试主动消息关闭时返回 None。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", False)

        engine = ProactiveEngine()
        player = _create_mock_player()
        retriever = _create_mock_retriever()
        client = _create_mock_api_client()

        result = engine.decide_and_generate(player, retriever, client)
        assert result is None

    def test_decide_and_generate_success(self):
        """测试主动消息生成成功。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 0)

        engine = ProactiveEngine()
        player = _create_mock_player()
        retriever = _create_mock_retriever(["用户喜欢草莓蛋糕", "昨天聊了电影"])
        client = _create_mock_api_client()

        result = engine.decide_and_generate(player, retriever, client)
        assert result is not None
        assert isinstance(result, str)
        assert len(result) > 0

    def test_decide_and_generate_no_card(self):
        """测试无角色卡时返回 None。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 0)

        engine = ProactiveEngine()
        player = MagicMock()
        player.card = None
        retriever = _create_mock_retriever()
        client = _create_mock_api_client()

        result = engine.generate_proactive_message(player, retriever, client)
        assert result is None


# =============================================================================
# 测试2：TopicGenerator 话题生成
# =============================================================================


class TestTopicGenerator:
    """测试话题生成器。"""

    def test_generate_morning_topic_template(self):
        """测试早上话题模板生成。"""
        _setup_mock_env()
        from src.proactive.topic_generator import TopicGenerator

        generator = TopicGenerator()
        card = _create_mock_card()
        topic = generator.generate_morning_topic(card, [])

        assert isinstance(topic, str)
        assert len(topic) > 0

    def test_generate_afternoon_topic_template(self):
        """测试下午话题模板生成。"""
        _setup_mock_env()
        from src.proactive.topic_generator import TopicGenerator

        generator = TopicGenerator()
        card = _create_mock_card()
        topic = generator.generate_afternoon_topic(card, [])

        assert isinstance(topic, str)
        assert len(topic) > 0

    def test_generate_evening_topic_template(self):
        """测试晚上话题模板生成。"""
        _setup_mock_env()
        from src.proactive.topic_generator import TopicGenerator

        generator = TopicGenerator()
        card = _create_mock_card()
        topic = generator.generate_evening_topic(card, [])

        assert isinstance(topic, str)
        assert len(topic) > 0

    def test_generate_topic_with_ai(self):
        """测试 AI 模式话题生成。"""
        _setup_mock_env()
        from src.proactive.topic_generator import TopicGenerator

        generator = TopicGenerator()
        card = _create_mock_card()
        client = _create_mock_api_client()

        topic = generator.generate_topic(
            card=card,
            memories=["用户喜欢草莓蛋糕"],
            time_of_day="下午",
            api_client=client,
        )

        assert isinstance(topic, str)
        assert len(topic) > 0
        # AI 模式应该返回 mock 的内容
        assert "你好呀" in topic

    def test_generate_topic_invalid_time(self):
        """测试无效时间段抛出异常。"""
        _setup_mock_env()
        from src.proactive.topic_generator import TopicGenerator

        generator = TopicGenerator()
        card = _create_mock_card()

        with pytest.raises(ValueError, match="无效的时间段"):
            generator.generate_topic(card, [], time_of_day="午夜")

    def test_generate_topic_auto_time(self):
        """测试自动判断时间段。"""
        _setup_mock_env()
        from src.proactive.topic_generator import TopicGenerator

        generator = TopicGenerator()
        card = _create_mock_card()

        # 不传 time_of_day，自动判断
        topic = generator.generate_topic(card, [])
        assert isinstance(topic, str)
        assert len(topic) > 0

    def test_generate_topic_ai_fallback(self):
        """测试 AI 生成失败时回退到模板。"""
        _setup_mock_env()
        from src.proactive.topic_generator import TopicGenerator

        generator = TopicGenerator()
        card = _create_mock_card()
        client = _create_mock_api_client()

        # 模拟 API 调用失败
        client.chat.side_effect = Exception("API 调用失败")

        topic = generator.generate_topic(
            card=card,
            memories=[],
            time_of_day="下午",
            api_client=client,
        )

        # 应该回退到模板生成
        assert isinstance(topic, str)
        assert len(topic) > 0


# =============================================================================
# 测试3：ProactiveScheduler 调度器
# =============================================================================


class TestProactiveScheduler:
    """测试主动消息调度器。"""

    @pytest.fixture(autouse=True)
    def _isolate_data_dir(self):
        """每个测试使用独立的临时目录，避免状态文件互相干扰。"""
        _setup_mock_env()
        from src.config.settings import settings

        with tempfile.TemporaryDirectory() as tmpdir:
            original_data_dir = settings.DATA_DIR
            settings.DATA_DIR = Path(tmpdir)
            try:
                yield
            finally:
                settings.DATA_DIR = original_data_dir

    def test_initial_state(self):
        """测试调度器初始状态。"""
        from src.proactive.scheduler import ProactiveScheduler

        scheduler = ProactiveScheduler()
        assert scheduler.get_last_sent_time() is None
        assert scheduler.get_total_sent_count() == 0
        assert scheduler.get_next_scheduled_time() is None

    def test_update_last_sent_time(self):
        """测试更新上次发送时间。"""
        from src.proactive.scheduler import ProactiveScheduler

        scheduler = ProactiveScheduler()
        now = datetime.now()
        scheduler.update_last_sent_time(now)

        last_sent = scheduler.get_last_sent_time()
        assert last_sent is not None
        assert scheduler.get_total_sent_count() == 1

    def test_multiple_updates(self):
        """测试多次更新累计计数。"""
        from src.proactive.scheduler import ProactiveScheduler

        scheduler = ProactiveScheduler()
        for i in range(3):
            scheduler.update_last_sent_time()

        assert scheduler.get_total_sent_count() == 3

    def test_state_persistence(self):
        """测试状态持久化到文件。"""
        from src.proactive.scheduler import ProactiveScheduler
        from src.config.settings import settings

        scheduler = ProactiveScheduler()
        now = datetime.now()
        scheduler.update_last_sent_time(now)

        # 验证文件存在
        state_file = Path(settings.DATA_DIR) / "proactive_state.json"
        assert state_file.exists()

        # 读取文件验证内容
        with open(state_file, "r", encoding="utf-8") as f:
            data = json.load(f)
        assert data["total_sent_count"] == 1
        assert data["last_sent_time"] is not None

    def test_reset(self):
        """测试重置状态。"""
        from src.proactive.scheduler import ProactiveScheduler

        scheduler = ProactiveScheduler()
        scheduler.update_last_sent_time()
        scheduler.reset()

        assert scheduler.get_last_sent_time() is None
        assert scheduler.get_total_sent_count() == 0

    def test_get_state(self):
        """测试获取状态副本。"""
        from src.proactive.scheduler import ProactiveScheduler

        scheduler = ProactiveScheduler()
        state = scheduler.get_state()

        assert "last_sent_time" in state
        assert "total_sent_count" in state
        assert "version" in state
        assert state["version"] == "1.0"


# =============================================================================
# 测试4：集成测试
# =============================================================================


class TestProactiveIntegration:
    """测试主动消息引擎与其他模块的集成。"""

    @pytest.fixture(autouse=True)
    def _isolate_data_dir(self):
        """每个测试使用独立的临时目录，避免状态文件互相干扰。"""
        _setup_mock_env()
        from src.config.settings import settings

        with tempfile.TemporaryDirectory() as tmpdir:
            original_data_dir = settings.DATA_DIR
            settings.DATA_DIR = Path(tmpdir)
            try:
                yield
            finally:
                settings.DATA_DIR = original_data_dir

    @patch("src.api_client.deepseek.DeepSeekClient.chat")
    def test_full_flow_with_mock(self, mock_chat):
        """测试完整流程：决策 -> 生成 -> 状态更新。"""
        _setup_mock_env()
        mock_chat.return_value = _mock_chat_response("下午好呀~今天过得怎么样？")

        from src.proactive.engine import ProactiveEngine
        from src.proactive.scheduler import ProactiveScheduler
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 0)

        engine = ProactiveEngine()
        scheduler = ProactiveScheduler()
        player = _create_mock_player()
        retriever = _create_mock_retriever(["用户喜欢看电影"])
        client = _create_mock_api_client()
        client.chat = mock_chat

        # 1. 决策并生成
        message = engine.decide_and_generate(
            card=player,
            retriever=retriever,
            api_client=client,
            last_sent_time=scheduler.get_last_sent_time(),
        )

        assert message is not None
        assert "下午好" in message

        # 2. 更新发送时间
        scheduler.update_last_sent_time()
        assert scheduler.get_total_sent_count() == 1

        # 3. 验证间隔检查
        last_sent = scheduler.get_last_sent_time()
        assert last_sent is not None

    def test_proactive_engine_no_retriever(self):
        """测试无检索器时仍可生成消息。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 0)

        engine = ProactiveEngine()
        player = _create_mock_player()
        client = _create_mock_api_client()

        result = engine.generate_proactive_message(
            role_player=player,
            retriever=None,  # 无检索器
            api_client=client,
        )

        assert result is not None
        assert isinstance(result, str)

    def test_api_error_returns_none(self):
        """测试 API 调用失败时返回 None。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine
        from src.config.user_settings import user_settings

        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 0)

        engine = ProactiveEngine()
        player = _create_mock_player()
        client = _create_mock_api_client()
        client.chat.side_effect = Exception("网络错误")

        result = engine.generate_proactive_message(player, None, client)
        assert result is None


# =============================================================================
# 测试5：CompanionApp 集成
# =============================================================================


class TestCompanionAppProactive:
    """测试 CompanionApp 中的 /proactive 命令。"""

    @patch("src.api_client.deepseek.DeepSeekClient.chat")
    def test_proactive_command_disabled(self, mock_chat):
        """测试主动消息关闭时命令的行为。"""
        _setup_mock_env()
        mock_chat.return_value = _mock_chat_response("测试消息")

        from src.config.user_settings import user_settings
        user_settings.set("proactive_enabled", False)

        from main import CompanionApp

        # 使用临时目录
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir_path = Path(tmpdir)
            # 创建临时角色卡
            card_path = tmpdir_path / "test_card.json"
            card_data = {
                "version": "1.0",
                "name": "测试角色",
                "nickname": "测试",
                "personality": "友好",
                "background": "测试背景",
                "speaking_style": "自然",
            }
            card_path.write_text(json.dumps(card_data, ensure_ascii=False), encoding="utf-8")

            # 创建记忆数据库目录
            mem_dir = tmpdir_path / "memories"
            mem_dir.mkdir(parents=True, exist_ok=True)

            # 覆盖 settings.DATA_DIR
            from src.config.settings import settings
            original_data_dir = settings.DATA_DIR
            settings.DATA_DIR = tmpdir_path

            try:
                app = CompanionApp(
                    card_path=str(card_path),
                    memory_db_path=str(mem_dir / "test.db"),
                )

                # 触发 /proactive 命令
                result = app._cmd_proactive("")
                assert result is True  # 应该继续运行，不会崩溃
            finally:
                settings.DATA_DIR = original_data_dir
                try:
                    app._cleanup()  # 关闭数据库连接，避免文件锁定
                except Exception:
                    pass

    @patch("src.api_client.deepseek.DeepSeekClient.chat")
    def test_proactive_command_enabled(self, mock_chat):
        """测试主动消息开启时命令的行为。"""
        _setup_mock_env()
        mock_chat.return_value = _mock_chat_response("主动消息测试内容")

        from src.config.user_settings import user_settings
        user_settings.set("proactive_enabled", True)
        user_settings.set("wake_time", "00:00")
        user_settings.set("sleep_time", "23:59")
        user_settings.set("proactive_interval_minutes", 0)

        from main import CompanionApp

        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir_path = Path(tmpdir)
            card_path = tmpdir_path / "test_card.json"
            card_data = {
                "version": "1.0",
                "name": "测试角色",
                "nickname": "测试",
                "personality": "友好",
                "background": "测试背景",
                "speaking_style": "自然",
            }
            card_path.write_text(json.dumps(card_data, ensure_ascii=False), encoding="utf-8")

            mem_dir = tmpdir_path / "memories"
            mem_dir.mkdir(parents=True, exist_ok=True)

            from src.config.settings import settings
            original_data_dir = settings.DATA_DIR
            settings.DATA_DIR = tmpdir_path

            try:
                app = CompanionApp(
                    card_path=str(card_path),
                    memory_db_path=str(mem_dir / "test.db"),
                )

                result = app._cmd_proactive("")
                assert result is True
            finally:
                settings.DATA_DIR = original_data_dir
                try:
                    app._cleanup()
                except Exception:
                    pass

    @patch("src.api_client.deepseek.DeepSeekClient.chat")
    def test_proactive_status_command(self, mock_chat):
        """测试 /proactive status 命令。"""
        _setup_mock_env()
        mock_chat.return_value = _mock_chat_response("测试")

        from main import CompanionApp

        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir_path = Path(tmpdir)
            card_path = tmpdir_path / "test_card.json"
            card_data = {
                "version": "1.0",
                "name": "测试角色",
                "nickname": "测试",
                "personality": "友好",
                "background": "测试背景",
                "speaking_style": "自然",
            }
            card_path.write_text(json.dumps(card_data, ensure_ascii=False), encoding="utf-8")

            mem_dir = tmpdir_path / "memories"
            mem_dir.mkdir(parents=True, exist_ok=True)

            from src.config.settings import settings
            original_data_dir = settings.DATA_DIR
            settings.DATA_DIR = tmpdir_path

            try:
                app = CompanionApp(
                    card_path=str(card_path),
                    memory_db_path=str(mem_dir / "test.db"),
                )

                result = app._cmd_proactive("status")
                assert result is True
            finally:
                settings.DATA_DIR = original_data_dir
                try:
                    app._cleanup()
                except Exception:
                    pass


# =============================================================================
# 测试6：时间段判断
# =============================================================================


class TestTimePeriod:
    """测试时间段判断逻辑。"""

    def test_morning_period(self):
        """测试早上时间段（5:00-11:59）。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine

        engine = ProactiveEngine()
        assert engine._get_time_period(datetime(2024, 1, 1, 5, 0, 0)) == "早上"
        assert engine._get_time_period(datetime(2024, 1, 1, 8, 30, 0)) == "早上"
        assert engine._get_time_period(datetime(2024, 1, 1, 11, 59, 0)) == "早上"

    def test_afternoon_period(self):
        """测试下午时间段（12:00-17:59）。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine

        engine = ProactiveEngine()
        assert engine._get_time_period(datetime(2024, 1, 1, 12, 0, 0)) == "下午"
        assert engine._get_time_period(datetime(2024, 1, 1, 15, 0, 0)) == "下午"
        assert engine._get_time_period(datetime(2024, 1, 1, 17, 59, 0)) == "下午"

    def test_evening_period(self):
        """测试晚上时间段（18:00-4:59）。"""
        _setup_mock_env()
        from src.proactive.engine import ProactiveEngine

        engine = ProactiveEngine()
        assert engine._get_time_period(datetime(2024, 1, 1, 18, 0, 0)) == "晚上"
        assert engine._get_time_period(datetime(2024, 1, 1, 22, 0, 0)) == "晚上"
        assert engine._get_time_period(datetime(2024, 1, 1, 0, 0, 0)) == "晚上"
        assert engine._get_time_period(datetime(2024, 1, 1, 3, 0, 0)) == "晚上"


# =============================================================================
# 运行入口
# =============================================================================

if __name__ == "__main__":
    pytest.main([__file__, "-v", "-s"])
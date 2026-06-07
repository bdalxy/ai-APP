"""AI 伴侣应用 - 命令行交互入口。

集成所有 P1 阶段模块，提供完整的角色扮演对话体验。

功能：
    - 对话式角色扮演（基于角色卡 + 世界书）
    - 长期记忆系统（自动提取 + 检索注入）
    - 命令行管理（角色卡切换、世界书加载、记忆统计、导出等）
    - 成本追踪（每次对话的 token 消耗和费用）

使用方式:
    python main.py                          # 默认加载小美角色卡
    python main.py --card data/role_cards/小美.json  # 指定角色卡
    python main.py --help                  # 显示帮助

依赖：
    - src.config: Settings, UserSettings, configure_logger
    - src.api_client: DeepSeekClient
    - src.chat_engine: RolePlayer, CardParser
    - src.memory: VectorStore, MemoryRetriever, MemoryExtractor, 导出函数
    - src.utils: configure_logger, format_timestamp
    - src.exceptions: 所有异常类
"""

import argparse
import sys
from pathlib import Path
from typing import Optional

from src.config.settings import settings
from src.config.user_settings import user_settings
from src.utils.logger import get_logger, configure_logger
from src.utils.time_utils import format_timestamp, format_timestamp_iso
from src.api_client.deepseek import DeepSeekClient
from src.chat_engine.role_player import RolePlayer, RolePlayerError
from src.chat_engine.card_parser import CardParseError
from src.memory.vector_store import VectorStore, MemoryEntry
from src.memory.retriever import MemoryRetriever
from src.memory.extractor import MemoryExtractor
from src.memory.exporter import export_chat_history, export_memories, export_full
from src.proactive import ProactiveEngine, ProactiveScheduler
from src.exceptions import (
    APIException,
    APIKeyError,
    APIQuotaError,
    APITimeoutError,
    APIRateLimitError,
    APIServerError,
    APIContentFilterError,
    MemoryException,
    MemoryNotFoundError,
    MemoryStorageError,
)


# =============================================================================
# 应用常量
# =============================================================================

# 默认角色卡路径
DEFAULT_CARD_PATH = "data/role_cards/小美.json"

# 记忆数据库路径
DEFAULT_MEMORY_DB_PATH = "data/memories/companion_memory.db"

# 导出目录
DEFAULT_EXPORT_DIR = "data/archives"

# 对话轮次 ID 前缀
TURN_ID_PREFIX = "turn"


def _print_wrapped(text: str, prefix: str = "", width: int = 56) -> None:
    """按指定宽度换行打印文本，自动在中文/英文单词边界处换行。

    Args:
        text: 要打印的文本。
        prefix: 每行前缀。
        width: 每行最大宽度（字符数）。
    """
    line = ""
    for char in text:
        line += char
        if len(line) >= width:
            print(f"{prefix}{line}")
            line = ""
    if line:
        print(f"{prefix}{line}")


class CompanionApp:
    """AI 伴侣应用主类。

    管理所有模块的生命周期和对话流程。

    Attributes:
        settings: 全局配置单例。
        user_settings: 用户设置管理器。
        client: DeepSeek API 客户端。
        player: 角色扮演对话引擎。
        vector_store: 向量存储（记忆数据库）。
        retriever: 记忆检索器。
        extractor: 记忆提取器。
        turn_count: 当前会话对话轮次计数。
    """

    def __init__(
        self,
        card_path: str | None = None,
        memory_db_path: str | None = None,
    ) -> None:
        """初始化 AI 伴侣应用。

        Args:
            card_path: 角色卡文件路径，为 None 时使用默认路径。
            memory_db_path: 记忆数据库路径，为 None 时使用默认路径。
        """
        self._log = get_logger()
        self._log.info("=" * 60)
        self._log.info("AI 伴侣应用启动中...")
        self._log.info("=" * 60)

        # ── 1. 配置验证 ──
        self._validate_config()

        # ── 2. 初始化 API 客户端 ──
        self.client: DeepSeekClient = DeepSeekClient()
        self._log.info("DeepSeek API 客户端已就绪")

        # ── 3. 初始化角色扮演引擎 ──
        self.player: RolePlayer = RolePlayer(
            client=self.client,
            max_context_tokens=4000,
            temperature=0.9,
            max_tokens=2000,
        )

        # ── 4. 初始化记忆系统 ──
        self._init_memory_system(memory_db_path)

        # ── 5. 初始化主动消息引擎 ──
        self._init_proactive_engine()

        # ── 6. 加载角色卡 ──
        self._load_card(card_path or DEFAULT_CARD_PATH)

        # ── 7. 加载世界书 ──
        self._load_world_book()

        # ── 状态 ──
        self.turn_count: int = 0
        self._log.info("AI 伴侣应用初始化完成，可以开始对话！")

    # -------------------------------------------------------------------------
    # 初始化辅助方法
    # -------------------------------------------------------------------------

    def _validate_config(self) -> None:
        """验证配置是否完整，缺失时给出友好提示并退出。"""
        if not settings.validate():
            print("\n" + "=" * 60)
            print("  [错误] DeepSeek API Key 未配置！")
            print("=" * 60)
            print()
            print("  请按以下步骤配置：")
            print(f"  1. 复制 .env.example 为 .env")
            print(f"  2. 编辑 .env 文件，填入你的 API Key")
            print(f"  3. 重新运行程序")
            print()
            print("=" * 60 + "\n")
            sys.exit(1)

    def _init_memory_system(self, memory_db_path: str | None) -> None:
        """初始化记忆系统（向量存储、检索器、提取器）。

        Args:
            memory_db_path: 记忆数据库路径。
        """
        # 确定数据库路径
        if memory_db_path is None:
            db_path = settings.DATA_DIR / "memories" / "companion_memory.db"
        else:
            db_path = Path(memory_db_path)

        # 确保目录存在
        db_path.parent.mkdir(parents=True, exist_ok=True)

        try:
            self.vector_store = VectorStore(db_path=str(db_path))
            self.retriever = MemoryRetriever(
                vector_store=self.vector_store,
                deepseek_client=self.client,
            )
            self.extractor = MemoryExtractor(
                deepseek_client=self.client,
                vector_store=self.vector_store,
            )
            self._log.info(f"记忆系统已就绪: db={db_path}, 记忆数={self.vector_store.count()}")
        except Exception as e:
            self._log.error(f"记忆系统初始化失败: {e}")
            self.vector_store = None  # type: ignore[assignment]
            self.retriever = None  # type: ignore[assignment]
            self.extractor = None  # type: ignore[assignment]
            print(f"  [警告] 记忆系统初始化失败: {e}，将跳过记忆功能")

    def _init_proactive_engine(self) -> None:
        """初始化主动消息引擎（决策器 + 调度器）。"""
        try:
            self.proactive_engine = ProactiveEngine()
            self.proactive_scheduler = ProactiveScheduler()
            self._log.info("主动消息引擎已就绪")
        except Exception as e:
            self._log.error(f"主动消息引擎初始化失败: {e}")
            self.proactive_engine = None  # type: ignore[assignment]
            self.proactive_scheduler = None  # type: ignore[assignment]
            print(f"  [警告] 主动消息引擎初始化失败: {e}")

    def _load_card(self, card_path: str) -> None:
        """加载角色卡。

        Args:
            card_path: 角色卡文件路径。
        """
        try:
            card = self.player.load_card(card_path)
            print(f"  [角色卡] 已加载: {card.name}")
            if card.nickname:
                print(f"           昵称: {card.nickname}")
            print(f"           性格: {card.personality[:50]}...")
            print()
        except FileNotFoundError:
            self._log.error(f"角色卡文件不存在: {card_path}")
            print(f"  [错误] 角色卡文件不存在: {card_path}")
            print(f"         请确认文件路径正确。")
            sys.exit(1)
        except CardParseError as e:
            self._log.error(f"角色卡解析失败: {e}")
            print(f"  [错误] 角色卡解析失败: {e}")
            sys.exit(1)

    def _load_world_book(self) -> None:
        """加载用户设置中指定的世界书。"""
        wb_path = user_settings.get("selected_world_book")
        if wb_path and wb_path != "reality_world":
            path = Path(wb_path)
            if path.exists():
                try:
                    entries = self.player.load_world_book(str(path))
                    print(f"  [世界书] 已加载: {len(entries)} 条\n")
                except Exception as e:
                    self._log.warning(f"世界书加载失败: {e}")
                    print(f"  [警告] 世界书加载失败: {e}\n")

    # -------------------------------------------------------------------------
    # 命令处理
    # -------------------------------------------------------------------------

    def _handle_command(self, raw_input: str) -> bool:
        """处理以 '/' 开头的命令。

        Args:
            raw_input: 用户原始输入。

        Returns:
            True 表示应用继续运行，False 表示退出。
        """
        parts = raw_input.strip().split(maxsplit=1)
        cmd = parts[0].lower()
        arg = parts[1] if len(parts) > 1 else ""

        handlers = {
            "/help": self._cmd_help,
            "/h": self._cmd_help,
            "/card": self._cmd_card,
            "/world": self._cmd_world,
            "/memory": self._cmd_memory,
            "/export": self._cmd_export,
            "/clear": self._cmd_clear,
            "/cost": self._cmd_cost,
            "/quit": self._cmd_quit,
            "/q": self._cmd_quit,
            "/proactive": self._cmd_proactive,
            "/pa": self._cmd_proactive,
        }

        handler = handlers.get(cmd)
        if handler:
            return handler(arg)
        else:
            print(f"  [未知命令] {cmd}，输入 /help 查看可用命令")
            return True

    def _cmd_help(self, arg: str) -> bool:
        """显示帮助信息。"""
        print()
        print("=" * 60)
        print("  AI 伴侣 - 命令行帮助")
        print("=" * 60)
        print()
        print("  基本使用:")
        print("    直接输入文字即可与角色对话")
        print()
        print("  可用命令:")
        print("    /help, /h        显示此帮助信息")
        print("    /card <路径>      切换角色卡")
        print("    /world <路径>     加载世界书")
        print("    /memory           显示记忆统计")
        print("    /export           导出聊天记录和记忆")
        print("    /clear            清空对话上下文")
        print("    /cost             显示 API 费用统计")
        print("    /quit, /q         退出程序")
        print("    /proactive, /pa   手动触发主动消息")
        print("    /proactive status 查看主动消息状态")
        print()
        print("  示例:")
        print("    /card data/role_cards/小美.json")
        print("    /world data/world_books/fantasy.json")
        print("    /export")
        print()
        print("=" * 60)
        print()
        return True

    def _cmd_card(self, arg: str) -> bool:
        """切换角色卡。

        Args:
            arg: 角色卡文件路径。
        """
        if not arg:
            print("  [用法] /card <角色卡文件路径>")
            return True

        try:
            self.player.clear_context()
            self.player.clear_memories()
            self._load_card(arg)
            self.turn_count = 0
            print("  [提示] 对话上下文已重置，开始与新角色对话吧！")
        except Exception as e:
            print(f"  [错误] 切换角色卡失败: {e}")
        return True

    def _cmd_world(self, arg: str) -> bool:
        """加载世界书。

        Args:
            arg: 世界书文件路径。
        """
        if not arg:
            print("  [用法] /world <世界书文件路径>")
            return True

        try:
            entries = self.player.load_world_book(arg)
            print(f"  [世界书] 已加载: {len(entries)} 条")
            for i, entry in enumerate(entries[:3], 1):
                preview = entry[:60] + "..." if len(entry) > 60 else entry
                print(f"    {i}. {preview}")
            if len(entries) > 3:
                print(f"    ... 共 {len(entries)} 条")
        except FileNotFoundError:
            print(f"  [错误] 世界书文件不存在: {arg}")
        except Exception as e:
            print(f"  [错误] 加载世界书失败: {e}")
        return True

    def _cmd_memory(self, arg: str) -> bool:
        """显示记忆统计信息。"""
        if self.vector_store is None:
            print("  [提示] 记忆系统未启用")
            return True

        try:
            total = self.vector_store.count()
            print()
            print(f"  [记忆统计] 总计: {total} 条记忆")
            print(f"             数据库: {self.vector_store.db_path}")

            if total > 0:
                # 按类型统计
                all_memories = self.vector_store.get_all()
                type_counts: dict[str, int] = {}
                for m in all_memories:
                    type_counts[m.memory_type] = type_counts.get(m.memory_type, 0) + 1

                print("  [类型分布]")
                for mem_type, count in sorted(type_counts.items()):
                    type_names = {
                        "user_fact": "用户事实",
                        "semantic": "语义知识",
                        "episodic": "事件记忆",
                    }
                    name = type_names.get(mem_type, mem_type)
                    print(f"    {name}: {count} 条")

                # 显示最近几条记忆
                print("  [最近记忆]")
                all_memories.sort(key=lambda m: m.created_at, reverse=True)
                for m in all_memories[:5]:
                    preview = m.content[:60] + "..." if len(m.content) > 60 else m.content
                    print(f"    [{m.memory_type}] {preview}")
            print()
        except Exception as e:
            print(f"  [错误] 获取记忆统计失败: {e}")
        return True

    def _cmd_export(self, arg: str) -> bool:
        """导出聊天记录和记忆。"""
        try:
            # 确保导出目录存在
            export_dir = settings.DATA_DIR / "archives"
            export_dir.mkdir(parents=True, exist_ok=True)

            timestamp = format_timestamp(fmt="%Y%m%d_%H%M%S")

            # 导出对话历史
            messages = self.player.get_context()
            if messages:
                chat_path = export_dir / f"chat_history_{timestamp}.json"
                export_chat_history(messages, chat_path)
                print(f"  [导出] 对话历史 -> {chat_path}")

            # 导出记忆
            if self.vector_store and self.vector_store.count() > 0:
                memories = self.vector_store.get_all()
                mem_path = export_dir / f"memories_{timestamp}.json"
                export_memories(memories, mem_path)
                print(f"  [导出] 长期记忆 -> {mem_path}")

            # 完整导出
            if messages or (self.vector_store and self.vector_store.count() > 0):
                full_path = export_dir / f"full_export_{timestamp}.json"
                all_memories = self.vector_store.get_all() if self.vector_store else []
                export_full(messages, all_memories, full_path)
                print(f"  [导出] 完整数据 -> {full_path}")

            if not messages and (not self.vector_store or self.vector_store.count() == 0):
                print("  [提示] 没有可导出的数据")
        except Exception as e:
            print(f"  [错误] 导出失败: {e}")
        return True

    def _cmd_clear(self, arg: str) -> bool:
        """清空对话上下文。"""
        self.player.clear_context()
        self.player.clear_memories()
        self.turn_count = 0
        print("  [提示] 对话上下文已清空，开始新对话！")
        return True

    def _cmd_cost(self, arg: str) -> bool:
        """显示 API 费用统计。"""
        cost = self.client.get_total_cost()
        tokens = self.client.get_total_tokens()
        print()
        print(f"  [费用统计]")
        print(f"    输入 tokens:  {tokens['input_tokens']:>10,}")
        print(f"    输出 tokens:  {tokens['output_tokens']:>10,}")
        print(f"    总计 tokens:  {tokens['total_tokens']:>10,}")
        print(f"    累计费用:     ¥{cost:.6f}")
        print()
        return True

    def _cmd_quit(self, arg: str) -> bool:
        """退出应用。"""
        print("\n  [提示] 再见！期待下次聊天~")
        return False

    def _cmd_proactive(self, arg: str) -> bool:
        """手动触发主动消息。

        显示决策过程，如果条件满足则生成并显示主动消息。

        Args:
            arg: 可选参数，支持 "status" 查看当前状态。
        """
        # 处理子命令
        if arg.lower() == "status":
            return self._cmd_proactive_status()

        if self.proactive_engine is None or self.proactive_scheduler is None:
            print("  [错误] 主动消息引擎未初始化")
            return True

        print()
        print("=" * 60)
        print("  主动消息 - 手动触发")
        print("=" * 60)

        # ── 显示决策信息 ──
        proactive_enabled = user_settings.get("proactive_enabled")
        wake_time = user_settings.get("wake_time")
        sleep_time = user_settings.get("sleep_time")
        interval = user_settings.get("proactive_interval_minutes")
        last_sent = self.proactive_scheduler.get_last_sent_time()

        print(f"  主动消息: {'已开启' if proactive_enabled else '未开启'}")
        print(f"  活跃时段: {wake_time} ~ {sleep_time}")
        print(f"  最小间隔: {interval} 分钟")
        if last_sent:
            from src.utils.time_utils import time_diff_seconds, now_cst
            elapsed = time_diff_seconds(last_sent, now_cst())
            print(f"  上次发送: {last_sent.strftime('%Y-%m-%d %H:%M')} "
                  f"({elapsed / 60:.0f} 分钟前)")
        else:
            print(f"  上次发送: 从未发送")
        print(f"  累计发送: {self.proactive_scheduler.get_total_sent_count()} 次")
        print("-" * 60)

        # ── 执行决策和生成 ──
        try:
            message = self.proactive_engine.decide_and_generate(
                card=self.player,
                retriever=self.retriever,
                api_client=self.client,
                last_sent_time=last_sent,
            )

            if message is None:
                print("  结果: 不满足发送条件，跳过")
                print("=" * 60)
                print()
            else:
                print(f"  生成的主动消息:")
                print(f"  ┌{'─' * 56}┐")
                # 自动换行显示
                _print_wrapped(message, prefix="  │ ", width=56)
                print(f"  └{'─' * 56}┘")
                print("=" * 60)
                print()

                # 更新发送时间
                self.proactive_scheduler.update_last_sent_time()
                print("  [提示] 已更新上次发送时间")
        except Exception as e:
            print(f"  [错误] 主动消息生成失败: {e}")
            self._log.error(f"主动消息生成失败: {e}", exc_info=True)

        return True

    def _cmd_proactive_status(self) -> bool:
        """显示主动消息引擎的详细状态。"""
        if self.proactive_scheduler is None:
            print("  [提示] 主动消息引擎未初始化")
            return True

        state = self.proactive_scheduler.get_state()
        print()
        print("=" * 60)
        print("  主动消息引擎 - 状态详情")
        print("=" * 60)
        print(f"  版本:        {state.get('version', 'N/A')}")
        print(f"  上次发送:    {state.get('last_sent_time', '从未')}")
        print(f"  累计发送:    {state.get('total_sent_count', 0)} 次")
        print()
        print("  用户设置:")
        print(f"    开启:      {user_settings.get('proactive_enabled')}")
        print(f"    活跃时段:  {user_settings.get('wake_time')} ~ {user_settings.get('sleep_time')}")
        print(f"    最小间隔:  {user_settings.get('proactive_interval_minutes')} 分钟")
        print("=" * 60)
        print()
        return True

    # -------------------------------------------------------------------------
    # 对话主流程
    # -------------------------------------------------------------------------

    def _process_chat(self, user_input: str) -> None:
        """处理用户输入，执行完整对话流程。

        流程：
        1. 检索相关记忆
        2. 注入记忆到角色扮演引擎
        3. 调用 AI
        4. 显示回复
        5. 异步提取新记忆
        6. 显示成本

        Args:
            user_input: 用户输入文本。
        """
        self.turn_count += 1
        turn_id = f"{TURN_ID_PREFIX}_{self.turn_count}_{format_timestamp_iso()}"

        try:
            # ── 步骤1：检索相关记忆 ──
            if self.retriever and self.vector_store and self.vector_store.count() > 0:
                try:
                    retrieved = self.retriever.retrieve(
                        query_text=user_input,
                        top_k=3,
                        apply_decay=True,
                        min_similarity=0.3,
                    )
                    if retrieved:
                        memory_texts = [m.content for m in retrieved]
                        self.player.inject_memories(memory_texts)
                        self._log.debug(f"检索到 {len(retrieved)} 条相关记忆")
                except Exception as e:
                    self._log.warning(f"记忆检索失败: {e}")

            # ── 步骤2-3：调用 AI 对话 ──
            ai_reply = self.player.chat(user_input)

            # ── 步骤4：显示回复 ──
            card_name = self.player.card.name if self.player.card else "AI"
            print(f"\n  [{card_name}]: {ai_reply}\n")

            # ── 步骤5：提取新记忆 ──
            if self.extractor:
                try:
                    # 构建本轮对话消息
                    turn_messages = [
                        {"role": "user", "content": user_input},
                        {"role": "assistant", "content": ai_reply},
                    ]
                    # 使用规则模式提取（避免额外的 API 调用开销）
                    mode = user_settings.get("memory_extraction_mode")
                    if not isinstance(mode, str):
                        mode = "rule"
                    new_entries = self.extractor.extract(
                        messages=turn_messages,
                        mode=mode,
                        source_turn_id=turn_id,
                    )
                    # 存储新记忆
                    for entry in new_entries:
                        try:
                            self.vector_store.add(entry)
                        except Exception as e:
                            self._log.warning(f"存储记忆失败: {e}")
                    if new_entries:
                        self._log.debug(f"提取了 {len(new_entries)} 条新记忆")
                except Exception as e:
                    self._log.warning(f"记忆提取失败: {e}")

            # ── 步骤6：显示成本 ──
            cost = self.client.get_total_cost()
            tokens = self.client.get_total_tokens()
            self._log.info(
                f"本轮完成 | turn={self.turn_count} | "
                f"累计 tokens={tokens['total_tokens']} | "
                f"累计费用=¥{cost:.6f}"
            )

        except APIKeyError:
            print("\n  [错误] API Key 无效！请检查 .env 文件中的 DEEPSEEK_API_KEY")
        except APIQuotaError:
            print("\n  [错误] API 余额不足！请充值后重试")
        except APITimeoutError:
            print("\n  [错误] API 请求超时，请稍后重试")
        except APIRateLimitError:
            print("\n  [错误] 请求频率过高，请稍后重试")
        except APIContentFilterError:
            print("\n  [提示] 消息被内容过滤，请尝试换一种表达方式")
        except APIServerError:
            print("\n  [错误] API 服务器内部错误，请稍后重试")
        except APIException as e:
            print(f"\n  [错误] API 调用失败: {e}")
        except RolePlayerError as e:
            print(f"\n  [错误] 角色扮演错误: {e}")
        except Exception as e:
            self._log.error(f"未预期的错误: {e}", exc_info=True)
            print(f"\n  [错误] 发生未预期的错误: {e}")

    # -------------------------------------------------------------------------
    # 主循环
    # -------------------------------------------------------------------------

    def run(self) -> None:
        """运行主对话循环。"""
        print()
        print("=" * 60)
        print(f"  AI 伴侣 - 命令行对话")
        print("=" * 60)
        print(f"  输入文字开始对话，输入 /help 查看命令")
        print(f"  输入 /quit 退出程序")
        print("=" * 60)
        print()

        try:
            while True:
                # 读取用户输入
                try:
                    user_input = input("  你: ").strip()
                except (EOFError, KeyboardInterrupt):
                    print("\n\n  [提示] 再见！期待下次聊天~")
                    break

                if not user_input:
                    continue

                # 命令处理
                if user_input.startswith("/"):
                    should_continue = self._handle_command(user_input)
                    if not should_continue:
                        break
                else:
                    # 对话处理
                    self._process_chat(user_input)

        except KeyboardInterrupt:
            print("\n\n  [提示] 再见！期待下次聊天~")
        finally:
            self._cleanup()

    def _cleanup(self) -> None:
        """清理资源：关闭 API 连接和数据库。"""
        try:
            self.client.close()
        except Exception as e:
            self._log.warning(f"关闭 API 客户端失败: {e}")

        if self.vector_store:
            try:
                self.vector_store.close()
            except Exception as e:
                self._log.warning(f"关闭向量存储失败: {e}")

        self._log.info("AI 伴侣应用已关闭")


# =============================================================================
# 命令行入口
# =============================================================================


def parse_args() -> argparse.Namespace:
    """解析命令行参数。

    Returns:
        解析后的参数命名空间。
    """
    parser = argparse.ArgumentParser(
        description="AI 伴侣 - 命令行角色扮演聊天系统",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python main.py                                    # 默认加载小美角色卡
  python main.py --card data/role_cards/小美.json   # 指定角色卡
  python main.py --log-level DEBUG                  # 调试模式
        """,
    )
    parser.add_argument(
        "--card",
        "-c",
        type=str,
        default=None,
        help=f"角色卡文件路径（默认: {DEFAULT_CARD_PATH}）",
    )
    parser.add_argument(
        "--memory-db",
        "-m",
        type=str,
        default=None,
        help="记忆数据库路径（默认: data/memories/companion_memory.db）",
    )
    parser.add_argument(
        "--log-level",
        "-l",
        type=str,
        default=None,
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="日志级别（默认: INFO）",
    )
    return parser.parse_args()


def main() -> None:
    """应用入口函数。"""
    args = parse_args()

    # 配置日志级别
    log_level = args.log_level or settings.LOG_LEVEL
    configure_logger(level=log_level)

    # 创建并运行应用
    app = CompanionApp(
        card_path=args.card,
        memory_db_path=args.memory_db,
    )
    app.run()


if __name__ == "__main__":
    main()
"""上下文管理器模块。

管理短期对话历史（最近 N 轮对话），基于 collections.deque 实现高效的
FIFO 队列，支持自动裁剪以控制 token 开销。

Token 估算规则：
    - 中文：约 1.5 字符 / token
    - 英文/数字/符号：约 4 字符 / token
    综合取约 2.5 字符 / token 作为估算基准。

依赖：
    - src.utils.logger: get_logger 日志实例

使用方式:
    ctx = ContextManager(max_tokens=4000)
    ctx.add_message("user", "你好呀！")
    ctx.add_message("assistant", "你好~")
    messages = ctx.get_context()
"""

from __future__ import annotations

from collections import deque

from src.utils.logger import get_logger


class ContextManager:
    """短期对话上下文管理器。

    使用 deque 维护最近 N 轮对话历史，支持 FIFO 自动裁剪。
    当估算 token 数超过 max_tokens 限制时，自动移除最早的消息。

    Attributes:
        max_tokens: 最大 token 数限制（默认 4000）。
        _history: 对话历史队列，每项为 {"role": str, "content": str}。
    """

    # Token 估算常量
    _CHARS_PER_TOKEN: float = 2.5

    # 每次裁剪时保留的安全余量比例（0.8 即裁剪到 max_tokens * 0.8）
    _SAFETY_MARGIN: float = 0.8

    def __init__(self, max_tokens: int = 4000) -> None:
        """初始化上下文管理器。

        Args:
            max_tokens: 最大 token 数限制。上下文估算 token 数超过此值时
                        自动裁剪最早的消息。建议值：2000~8000。
        """
        self.max_tokens: int = max_tokens
        self._history: deque[dict[str, str]] = deque()
        self._log = get_logger()
        self._log.debug(f"ContextManager 初始化: max_tokens={max_tokens}")

    # -------------------------------------------------------------------------
    # 公开方法
    # -------------------------------------------------------------------------

    def add_message(self, role: str, content: str) -> None:
        """向对话历史添加一条消息。

        添加后自动检查 token 数，超出限制则触发裁剪。

        Args:
            role: 消息角色，如 "user"、"assistant"、"system"。
            content: 消息文本内容。
        """
        if not role:
            raise ValueError("消息角色 role 不能为空")
        if not content:
            self._log.warning(f"添加了空的 {role} 消息")
            return

        self._history.append({"role": role, "content": content})
        self._log.debug(f"添加上下文: role={role}, len={len(content)} 字符")

        # 自动裁剪
        self._auto_trim()

    def get_context(self) -> list[dict[str, str]]:
        """获取当前对话历史，返回列表副本。

        Returns:
            消息列表，每项为 {"role": str, "content": str}。
        """
        return list(self._history)

    def clear(self) -> None:
        """清空所有对话历史。"""
        count = len(self._history)
        self._history.clear()
        self._log.info(f"上下文已清空，共移除 {count} 条消息")

    def get_token_estimate(self) -> int:
        """估算当前对话历史的 token 数。

        使用字符数 / 每 token 字符数 的简单估算。

        Returns:
            估算的 token 数。
        """
        total_chars = sum(len(msg.get("content", "")) for msg in self._history)
        return int(total_chars / self._CHARS_PER_TOKEN)

    def count(self) -> int:
        """获取当前消息条数。

        Returns:
            对话历史中的消息数量。
        """
        return len(self._history)

    # -------------------------------------------------------------------------
    # 内部方法
    # -------------------------------------------------------------------------

    def _auto_trim(self) -> None:
        """自动裁剪：当估算 token 数超过限制时移除最早的消息。

        裁剪目标为 max_tokens * _SAFETY_MARGIN，保留安全余量。
        至少保留最后一条消息不被裁剪。
        """
        # 如果只有一条消息，不裁剪（保留基本上下文）
        if len(self._history) <= 1:
            return

        current_tokens = self.get_token_estimate()
        if current_tokens <= self.max_tokens:
            return

        target_tokens = int(self.max_tokens * self._SAFETY_MARGIN)
        removed_count = 0

        while (
            len(self._history) > 1
            and self.get_token_estimate() > target_tokens
        ):
            removed = self._history.popleft()
            removed_count += 1
            self._log.debug(
                f"自动裁剪: 移除 role={removed['role']}, "
                f"剩余={len(self._history)} 条"
            )

        if removed_count > 0:
            self._log.info(
                f"上下文自动裁剪完成: 移除 {removed_count} 条, "
                f"剩余 {len(self._history)} 条, "
                f"估算 token={self.get_token_estimate()}/{self.max_tokens}"
            )

    def _char_count_for_token(self, text: str) -> float:
        """计算文本的等效 token 字符数。

        根据字符类型分别估算：
            - 中文字符：按 _CHARS_PER_TOKEN 计算
            - 其他字符：按自身计算

        Args:
            text: 输入文本。

        Returns:
            等效字符数。
        """
        # 简化实现：直接返回字符长度
        return len(text)
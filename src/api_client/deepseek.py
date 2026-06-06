"""DeepSeek API 客户端模块。

封装 DeepSeek Chat Completion 和 Embedding API 调用，
提供统一的错误处理、成本追踪和 HTTP 连接池复用。

依赖：
    - requests: HTTP 请求（连接池复用）
    - src.config.settings: Settings 单例（API Key、模型、Base URL）
    - src.exceptions: 自定义异常类（APIKeyError 等）
    - src.utils.retry: @retry 重试装饰器
    - src.utils.logger: get_logger 日志实例
"""

from dataclasses import dataclass
from typing import Any

import requests

from src.config.settings import settings
from src.exceptions import (
    APIContentFilterError,
    APIException,
    APIKeyError,
    APIQuotaError,
    APIRateLimitError,
    APIServerError,
    APITimeoutError,
)
from src.utils.logger import get_logger
from src.utils.retry import retry


# =============================================================================
# 响应数据结构
# =============================================================================


@dataclass
class ChatResponse:
    """Chat Completion API 响应。

    Attributes:
        content: AI 回复的文本内容。
        model: 实际使用的模型名称。
        usage: token 统计，{"prompt_tokens": N, "completion_tokens": N, "total_tokens": N}。
        finish_reason: 结束原因（stop / length / content_filter 等）。
    """

    content: str
    model: str
    usage: dict[str, int]
    finish_reason: str


@dataclass
class EmbeddingResponse:
    """Embedding API 响应。

    Attributes:
        embeddings: 向量列表，每个向量为 float 列表。
        model: 实际使用的模型名称。
        usage: token 统计，{"prompt_tokens": N, "completion_tokens": 0, "total_tokens": N}。
    """

    embeddings: list[list[float]]
    model: str
    usage: dict[str, int]


# =============================================================================
# 成本追踪器
# =============================================================================


class CostTracker:
    """API 调用成本追踪器。

    内部维护输入/输出 token 计数和累计费用（人民币）。
    定价参考（人民币/百万 tokens）：
        - deepseek-v4-flash:    输入 ¥1，输出 ¥2
        - deepseek-embedding-v2: 输入 ¥0.01
        - 默认（未知模型）:      输入 ¥1，输出 ¥2
    """

    # 定价表：{模型名: {"input": 输入单价, "output": 输出单价}}
    PRICING: dict[str, dict[str, float]] = {
        "deepseek-v4-flash": {"input": 1.0, "output": 2.0},
        "deepseek-v4-pro": {"input": 1.0, "output": 2.0},
        "deepseek-embedding-v2": {"input": 0.01, "output": 0.0},
        "default": {"input": 1.0, "output": 2.0},
    }

    def __init__(self) -> None:
        """初始化成本追踪器，所有计数归零。"""
        self._total_input_tokens: int = 0
        self._total_output_tokens: int = 0
        self._total_cost: float = 0.0
        self._log = get_logger()

    def record(
        self, model: str, prompt_tokens: int, completion_tokens: int = 0
    ) -> None:
        """记录一次 API 调用的 token 消耗和费用。

        Args:
            model: 使用的模型名称（用于匹配定价）。
            prompt_tokens: 输入 token 数。
            completion_tokens: 输出 token 数（embedding 调用时为 0）。
        """
        pricing = self.PRICING.get(model, self.PRICING["default"])
        input_cost = prompt_tokens * pricing["input"] / 1_000_000
        output_cost = completion_tokens * pricing["output"] / 1_000_000
        call_cost = input_cost + output_cost

        self._total_input_tokens += prompt_tokens
        self._total_output_tokens += completion_tokens
        self._total_cost += call_cost

        self._log.debug(
            f"[成本] 本次: input={prompt_tokens}, output={completion_tokens}, "
            f"费用=¥{call_cost:.6f} | 累计: ¥{self._total_cost:.6f}"
        )

    def get_total_cost(self) -> float:
        """获取累计总费用（人民币）。

        Returns:
            累计总费用。
        """
        return self._total_cost

    def get_total_tokens(self) -> dict[str, int]:
        """获取累计 token 统计。

        Returns:
            包含 input_tokens, output_tokens, total_tokens 的字典。
        """
        return {
            "input_tokens": self._total_input_tokens,
            "output_tokens": self._total_output_tokens,
            "total_tokens": self._total_input_tokens + self._total_output_tokens,
        }

    def reset(self) -> None:
        """重置所有成本计数。"""
        self._total_input_tokens = 0
        self._total_output_tokens = 0
        self._total_cost = 0.0
        self._log.info("[成本] 成本追踪已重置")


# =============================================================================
# DeepSeek 客户端
# =============================================================================


class DeepSeekClient:
    """DeepSeek API 客户端。

    封装 Chat Completion 和 Embedding API 调用，提供：
    - 基于 requests.Session 的 HTTP 连接池复用
    - 统一错误处理：HTTP 状态码自动映射为对应异常类
    - 自动成本追踪（token 数 + 费用）
    - 通过 @retry 装饰器实现指数退避重试（超时/服务器错误/限流）

    使用方式:
        client = DeepSeekClient()
        response = client.chat([{"role": "user", "content": "你好"}])
        print(response.content)
        print(client.get_total_cost())

    Attributes:
        session: requests.Session 实例，复用 TCP 连接。
        cost_tracker: CostTracker 实例，追踪 API 费用。
    """

    # HTTP 超时设置（秒）
    CONNECT_TIMEOUT: float = 5.0
    READ_TIMEOUT: float = 30.0
    # Embedding 批量处理上限
    EMBED_BATCH_SIZE: int = 100

    def __init__(self) -> None:
        """初始化 DeepSeek 客户端。

        从 Settings 单例读取 API Key、模型名、Base URL，
        创建带 Authorization 头的请求 Session。
        """
        self._api_key: str = settings.DEEPSEEK_API_KEY
        self._base_url: str = settings.DEEPSEEK_BASE_URL.rstrip("/")
        self._chat_model: str = settings.DEEPSEEK_MODEL
        self._embed_model: str = settings.DEEPSEEK_EMBEDDING_MODEL

        # 创建 Session，设置默认请求头
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            }
        )

        # 成本追踪器和日志
        self.cost_tracker = CostTracker()
        self._log = get_logger()

        self._log.info(
            f"DeepSeekClient 初始化完成 | base_url={self._base_url}, "
            f"chat_model={self._chat_model}, embed_model={self._embed_model}"
        )

    # -------------------------------------------------------------------------
    # 内部方法：错误处理
    # -------------------------------------------------------------------------

    def _handle_http_error(self, response: requests.Response) -> None:
        """根据 HTTP 响应状态码抛出对应的异常。

        从响应体中尝试提取错误消息，映射规则：
            - 401 -> APIKeyError
            - 402 -> APIQuotaError
            - 429 -> APIRateLimitError
            - 5xx -> APIServerError
            - 400 (含 content/safety 关键词) -> APIContentFilterError
            - 其他 -> APIException

        Args:
            response: HTTP 响应对象。

        Raises:
            对应的 APIException 子类。
        """
        status_code = response.status_code

        # 尝试解析错误消息
        try:
            error_body = response.json()
        except Exception:
            error_body = {}

        if isinstance(error_body, dict):
            error_msg = error_body.get("error", {}).get("message", response.text)
        else:
            error_msg = response.text

        self._log.error(f"[API 错误] status={status_code}, body={str(error_msg)[:200]}")

        if status_code == 401:
            raise APIKeyError(f"认证失败 (401): {error_msg}")
        elif status_code == 402:
            raise APIQuotaError(f"配额不足 (402): {error_msg}")
        elif status_code == 429:
            raise APIRateLimitError(f"请求频率过高 (429): {error_msg}")
        elif status_code >= 500:
            raise APIServerError(
                f"服务器错误 ({status_code}): {error_msg}", status_code=status_code
            )
        elif status_code == 400:
            # 400 可能是参数错误，也可能是内容过滤
            lower_msg = str(error_msg).lower()
            if "content" in lower_msg or "safety" in lower_msg or "filter" in lower_msg:
                raise APIContentFilterError(f"内容被过滤 (400): {error_msg}")
            raise APIException(f"请求参数错误 (400): {error_msg}", status_code=400)
        else:
            raise APIException(
                f"未知错误 ({status_code}): {error_msg}", status_code=status_code
            )

    # -------------------------------------------------------------------------
    # Chat Completion API
    # -------------------------------------------------------------------------

    # 重试策略：超时/服务器错误/限流时最多重试 3 次，指数退避
    @retry(
        max_retries=3,
        base_delay=1.0,
        max_delay=30.0,
        retryable_exceptions=(APITimeoutError, APIServerError, APIRateLimitError),
    )
    def chat(
        self,
        messages: list[dict[str, str]],
        temperature: float = 0.7,
        max_tokens: int = 2000,
        stream: bool = False,
    ) -> ChatResponse:
        """调用 DeepSeek Chat Completion API。

        Args:
            messages: 对话消息列表，每条为 {"role": "...", "content": "..."}。
            temperature: 采样温度 (0~2)，值越高输出越随机。
            max_tokens: 最大输出 token 数。
            stream: 是否启用流式输出（当前版本不支持流式）。

        Returns:
            ChatResponse: 包含 content, model, usage, finish_reason 的响应对象。

        Raises:
            ValueError: messages 为空时。
            APIKeyError: API Key 无效（401）。
            APIQuotaError: 余额不足（402）。
            APITimeoutError: 请求超时。
            APIRateLimitError: 频率超限（429）。
            APIServerError: 服务器内部错误（5xx）。
            APIContentFilterError: 内容被安全过滤。
            APIException: 其他未知 API 错误。
        """
        if not messages:
            raise ValueError("messages 不能为空")

        if stream:
            self._log.warning("stream=True 当前不支持，已自动切换为 stream=False")
            stream = False

        url = f"{self._base_url}/v1/chat/completions"
        payload: dict[str, Any] = {
            "model": self._chat_model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": stream,
        }

        self._log.debug(f"[Chat] 请求: model={self._chat_model}, msgs={len(messages)}, temp={temperature}")

        # 发送 HTTP 请求
        try:
            response = self.session.post(
                url,
                json=payload,
                timeout=(self.CONNECT_TIMEOUT, self.READ_TIMEOUT),
            )
        except requests.exceptions.Timeout:
            raise APITimeoutError(
                f"Chat API 请求超时 (连接={self.CONNECT_TIMEOUT}s, 读取={self.READ_TIMEOUT}s)"
            )
        except requests.exceptions.ConnectionError as e:
            raise APIException(f"连接失败: {e}")

        # 检查 HTTP 状态码
        if response.status_code != 200:
            self._handle_http_error(response)

        # 解析成功响应
        data = response.json()
        choice = data["choices"][0]
        usage_raw = data.get("usage", {})

        result = ChatResponse(
            content=choice["message"]["content"],
            model=data.get("model", self._chat_model),
            usage={
                "prompt_tokens": usage_raw.get("prompt_tokens", 0),
                "completion_tokens": usage_raw.get("completion_tokens", 0),
                "total_tokens": usage_raw.get("total_tokens", 0),
            },
            finish_reason=choice.get("finish_reason", "unknown"),
        )

        # 记录成本
        self.cost_tracker.record(
            model=result.model,
            prompt_tokens=result.usage["prompt_tokens"],
            completion_tokens=result.usage["completion_tokens"],
        )

        self._log.info(
            f"[Chat] 成功: model={result.model}, tokens={result.usage['total_tokens']}, "
            f"finish={result.finish_reason}"
        )
        return result

    # -------------------------------------------------------------------------
    # Embedding API
    # -------------------------------------------------------------------------

    @retry(
        max_retries=3,
        base_delay=1.0,
        max_delay=30.0,
        retryable_exceptions=(APITimeoutError, APIServerError, APIRateLimitError),
    )
    def embed(self, texts: str | list[str]) -> EmbeddingResponse:
        """调用 DeepSeek Embedding API，生成文本向量。

        支持单条文本 (str) 或多条文本 (list[str])。
        多条文本自动分批，每批最多 100 条。

        Args:
            texts: 单条文本或文本列表。

        Returns:
            EmbeddingResponse: 包含 embeddings, model, usage 的响应对象。

        Raises:
            ValueError: texts 为空时。
            APIKeyError: API Key 无效（401）。
            APIQuotaError: 余额不足（402）。
            APITimeoutError: 请求超时。
            APIRateLimitError: 频率超限（429）。
            APIServerError: 服务器内部错误（5xx）。
            APIException: 其他未知 API 错误。
        """
        # 输入归一化
        if isinstance(texts, str):
            texts = [texts]

        if not texts:
            raise ValueError("texts 不能为空")

        self._log.debug(f"[Embed] 请求: model={self._embed_model}, 文本数={len(texts)}")

        # 分批处理
        all_embeddings: list[list[float]] = []
        total_prompt_tokens = 0
        batch_count = 0

        for i in range(0, len(texts), self.EMBED_BATCH_SIZE):
            batch = texts[i : i + self.EMBED_BATCH_SIZE]
            batch_count += 1

            url = f"{self._base_url}/v1/embeddings"
            payload = {"model": self._embed_model, "input": batch}

            try:
                response = self.session.post(
                    url,
                    json=payload,
                    timeout=(self.CONNECT_TIMEOUT, self.READ_TIMEOUT),
                )
            except requests.exceptions.Timeout:
                raise APITimeoutError(
                    f"Embedding API 请求超时 (连接={self.CONNECT_TIMEOUT}s, 读取={self.READ_TIMEOUT}s)"
                )
            except requests.exceptions.ConnectionError as e:
                raise APIException(f"连接失败: {e}")

            if response.status_code != 200:
                self._handle_http_error(response)

            data = response.json()
            batch_tokens = data.get("usage", {}).get("total_tokens", 0)
            total_prompt_tokens += batch_tokens

            # 按 index 排序后提取向量
            items = sorted(data["data"], key=lambda x: x.get("index", 0))
            for item in items:
                all_embeddings.append(item["embedding"])

        # 构建结果
        result = EmbeddingResponse(
            embeddings=all_embeddings,
            model=self._embed_model,
            usage={
                "prompt_tokens": total_prompt_tokens,
                "completion_tokens": 0,
                "total_tokens": total_prompt_tokens,
            },
        )

        # 记录成本
        self.cost_tracker.record(
            model=self._embed_model,
            prompt_tokens=total_prompt_tokens,
            completion_tokens=0,
        )

        vec_dim = len(all_embeddings[0]) if all_embeddings else 0
        self._log.info(
            f"[Embed] 成功: 文本={len(texts)}, 批次={batch_count}, "
            f"向量维度={vec_dim}, tokens={total_prompt_tokens}"
        )
        return result

    # -------------------------------------------------------------------------
    # 成本查询 / 生命周期
    # -------------------------------------------------------------------------

    def get_total_cost(self) -> float:
        """获取累计 API 调用总费用（人民币）。

        Returns:
            累计总费用。
        """
        return self.cost_tracker.get_total_cost()

    def get_total_tokens(self) -> dict[str, int]:
        """获取累计 token 统计。

        Returns:
            {"input_tokens": N, "output_tokens": N, "total_tokens": N}
        """
        return self.cost_tracker.get_total_tokens()

    def reset_cost(self) -> None:
        """重置成本追踪计数器。"""
        self.cost_tracker.reset()

    def close(self) -> None:
        """关闭客户端，释放 HTTP 连接池资源。"""
        self.session.close()
        self._log.info("DeepSeekClient 已关闭")

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

import json
from dataclasses import dataclass
from typing import Any, Generator

import requests

from src.config.settings import settings
from src.exceptions import (
    APIConnectionError,
    APIContentFilterError,
    APIException,
    APIKeyError,
    APIQuotaError,
    APIRateLimitError,
    APIServerError,
    APITimeoutError,
)
from src.utils.logger import get_logger
from src.utils.lru_cache import LRUCache
from src.utils.retry import retry


@dataclass
class ChatResponse:
    """Chat Completion API 响应。"""
    content: str
    model: str
    usage: dict[str, int]
    finish_reason: str


@dataclass
class EmbeddingResponse:
    """Embedding API 响应。"""
    embeddings: list[list[float]]
    model: str
    usage: dict[str, int]


class CostTracker:
    """API 调用成本追踪器。"""

    PRICING: dict[str, dict[str, float]] = {
        "deepseek-v4-flash": {"input": 1.0, "output": 2.0},
        "deepseek-v4-pro": {"input": 1.0, "output": 2.0},
        "deepseek-embedding-v2": {"input": 0.01, "output": 0.0},
        "default": {"input": 1.0, "output": 2.0},
    }

    def __init__(self) -> None:
        self._total_input_tokens: int = 0
        self._total_output_tokens: int = 0
        self._total_cost: float = 0.0
        self._log = get_logger()

    def record(self, model: str, prompt_tokens: int, completion_tokens: int = 0) -> None:
        pricing = self.PRICING.get(model, self.PRICING["default"])
        input_cost = prompt_tokens * pricing["input"] / 1_000_000
        output_cost = completion_tokens * pricing["output"] / 1_000_000
        call_cost = input_cost + output_cost
        self._total_input_tokens += prompt_tokens
        self._total_output_tokens += completion_tokens
        self._total_cost += call_cost
        self._log.debug(f"[成本] 本次: input={prompt_tokens}, output={completion_tokens}, 费用={call_cost:.6f} | 累计: {self._total_cost:.6f}")

    def get_total_cost(self) -> float:
        return self._total_cost

    def get_total_tokens(self) -> dict[str, int]:
        return {
            "input_tokens": self._total_input_tokens,
            "output_tokens": self._total_output_tokens,
            "total_tokens": self._total_input_tokens + self._total_output_tokens,
        }

    def reset(self) -> None:
        self._total_input_tokens = 0
        self._total_output_tokens = 0
        self._total_cost = 0.0
        self._log.info("[成本] 成本追踪已重置")


class DeepSeekClient:
    """DeepSeek API 客户端。"""

    CONNECT_TIMEOUT: float = 5.0
    READ_TIMEOUT: float = 30.0
    EMBED_BATCH_SIZE: int = 100

    def __init__(self) -> None:
        self._api_key: str = settings.DEEPSEEK_API_KEY
        self._base_url: str = settings.DEEPSEEK_BASE_URL.rstrip("/")
        self._chat_model: str = settings.DEEPSEEK_MODEL
        self._embed_model: str = settings.DEEPSEEK_EMBEDDING_MODEL
        self.session = requests.Session()
        self.session.headers.update({
            "Content-Type": "application/json",
        })
        # 仅在 API Key 非空时初始化时设置 Authorization header
        # 如果初始为空，将在首次 API 调用时通过 _ensure_auth() 延迟校验
        if self._api_key:
            self.session.headers["Authorization"] = f"Bearer {self._api_key}"
        self.cost_tracker = CostTracker()
        self._embed_cache = LRUCache(capacity=500)
        self._log = get_logger()
        self._log.info(f"DeepSeekClient 初始化完成 | base_url={self._base_url}, chat_model={self._chat_model}, embed_model={self._embed_model}, embed_cache=LRU(500)")

    def set_model(self, model: str) -> None:
        """设置对话模型名称。
        
        Args:
            model: 模型名称，如 "deepseek-chat"。
        """
        self._chat_model = model
        self._log.info(f"[模型] 已切换为: {model}")

    def update_api_key(self, api_key: str) -> None:
        """更新 API Key 并同步到 session header。
        
        当用户运行时修改 API Key 时调用此方法，
        确保已存在的 DeepSeekClient 实例能使用新 Key。
        
        Args:
            api_key: 新的 DeepSeek API Key。
        """
        self._api_key = api_key
        self.session.headers["Authorization"] = f"Bearer {api_key}"
        self._log.info("[API Key] session header 已更新")

    def _ensure_auth(self) -> None:
        """确保 Authorization header 已设置（延迟校验）。
        
        如果初始化时 API Key 为空，首次实际 API 调用时通过此方法检查。
        若后续通过 set_api_key() 设置了 Key，此方法也会自动生效。
        
        Raises:
            APIKeyError: API Key 仍为空时抛出。
        """
        if "Authorization" not in self.session.headers:
            key = settings.DEEPSEEK_API_KEY
            if not key:
                raise APIKeyError("API Key 未配置，请先设置 API Key")
            self.session.headers["Authorization"] = f"Bearer {key}"
            self._api_key = key
            self._log.info("[API Key] 延迟设置 Authorization header 成功")

    def _handle_http_error(self, response: requests.Response) -> None:
        status_code = response.status_code
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
            raise APIServerError(f"服务器错误 ({status_code}): {error_msg}", status_code=status_code)
        elif status_code == 400:
            lower_msg = str(error_msg).lower()
            if "content" in lower_msg or "safety" in lower_msg or "filter" in lower_msg:
                raise APIContentFilterError(f"内容被过滤 (400): {error_msg}")
            raise APIException(f"请求参数错误 (400): {error_msg}", status_code=400)
        else:
            raise APIException(f"未知错误 ({status_code}): {error_msg}", status_code=status_code)

    @retry(max_retries=3, base_delay=1.0, max_delay=30.0, retryable_exceptions=(APITimeoutError, APIServerError, APIRateLimitError, APIConnectionError))
    def chat(self, messages: list[dict[str, str]], temperature: float = 0.7, max_tokens: int = 2000, stream: bool = False) -> ChatResponse:
        self._ensure_auth()
        if not messages:
            raise ValueError("messages 不能为空")
        if stream:
            self._log.warning("stream=True 当前不支持，已自动切换为 stream=False")
            stream = False
        url = f"{self._base_url}/v1/chat/completions"
        payload: dict[str, Any] = {"model": self._chat_model, "messages": messages, "temperature": temperature, "max_tokens": max_tokens, "stream": stream}
        self._log.debug(f"[Chat] 请求: model={self._chat_model}, msgs={len(messages)}, temp={temperature}")
        try:
            response = self.session.post(url, json=payload, timeout=(self.CONNECT_TIMEOUT, self.READ_TIMEOUT))
        except requests.exceptions.Timeout:
            raise APITimeoutError(f"Chat API 请求超时 (连接={self.CONNECT_TIMEOUT}s, 读取={self.READ_TIMEOUT}s)")
        except requests.exceptions.ConnectionError as e:
            raise APIConnectionError(f"Chat API 连接失败: {e}")
        if response.status_code != 200:
            self._handle_http_error(response)
        data = response.json()
        choice = data["choices"][0]
        usage_raw = data.get("usage", {})
        result = ChatResponse(
            content=choice["message"]["content"],
            model=data.get("model", self._chat_model),
            usage={"prompt_tokens": usage_raw.get("prompt_tokens", 0), "completion_tokens": usage_raw.get("completion_tokens", 0), "total_tokens": usage_raw.get("total_tokens", 0)},
            finish_reason=choice.get("finish_reason", "unknown"),
        )
        self.cost_tracker.record(model=result.model, prompt_tokens=result.usage["prompt_tokens"], completion_tokens=result.usage["completion_tokens"])
        self._log.info(f"[Chat] 成功: model={result.model}, tokens={result.usage['total_tokens']}, finish={result.finish_reason}")
        return result

    @retry(max_retries=2, base_delay=1.0, max_delay=15.0, retryable_exceptions=(APITimeoutError, APIServerError, APIRateLimitError, APIConnectionError))
    def chat_stream(
        self,
        messages: list[dict[str, str]],
        temperature: float = 0.7,
        max_tokens: int = 2000,
    ) -> Generator[str, None, ChatResponse]:
        """流式聊天，逐 token yield 内容。

        每收到一个 delta content 就 yield 出去，
        调用方遍历完所有 token 后可通过 StopIteration.value 获取最终 ChatResponse。

        Yields:
            每个 delta content 字符串。

        Returns:
            最终 ChatResponse（通过 StopIteration.value 访问）。

        Raises:
            ValueError: messages 为空时。
            APITimeoutError: 请求超时。
            APIConnectionError: 连接失败。
            其他 API 异常: 由 _handle_http_error() 处理。
        """
        self._ensure_auth()
        if not messages:
            raise ValueError("messages 不能为空")

        url = f"{self._base_url}/v1/chat/completions"
        payload: dict[str, Any] = {
            "model": self._chat_model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": True,
        }

        self._log.debug(f"[Chat Stream] 请求: model={self._chat_model}, msgs={len(messages)}")

        try:
            response = self.session.post(
                url, json=payload,
                timeout=(self.CONNECT_TIMEOUT, self.READ_TIMEOUT * 2),
                stream=True,
            )
        except requests.exceptions.Timeout:
            raise APITimeoutError("Chat Stream API 请求超时")
        except requests.exceptions.ConnectionError as e:
            raise APIConnectionError(f"Chat Stream API 连接失败: {e}")

        if response.status_code != 200:
            self._handle_http_error(response)

        full_content = ""
        finish_reason = "stop"
        model = self._chat_model
        usage: dict[str, int] = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

        for line in response.iter_lines(decode_unicode=True):
            if not line or line.startswith(":"):
                continue
            if line.startswith("data: "):
                data_str = line[6:]
                if data_str == "[DONE]":
                    break
                try:
                    obj = json.loads(data_str)
                    choices = obj.get("choices", [])
                    if choices:
                        delta = choices[0].get("delta", {})
                        content = delta.get("content", "")
                        if content:
                            full_content += content
                            yield content
                        if choices[0].get("finish_reason"):
                            finish_reason = choices[0]["finish_reason"]
                    # 从最后一个 chunk 获取 usage 信息
                    chunk_usage = obj.get("usage")
                    if chunk_usage:
                        usage = {
                            "prompt_tokens": chunk_usage.get("prompt_tokens", 0),
                            "completion_tokens": chunk_usage.get("completion_tokens", 0),
                            "total_tokens": chunk_usage.get("total_tokens", 0),
                        }
                    model = obj.get("model", model)
                except json.JSONDecodeError:
                    self._log.debug(f"[Chat Stream] JSON 解析失败，跳过: {data_str[:50]}...")
                    continue

        result = ChatResponse(
            content=full_content,
            model=model,
            usage=usage,
            finish_reason=finish_reason,
        )
        self.cost_tracker.record(
            model=result.model,
            prompt_tokens=result.usage["prompt_tokens"],
            completion_tokens=result.usage["completion_tokens"],
        )
        self._log.info(
            f"[Chat Stream] 成功: model={result.model}, tokens={result.usage['total_tokens']}, "
            f"finish={result.finish_reason}, 内容长度={len(full_content)}"
        )
        return result

    @retry(max_retries=3, base_delay=1.0, max_delay=30.0, retryable_exceptions=(APITimeoutError, APIServerError, APIRateLimitError, APIConnectionError))
    def embed(self, texts: str | list[str]) -> EmbeddingResponse:
        self._ensure_auth()
        if isinstance(texts, str):
            texts = [texts]
        if not texts:
            raise ValueError("texts 不能为空")
        self._log.debug(f"[Embed] 请求: model={self._embed_model}, 文本数={len(texts)}")
        all_embeddings: list[list[float]] = []
        total_prompt_tokens = 0
        batch_count = 0
        for i in range(0, len(texts), self.EMBED_BATCH_SIZE):
            batch = texts[i : i + self.EMBED_BATCH_SIZE]
            batch_count += 1
            url = f"{self._base_url}/v1/embeddings"
            payload = {"model": self._embed_model, "input": batch}
            try:
                response = self.session.post(url, json=payload, timeout=(self.CONNECT_TIMEOUT, self.READ_TIMEOUT))
            except requests.exceptions.Timeout:
                raise APITimeoutError(f"Embedding API 请求超时")
            except requests.exceptions.ConnectionError as e:
                raise APIConnectionError(f"Embedding API 连接失败: {e}")
            if response.status_code != 200:
                self._log.warning(
                    f"[Embed] API 不可用 (status={response.status_code})，"
                    f"记忆存储仍会继续(无向量索引): {response.text[:100]}"
                )
                raise APIException(
                    f"Embedding API 不可用 (status={response.status_code})"
                )
            data = response.json()
            batch_tokens = data.get("usage", {}).get("total_tokens", 0)
            total_prompt_tokens += batch_tokens
            items = sorted(data["data"], key=lambda x: x.get("index", 0))
            for item in items:
                all_embeddings.append(item["embedding"])
        result = EmbeddingResponse(
            embeddings=all_embeddings,
            model=self._embed_model,
            usage={"prompt_tokens": total_prompt_tokens, "completion_tokens": 0, "total_tokens": total_prompt_tokens},
        )
        self.cost_tracker.record(model=self._embed_model, prompt_tokens=total_prompt_tokens, completion_tokens=0)
        vec_dim = len(all_embeddings[0]) if all_embeddings else 0
        self._log.info(f"[Embed] 成功: 文本={len(texts)}, 批次={batch_count}, 向量维度={vec_dim}, tokens={total_prompt_tokens}")
        return result

    def embed_cached(self, texts: str | list[str]) -> EmbeddingResponse:
        """带 LRU 缓存获取文本 Embedding 向量。

        先查缓存，命中直接返回；未命中调用 embed() 后存入缓存。
        支持单文本（str）和批量文本（list[str]）。

        批量模式：逐条检查缓存，只对未缓存的文本调用 API，
        然后按原始顺序合并缓存命中和新请求的结果。

        Args:
            texts: 单个文本或文本列表。

        Returns:
            EmbeddingResponse，包含 embeddings、model 和 usage。
        """
        # ---- 单文本模式 ----
        if isinstance(texts, str):
            cached = self._embed_cache.get(texts)
            if cached is not None:
                self._log.info(
                    f"[EmbedCache] 命中: text='{texts[:30]}...'"
                )
                return EmbeddingResponse(
                    embeddings=[cached],
                    model=self._embed_model,
                    usage={"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
                )
            # 未命中，调用 API
            self._log.info(
                f"[EmbedCache] 未命中: text='{texts[:30]}...'"
            )
            result = self.embed(texts)
            # 存入缓存
            if result.embeddings:
                self._embed_cache.put(texts, result.embeddings[0])
            return result

        # ---- 批量模式 ----
        if not texts:
            raise ValueError("texts 不能为空")

        # 逐条检查缓存
        cached_indices: dict[int, list[float]] = {}   # 索引 → 命中向量
        uncached_indices: list[int] = []               # 未命中的索引
        uncached_texts: list[str] = []                 # 未命中的文本

        for i, t in enumerate(texts):
            vec = self._embed_cache.get(t)
            if vec is not None:
                cached_indices[i] = vec
            else:
                uncached_indices.append(i)
                uncached_texts.append(t)

        hits = len(cached_indices)
        misses = len(uncached_texts)
        self._log.info(
            f"[EmbedCache] 批量: 总数={len(texts)}, "
            f"缓存命中={hits}, 未命中={misses}"
        )

        # 全部命中，直接返回
        if not uncached_texts:
            all_embeddings = [
                cached_indices[i] for i in range(len(texts))
            ]
            return EmbeddingResponse(
                embeddings=all_embeddings,
                model=self._embed_model,
                usage={"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
            )

        # 只对未缓存的文本调用 API
        new_result = self.embed(uncached_texts)

        # 将新结果按原始索引插入
        all_embeddings: list[list[float]] = []
        new_idx = 0
        for i in range(len(texts)):
            if i in cached_indices:
                all_embeddings.append(cached_indices[i])
            else:
                vec = new_result.embeddings[new_idx]
                all_embeddings.append(vec)
                # 存入缓存
                self._embed_cache.put(texts[i], vec)
                new_idx += 1

        return EmbeddingResponse(
            embeddings=all_embeddings,
            model=self._embed_model,
            usage=new_result.usage,
        )

    def get_embed_cache_stats(self) -> dict:
        """获取 Embedding 缓存命中率统计。

        Returns:
            dict: 包含 hits、misses、total_queries、hit_rate、cache_size、capacity。
        """
        return self._embed_cache.stats()

    def reset_embed_cache(self) -> None:
        """重置 Embedding 缓存（清空缓存和统计计数器）。"""
        self._embed_cache.reset()
        self._log.info("[EmbedCache] 缓存已重置")

    def get_total_cost(self) -> float:
        return self.cost_tracker.get_total_cost()

    def get_total_tokens(self) -> dict[str, int]:
        return self.cost_tracker.get_total_tokens()

    def reset_cost(self) -> None:
        self.cost_tracker.reset()

    def close(self) -> None:
        self.session.close()
        self._log.info("DeepSeekClient 已关闭")
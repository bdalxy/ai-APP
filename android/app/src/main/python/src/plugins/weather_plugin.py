"""天气插件 — 使用 wttr.in 免费 API 获取真实天气，失败时回退模拟数据"""

import random
import re
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError
from typing import Optional, Dict, Tuple

import requests

from .plugin_base import BasePlugin


class WeatherPlugin(BasePlugin):
    """天气查询插件。使用 /weather 城市名 触发。
    
    优先使用 wttr.in 免费 API 获取真实天气数据，
    失败时回退到模拟数据。同一城市 10 分钟内缓存结果。
    """

    name = "weather"
    version = "2.0.0"
    description = "天气查询 — 使用 wttr.in 免费 API，支持全球城市"
    category = "script"
    icon = "cloud"

    # ── 模拟数据回退 ──
    # TODO-i18n: 天气描述需支持多语言
    _WEATHER = ["晴", "多云", "阴", "小雨", "阵雨", "雪"]
    _CITIES = {
        "北京": (10, 35), "上海": (15, 35), "广州": (18, 38),
        "深圳": (18, 37), "杭州": (12, 36), "成都": (10, 33),
        "武汉": (12, 37), "南京": (10, 35), "重庆": (15, 38),
        "西安": (8, 33), "哈尔滨": (-5, 28), "昆明": (12, 28),
        "厦门": (15, 33), "长沙": (10, 36), "郑州": (8, 34),
        "天津": (8, 33), "苏州": (12, 34), "青岛": (8, 30),
        "大连": (5, 29), "三亚": (22, 35),
    }

    # ── API 配置 ──
    _WTTR_URL = "https://wttr.in/{}?format=j1"
    _REQUEST_TIMEOUT = 8  # 请求超时秒数
    _CACHE_TTL = 600  # 缓存有效期 10 分钟

    def __init__(self):
        super().__init__()
        # 缓存: {city: (timestamp, formatted_result)}
        self._cache: Dict[str, Tuple[float, str]] = {}
        # 线程池用于异步 HTTP 请求，避免阻塞 Chaquopy 主线程
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="weather")

    def pre_process(self, user_input: str) -> Optional[str]:
        m = re.match(r"^/weather\s+(.+)", user_input.strip())
        if not m:
            return None
        city = m.group(1).strip()
        if not city:
            return "[天气插件] 请输入城市名称，例如：/weather 北京"
        return self._get_weather(city)

    def _get_weather(self, city: str) -> str:
        """获取城市天气（优先真实 API，失败回退模拟）"""
        # 检查缓存
        cached = self._cache.get(city)
        if cached:
            ts, result = cached
            if time.time() - ts < self._CACHE_TTL:
                return result
            del self._cache[city]

        # 尝试真实 API（在线程池中执行，避免阻塞主线程）
        try:
            future = self._executor.submit(self._fetch_from_api, city)
            result = future.result(timeout=self._REQUEST_TIMEOUT + 2)
            if result is not None:
                self._cache[city] = (time.time(), result)
                return result
        except TimeoutError:
            pass  # 超时，回退到模拟数据
        except Exception:
            pass  # 其他异常，回退到模拟数据

        # 回退到模拟数据
        return self._get_simulated_weather(city)

    def _fetch_from_api(self, city: str) -> Optional[str]:
        """从 wttr.in API 获取天气数据。
        
        Returns:
            格式化后的天气字符串，失败返回 None
        """
        url = self._WTTR_URL.format(city)
        resp = requests.get(url, timeout=self._REQUEST_TIMEOUT)
        if resp.status_code != 200:
            return None

        data = resp.json()
        current = data.get("current_condition", [{}])[0]
        if not current:
            return None

        # 解析天气数据
        temp_c = current.get("temp_C", "N/A")
        humidity = current.get("humidity", "N/A")
        wind_speed = current.get("windspeedKmph", "N/A")
        wind_dir = current.get("winddir16Point", "")
        weather_desc = current.get("weatherDesc", [{}])[0].get("value", "未知")  # TODO-i18n: 天气描述需支持多语言
        feels_like = current.get("FeelsLikeC", "N/A")
        visibility = current.get("visibility", "N/A")

        # 获取城市名称（API 可能返回英文名）
        nearest = data.get("nearest_area", [{}])[0]
        area_name = nearest.get("areaName", [{}])[0].get("value", city)
        country = nearest.get("country", [{}])[0].get("value", "")

        location = f"{area_name}, {country}" if country else area_name

        parts = [
            f"[天气插件] {location}",
            f"天气：{weather_desc}",
            f"温度：{temp_c}°C（体感 {feels_like}°C）",
        ]
        if humidity != "N/A":
            parts.append(f"湿度：{humidity}%")
        if wind_speed != "N/A" and wind_speed != "0":
            parts.append(f"风速：{wind_speed} km/h {wind_dir}")
        if visibility != "N/A":
            parts.append(f"能见度：{visibility} km")

        return " · ".join(parts)

    def _get_simulated_weather(self, city: str) -> str:
        """生成模拟天气数据（回退方案）"""
        if city not in self._CITIES:
            # TODO-i18n: 天气描述需支持多语言
            return f"[天气插件] 暂不支持查询「{city}」的天气，请尝试：北京、上海、广州等城市。"
        lo, hi = self._CITIES[city]
        temp = random.randint(lo, hi)
        w = random.choice(self._WEATHER)
        humidity = random.randint(30, 90)
        # TODO-i18n: 天气描述需支持多语言
        return f"[天气插件] {city}今天{w}，{temp}°C，湿度{humidity}%（模拟数据）"
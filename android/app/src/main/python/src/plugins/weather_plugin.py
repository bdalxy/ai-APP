"""天气插件 — 模拟天气查询"""

import random
import re
from typing import Optional

from .plugin_base import BasePlugin


class WeatherPlugin(BasePlugin):
    """模拟天气查询插件。使用 /weather 城市名 触发。"""

    name = "weather"
    version = "1.0.0"
    description = "天气查询 — 模拟城市天气数据"
    category = "script"
    icon = "cloud"

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

    def pre_process(self, user_input: str) -> Optional[str]:
        m = re.match(r"^/weather\s+(.+)", user_input.strip())
        if not m:
            return None
        city = m.group(1).strip()
        if city not in self._CITIES:
            return f"[天气插件] 暂不支持查询「{city}」的天气，请尝试：北京、上海、广州等城市。"
        lo, hi = self._CITIES[city]
        temp = random.randint(lo, hi)
        w = random.choice(self._WEATHER)
        humidity = random.randint(30, 90)
        return f"[天气插件] {city}今天{w}，{temp}°C，湿度{humidity}%"
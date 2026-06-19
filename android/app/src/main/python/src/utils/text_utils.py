"""文本处理工具模块。

提供通用的文本相似度计算、归一化等工具函数，供 memory/ 等模块复用。
"""

from __future__ import annotations

import re


def text_similarity(text1: str, text2: str) -> float:
    """计算两个文本的 bigram Jaccard 相似度。

    使用字符级 bigram 的 Jaccard 相似度，简单高效，不依赖外部库。

    Args:
        text1: 第一个文本。
        text2: 第二个文本。

    Returns:
        相似度值（0.0 ~ 1.0），0.0 表示完全不同，1.0 表示完全相同。
    """
    if not text1 or not text2:
        return 0.0

    def _bigrams(s: str) -> set[str]:
        cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", s)
        return {cleaned[i : i + 2] for i in range(len(cleaned) - 1)}

    bg1 = _bigrams(text1)
    bg2 = _bigrams(text2)

    if not bg1 or not bg2:
        return 0.0

    intersection = len(bg1 & bg2)
    union = len(bg1 | bg2)

    return intersection / union if union > 0 else 0.0
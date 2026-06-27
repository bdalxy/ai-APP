# VITS AISHELL-3 中文 TTS 模型

## 模型说明

此模型基于 VITS (Variational Inference with adversarial learning for end-to-end Text-to-Speech) 架构，
使用 AISHELL-3 中文数据集训练，支持高质量中文语音合成。

- 数据集：AISHELL-3（多说话人中文普通话语音数据库）
- 采样率：22050 Hz
- 语言：中文

## 模型文件下载

需要从 Sherpa-ONNX 官方模型库下载以下文件到本目录：

### 下载地址

模型文件可以从以下任一地址下载：

1. **GitHub Releases**:
   https://github.com/k2-fsa/sherpa-onnx/releases
   搜索 "vits-aishell3" 找到对应模型包

2. **Hugging Face**:
   https://huggingface.co/csukuangfj/vits-aishell3

3. **直接下载链接** (Sherpa-ONNX 官方):
   ```
   https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-aishell3.tar.bz2
   ```

### 需要下载的文件

解压后，将以下文件放入本目录 (`vits-aishell3/`)：

| 文件 | 说明 |
|------|------|
| `model.onnx` | VITS 声学模型（~50MB） |
| `tokens.txt` | 音素/字符映射表 |
| `lexicon.txt` | 中文发音词典（可复用 matcha-icefall-zh-baker 中的） |

### 共享文件（复用 matcha-icefall-zh-baker 目录）

以下文件在 Matcha 和 VITS 模型间共享，无需重复下载：

| 文件 | 来源 |
|------|------|
| `lexicon.txt` | 可复用 `matcha-icefall-zh-baker/lexicon.txt` |
| `date.fst` | 可复用 `matcha-icefall-zh-baker/date.fst` |
| `number.fst` | 可复用 `matcha-icefall-zh-baker/number.fst` |
| `phone.fst` | 可复用 `matcha-icefall-zh-baker/phone.fst` |
| `dict/` | 可复用 `matcha-icefall-zh-baker/dict/` 目录 |

> **注意**：如果 VITS 模型目录下缺少 lexicon.txt 或 dict 目录，
> TTS 引擎会自动回退到 Matcha 模型目录查找这些文件。

## 快速脚本

```bash
# 下载并解压 VITS AISHELL-3 模型
cd android/app/src/main/assets/vits-aishell3
curl -L -o vits-aishell3.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-aishell3.tar.bz2
tar -xjf vits-aishell3.tar.bz2
rm vits-aishell3.tar.bz2
```

## 模型切换

TTS 引擎会自动检测可用的模型：
- 优先使用 VITS (aishell3) 模型
- 若 VITS 模型不存在，回退使用 Matcha (baker) 模型
- 可在「设置 → 语音设置」中查看当前使用的模型类型
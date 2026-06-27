# TTS VITS 替换方案

> 创建日期：2026-06-27
> 方案版本：v1.0
> 目标：将 Sherpa-ONNX Matcha TTS 替换为 VITS TTS，零代码改动，只换模型文件

---

## 一、现状分析

### 当前 TTS 架构

| 项目 | 详情 |
|------|------|
| 引擎 | Sherpa-ONNX（JNI 集成，本地 .so 库） |
| 模型类型 | Matcha（流匹配） |
| 模型名 | matcha-icefall-zh-baker |
| 训练数据 | 中文女声，Baker 数据集，约 12 小时 |
| 声学模型 | `model-steps-3.onnx`（72MB） |
| 声码器 | `vocos-22khz-univ.onnx`（51MB） |
| 总模型体积 | 约 139MB（含 tokens、lexicon、dict 等） |
| 采样率 | 22050 Hz |
| 配置类 | `OfflineTtsMatchaModelConfig` |
| 关键代码 | `SherpaTtsEngine.kt` 第 95-107 行 |

### 当前模型文件清单

```
android/app/src/main/assets/
├── vocos-22khz-univ.onnx                    # 声码器（51MB）
└── matcha-icefall-zh-baker/
    ├── model-steps-3.onnx                   # 声学模型（72MB）
    ├── tokens.txt
    ├── lexicon.txt
    ├── phone.fst
    ├── number.fst
    ├── date.fst
    └── dict/
        ├── jieba.dict.utf8
        ├── user.dict.utf8
        ├── hmm_model.utf8
        ├── idf.utf8
        ├── stop_words.utf8
        └── pos_dict/
            ├── char_state_tab.utf8
            ├── prob_emit.utf8
            ├── prob_start.utf8
            └── prob_trans.utf8
```

### 当前 Kotlin 配置代码（SherpaTtsEngine.kt，第 95-107 行）

```kotlin
val config = OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        matcha = OfflineTtsMatchaModelConfig(
            acousticModel = acousticModel,    // "matcha-icefall-zh-baker/model-steps-3.onnx"
            vocoder = VOCODER,                // "vocos-22khz-univ.onnx"
            lexicon = lexicon,                // "matcha-icefall-zh-baker/lexicon.txt"
            tokens = tokens,                  // "matcha-icefall-zh-baker/tokens.txt"
            dataDir = "",
        ),
        numThreads = 2,
        debug = false,
        provider = "cpu",
    ),
)
```

---

## 二、候选模型对比

### 2.1 模型总览

以下模型均来自 Sherpa-ONNX 官方预训练模型页面，可直接用于 Android 端，无需额外转换。

| # | 模型名 | 下载包名 | 语言 | 说话人 | 模型大小 | 采样率 | RTF(Pi4/1线程) | RTF(Pi4/4线程) |
|---|--------|---------|------|--------|---------|--------|---------------|---------------|
| **A** | **vits-icefall-zh-aishell3** | `vits-icefall-zh-aishell3.tar.bz2` | 中文 | **174人** | 116MB | 8000Hz | **0.365** | **0.156** |
| B | vits-melo-tts-zh_en | `vits-melo-tts-zh_en.tar.bz2` | 中英双语 | 1人 | 163MB | 44100Hz | 6.727 | 2.518 |
| C | sherpa-onnx-vits-zh-ll | `sherpa-onnx-vits-zh-ll.tar.bz2` | 中文 | 5人 | 115MB | 16000Hz | 4.275 | 1.593 |
| D | vits-zh-hf-fanchen-C | `vits-zh-hf-fanchen-C.tar.bz2` | 中文 | 187人 | 116MB | 16000Hz | 4.306 | 1.600 |
| E | vits-zh-hf-fanchen-wnj | `vits-zh-hf-fanchen-wnj.tar.bz2` | 中文 | 1男 | 116MB | 16000Hz | 4.276 | 1.608 |
| F | vits-zh-hf-theresa | `vits-zh-hf-theresa.tar.bz2` | 中文 | 804人 | 117MB | 22050Hz | 6.032 | 2.210 |
| G | vits-zh-hf-eula | `vits-zh-hf-eula.tar.bz2` | 中文 | 804人 | 117MB | 22050Hz | 6.011 | 2.231 |
| **对比** | **matcha-icefall-zh-baker（当前）** | — | 中文 | 1女 | 73MB+51MB | 22050Hz | 0.892 | 0.391 |

> RTF（Real-Time Factor）在 Raspberry Pi 4 Model B 上测试，值越小越快。RTF < 1.0 表示实时性良好。

### 2.2 首选推荐：vits-icefall-zh-aishell3（模型 A）

**推荐理由：**

1. **推理速度最快**：RTF 仅 0.365（1线程），比当前 Matcha（0.892）快 2.4 倍，在手机上几乎无延迟
2. **多说话人**：174 个说话人，可通过 `sid` 参数切换不同音色
3. **训练数据优质**：AISHELL-3 是业界标准的中文 TTS 数据集，录音质量高（218 位录音人在安静环境中录制）
4. **体积更小**：116MB 单文件（不含声码器），vs 当前 Matcha 的 123MB（72+51）
5. **官方 Android APK 验证**：已有专门的中文 VITS 官方 APK 可用

**缺点：**
- 采样率仅 8000Hz（电话音质），但作为对话朗读场景已足够
- 不直接支持英文混读（但可通过 lexicon 扩展）

### 2.3 次选推荐：vits-melo-tts-zh_en（模型 B）

**推荐理由：**

1. **中英双语**：无需额外配置即可朗读中英文混合文本
2. **音质最好**：44.1kHz 采样率，基于 MeloTTS，接近自然语音
3. **模型体积可接受**：163MB，在移动端可接受范围

**缺点：**
- 推理速度最慢（RTF 6.727），手机上可能有 1-3 秒延迟
- 仅 1 个说话人，无法切换音色

### 2.4 其他模型简评

| 模型 | 适用场景 |
|------|---------|
| C (vits-zh-ll) | 5 说话人可选，速度中等，音质一般 |
| D (fanchen-C) | 187 说话人，音色丰富，但 RTF 较高 |
| E (fanchen-wnj) | 1 个男声，如需男声可考虑 |
| F/G (theresa/eula) | 804 说话人，最多选择，但 RTF 最高 |

---

## 三、替换实施步骤

### 3.1 方案 A：替换为 vits-icefall-zh-aishell3（首选）

#### 步骤 1：下载模型

```bash
# 下载地址
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2

# 解压
tar xvf vits-icefall-zh-aishell3.tar.bz2
```

解压后文件结构：
```
vits-icefall-zh-aishell3/
├── model.onnx          # 116MB，VITS 模型（自包含声学+声码器）
├── tokens.txt
├── lexicon.txt
├── phone.fst
├── number.fst
├── date.fst
└── dict/
    ├── jieba.dict.utf8
    ├── user.dict.utf8
    └── ...
```

#### 步骤 2：替换模型文件

**删除旧文件：**
```bash
# 删除 Matcha 模型目录
rm -rf android/app/src/main/assets/matcha-icefall-zh-baker/

# 删除旧声码器（VITS 不需要独立声码器）
rm android/app/src/main/assets/vocos-22khz-univ.onnx
```

**部署新模型：**
```bash
# 将解压后的模型目录复制到 assets
cp -r vits-icefall-zh-aishell3/ android/app/src/main/assets/vits-icefall-zh-aishell3/
```

#### 步骤 3：修改 Kotlin 代码

**文件：** `android/app/src/main/java/com/aicompanion/app/speech/SherpaTtsEngine.kt`

**改动 1：修改常量定义（第 23-29 行）**

```diff
  companion object {
      private const val TAG = "SherpaTtsEngine"
-     private const val MODEL_DIR = "matcha-icefall-zh-baker"
-     private const val ACOUSTIC_MODEL = "model-steps-3.onnx"
-     private const val VOCODER = "vocos-22khz-univ.onnx"
-     private const val LEXICON = "lexicon.txt"
+     private const val MODEL_DIR = "vits-icefall-zh-aishell3"
+     private const val VITS_MODEL = "model.onnx"
+     private const val LEXICON = "lexicon.txt"
  }
```

**改动 2：添加 import（第 11 行附近）**

```diff
  import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
+ import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
```

**改动 3：修改配置构建（第 95-107 行）**

```diff
  val config = OfflineTtsConfig(
      model = OfflineTtsModelConfig(
-         matcha = OfflineTtsMatchaModelConfig(
-             acousticModel = acousticModel,
-             vocoder = VOCODER,
-             lexicon = lexicon,
-             tokens = tokens,
-             dataDir = "",
-         ),
+         vits = OfflineTtsVitsModelConfig(
+             model = "$modelDir/$VITS_MODEL",
+             lexicon = lexicon,
+             tokens = tokens,
+             dataDir = "",
+         ),
          numThreads = 2,
          debug = false,
          provider = "cpu",
      ),
  )
```

**改动 4：修改模型验证逻辑（第 81-85 行附近）**

```diff
  try {
-     assetManager.open(acousticModel).close()
+     assetManager.open("$modelDir/$VITS_MODEL").close()
      assetManager.open(lexicon).close()
      assetManager.open(tokens).close()
-     assetManager.open(VOCODER).close()
  } catch (e: Exception) {
```

#### 步骤 4：更新 ProGuard 规则（可选）

**文件：** `android/app/proguard-rules.pro`

```diff
  -keep class com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig { *; }
+ -keep class com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig { *; }
```

#### 步骤 5：编译验证

```bash
cd android
./gradlew assembleDebug
```

### 3.2 方案 B：替换为 vits-melo-tts-zh_en（中英双语）

步骤与方案 A 类似，差异如下：

| 差异项 | 方案 A | 方案 B |
|--------|--------|--------|
| 下载包 | `vits-icefall-zh-aishell3.tar.bz2` | `vits-melo-tts-zh_en.tar.bz2` |
| 模型目录 | `vits-icefall-zh-aishell3/` | `vits-melo-tts-zh_en/` |
| 模型文件 | `model.onnx`（116MB） | `model.onnx`（163MB） |
| 采样率 | 8000Hz | 44100Hz |
| 线程数建议 | 2 | 4（更大模型，建议多线程） |

```kotlin
// 方案 B 的 numThreads 建议
numThreads = 4,
```

---

## 四、验证方法

### 4.1 编译验证

```bash
cd android
./gradlew assembleDebug
```

期望结果：编译成功，无错误。

### 4.2 运行时验证

1. 安装 APK 到真机
2. 启动 APP，进入对话界面
3. 发送一段中文文本，AI 回复后应自动播放 TTS 语音
4. 检查 Logcat 日志：

```bash
adb logcat -s SherpaTtsEngine:D
```

期望日志：
```
SherpaTtsEngine: 开始初始化 Sherpa-ONNX TTS 引擎...
SherpaTtsEngine: Sherpa-ONNX TTS 引擎初始化成功, 采样率=8000
SherpaTtsEngine: 开始合成: 你好，我是AI助手...
SherpaTtsEngine: 合成完成, samples=xxxxx, sampleRate=8000
SherpaTtsEngine: 播放完成
```

### 4.3 测试合格标准

| 测试项 | 通过标准 |
|--------|---------|
| 编译 | `assembleDebug` 成功，无错误 |
| 初始化 | Logcat 显示"初始化成功"，无 crash |
| 合成 | 生成音频样本数 > 0 |
| 播放 | 能听到清晰的语音朗读 |
| 多说话人 | 切换 `sid` 参数（0-173），音色有明显变化 |
| 降级模式 | 删除模型文件，APP 不 crash，提示"语音朗读已禁用" |

### 4.4 多说话人测试（方案 A 特有）

```kotlin
// 在 synthesize 方法中，修改 GenerationConfig 指定说话人
val genConfig = GenerationConfig(speed = speed, sid = 0)  // 0-173
```

---

## 五、预期效果对比

### 5.1 音质评估

| 维度 | Matcha（当前） | VITS-AISHELL3（方案A） | VITS-MeloTTS（方案B） |
|------|:---:|:---:|:---:|
| 自然度 | ★★★☆☆ | ★★★★☆ | ★★★★★ |
| 清晰度 | ★★★★☆ | ★★★★☆ | ★★★★★ |
| 韵律感 | ★★★☆☆ | ★★★★☆ | ★★★★☆ |
| 噪音水平 | ★★★★☆ | ★★★★☆ | ★★★★★ |
| 中英混读 | ★★★★☆ | ★★☆☆☆ | ★★★★★ |
| 多音色 | ❌ | ✅ 174人 | ❌ |

> 注：音质评估基于公开评测和社区反馈，以实际听感为准。

### 5.2 体积变化

| 项目 | 当前（Matcha） | 方案 A（AISHELL3） | 方案 B（MeloTTS） |
|------|:---:|:---:|:---:|
| 模型文件 | 72MB | 116MB | 163MB |
| 声码器 | 51MB | 0（内置） | 0（内置） |
| 词典/字典 | ~16MB | ~16MB | ~6.5MB |
| **总计** | **~139MB** | **~132MB** | **~170MB** |
| APK 体积影响 | 基准 | **-7MB** | +31MB |

### 5.3 性能影响（预估）

| 指标 | 当前（Matcha） | 方案 A（AISHELL3） | 方案 B（MeloTTS） |
|------|:---:|:---:|:---:|
| 模型加载时间 | ~1-2s | ~1-2s | ~2-3s |
| 合成延迟（短句） | <0.5s | **<0.3s** | 1-3s |
| 内存占用 | ~200MB | ~200MB | ~300MB |
| CPU 使用率 | 中 | **低** | 高 |

---

## 六、回滚方案

如果 VITS 效果不理想，可快速回退到 Matcha：

### 6.1 文件回滚

```bash
cd android/app/src/main/assets/

# 删除 VITS 模型目录
rm -rf vits-icefall-zh-aishell3/

# 恢复 Matcha 模型（从 Git 或备份）
# 如果 Matcha 模型仍在 .git 跟踪中：
git checkout -- matcha-icefall-zh-baker/
git checkout -- vocos-22khz-univ.onnx
```

### 6.2 代码回滚

```bash
cd android
git checkout -- app/src/main/java/com/aicompanion/app/speech/SherpaTtsEngine.kt
git checkout -- app/proguard-rules.pro
```

### 6.3 建议：保留 Matcha 模型在 Git 中

```bash
# 确保 Matcha 模型文件仍在 git 跟踪中，不要 git rm
# 如果模型文件太大，可使用 Git LFS
git lfs track "*.onnx"
```

### 6.4 快速 A/B 切换方案（可选优化）

如果要支持运行时切换，可修改 `SherpaTtsEngine` 为支持两种模型配置：

```kotlin
enum class TtsModelType { MATCHA, VITS }

fun initialize(modelType: TtsModelType = TtsModelType.VITS) {
    // 根据 modelType 选择不同的配置
}
```

---

## 七、后续优化路径

### 7.1 多模型切换（v2.1）

**目标：** 用户可在设置中选择不同 TTS 模型

**实现思路：**
1. 在 `SettingsDetailActivity` 中添加 TTS 模型选择器
2. 模型列表配置化（JSON 文件描述模型路径、参数）
3. `SherpaTtsEngine` 支持运行时销毁和重建不同模型
4. 模型通过 `SharedPreferences` 持久化选择

**模型清单示例：**
```json
{
  "models": [
    {
      "id": "vits-aishell3",
      "name": "AISHELL-3 多音色",
      "type": "vits",
      "dir": "vits-icefall-zh-aishell3",
      "model": "model.onnx",
      "numSpeakers": 174
    },
    {
      "id": "vits-melo",
      "name": "MeloTTS 中英双语",
      "type": "vits",
      "dir": "vits-melo-tts-zh_en",
      "model": "model.onnx",
      "numSpeakers": 1
    },
    {
      "id": "matcha-baker",
      "name": "Matcha 女声",
      "type": "matcha",
      "dir": "matcha-icefall-zh-baker",
      "model": "model-steps-3.onnx",
      "vocoder": "vocos-22khz-univ.onnx",
      "numSpeakers": 1
    }
  ]
}
```

### 7.2 模型导入导出（.ttsmodel.zip）

**目标：** 用户可自行下载模型，导入 APP

**格式设计：**
```
my_model.ttsmodel.zip
├── model.json              # 模型元数据
│   {
│     "type": "vits",
│     "name": "自定义模型",
│     "language": "zh",
│     "version": "1.0",
│     "sampleRate": 22050
│   }
├── model.onnx              # 主模型
├── tokens.txt
├── lexicon.txt
└── dict/                   # 可选
```

**实现要点：**
1. 文件选择器：`Intent.ACTION_OPEN_DOCUMENT`
2. 解压到 `app/files/tts_models/` 目录
3. 校验 `model.json` 完整性
4. 注册到模型列表
5. `SherpaTtsEngine` 使用文件路径而非 AssetManager 加载（`OfflineTts(null, config)` 模式）

### 7.3 PC 端模型训练

**目标：** 用户可训练自己的声音模型，导出为 ONNX 供移动端使用

**技术路线：**

| 阶段 | 内容 | 工具 |
|------|------|------|
| 数据准备 | 录制 20-200 句中文语音 | 手机 APP 录音模块 |
| 自动标注 | 语音转文字对齐 | Whisper / MFA |
| 训练 | 在 PC 上微调 VITS | https://github.com/jaywalnut310/vits |
| 转换 | PyTorch → ONNX | sherpa-onnx 转换脚本 |
| 导出 | 打包为 .ttsmodel.zip | APP 内导出功能 |

**参考项目：**
- Sherpa-ONNX 模型转换脚本：https://github.com/k2-fsa/sherpa-onnx/tree/master/scripts
- AISHELL-3 训练脚本：https://github.com/k2-fsa/icefall/tree/master/egs/aishell3/TTS

---

## 八、风险与注意事项

### 8.1 已知风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 采样率 8000Hz 音质偏低 | 方案 A 音质可能不如预期 | 方案 B 作为备选（44100Hz） |
| 模型文件较大，APK 体积增加 | 方案 B 增加 31MB | 方案 A 反而减少 7MB |
| 某些 Android 设备兼容性 | 模型加载失败 | 降级模式自动处理 |
| ProGuard 混淆导致类找不到 | 运行时 crash | 更新 ProGuard 规则 |

### 8.2 注意事项

1. **不要删除旧模型文件后再提交**：先在本地测试，确认无误后再清理
2. **模型文件许可证**：AISHELL-3 数据集仅限非商业用途，MeloTTS 为 MIT 许可
3. **Sherpa-ONNX 版本兼容性**：当前使用的 JNI 库版本需确认支持 VITS 模型（通常 >= 1.8 版本均支持）
4. **首次启动需下载模型**：如果 APK 体积过大，可考虑首次启动时从服务器下载模型

---

## 九、总结与建议

### 推荐方案

| 优先级 | 方案 | 适用场景 |
|:---:|------|---------|
| **首选** | 方案 A：vits-icefall-zh-aishell3 | 追求速度快、多音色、APK 体积小 |
| 备选 | 方案 B：vits-melo-tts-zh_en | 追求音质高、中英混读 |

### 实施建议

1. **先实施方案 A**，验证在真机上的效果
2. 如果音质不满意（8000Hz 偏低），切换到方案 B
3. 保留 Matcha 模型在 Git 中作为降级回退方案
4. 后续迭代实现多模型切换功能

### 改动量统计

| 文件 | 改动类型 | 行数 |
|------|---------|:---:|
| `SherpaTtsEngine.kt` | 修改 | ~15 行 |
| `proguard-rules.pro` | 修改 | 1 行 |
| 模型文件 | 替换 | 删除旧文件，新增 132MB |
| **总计** | — | **约 16 行代码改动** |
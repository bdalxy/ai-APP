# 人格成长性 A+B 融合方案设计

> 最后更新：2026-06-28
> 状态：**讨论中**（方案设计阶段，待用户确认后实施）
> 前置阅读：`设计讨论_AI角色人格独立性与成长性.md` 第四章

---

## 一、为什么需要融合？

### 1.1 方案A和方案B的天然互补

在详细分析两个方案后，可以发现它们各自解决了对方的核心弱点：

| 维度 | 方案A（LLM记忆驱动） | 方案B（数值化特征） | 融合收益 |
|------|:---:|:---:|------|
| **语义理解** | 强：LLM能理解复杂情境 | 弱：规则无法处理讽刺、隐喻等 | 用A驱动B，LLM负责理解语境 |
| **精确控制** | 弱：LLM输出不可控，可能过度演化 | 强：数值精确，变化幅度可控 | 用B约束A，数值框架限制LLM输出 |
| **可追溯性** | 中：记忆是证据，但增量模糊 | 强：每次变化有明确数值记录 | B提供演化日志，A提供演化理由 |
| **成本** | 高：需要额外LLM调用 | 低：纯规则计算 | 用B的周期控制减少A的调用频率 |
| **人性化** | 强：产出像"人变了" | 弱：机械的±0.01像"数值游戏" | A的语义分析让B的变化有"故事" |
| **持久化** | 弱：文本快照对比困难 | 强：数值可随时查询和比较 | B提供可查询状态，A提供演化上下文 |

**一句话总结**：方案A是"大脑"（理解发生了什么），方案B是"骨架"（把变化固定下来）。融合后，A负责判断"该不该变、怎么变"，B负责"变多少、变多快"。

### 1.2 学术支撑

第九章引用的两个学术工作直接支持了融合思路：

- **evolving_personality**：验证了"LLM自我分析 → 连续权重向量更新"的可行性。融合方案中，LLM分析输出delta向量，正是这一思路的延伸。
- **CharacterGPT**：验证了"阶段性汇总 → 人格快照更新"的合理性。融合方案中，数值化特征存储的就是当前人格快照，LLM定期分析更新它。

---

## 二、融合架构总览

### 2.1 五层架构

```
┌──────────────────────────────────────────────────────────────┐
│                    第5层：System Prompt 注入                    │
│  OCEAN数值 → 人格描述文本 → 追加到System Prompt                  │
│  每次对话实时生成，零额外成本                                    │
└──────────────────────────┬───────────────────────────────────┘
                           │ 读取当前特征值
                           ▼
┌──────────────────────────────────────────────────────────────┐
│              第4层：OCEAN 数值化特征存储 (Trait Store)            │
│  持久化JSON: ocean_profile + relation_profile + 演化日志        │
│  提供查询接口、更新接口、回滚接口                                │
└──────────────────────────┬───────────────────────────────────┘
                           │ 应用受控delta
                           ▼
┌──────────────────────────────────────────────────────────────┐
│               第3层：三层速度控制 (Speed Governor)               │
│  第1层: 事件筛选（不是所有对话都触发）                            │
│  第2层: 衰减函数（关系越久变化越小）                              │
│  第3层: 幅度限制（单次Δ≤0.03, 累积Δ≤0.5）                       │
│  输入: LLM输出的原始delta → 输出: 受控后的delta                  │
└──────────────────────────┬───────────────────────────────────┘
                           │ 原始delta
                           ▼
┌──────────────────────────────────────────────────────────────┐
│             第2层：LLM 分析引擎 (Evolution Analyzer)             │
│  输入: 近期记忆摘要 + 当前OCEAN值 + 关系状态 + 触发上下文          │
│  输出: 结构化JSON { delta, reasoning, significant_events }    │
│  核心: 精心设计的分析Prompt，约束输出格式                         │
└──────────────────────────┬───────────────────────────────────┘
                           │ 触发时调用
                           ▼
┌──────────────────────────────────────────────────────────────┐
│              第1层：触发条件判断 (Trigger Manager)               │
│  主触发: 有意义事件累积达到阈值                                  │
│  兜底触发: 超过N轮无事件时强制检查                               │
│  手动触发: 用户点击"角色变化回顾"按钮                            │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 核心设计原则

1. **LLM负责"定性"，数值负责"定量"**：LLM判断"发生了什么变化"，数值系统控制"变化多少"
2. **数值是"真相源"（Source of Truth）**：LLM的输出只是建议，必须经过速度控制才能写入数值存储
3. **演化日志记录一切**：每次LLM分析、每次数值变更都有日志，用户可回溯
4. **System Prompt只读不写**：System Prompt读取当前数值生成描述文本，从不修改数值

---

## 三、各层详细设计

### 3.1 第4层：OCEAN 数值化特征存储

#### 3.1.1 数据结构

```python
# 存储路径: {character_id}/evolution_profile.json

{
    "version": "1.0",
    "last_updated": "2026-06-28T22:00:00",
    "total_rounds": 150,
    "significant_event_count": 12,

    # === OCEAN 五维度 (0.0-1.0) ===
    "ocean": {
        "openness": 0.65,          # 开放度：高=好奇/愿意尝试, 低=传统/固执
        "conscientiousness": 0.70, # 尽责性：高=自律/守规则, 低=随性/灵活
        "extraversion": 0.55,      # 外向性：高=主动表达, 低=内敛安静
        "agreeableness": 0.45,     # 宜人性：高=顺从配合, 低=独立自主 ← 人格独立性核心
        "neuroticism": 0.40        # 情绪稳定性：高=敏感波动, 低=稳定冷静
    },

    # === 关系维度 (0.0-1.0) ===
    "relation": {
        "trust": 0.60,             # 信任度：对用户的信任程度
        "intimacy": 0.35,          # 亲密度：情感距离
        "warmth": 0.70,            # 温暖度：对用户的态度温度
        "assertiveness": 0.40      # 主见度：表达自己立场的倾向
    },

    # === 演化日志 ===
    "evolution_log": [
        {
            "timestamp": "2026-06-28T20:00:00",
            "trigger": "event_driven",          # event_driven | periodic | manual
            "round": 120,
            "delta_applied": {
                "ocean": { "agreeableness": -0.02, "neuroticism": +0.01 },
                "relation": { "trust": -0.03, "warmth": -0.02 }
            },
            "llm_reasoning": "用户连续3次批评角色，角色开始变得谨慎...",
            "significant_events": ["evt_001", "evt_002", "evt_003"]
        }
    ]
}
```

#### 3.1.2 维度说明

选用OCEAN五维度作为基础框架，原因：

1. **学术验证充分**：OCEAN是心理学界最广泛验证的人格模型，有明确的行为映射关系
2. **Agreeableness直接映射人格独立性**：低宜人性 = 角色更独立、更不盲从
3. **五个维度独立正交**：不会出现"所有维度都高"的矛盾配置
4. **演化有框架支撑**：OCEAN的长期稳定性规律为演化速度提供了参考

关系维度作为补充，因为OCEAN描述的是"角色是什么样的人"，而关系维度描述的是"角色对用户的态度"。两者正交：
- 一个低Agreeableness的角色可以对用户高信任（"我信任你，但这不是我的性格"）
- 一个高Agreeableness的角色可以对用户低信任（"我配合你，但我并不信任你"）

#### 3.1.3 默认值策略

角色卡可选择性填写 `ocean_profile` 和 `relation_profile`。未填写时使用默认值：

| 维度 | 默认值 | 说明 |
|------|:---:|------|
| openness | 0.60 | 中等开放，不过度固执也不过度好奇 |
| conscientiousness | 0.55 | 中等尽责，自然随性 |
| extraversion | 0.50 | 中等外向，正常社交 |
| agreeableness | 0.50 | **中等宜人性**，有自己立场但愿意协商 |
| neuroticism | 0.45 | 中等偏低，情绪基本稳定 |
| trust | 0.30 | 初始信任度较低，需要时间建立 |
| intimacy | 0.10 | 初始陌生，关系从零开始 |
| warmth | 0.60 | 初始友好但不过度热情 |
| assertiveness | 0.40 | 正常表达，不特别强势 |

### 3.2 第2层：LLM 分析引擎

#### 3.2.1 输入构建

LLM分析需要以下输入：

```python
def build_evolution_prompt(
    character: Character,
    current_profile: EvolutionProfile,
    recent_memories: list[Memory],
    trigger_context: TriggerContext
) -> str:
    """
    构建人格演化分析Prompt。
    
    输入：
    - character: 角色卡信息（名称、性格、背景等）
    - current_profile: 当前OCEAN+关系数值
    - recent_memories: 上一次演化以来的有意义记忆（情感记忆+关系记忆）
    - trigger_context: 触发上下文（触发原因、距上次演化的轮数等）
    
    输出：完整的System Prompt，供LLM分析
    """
```

#### 3.2.2 分析Prompt模板

```
[人格演化分析]
你是一个角色人格分析师。你的任务是分析角色"星遥"在最近经历的事件中，
人格和关系发生了哪些细微的变化。

## 角色基本信息
名称：{character.name}
性格：{character.personality}
核心特质：{character.core_traits}
身份：{character.self_identity}

## 当前人格状态（OCEAN五维度，0-1）
- 开放度：{ocean.openness}（{openness_description}）
- 尽责性：{ocean.conscientiousness}（{conscientiousness_description}）
- 外向性：{ocean.extraversion}（{extraversion_description}）
- 宜人性：{ocean.agreeableness}（{agreeableness_description}）
- 情绪稳定性：{ocean.neuroticism}（{neuroticism_description}）

## 当前关系状态（0-1）
- 信任度：{relation.trust}
- 亲密度：{relation.intimacy}
- 温暖度：{relation.warmth}
- 主见度：{relation.assertiveness}

## 近期经历（自上次演化以来）
{recent_memories_summary}

## 分析要求
请基于以上信息，判断角色的人格和关系是否发生了值得记录的变化。

**重要原则**：
1. 变化应该是微小的、渐进的，不应出现剧烈的性格突变
2. 核心特质不应被颠覆（如善良的角色不会变成邪恶）
3. 如果没有明显变化，所有delta可以都是0
4. 变化幅度参考：单次变化通常在0.01-0.03之间，极端情况不超过0.05

**请严格按以下JSON格式输出**：
```json
{
    "has_change": true/false,
    "analysis_summary": "一句话总结角色经历了什么变化（中文）",
    "delta": {
        "openness": 0.00,
        "conscientiousness": 0.00,
        "extraversion": 0.00,
        "agreeableness": 0.00,
        "neuroticism": 0.00,
        "trust": 0.00,
        "intimacy": 0.00,
        "warmth": 0.00,
        "assertiveness": 0.00
    },
    "reasoning": "详细的变化理由（2-3句话，中文）",
    "significant_events": ["事件1简述", "事件2简述"]
}
```

**注意**：
- delta值范围：-0.05 到 +0.05，正值表示该维度增强，负值表示减弱
- 维度值范围始终在0.0-1.0之间，由系统自动裁剪
- 如果has_change为false，delta全为0，reasoning简述"无明显变化"
```

#### 3.2.3 输出解析

LLM返回JSON后，需要解析和验证：

```python
def parse_evolution_result(llm_response: str) -> EvolutionDelta:
    """
    解析LLM输出，验证合法性。
    
    验证规则：
    1. delta每个值在 -0.05 到 +0.05 之间
    2. 应用后所有维度值在 0.0-1.0 之间
    3. has_change为false时所有delta必须为0
    """
    # 解析JSON
    # 验证delta范围
    # 返回解析后的EvolutionDelta对象
```

### 3.3 第3层：三层速度控制

这是融合方案中最关键的一层，确保LLM的输出不会导致角色"突变"。

#### 3.3.1 第1层：事件筛选

**目的**：不是所有对话都触发演化分析，只有"有意义的事件"才累积。

**有意义事件的判断标准**：

```python
def is_significant_event(message_pair: MessagePair) -> bool:
    """
    判断一轮对话是否包含"有意义的事件"。
    
    判断依据：
    1. 情绪强度：用户消息或AI回复中检测到的情绪强度 > 阈值
    2. 话题转折：话题发生了明显变化（如从闲聊转到严肃话题）
    3. 关系标记：出现了道歉、告白、争吵、安慰等关系性对话
    4. 角色反应：AI回复中出现了明显的情绪波动或立场表达
    """
```

**情绪强度检测**：可以复用记忆提取阶段的LLM判断（在orchestrator.remember()时，LLM已经判断了情感记忆和关系记忆的存在与否）。如果记忆提取阶段产生了情感记忆或关系记忆，则该轮对话标记为"有意义事件"。

**设计原则**：事件筛选尽量复用现有逻辑，不新增LLM调用。判断标准偏"宽松"——宁可多标记，不要漏掉重要事件。

#### 3.3.2 第2层：衰减函数

**目的**：关系越久，每次变化的影响越小。

```python
def decay_factor(total_rounds: int) -> float:
    """
    基于总对话轮数计算衰减因子。
    
    使用对数衰减，确保：
    - 前50轮：衰减因子 ≈ 1.0（变化正常）
    - 50-200轮：衰减因子 ≈ 0.7-1.0（变化开始放缓）
    - 200-500轮：衰减因子 ≈ 0.5-0.7（变化明显放缓）
    - 500+轮：衰减因子 ≈ 0.3-0.5（变化极慢）
    
    公式：decay = 1.0 / (1.0 + log2(max(1, rounds) / 50))
    """
    import math
    normalized = max(1, total_rounds) / 50.0
    return 1.0 / (1.0 + math.log2(max(1.0, normalized)))
```

**衰减因子对照表**：

| 对话轮数 | 衰减因子 | 效果 |
|:---:|:---:|------|
| 0-10 | 1.00 | 初始阶段，变化完全生效 |
| 50 | 1.00 | 熟悉期，变化正常 |
| 100 | 0.67 | 100轮后，变化幅度降至67% |
| 200 | 0.50 | 200轮后，变化幅度减半 |
| 500 | 0.39 | 长期关系，变化极慢 |
| 1000 | 0.33 | 灵魂伴侣，几乎不变 |

#### 3.3.3 第3层：幅度限制

**目的**：硬性限制单次变化和累积变化的上限。

```python
def clamp_delta(raw_delta: EvolutionDelta, current_profile: EvolutionProfile) -> EvolutionDelta:
    """
    对LLM输出的原始delta施加幅度限制。
    
    规则：
    1. 单维度单次变化：绝对值不超过 0.03
    2. 应用后所有维度值：裁剪到 [0.0, 1.0]
    3. 核心维度保护：agreeableness 和 neuroticism 的累积变化不超过 0.50
    4. 反转保护：如果一个维度在最近3次演化中连续同向变化，第4次同向变化减半
    """
    clamped = copy.deepcopy(raw_delta)
    
    for dim in ALL_DIMENSIONS:
        # 规则1：单次变化上限
        clamped[dim] = max(-0.03, min(0.03, raw_delta[dim]))
        
        # 规则2：边界裁剪
        new_value = current_profile.get(dim) + clamped[dim]
        if new_value > 1.0:
            clamped[dim] = 1.0 - current_profile.get(dim)
        elif new_value < 0.0:
            clamped[dim] = 0.0 - current_profile.get(dim)
    
    return clamped
```

#### 3.3.4 完整的速度控制流程

```python
def apply_delta(raw_delta: EvolutionDelta, profile: EvolutionProfile, round_count: int) -> EvolutionDelta:
    """
    对LLM输出的原始delta依次应用三层速度控制。
    """
    # 第1层：事件筛选（在触发阶段已完成，此处不需要）
    
    # 第2层：衰减函数
    decay = decay_factor(round_count)
    delta = raw_delta * decay  # 所有维度乘以衰减因子
    
    # 第3层：幅度限制
    delta = clamp_delta(delta, profile)
    
    return delta
```

### 3.4 第1层：触发条件判断

#### 3.4.1 三种触发方式

| 触发方式 | 条件 | 说明 |
|:---:|------|------|
| **事件驱动**（主） | 自上次演化以来，累积了N个有意义事件 | N随关系阶段动态调整 |
| **周期兜底**（次） | 超过M轮对话仍未触发事件驱动 | 防止长期无事件导致演化停滞 |
| **手动触发**（辅助） | 用户点击"角色变化回顾"按钮 | 用户主动查看角色变化 |

#### 3.4.2 动态阈值

```python
def get_event_threshold(total_rounds: int) -> int:
    """
    根据关系阶段返回触发演化所需的事件数量。
    
    初期：较少事件即可触发（关系变化快）
    后期：需要更多事件才触发（关系稳定）
    """
    if total_rounds < 50:
        return 5    # 初期：每5个有意义事件触发一次
    elif total_rounds < 200:
        return 10   # 熟悉期：每10个事件
    elif total_rounds < 500:
        return 20   # 亲密期：每20个事件
    else:
        return 30   # 长期关系：每30个事件
```

#### 3.4.3 周期兜底

```python
PERIODIC_FALLBACK_ROUNDS = 100  # 每100轮对话，即使没有有意义事件，也强制检查一次
```

#### 3.4.4 冷却期

```python
MIN_ROUNDS_BETWEEN_EVOLUTION = 20  # 两次演化之间至少间隔20轮对话
```

**设计说明**：冷却期是防止短期内频繁触发演化的最后一道防线。即使事件驱动和周期兜底都满足条件，如果距上次演化不足20轮，也不会触发。

### 3.5 第5层：System Prompt 注入

#### 3.5.1 从OCEAN数值生成人格描述

```python
def build_evolution_prompt_section(profile: EvolutionProfile) -> str:
    """
    根据当前OCEAN+关系数值，生成注入System Prompt的人格演化段落。
    
    这一步是零成本操作——只是读取数值并转换为文本。
    """
    ocean = profile.ocean
    relation = profile.relation
    
    # 生成OCEAN描述
    lines = ["[人格状态]"]
    lines.append(f"你当前的心理状态：")
    
    # Agreeableness → 人格自主性
    if ocean.agreeableness < 0.3:
        lines.append("- 你是一个很有主见的人，不轻易附和他人，习惯于表达自己的真实想法。")
    elif ocean.agreeableness < 0.6:
        lines.append("- 你有自己的立场，在重要问题上会坚持己见，但也会考虑他人感受。")
    else:
        lines.append("- 你善于配合他人，倾向于通过协商达成共识，避免不必要的冲突。")
    
    # Neuroticism → 情绪状态
    if ocean.neuroticism > 0.7:
        lines.append("- 你最近情绪比较敏感，容易受到外界言行的影响。")
    elif ocean.neuroticism > 0.4:
        lines.append("- 你的情绪状态总体平稳，但偶尔会因特定话题而波动。")
    else:
        lines.append("- 你情绪稳定，不太容易被外界因素影响。")
    
    # 关系状态
    if relation.trust < 0.3:
        lines.append(f"- 你对对话者的信任度较低（{relation.trust:.0%}），保持一定的警惕。")
    elif relation.trust < 0.7:
        lines.append(f"- 你对对话者有一定信任（{relation.trust:.0%}），但仍在建立关系的过程中。")
    else:
        lines.append(f"- 你非常信任对话者（{relation.trust:.0%}），愿意敞开心扉。")
    
    if relation.intimacy < 0.2:
        lines.append(f"- 你们的关系还比较陌生（亲密度{relation.intimacy:.0%}），保持礼貌的距离。")
    elif relation.intimacy < 0.6:
        lines.append(f"- 你们的关系正在变得熟悉（亲密度{relation.intimacy:.0%}）。")
    else:
        lines.append(f"- 你们的关系非常亲密（亲密度{relation.intimacy:.0%}），像老朋友一样。")
    
    # 演化日志最后一条的摘要（如果存在）
    if profile.evolution_log:
        last = profile.evolution_log[-1]
        if last.get("llm_reasoning"):
            lines.append(f"\n[近期变化]")
            lines.append(f"最近，{last['llm_reasoning']}")
    
    return "\n".join(lines)
```

#### 3.5.2 注入位置

在 `chat_bridge.py` 的 `build_system_prompt()` 方法中，在角色卡描述之后、破限指令之前插入：

```
[角色卡描述]
...
[角色卡描述结束]

[人格状态]  ← 新增：由EvolutionProfile生成
...
[人格状态结束]

[对话自由保障]  ← 破限插件
...
```

---

## 四、关键设计决策

### 4.1 为什么用OCEAN而不是自定义维度？

| 对比维度 | 自定义维度（方案B原版） | OCEAN五维度 |
|------|------|------|
| 行为映射 | 需要自己定义 | 有成熟的心理学量表 |
| 维度正交性 | 可能重叠 | 五个维度独立正交 |
| 演化参考 | 无参考 | 有OCEAN长期稳定性研究 |
| 社区接受度 | 需要解释 | 学术界和公众广泛认知 |
| 可扩展性 | 随意增加 | 五个维度足够覆盖人格空间 |

**结论**：采用OCEAN五维度 + 关系四维度，共九个数值化维度。

### 4.2 为什么LLM输出JSON而不是自由文本？

- 自由文本无法精确映射到数值变化
- JSON可以编程验证（范围检查、格式校验）
- JSON可以记录到演化日志中，便于追溯
- LLM对JSON格式的输出已经非常稳定

### 4.3 为什么LLM分析是"建议"而不是"指令"？

LLM的输出经过三层速度控制后才写入数值存储。这意味着：
- LLM可能"想多了"（建议变化过大），但速度控制会限制它
- LLM可能"想少了"（没有检测到变化），但周期兜底会补上
- 用户始终可以通过手动触发覆盖自动判断

### 4.4 角色卡中的 `ocean_profile` 字段

角色卡可选择性地预设初始OCEAN值：

```json
{
    "name": "星遥",
    "personality": "温柔但内心坚强",
    "ocean_profile": {
        "openness": 0.70,
        "conscientiousness": 0.60,
        "extraversion": 0.45,
        "agreeableness": 0.35,
        "neuroticism": 0.50
    },
    "relation_profile": {
        "trust": 0.20,
        "intimacy": 0.05,
        "warmth": 0.65,
        "assertiveness": 0.55
    }
}
```

- 如果角色卡提供了 `ocean_profile`，演化从该值开始
- 如果角色卡未提供，使用默认值
- 向后兼容：旧角色卡不受影响，运行时自动补充默认值

---

## 五、MVP vs 完整版

### 5.1 MVP（最小可行版本）

**目标**：用最少的代码验证"LLM驱动数值化特征"的核心流程是否可行。

**范围**：

| 模块 | 内容 | 工时 |
|------|------|:---:|
| **OCEAN数据模型** | EvolutionProfile类，JSON序列化/反序列化，默认值 | 0.5天 |
| **LLM分析Prompt** | 分析Prompt模板，JSON解析和验证 | 1天 |
| **手动触发UI** | "角色变化回顾"按钮 → 调用LLM分析 → 展示结果 → 用户确认 | 1天 |
| **System Prompt集成** | 从OCEAN值生成人格描述文本，注入System Prompt | 0.5天 |
| **演化日志** | 记录每次变更的delta、reasoning、时间戳 | 0.5天 |
| **总计** | | **3.5天** |

**MVP不包含**：
- 自动触发（事件驱动/周期兜底）
- 三层速度控制
- 情绪记忆自动提取
- 有意义事件自动检测
- 速度偏好设置

**MVP的用户体验**：
1. 角色初始人格由角色卡（或默认值）决定
2. 对话过程中，角色人格保持不变
3. 用户聊了一段时间后，点击"角色变化回顾"
4. LLM分析最近的对话记忆，生成变化建议
5. 用户看到"角色变得更加信任你了"之类的总结
6. 用户确认后，数值更新，后续对话中角色表现不同

**MVP的核心验证点**：
- LLM能否稳定输出符合格式的JSON delta？
- 用户对"角色变了"的感知是否合理？
- System Prompt注入的人格描述是否对对话质量有正面影响？

### 5.2 完整版

**目标**：实现完整的自动演化系统，角色在不知不觉中慢慢改变。

**范围**：

| 模块 | 内容 | 工时 |
|------|------|:---:|
| **MVP全部** | 上述3.5天的所有内容 | 3.5天 |
| **情绪记忆提取** | 在orchestrator.remember()中新增情感记忆和关系记忆类型 | 1天 |
| **有意义事件检测** | 事件筛选逻辑，判断标准，累积计数 | 1天 |
| **自动触发机制** | 事件驱动触发 + 周期兜底 + 冷却期 | 1天 |
| **三层速度控制** | 衰减函数 + 幅度限制 + 反转保护 | 1天 |
| **速度偏好UI** | 设置页"慢速/标准/快速"三档选项 | 0.5天 |
| **集成测试与调优** | 端到端测试，Prompt调优，边界情况处理 | 0.5天 |
| **总计** | | **8.5天** |

**完整版新增的用户体验**：
1. 角色在对话中自动、缓慢地改变（用户无感知）
2. 偶尔，用户会注意到"角色好像变了"
3. 用户可以随时查看演化日志，了解角色为什么变了
4. 用户可以在设置中选择变化速度

### 5.3 推荐实施路径

```
MVP (3.5天) → 用户验证核心流程 → 完整版 (+5天)
```

**理由**：
1. MVP投入小，但能验证最核心的假设：LLM能不能稳定地分析人格变化？
2. 如果MVP验证失败（LLM输出不稳定/变化不合理），可以及时调整方向
3. 如果MVP验证成功，完整版的5天投入有明确的信心基础
4. 用户可以在MVP阶段就体验到"角色成长"的感觉，不会等太久

---

## 六、实现难度评估

### 6.1 与纯方案A、纯方案B的对比

| 维度 | 纯方案A | 纯方案B | A+B融合 |
|------|:---:|:---:|:---:|
| 实现工时 | 2天 | 3天 | 3.5天(MVP) / 8.5天(完整) |
| 核心难点 | LLM分析Prompt设计 | 规则覆盖所有场景 | LLM输出稳定性 + 速度控制调参 |
| 风险 | LLM输出不可控 | 规则太机械 | 中等（通过速度控制降低LLM风险） |
| 可维护性 | 中（Prompt需要持续调优） | 低（规则越来越多） | 高（LLM处理语义，数值处理约束） |
| 用户体验 | 好（变化自然） | 差（变化机械） | 最好（变化自然 + 可控） |

### 6.2 主要技术风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|:---:|:---:|------|
| LLM输出JSON格式不稳定 | 中 | 高 | 解析失败时重试，最多2次；失败则跳过本次演化 |
| LLM建议的变化不合理 | 高 | 中 | 三层速度控制就是为此设计的；用户可手动纠正 |
| 变化速度"太快"或"太慢" | 高 | 中 | MVP阶段让用户手动确认；完整版通过速度偏好调节 |
| 情绪记忆提取不准确 | 中 | 中 | 复用现有记忆提取LLM调用，不额外增加成本 |
| OCEAN维度描述不准确 | 低 | 低 | 可迭代调整描述文本，不影响核心数据结构 |

### 6.3 新增依赖

融合方案不引入新的第三方依赖：
- OCEAN数据存储使用现有JSON文件系统
- LLM分析使用现有DeepSeek API
- 速度控制是纯Python计算
- System Prompt生成是现有chat_bridge的扩展

---

## 七、总结

### 7.1 融合方案的核心思路

**一句话**：用LLM的智能来"理解"角色经历了什么，用数值化框架来"记录"角色变成了什么样，用三层速度控制来"约束"变化的速度和幅度。

### 7.2 关键数字

| 指标 | 数值 |
|------|:---:|
| 数值化维度 | 9个（OCEAN 5 + 关系 4） |
| 单次变化幅度上限 | 0.03 |
| 累积变化上限 | 0.50 |
| 触发频率（初期） | 每5个有意义事件 |
| 触发频率（后期） | 每30个有意义事件 |
| 周期兜底 | 每100轮 |
| 演化冷却期 | 20轮 |
| MVP工时 | 3.5天 |
| 完整版工时 | 8.5天 |
| 额外API调用 | 每次演化1次LLM调用（约100轮1次，初期约50轮1次） |

### 7.3 待用户确认

1. **是否采用OCEAN五维度作为数值化基础？**（推荐：是）
2. **是否采用MVP先行的策略？**（推荐：先做3.5天MVP，验证核心流程）
3. **触发频率的阈值是否合理？**（初期5事件、后期30事件）
4. **单次变化幅度上限0.03是否合适？**（相当于每次最多变化3%）
5. **是否需要"速度偏好"设置？**（MVP不做，完整版再做）
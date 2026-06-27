# TTS 模型文件体积记录

> 生成时间: 2026-06-27
> 目的: 标记各模型文件体积，为 APK 体积优化提供参考

## 总体积

| 目录/文件 | 大小 |
|-----------|------|
| matcha-icefall-zh-baker/ (含子目录) | **~87.54 MB** |
| vits-aishell3/ (仅 README) | **~2.3 KB** |
| vocos-22khz-univ.onnx | **~51.39 MB** |
| **合计** | **~138.93 MB** |

## matcha-icefall-zh-baker/ 详细

| 文件 | 大小 |
|------|------|
| model-steps-3.onnx | 72.12 MB |
| dict/idf.utf8 | 5.72 MB |
| dict/jieba.dict.utf8 | 4.84 MB |
| dict/pos_dict/prob_emit.utf8 | 1.61 MB |
| lexicon.txt | 1.30 MB |
| dict/user.dict.utf8 | 809.76 KB |
| dict/hmm_model.utf8 | 507.56 KB |
| dict/pos_dict/char_state_tab.utf8 | 319.47 KB |
| dict/pos_dict/prob_trans.utf8 | 121.25 KB |
| phone.fst | 86.55 KB |
| number.fst | 62.97 KB |
| date.fst | 57.77 KB |
| tokens.txt | 19.13 KB |
| dict/stop_words.utf8 | 8.76 KB |
| dict/pos_dict/prob_start.utf8 | 4.25 KB |
| dict/README.md | 0.67 KB |
| dict/generate_user_dict.py | 0.53 KB |
| README.md | 0.36 KB |

## vits-aishell3/ 详细

| 文件 | 大小 |
|------|------|
| README.md | 2.3 KB |

> ⚠️ vits-aishell3 目录下仅有 README，无实际模型文件，可能尚未部署。

## vocos-22khz-univ.onnx

| 文件 | 大小 |
|------|------|
| vocos-22khz-univ.onnx | 51.39 MB |

> Vocos 声码器模型，用于将梅尔频谱转换为音频波形。

## 优化建议

1. **模型量化**: ONNX 模型可考虑 INT8 量化，预计可减少 50-70% 体积
2. **按需下载**: 可将大模型文件改为首次启动时从服务器下载，而非打包进 APK
3. **字典压缩**: jieba.dict.utf8 (4.84 MB) 和 idf.utf8 (5.72 MB) 可考虑 gzip 压缩后运行时解压
4. **vits-aishell3**: 如不使用该模型，可移除整个目录
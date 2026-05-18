# HPROF 双端对比校验（mem-analyze JAR vs hprof_parser.py）

同一份 `.hprof` 分别用 **Kotlin/Shark** 与 **Python 解析器** 导出可对比指标 JSON，再自动 diff。

## 一键对比

```bash
cd mem-analyze
./scripts/compare-with-hprof-parser.sh -f /path/to/heap.hprof -o ./build/compare-reports/my_run
```

环境变量：

- `HPROF_PARSER_DIR` — 含 `hprof_parser.py` 的目录（默认 `~/workspaces/Android-App-Memory-Analysis/tools`）
- `MEM_ANALYZE_JAR` — fat jar（默认 `build/libs/mem-analyze-1.0.0-all.jar`）

## 分步执行

```bash
# 1) 构建 JAR（若尚未构建）
./gradlew shadowJar -x test

# 2) 仅导出 JAR 指标
java -jar build/libs/mem-analyze-1.0.0-all.jar \
  -f /path/to/heap.hprof \
  --export-compare-json /tmp/jar_metrics.json

# 3) 仅导出 Python 指标
python3 scripts/hprof_parser_export_compare.py \
  -f /path/to/heap.hprof \
  -o /tmp/python_metrics.json

# 4) 对比（可复用已有 JSON：--skip-jar / --skip-python）
python3 scripts/compare_hprof_diagnostics.py \
  -f /path/to/heap.hprof \
  -o /tmp/compare-out
```

## 对比项说明

| 指标 | 期望 |
|------|------|
| GC Root 分类型数量 | 应一致（总量 = 各类型之和） |
| duplicateStringGroupCount | 应一致或接近 |
| accumulationPointCount | 支配树实现不同，允许较大偏差；Top 类名应有重叠 |
| staticHoldingCount | 规则对齐后应接近；JAR 不应出现 `$class$` |
| largeArrayCount | Python 仅统计 byte[]；JAR 含全部 primitive，数量不必相等 |
| collectionRiskCount | 判定规则不同，仅作参考 |

退出码：`0` = 在容差内通过；`1` = 存在需关注的差异（见 `compare_report.txt`）。

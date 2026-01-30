# 提交分组说明（不同内容区分提交）

按修改内容拆分提交时，可按下述顺序分批 `git add` 与 `git commit`。  
**说明**：`bin/` 为编译产物，一般可不提交；若仓库习惯提交 bin，可随对应源码一起提交。

---

## 1. 移除 BroadcastReceiver 支持（与 LeakCanary 对齐）

**范围**：LeakCanary 不支持 BroadcastReceiver 泄露检测，分析器、测试与文档同步移除相关能力。

**建议提交文件**：
- `src/main/java/com/koom/monitor/analyzer/HprofAnalyzer.kt`
- `src/test/kotlin/com/koom/monitor/HprofAnalyzerLeakDetectionTest.kt`
- `right-click-menu/README.md`
- `COMMIT_GROUPS.md`

**建议提交信息**：
```
fix(analyzer): 移除 BroadcastReceiver 泄露检测，与 LeakCanary 对齐

- HprofAnalyzer 移除 BroadcastReceiver 检测、统计与报告
- 移除 isBroadcastReceiver、isInnerClassOfActivity 及 leakedBroadcastReceiverCount
- 测试移除 testBroadcastReceiverLeakDetection 及综合测试中的 BroadcastReceiver 统计
- 文档移除 BroadcastReceiver 支持说明并更新 COMMIT_GROUPS
```

---

## 2. device-watch：独立目录与脚本迁移

**范围**：将设备端监控脚本从 `scripts/` 迁到 `device-watch/`。

**建议提交文件**：
- `device-watch/device-watch.sh`（新）
- `device-watch/deploy-device-watch.sh`（新）
- `scripts/deploy-device-watch.sh`（删除）
- `scripts/device-watch.sh`（删除）

**建议提交信息**：
```
refactor(device-watch): 设备端监控脚本迁至 device-watch 目录

- 新增 device-watch/ 目录
- 从 scripts/ 移入 device-watch.sh、deploy-device-watch.sh
```

---

## 3. device-watch.sh：多包名、PSS 与“谁启动监控谁”

**范围**：支持多包名、去掉 PSS 展示、未启动仅跳过不等待。

**建议提交文件**：
- `device-watch/device-watch.sh`

**建议提交信息**：
```
feat(device-watch): 支持包名列表与监控逻辑调整

- -p 可多次指定，支持多包名监控
- 移除 PSS 展示
- 谁启动监控谁，不要求全部应用启动；未启动包仅跳过并提示
```

---

## 4. deploy-device-watch.sh：仅推荐后台方式与停止说明

**范围**：部署说明只推荐后台运行、补充停止与 watch.log 说明（不包含 package_list 相关）。

**建议提交文件**：
- `device-watch/deploy-device-watch.sh`

**建议提交信息**：
```
docs(deploy): 仅推荐后台运行并补充停止与 watch.log 说明

- 用法仅保留 nohup 后台方式，可拔 USB
- 补充停止监控命令与查看 watch.log 是否正常
```

**注意**：若你已把「package_list.txt 封装」与「仅推荐后台」做在同一轮修改里，可把 5 和 6 合并为一次提交，或按你仓库习惯拆成两次。

---

## 5. deploy-device-watch.sh：package_list.txt 封装

**范围**：通过 package_list.txt 生成并推送 run-device-watch.sh。

**建议提交文件**：
- `device-watch/deploy-device-watch.sh`
- `device-watch/package_list.txt.example`（新）
- `device-watch/README.md`（新或更新）

**建议提交信息**：
```
feat(deploy): 支持 package_list.txt 生成设备端启动脚本

- -f/位置参数指定包名列表文件，每行一个包名，# 为注释
- 生成并推送 run-device-watch.sh，设备上直接执行即可按列表监控
- 新增 package_list.txt.example、更新 device-watch/README.md
```

---

## 6. DEVICE_WATCH.md：文档与路径更新

**范围**：路径改为 device-watch/、停止说明、多包名与 package_list 说明。

**建议提交文件**：
- `DEVICE_WATCH.md`

**建议提交信息**：
```
docs(DEVICE_WATCH): 路径与用法更新

- 部署与脚本路径改为 device-watch/
- 补充停止监控、多包名、package_list.txt、watch.log 说明
```

---

## 7. 测试：修改部分验证（test.hprof）

**范围**：新增以 test.hprof 为输入的修改部分验证用例。

**建议提交文件**：
- `src/test/kotlin/com/koom/monitor/HprofAnalyzerLeakDetectionTest.kt`

**建议提交信息**：
```
test(analyzer): 新增以 test.hprof 为输入的修改部分验证

- testModifiedPartsWithTestHprof() 校验无硬编码/逻辑错误与报告生成
```

---

## 可选：不提交或按仓库习惯处理

- **bin/**：编译输出，通常加入 `.gitignore` 或按现有规范决定是否提交。
- **right-click-menu/mem-analyze.zip**：若为构建产物，一般可不提交。
- **demo/settings.gradle.kts**：若本次未 intentionally 修改，可单独检查后决定是否放入某次提交。

---

## 建议提交顺序（可减少冲突）

1. 先提交 **1（移除 BroadcastReceiver）**（分析器、测试与文档）。
2. 再提交 **2（迁移）**、**3（device-watch.sh）**、**4（deploy 推荐后台）**、**5（package_list）**、**6（DEVICE_WATCH.md）**、**7（测试 test.hprof）**（按需）。

按上述分组分别 `git add` 对应文件后执行 `git commit -m "..."` 即可实现「不同内容区分提交」。

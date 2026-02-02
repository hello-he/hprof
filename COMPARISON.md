# Analyze 模式对比：mem-analyze vs KOOM vs LeakCanary vs Android Studio Profiler

本文仅对比**离线/分析 hprof 的模式**（analyze 模式）：对已有 `.hprof` 文件做泄露检测与报告，不对比各工具的自动 dump、监控、上报等能力。

---

## 1. 概览

| 维度 | mem-analyze (本工具) | KOOM (koom-java-leak) | LeakCanary / Shark | Android Studio Profiler |
|------|---------------------|------------------------|--------------------|--------------------------|
| **分析发生位置** | 本机 (PC/Linux) CLI | 设备端 (dump 后当场分析) | 本机 CLI 或 App 内 | 本机 IDE，连接设备或打开 hprof |
| **输入** | 任意 hprof 文件 | 本应用触发的 hprof | 任意 hprof（Shark CLI）或 App 内 dump | 从运行中应用抓的 dump 或已保存的 hprof |
| **是否依赖 App 集成** | 否 | 是（需集成 KOOM） | 否（Shark CLI）/ 是（App 内） | 否（仅分析时可不依赖） |
| **典型用法** | `java -jar mem-analyze-*.jar -f xxx.hprof` | 阈值触发 → 设备上 dump+分析 → 上报 | `shark-cli -h file.hprof` 或 App 内自动 | 在 IDE 里抓 dump → 点「Analyze」/「Show activity/fragment leaks」 |
| **输出** | 文本 + HTML 报告，可选 Bitmap 提取 | 设备端报告（可上传） | 控制台/报告 + 堆分析结果 | 界面：类列表、实例、引用链 |

---

## 2. 泄露检测能力（仅 analyze 相关）

| 检测类型 | mem-analyze | KOOM | LeakCanary/Shark | Android Studio Profiler |
|----------|-------------|------|------------------|--------------------------|
| **Activity 泄露** | ✅ 已销毁仍可达 (mDestroyed/mFinished) | ✅ 同上 | ✅ | ✅ Activity/Fragment 泄露视图 |
| **Fragment 泄露** | ✅ mFragmentManager==null 或 Lifecycle DESTROYED | ✅ mFragmentManager==null && mCalled | ✅（含 AndroidX） | ✅ |
| **Animator 泄露** | ✅ 仅 **正在运行** (mRunning=true) | ❌ 未强调 | ✅ 仅运行中的 animator | 需自行在堆里看 |
| **Service 泄露** | ✅ 未由 ActivityThread 持有但仍可达 | 未在文档强调 | 可查引用链 | 需自行看引用 |
| **大 Bitmap / 大 ByteArray** | ✅ 阈值报告 + 可选 Bitmap 提取与重复检测 | ❌ | ❌ | 仅看大小，无专门「泄露」标记 |
| **BroadcastReceiver** | ❌ 不检测（与 LeakCanary 一致） | ❌ | ❌ | ❌ |
| **线程泄露** | ✅ 同名线程过多告警 | ❌ | ❌ | 需自行看线程列表 |
| **GC 路径** | ✅ 仅统计有 GC Root 路径的对象 | ✅ Shark 找路径 | ✅ Shark | ✅ 显示到 GC Root 的引用链 |

---

## 3. 优劣对比（仅 analyze 模式）

### mem-analyze（本工具）

**优势**

- **零 App 依赖**：任意来源的 hprof（ADB dump、拷贝、CI 产出）均可分析，无需在应用里集成 SDK。
- **单 JAR 命令行**：适合脚本、CI、右键菜单；不依赖 Android Studio 或 IDE。
- **报告形态清晰**：泄露类型统计、类实例统计、代表性泄露路径、HTML+文本，便于归档与二次处理。
- **与 LeakCanary 对齐**：Fragment/Activity 判定、Animator 仅算运行中、不报 BroadcastReceiver；减少误报与分歧。
- **路径去重与多类展示**：同一路径下不同类（如 HomeFragment / HomeListFragment）会分别列出，统计与 Profiler 一致（如 54 个 HomeListFragment）。
- **可选 Bitmap 分析**：在具备 dump 数据时支持大图与重复 Bitmap 检测；无 bitmap 报告时不展示该项。

**劣势**

- 无图形化堆浏览：不能像 MAT/Profiler 那样随意点选对象、跑 OQL。
- 需自建/自配：无官方云或默认上报，需自行集成到流水线或本地脚本。

---

### KOOM (koom-java-leak)

**优势**

- **设备端完成分析**：从触发到 dump+分析在设备上完成，适合不能把 hprof 拉出来的场景。
- **与 mem-analyze 同源思路**：Activity/Fragment 判定一致（mDestroyed、mFragmentManager==null），都基于 Shark 解析 hprof。

**劣势（针对「analyze 模式」）**

- **非桌面离线分析工具**：主要流程是「监控 → 触发 dump → 设备上分析 → 上报」，没有提供「在 PC 上对任意 hprof 跑 analyze」的独立 CLI。
- **必须集成**：需在 App 里集成 KOOM，无法对第三方或未集成应用的 hprof 做统一分析。

---

### LeakCanary / Shark

**优势**

- **业界标准**：LeakCanary 的泄露定义（含 Fragment/Animator）被广泛采用；Shark 是同一套分析内核。
- **Shark CLI 可离线**：`shark-cli -h file.hprof` 可在本机分析任意 hprof，不依赖 App。
- **内存与性能**：Shark 索引式读取、低内存占用，大堆分析效率高。

**劣势（仅相对本工具）**

- **Shark CLI 需单独安装**（如 brew），非单 JAR；本工具单 JAR 即可。
- **报告形态不同**：Shark 输出更偏开发者/原始堆；本工具提供「泄露类型统计 + 类实例统计 + 中文报告」更适合直接给测试/归档。
- **无内置 Bitmap/线程/大对象统计**：需自行基于堆数据做或配合其他工具。

---

### Android Studio Profiler

**优势**

- **官方 IDE 集成**：抓 dump、看堆、看引用链一气呵成；「Show activity/fragment leaks」与类/实例视图一致。
- **交互强**：按类、按实例浏览，查看引用链、深度、大小，适合人工排查。
- **无需额外安装**：有 Android Studio 即可。

**劣势（仅针对 analyze 场景）**

- **依赖 IDE**：难以在无界面环境、CI、脚本中批量分析 hprof。
- **不能完全无头**：批量、自动化分析不如 CLI 方便。
- **大对象/线程**：无类似本工具的「大 Bitmap/大 ByteArray/线程泄露」聚合统计，需自己在堆里找。

---

## 4. 使用场景建议（analyze 模式）

- **在 PC 上对任意 hprof 做一次性或批量分析、要可脚本化、要中文报告与统计**  
  → 用 **mem-analyze** `-f xxx.hprof`。

- **只在设备上触发分析、不能或不想把 hprof 拷出来**  
  → 用 **KOOM**（需 App 集成）。

- **希望与 LeakCanary 定义完全一致、且习惯 Shark 输出**  
  → 用 **LeakCanary（App 内）或 Shark CLI**（本机离线）。

- **人工在 IDE 里看单次 dump、查引用链**  
  → 用 **Android Studio Profiler**。

---

## 5. 版本与参考

- **mem-analyze**：本仓库 `mem-analyze-1.0.0-all.jar`，analyze 行为以当前实现为准。
- **KOOM**： [KwaiAppTeam/KOOM](https://github.com/KwaiAppTeam/KOOM)（koom-java-leak）。
- **LeakCanary / Shark**： [square/leakcanary](https://github.com/square/leakcanary)，[Shark 文档](https://square.github.io/leakcanary/shark/)。
- **Android Studio Profiler**： [Analyze memory with the Memory Profiler](https://developer.android.com/studio/profile/memory-profiler)。

上述对比仅针对「对 hprof 做泄露分析」这一环节，不涵盖各工具的监控、dump 策略、云上报等完整能力。

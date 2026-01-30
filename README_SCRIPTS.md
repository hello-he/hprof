# 脚本使用说明

所有测试脚本都位于 `scripts/` 目录下。

## 主要脚本

### 1. `scripts/test_leaks.sh` - 自动化泄露测试

**功能**：自动触发、dump、分析、验证所有泄露类型

**使用方法**：
```bash
cd mem-monitor

# 运行所有测试
./scripts/test_leaks.sh all

# 运行单个测试
./scripts/test_leaks.sh activity
./scripts/test_leaks.sh fragment
./scripts/test_leaks.sh view
./scripts/test_leaks.sh viewmodel
./scripts/test_leaks.sh service
./scripts/test_leaks.sh dialog
./scripts/test_leaks.sh handler_message
./scripts/test_leaks.sh animator
./scripts/test_leaks.sh bitmap
./scripts/test_leaks.sh bytearray
```

**特点**：
- ✅ 完全自动化，无需手动操作
- ✅ 自动检查设备连接
- ✅ 自动构建 JAR（如果不存在）
- ✅ 自动触发泄露（通过 Intent）
- ✅ 自动 dump hprof
- ✅ 自动分析并验证结果

### 2. `scripts/generate_test_hprof.sh` - 生成测试数据

**功能**：生成用于单元测试的 hprof 文件

**使用方法**：
```bash
cd mem-monitor

# 生成 Activity 泄露测试文件
./scripts/generate_test_hprof.sh activity

# 生成所有类型的测试文件
for type in activity fragment view viewmodel service dialog handler_message animator bitmap bytearray; do
    ./scripts/generate_test_hprof.sh $type
done
```

## 脚本位置

所有脚本统一放在 `scripts/` 目录：

```
mem-monitor/
└── scripts/
    ├── test_leaks.sh              # 自动化测试脚本（主要）
    ├── generate_test_hprof.sh     # 生成测试数据
    ├── monkey_test.sh             # Monkey 测试
    ├── run_test.sh                # 运行测试
    └── watch_test.sh              # 监控测试
```

## 路径说明

脚本会自动处理路径问题：
- `SCRIPT_DIR`: 脚本所在目录（`scripts/`）
- `PROJECT_DIR`: 项目根目录（`mem-monitor/`）
- `JAR_PATH`: JAR 文件路径（`$PROJECT_DIR/build/libs/mem-monitor-1.0.0-all.jar`）

脚本会：
1. 自动检测 JAR 文件是否存在
2. 如果不存在，自动切换到项目根目录构建
3. 使用绝对路径确保路径正确

## 注意事项

1. **运行位置**：可以在任何目录运行，脚本会自动找到项目根目录
2. **JAR 构建**：如果 JAR 不存在，脚本会自动构建（需要项目根目录有 `gradlew`）
3. **设备连接**：运行前确保 Android 设备已连接并开启 USB 调试

# 设备端内存监控 (device-watch)

本目录包含在 Android 设备上运行的内存监控脚本及部署工具。

## 文件说明

| 文件 | 说明 |
|------|------|
| `device-watch.sh` | 设备端监控脚本：监控应用内存并自动 dump hprof |
| `deploy-device-watch.sh` | 部署脚本：将 device-watch.sh 推送到设备；可选根据 package_list.txt 生成启动脚本 |
| `package_list.txt.example` | 包名列表示例（每行一个包名，# 为注释） |

## 快速开始（推荐：用包名列表）

1. **准备包名列表**  
   复制 `package_list.txt.example` 为 `package_list.txt`，按行填写要监控的包名（`#` 开头为注释）。

2. **部署并生成启动脚本**
   ```bash
   cd device-watch
   ./deploy-device-watch.sh package_list.txt
   ```

3. **在设备上后台启动监控**（可拔掉 USB）
   ```bash
   adb shell "nohup sh /data/local/tmp/run-device-watch.sh > /data/local/tmp/watch.log 2>&1 &"
   ```

4. **查看是否正常运行**
   ```bash
   adb shell cat /data/local/tmp/watch.log
   ```

5. **停止监控**  
   见 [如何停止监控](#如何停止监控)。

## 仅部署（不生成启动脚本）

```bash
./deploy-device-watch.sh
```

然后手动执行：`adb shell "nohup sh /data/local/tmp/device-watch.sh -p <包名> > /data/local/tmp/watch.log 2>&1 &"`

## 如何停止监控

- **前台运行**：在运行 `device-watch.sh` 的终端按 **Ctrl+C** 即可正常停止。
- **后台运行**（如使用 `nohup ... &`）：
  1. 查找进程：`adb shell "ps -A | grep device-watch"`
  2. 结束进程：`adb shell "pkill -f device-watch.sh"`

## 更多说明

详见项目根目录下的 [DEVICE_WATCH.md](../DEVICE_WATCH.md)。

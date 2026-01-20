package com.koom.monitor

import com.koom.monitor.adb.AdbClient
import com.koom.monitor.command.ScanCommand
import com.koom.monitor.command.WatchCommand
import com.koom.monitor.command.AnalyzeCommand
import com.koom.monitor.model.MonitorConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * 主入口
 */
fun main(args: Array<String>) {
    val exitCode = CommandLine(MemMonitorCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(*args)
    kotlin.system.exitProcess(exitCode)
}

/**
 * 命令行入口
 */
@CommandLine.Command(
    name = "mem-monitor",
    mixinStandardHelpOptions = true,
    version = ["1.0.0"],
    description = ["Android内存监控工具 - 通过ADB监控应用内存、线程、文件句柄，并检测Bitmap泄漏"],
    subcommands = [
        ScanCommand::class,
        WatchCommand::class,
        AnalyzeCommand::class
    ]
)
class MemMonitorCommand : Runnable {
    private val logger = LoggerFactory.getLogger(MemMonitorCommand::class.java)

    override fun run() {
        CommandLine(this).usage(System.out)
    }
}

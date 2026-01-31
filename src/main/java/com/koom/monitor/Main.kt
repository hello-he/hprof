package com.koom.monitor

import com.koom.monitor.command.AnalyzeCommand
import org.slf4j.LoggerFactory
import picocli.CommandLine

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
    name = "mem-analyze",
    mixinStandardHelpOptions = true,
    version = ["1.0.0"],
    description = ["Android 内存泄露分析工具 - 离线分析 hprof 文件，检测 Activity/Fragment/Animator 等泄露"],
    subcommands = [
        AnalyzeCommand::class
    ]
)
class MemMonitorCommand : Runnable {
    private val logger = LoggerFactory.getLogger(MemMonitorCommand::class.java)

    override fun run() {
        CommandLine(this).usage(System.out)
    }
}

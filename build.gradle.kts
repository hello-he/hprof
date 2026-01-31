plugins {
    application
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.koom"
version = "1.0.0"

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}

dependencies {
    // Kotlin标准库
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.22")

    // Okio (Shark需要)
    implementation("com.squareup.okio:okio:1.17.6")

    // CLI框架
    implementation("info.picocli:picocli:4.7.5")
    annotationProcessor("info.picocli:picocli-codegen:4.7.5")
    implementation("org.jline:jline:3.24.1")

    // FreeMarker模板引擎
    implementation("org.freemarker:freemarker:2.3.32")

    // JSON处理
    implementation("com.google.code.gson:gson:2.10.1")

    // 日志
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // 测试
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
}

application {
    mainClass.set("com.koom.monitor.MainKt")
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    compileJava {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    shadowJar {
        archiveBaseName.set("mem-analyze")
        manifest {
            attributes("Main-Class" to "com.koom.monitor.MainKt")
        }
        mergeServiceFiles()
        // 构建JAR前先运行测试
        dependsOn("test")
    }
}

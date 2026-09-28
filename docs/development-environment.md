# 开发环境准备

## 基础要求

- 安装 JDK 21。JBR 21、OpenJDK 21 等发行版均可，路径不需要和其他电脑一致。
- 使用项目自带的 `./gradlew`，不要依赖本机全局 Gradle 版本。
- IDE 的 Project SDK 和 Gradle JVM 选择本机的 JDK 21。

## 项目如何固定版本

项目使用三层配置：

- [build.gradle.kts](../build.gradle.kts)：Java Toolchain 要求 Java 21。
- [gradle/gradle-daemon-jvm.properties](../gradle/gradle-daemon-jvm.properties)：要求 Gradle Daemon 使用 Java 21，Gradle 会从本机探测匹配的 JDK，不保存绝对路径。
- [gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties)：固定 Gradle Wrapper 版本。

因此，团队只需要统一提交这些项目配置；每台电脑各自安装 JDK 21 即可，不需要反复执行 `export JAVA_HOME=...`。相关机制见 [Gradle Daemon JVM 文档](https://docs.gradle.org/current/userguide/gradle_daemon.html)、[Java Toolchains 文档](https://docs.gradle.org/current/userguide/toolchains.html) 和 [Gradle Wrapper 文档](https://docs.gradle.org/current/userguide/gradle_wrapper.html)。

## 验证环境

```bash
java -version
./gradlew --version
```

`./gradlew --version` 中的 `Daemon JVM` 应显示兼容 Java 21。系统默认的 `java -version` 即使仍是其他版本，也不影响 Gradle 按项目配置选择 Java 21。

## 配置与运行

1. 参考 [配置示例](../.mewcode/config.yaml.example) 创建项目根目录下的 `.mewcode/config.yaml`。保留至少一个实际使用的 Provider，并填写它的 `name`、`protocol`、`model` 和 `api_key`；按服务需要填写 `base_url`。删除示例中不用的 Provider 和 MCP Server。该本地配置已被 `.gitignore` 忽略。
2. 构建并从项目根目录启动：

```bash
./gradlew shadowJar
java -jar build/libs/mewcode.jar
```

启动 JAR 的 `java` 也需要 Java 21 或更新版本。代码入口和各模块说明见 [架构与文档导航](architecture.md)。

## 开发验证

```bash
./gradlew spotlessCheck test shadowJar
```

功能开发完成后，在 tmux 中运行构建出的 JAR，输入真实对话，检查工具调用和回复，再按对应章节的 `checklist.md` 验收；章节入口见 [架构与文档导航](architecture.md)。

如果出现找不到匹配 Toolchain 的错误，请先确认本机已安装并能被 IDE/Gradle 探测到 JDK 21；不要把本机的绝对 JDK 路径写入项目配置。

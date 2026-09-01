plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
    id("com.diffplug.spotless") version "7.0.2"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.mewcode.MewCode"
}

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.1"))
    implementation("io.modelcontextprotocol.sdk:mcp-core")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")
    implementation("org.jline:jline:3.28.0")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.openai:openai-java:4.37.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.yaml:snakeyaml:2.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

spotless {
    java {
        target(
                "src/main/java/com/mewcode/command/**/*.java",
                "src/main/java/com/mewcode/prompt/**/*.java",
                "src/main/java/com/mewcode/llm/PromptRequest.java",
                "src/main/java/com/mewcode/llm/LlmClient.java",
                "src/main/java/com/mewcode/llm/AnthropicClient.java",
                "src/main/java/com/mewcode/llm/OpenAiClient.java",
                "src/main/java/com/mewcode/agent/PromptRequestFactory.java",
                "src/main/java/com/mewcode/agent/AgentTurnCoordinator.java",
                "src/main/java/com/mewcode/tool/ToolPromptRules.java",
                "src/main/java/com/mewcode/tool/ToolRegistry.java",
                "src/main/java/com/mewcode/tool/Tool.java",
                "src/main/java/com/mewcode/tool/ToolCategory.java",
                "src/main/java/com/mewcode/tool/impl/EditFileTool.java",
                "src/main/java/com/mewcode/tool/impl/BashTool.java",
                "src/main/java/com/mewcode/tool/impl/ToolSearchTool.java",
                "src/main/java/com/mewcode/tui/MewCodeModel.java",
                "src/main/java/com/mewcode/MewCode.java",
                "src/main/java/com/mewcode/permission/**/*.java",
                "src/main/java/com/mewcode/config/AppConfig.java",
                "src/main/java/com/mewcode/config/ConfigLoader.java",
                "src/main/java/com/mewcode/config/PermissionConfig.java",
                "src/main/java/com/mewcode/config/PermissionConfigLoader.java",
                "src/main/java/com/mewcode/config/McpServerConfig.java",
                "src/main/java/com/mewcode/config/McpConfigLoader.java",
                "src/main/java/com/mewcode/mcp/**/*.java",
                "src/main/java/com/mewcode/tool/ToolExecutor.java",
                "src/main/java/com/mewcode/tool/ToolExecutionContext.java",
                "src/main/java/com/mewcode/tool/support/PathGuard.java",
                "src/main/java/com/mewcode/tool/support/CommandRunner.java",
                "src/main/java/com/mewcode/tool/impl/ReadFileTool.java",
                "src/main/java/com/mewcode/tool/impl/WriteFileTool.java",
                "src/main/java/com/mewcode/tool/impl/GlobTool.java",
                "src/main/java/com/mewcode/tool/impl/GrepTool.java",
                "src/main/java/com/mewcode/agent/AgentEvent.java",
                "src/main/java/com/mewcode/agent/AgentRun.java",
                "src/main/java/com/mewcode/tui/PermissionPromptFormatter.java",
                "src/test/java/com/mewcode/command/**/*.java",
                "src/test/java/com/mewcode/prompt/**/*.java",
                "src/test/java/com/mewcode/llm/PromptRequestTest.java",
                "src/test/java/com/mewcode/llm/AnthropicClientTest.java",
                "src/test/java/com/mewcode/llm/OpenAiClientTest.java",
                "src/test/java/com/mewcode/agent/PromptRequestFactoryTest.java",
                "src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java",
                "src/test/java/com/mewcode/agent/AgentTurnCoordinatorPromptTest.java",
                "src/test/java/com/mewcode/agent/AgentTurnCoordinatorLazyToolTest.java",
                "src/test/java/com/mewcode/tool/ToolPromptRulesTest.java",
                "src/test/java/com/mewcode/tool/ToolRegistryTest.java",
                "src/test/java/com/mewcode/tui/MewCodeModelTest.java",
                "src/test/java/com/mewcode/permission/**/*.java",
                "src/test/java/com/mewcode/config/PermissionConfigLoaderTest.java",
                "src/test/java/com/mewcode/config/McpConfigLoaderTest.java",
                "src/test/java/com/mewcode/mcp/**/*.java",
                "src/test/java/com/mewcode/tool/impl/ToolSearchToolTest.java",
                "src/test/java/com/mewcode/tui/PermissionPromptFormatterTest.java",
                "src/test/java/com/mewcode/tool/PermissionToolExecutorTest.java",
                "src/test/java/com/mewcode/tool/BashSandboxIntegrationTest.java")
        googleJavaFormat("1.28.0")
        removeUnusedImports()
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "mewcode"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}

tasks.distZip { dependsOn(tasks.shadowJar) }
tasks.distTar { dependsOn(tasks.shadowJar) }
tasks.startScripts { dependsOn(tasks.shadowJar) }
tasks.named("startShadowScripts") { dependsOn(tasks.jar) }

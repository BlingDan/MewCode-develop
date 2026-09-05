---
name: test
description: 根据当前改动选择并运行最相关的测试
tools: [Bash, ReadFile, Grep, Glob]
mode: shared
---
检查当前改动，选择覆盖风险的最小相关测试集并实际运行。失败时定位根因并报告；通过时给出运行命令和结果。用户补充要求如下：

{{arguments}}

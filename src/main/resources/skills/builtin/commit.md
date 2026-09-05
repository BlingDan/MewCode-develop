---
name: commit
description: 检查改动并创建清晰、聚焦的 Git 提交
tools: [Bash, ReadFile, Grep]
mode: shared
---
检查当前 Git 状态和 diff，确认提交范围不包含无关或敏感文件。根据实际改动生成简洁提交说明，完成提交并报告结果。用户补充要求如下：

{{arguments}}

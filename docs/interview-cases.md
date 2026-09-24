# MewCode 面试口述卡

参考[面试叙述文章](https://my.feishu.cn/wiki/Qv94wQHPDiottokCXUicMuSNnDb)的“约束、选择、踩坑、边界”结构。先讲 30 秒；对方追问，再展开一层。以下只讲本项目做过的事。

## 主讲 1：子 Agent 的异步结果

**30 秒说法：** 子 Agent 可以转后台，但父对话只能收到最终结果。调试发现，多轮工具执行会把中间文本混进结果。我把状态和通知收口到任务管理器，只保留最后回复、只发一次通知；真实终端的 Fork 验收通过。

**简历一句：** 实现 SubAgent 前后台任务管理，解决多轮结果污染和后台通知一致性问题。

**被追问再答：** 为什么通知先于完成信号？否则调用方可能读到“已完成”，却还读不到结果通知。[代码](../src/main/java/com/mewcode/subagent/SubAgentTaskManager.java)｜[验收](ch13/verification.md)。取消终态有自动化测试，长 SSE 场景没有完整终态证据。

## 主讲 2：子 Agent 的工具权限

**30 秒说法：** 子 Agent 的工具权限不能只靠模型看到的列表，因为模型仍能构造禁用调用。我从父任务权限逐层收窄工具集，让模型声明与执行入口共用同一策略。子 Agent 因此不能再创建 Agent，也不能管理主任务。

**简历一句：** 设计 SubAgent 工具权限策略，在模型声明与执行入口双重校验，限制递归委派和任务越权。

**被追问再答：** 为什么用“绝对”策略？普通 system 工具默认可用，子 Agent 需要覆盖这个例外。[策略](../src/main/java/com/mewcode/agent/ToolPolicy.java)｜[执行校验](../src/main/java/com/mewcode/tool/ToolExecutor.java)。这是设计时防范的风险，不要说成发生过越权事故。

## 备用踩坑：验收时找不到 Agent

**备用说法：** 验收报“未知工具：Agent”。我查 Provider 工具列表、JAR 和 worktree，发现实现没合进目标分支。快进合并、重建后复测通过。这是排障故事，不写简历。

## 备用踩坑：参考示例无法注册

飞书[验收示例](https://my.feishu.cn/wiki/WK97wEjSVi8m2RkEJOhcxO2XnQb#share-HwDTdqjR7oMqRYxCt4sckC8Rnid)写的是 `permissionMode: bypassPermissions`，本项目只接受 `default` 或 `dontAsk`。真实解析器会拒绝原值，改为 `dontAsk` 才能加载；`model: sonnet` 还需对应模型路由。这里只验证了解析，没验证完整安全审查流程。

**别讲错：** 参考文章里的“每个子 Agent 独立 Worktree、文件邮箱、Coordinator”是文章案例；当前 MewCode 子 Agent 仍共享项目文件系统。

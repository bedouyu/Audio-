# Memory上下文：多策略与可复现实验

## 当前实现，不等于“已经找到了最优记忆算法”

本次把生产聊天从固定Top12消息/6000字符窗口，升级为可切换的有来源上下文组装。同一MySQL原始历史与会话检查点支持四种方法，不为每种方法建数据库。主控和基础聊天都接入；子Agent仍只拿明确task和产物，不自动共享主控公开历史。

| 策略 | 组成及适用尝试 |
|---|---|
| recent_window | 按完成轮次读取近期对话，适合短指代；超长轮明确摘录，不悄悄清空全部历史 |
| query_recall | 近期轮次 + BM25检索更早原文，适合名称、数值、以前讨论过的主题 |
| extractive_notes | 近期轮次 + 目标/约束/纠正等原文摘记，减少反复塞入长回复 |
| hybrid | 近期轮次 + 原文摘记 + 相关历史检索，尝试兼顾连续性与久远事实 |

这是确定性选择与词法检索，不是LLM摘要、embedding语义记忆、自动事实提取或语义冲突消解。摘记按透明关键词启发抽取，最多32条、每条最多300 UTF-16字符；关键词未覆盖的偏好可能漏掉。保留原文、时序和更正，让模型核对，不把旧要求的存在当成它仍有效。

默认hybrid是可切换的初始配置，不是用小型测试集证明的最优策略。实验显示保留信息较多也可能带入更多旧约束；是否影响实际回答必须单独评测。

## 三层持久化与预算

```text
messages：完整公开原始对话，新增turn_id（新完成轮共享UUID）
    ├→ 会话原文摘记checkpoint：source_id + turn_id + 原文范围 + 校验哈希
    └→ 查询时MemoryPack：近期 / 检索 / 摘记 / 混合
                        ↓
             同会话任务状态与产物编号 + 当前用户请求
                        ↓
             本轮assistant/tool完整协议及有界观察压缩
```

检查点保存在conversation_memory_checkpoints，含user_id/project_id/session_id、through_message_id、原文摘记、source哈希和乐观锁版本。不覆盖messages，也不保存模型私有推理。加载时再次查询同会话原始source，检查role、turn_id、范围、原文及哈希；格式损坏或原文变更不采用。损坏派生缓存可依据核验过的原文修复，原始对话不删除。项目删除清理检查点。

source_id为`m<数据库消息ID>`；坐标是消息原文的UTF-16半开区间，不是音频时间戳或文件byte。摘要字段没有“事实已认证”含义，当前根本没有生成式摘要。新轮按turn_id归组，能处理ID交错；旧记录没有turn_id时只采用无歧义的角色块，扫描左边界不明确则跳过并警告，不自动给旧库伪造轮次。

原生与基础聊天共用进程内会话回合保护，同一会话并发请求拒绝交错处理，别的会话可继续。它不是多进程/集群分布式锁。

每次至多扫描最近400条消息；窗口之外可保留已保存且验证过的摘记。旧会话第一次升级不会自动全文重建所有历史记忆；超出扫描窗口又没有摘记的旧目标可能缺失，不能宣称无限记忆。

初始记忆预算默认6000序列化JSON字符，含引用位置、角色、转义和状态元数据，不只数正文。最新完整轮在总预算内可放下时优先完整保留，其他近期轮可能只保留原文摘录。该单位不等于生成模型token数。总Agent上下文仍为48000字符，原生tool_call_id、assistant.tool_calls和供应商要求回灌的reasoning_content不能为了省预算拆散。

## 按需读取与继续任务

主控增加三个只读工具：

- memory_search：同会话选择四种记忆策略，单次预算1000–6000字符。
- memory_read：按source_id重读消息原文，最多2500字符，并按JSON转义后的观察预算继续缩小范围；返回end/has_more。
- task_read：同会话读取真实run的目标、状态和产物指针，不执行/重放任务；失败目标未进入聊天、或最近goal截断时也可恢复。

工具不接受user/project/session输入，范围来自已认证执行上下文；repository同时验证项目归属与会话归属，source查询绑定session。子Agent没有这些记忆工具权限，不因访问主控记忆扩大权限。已有产物读取仍按原项目授权及子Agent显式artifact白名单，不假装已经统一成所有产物均会话级。

最近任务状态对所有当前请求作为历史数据提供，而非只匹配一个“继续”字符串。至多检查最近20个同会话run，优先提供3个非纯“继续”的原始目标，附真实产物ID与最新状态；极长目标分段读取。这个恢复不是无损重放模型内部状态，更不会自动重复合成、写入计划等已确认动作。

## 两种本轮工具观察压缩

环境配置preview/structured：只压缩旧轮tool观察，最新轮和assistant必需协议字段保持不变。

- preview：完整JSON包装明确标记的原始文本前缀。前缀可能不是完整原观察，不冒充摘要。
- structured：保留原status、实际策略、warnings、引用/原文位置、产物指针、分页与省略数量，正文压缩标记；单条压缩结果最多1000字符且是完整JSON。

大量引用本身放不下也会省略并报告数量；不能声称全部引用在有限预算下必然保留。无法在48000上下文里保留必需协议时显式停止，不截断供应商必需的reasoning_content再继续。结构化元数据不等于事实验证。

该分层思路参考[Anthropic的context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)中compaction、笔记与按需读取，以及[LangGraph的memory说明](https://docs.langchain.com/oss/python/langgraph/add-memory)对会话范围及有效工具消息历史的要求。本项目是自研实现，没有接入LangGraph，也不能据此宣称企业效果。

## 使用与参数

聊天栏可选近期对话、历史检索、原文摘记或混合记忆。执行过程和运行记录的memory卡片显示本轮实际源片段、范围、预算和警告。

```powershell
$env:AUDIORAG_MEMORY_STRATEGY = 'hybrid'
$env:AUDIORAG_MEMORY_BUDGET_CHARS = '6000'
$env:AUDIORAG_TOOL_COMPACTION_STRATEGY = 'structured'
```

重启后端生效。HTTP聊天可选memory_strategy；仅允许四个枚举值，原有三字段请求仍兼容。启动前备份MySQL：ddl-auto=update会给messages增加turn_id及索引，并新增会话检查点表；不会给旧消息批量生成虚构turn_id。本次测试用H2映射及回环HTTP，不代替生产MySQL验收。

## 实验：保留信息与回答正确是两件事

默认仅本地，无模型调用、无网络、无生产库：

```powershell
./run-memory-experiments.ps1
# 自行编写同schema数据集
./run-memory-experiments.ps1 -Dataset 'D:\your-data\memory-eval.json'
```

20个人工场景（dev10/test10），每题16完整轮，4策略×1000/3000/6000字符，共240条件；不是240道独立题。涵盖久远目标、偏好、精确值、纠正、继续和超长消息。checkpoint与新摘记只由已完成历史抽取，不使用gold或当前query抽记忆；同一源、同一预算独立组装。

report.json保存每题实际片段、模型可见包、快照及独立标签指标；results.csv为逐条件结果，summary.csv分dev/test。指标包括源覆盖、原文区间覆盖、完整重要引句覆盖、全部要求是否保留、旧要求暴露、来源真实性及组装延迟。来源真实性不证明模型抗注入；旧要求暴露不证明模型采纳；内存组装耗时不代表包含数据库/网络/模型的生产延迟。纯fixture未传外国历史的检查不代替真实会话授权测试。

同脚本另回放8种工具观察×2压缩方法，输出tool-compaction-前缀报告，检查结构引用、status、原生协议、最新轮完整性及显式LIMIT。它是合成协议回放，不能当成真实工具/模型成绩。结果小结见[Memory诊断实验结果](08-Memory诊断实验结果.md)。

可选真实模型引用恢复测试，必须显式启用，可能计费：

```powershell
# 使用已有环境变量AUDIORAG_LLM_API_KEY或DEEPSEEK_API_KEY，不导入private配置。
./run-memory-experiments.ps1 -RunModelEvaluation -ModelBudget 3000 -MaxModelCalls 8
```

同2个test场景×四策略，最多8次请求、无自动重试；也可用4的倍数，最多40。模型只看到当前问题和选中的memory，不看到gold、未选历史或其他会话。要求返回可见原文引用，客观核对支持率和重要引句恢复，记录真实回复、usage、状态和延迟；无LLM自评。它仍不是自由回答正确率/完整课程成功率，最终策略还需要实际任务与人工审阅。API/截断/无效JSON与不支持的引用分别记录，不冒充成功。

本轮若未显式运行真实模型选项，就没有真实模型评测成绩。结果保存在忽略提交的.cache/memory-experiments目录；自定义数据集/报告可能含私有历史，应按私有资料处理。

## 后续选择策略的依据

先按开发集保留信息、上下文成本与旧约束暴露筛候选，再以固定模型、同任务、同总token/工具预算测试引用恢复、约束遵守和任务完成。需要跨文档/会话分组留出的真实场景，不能只优化这20个人工题；也不能把某一方法在诊断集中最高当成普遍最优。

可进一步添加带来源校验的生成式摘要或本地embedding历史召回，但当前尚未实现。摘要必须可追溯、处理新旧冲突且不能替代原始记录；跨会话用户画像/项目记忆要先明确授权及遗忘规则，不默认开启。

# Audio智能助手 · 自然语言音频中心

一句话描述想听的内容，系统准备文字稿与音频，保存到可继续收听的内容空间。首页不再要求先选内容模块或手动创建学习项目；学习、时政、原创故事与英语内容共用自然语言入口。

后端沿用 Java 17 + Spring Boot 4 + MySQL/JPA + MinIO，桌面端沿用 Electron + Vue。Qdrant/ONNX 继续提供资料检索，音频队列以 MySQL 为准，Redis 仅保存快照；旧 Redis 队列仍为只读兼容。

```text
audiorag-java/
├─ backend/          Java API、内容准备、Agent、RAG 与持久队列
├─ desktop/          Electron / Vue、自然语言入口与全局播放器
├─ docs/             架构、启动、实验与升级说明
├─ tools/            本机 Windows 语音合成
├─ data/             历史生成音频，继续沿用
├─ legacy/           旧 PWA 与原版交接文档
├─ start-backend-local.cmd
├─ start-frontend-local.cmd
└─ build-all.cmd
```

## 使用方式

登录后直接说“整理今天的时政，做成 10 分钟简报”“讲一个英文睡前故事”或“把刚才那段缩短一点”。音频请求先返回队列状态，文字稿准备和语音合成在后台执行；`PREPARING` 不代表已经生成成功。

全局播放器位于页面底部，返回首页或切换内容空间也能继续播放，支持前后切段与倍速。首页和空间内都能打开历史音频；历史恢复不会重新调用生成。收听位置按账号保存到服务端，本机另有带更新时间的恢复副本；恢复时选择较新的记录，离线期间较新的本机位置会随后同步。退出登录会暂停并清除当前播放器状态。

原文朗读可以从首页“添加资料”开始，系统自动取得“我的资料”空间并打开导入页，无需先生成内容或手动创建项目。也可以在已有空间的“资料”中上传完整文本、Markdown、字幕或带可用转写的音频，再要求朗读指定资料或章节。多份资料时应写明完整标题，接口调用也可指定 `source_record_id`。原文按完整索引的连续范围读取，不会把检索片段拼成原书。

“资料”“问问内容”及“更多工具”保留原有知识库、聊天、制作草稿、Agent 产物审核与执行轨迹能力。旧学习计划作为兼容入口保留；只有明确要求按学习计划生成时才沿用知识点规划，普通新闻、故事或英语请求不会自动套用课程知识点。

## 当前能力与边界

| 内容 | 已实现 | 当前边界 |
| --- | --- | --- |
| 时政与新闻 | 按请求联网检索、筛选发布日期与时间范围、保存来源 | 证据是 RSS 搜索摘要，未读取全文或独立核查；无合适带日期来源时明确失败 |
| 原创故事 | 按指令生成、改写、调整语言与时长、带前文接续 | 前文上下文有长度上限，不保证长篇人物、剧情与世界设定长期一致 |
| 原文朗读 | 从已导入完整资料中选择连续原文、章节及后续范围 | 需先上传资料；旧资料无完整索引时需重新上传；未实现版权内容商城 |
| 英语与中英双语 | 指令指定语言、校验正文语言、Windows 或 CosyVoice 本地语音播放 | 本机已启用 CosyVoice 官方示例音色；未实现逐句评分、跟读评测或完整语言课程体系 |
| 通用收听 | 持久队列、全局播放、历史、倍速、服务端进度 | 尚无移动客户端、跨设备音频分发、自动 RSS 订阅或创作者发布平台 |

模型不可用、生成正文为空或语言不符合请求时会明确显示失败，不会改用固定课程模板冒充成功。原文朗读不依赖模型创作，但仍需要本机语音能力。修改既有音频时，继承来源若未重新检索，会标注“沿用原音频来源，未重新检索”。

## 启动与升级

先启动已有 MySQL、MinIO、Qdrant、Redis 服务——本机由 compose 项目 `ai-services` 提供（`docker compose -f D:\AI-Services\docker-compose.yml start`），再运行 `start-backend-local.cmd` 与 `start-frontend-local.cmd`。脚本名称和用法不变。后端健康检查为 `http://127.0.0.1:18081/api/health`，桌面开发界面端口为 `5174`。

后端启动脚本使用 UTF-8，并通过 `-Djava.net.useSystemProxies=true` 沿用 Windows 系统代理，避免 Java 与系统客户端走不同网络而无法读取新闻流。程序对本机环回地址强制直连，确保 MySQL、MinIO、Qdrant 等本机服务不经代理。手工运行 JAR 时也应携带这两个 JVM 参数。

连接参数仍位于不提交的 `backend/config/application-private.yml`。模型密钥读取 `DEEPSEEK_API_KEY` 或 `AUDIORAG_LLM_API_KEY`。本机语音支持 Windows System.Speech 和 CosyVoice；当前本机部署标记使用 CosyVoice。音色列表来自真实服务检测，缺少对应语言的音色时不会悄悄降级为其他语言。

可选的 CosyVoice 3 本地自然语音、独立启动入口和音色配置见 [CosyVoice 本地语音部署](docs/14-CosyVoice本地语音部署.md)。部署验证后，后端启动入口会自动准备该语音服务；已有 WAV 和收听进度仍可使用。

自然语言音频中心升级增加了 `audio_instruction_receipts` 与 `audio_playback_progress` 两张表，没有删除或重命名旧表；继续沿用 JPA `ddl-auto=update`。2026-10-21 的语音兼容、语言判断、日期范围和检索修复没有增加数据表。

## 接口与兼容

自然语言入口为 `POST /api/v1/audio/requests`，必填 `instruction` 和 UUID `request_id`，可选 `project_id`、`previous_queue_id`、`source_record_id`、`voice`。上下文请求发送实际播放器的队列编号，避免误用同空间最近生成的另一段内容。

`GET /api/v1/audio/history` 提供历史队列，`GET /api/v1/audio/voices` 检查本机音色，`GET/PUT /api/v1/audio/queues/{queueId}/progress` 读写实际收听进度。完整协议、回执幂等和失败恢复见[自然语言音频中心重构](docs/13-自然语言音频中心重构.md)。原有项目、知识库、会话、单节/连续音频、Agent 产物及核验/重置接口继续保留。

## 文档与验证

- [自然语言音频中心重构](docs/13-自然语言音频中心重构.md)
- [架构说明](docs/01-架构说明.md)
- [启动与学习路线](docs/02-启动与学习路线.md)
- [Agent 运行时与多 Agent](docs/03-Agent运行时与多Agent.md)
- [简历与面试要点](docs/04-简历与面试要点.md)
- [共享 RAG 索引、模型选策略与实验](docs/05-自适应RAG与实验.md)
- [Memory 上下文与多策略实验](docs/07-Memory上下文与实验.md)
- [项目模拟面试](docs/09-项目模拟面试问答与审查.md)
- [Agent 可靠性升级](docs/10-Agent可靠性升级记录.md)
- [持久队列、超时、预算与调用级 Trace](docs/12-面经驱动的工程升级.md)
- [DeepSeek 真实接口测试记录](docs/15-DeepSeek真实接口测试记录.md)

2026-10-22 验证：完整 `Maven verify` 成功，481 项测试无失败、错误或跳过；桌面端 30 项测试与生产构建通过；本地语音适配器 9 项测试通过。CosyVoice 已在 RTX 4060 上实际生成中文、英语和双语 WAV。

常规自动化测试使用独立测试数据库与模拟外部依赖。真实 DeepSeek 联调经本地项目 API 运行，使用单独测试账号保存正文、队列、音频和审稿产物，详见测试记录；不会将模拟模型的测试当作真实模型结果。`build-all.cmd` 保留原有验证入口，桌面端可以在 `desktop` 中运行 `npm test` 与 `npm run build`。

原有实验命令保留：`run-rag-experiments.ps1` 运行本地 BGE/ONNX 检索对照，结果位于 `.cache/rag-experiments/`；`run-memory-experiments.ps1` 默认运行本地诊断，只有显式 `-RunModelEvaluation` 才启用可能计费的模型评测。小型诊断结果不能当作生产准确率。历史实验文档保留原貌；旧 PWA 在 `legacy/pwa`，不再作为后端首页。

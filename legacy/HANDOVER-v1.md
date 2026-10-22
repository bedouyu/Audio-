# AudioRAG Java 原版项目交接文档（归档）

## 1. 项目定位

AudioRAG 是一个面向学习场景的 AI 音频学习 Agent。用户创建学习主题项目后，可导入资料形成 RAG 知识库，围绕项目主题进行问答、生成音频课程，并保留资料、会话、执行轨迹和课程记录。

当前正式版本位于 `D:\desktop\AI\AI\audiorag-java`，后端为 Spring Boot 3.5.16 + Java 17。项目没有 Python 服务依赖，也没有使用 LangChain 或 Spring AI，RAG、工具调用和上下文选择逻辑均由 Java 代码直接实现。

## 2. 当前可用能力

- 用户注册、登录和 Token 鉴权。
- 按主题创建、查看、删除学习项目。
- 导入文本、字幕与音频资料；原文件存 MinIO，文本切片并向量化后存 Qdrant。
- 使用本地 BGE 中文向量模型进行检索；检索候选经过去重、来源均衡和上下文长度控制后再发送给大模型。
- DeepSeek 兼容接口生成问答和课程讲稿；没有配置密钥时会使用本地兜底内容。
- 知识库低置信度时可使用 Web Search 工具补充资料。
- MySQL 保存项目记录、会话消息、Agent Trace、知识资料元数据与学习计划。
- Windows `System.Speech` 合成 WAV，支持慧慧、康康、瑶瑶等本机已安装音色。
- 支持手机端 PWA、媒体控制和后台播放。
- 连续课程模式：一次调用大模型生成 30 或 60 分钟完整讲稿，按约 5 分钟切段；初始只合成前两段音频，播放时按需预取后续音频。

## 3. 架构与数据流

```text
浏览器 / 手机 PWA
        |
Spring Boot (8000)
  |-- Auth / Project / Record API ---- MySQL
  |-- KnowledgeService -------------- MinIO (原始文件)
  |-- TextChunker + BGE ONNX -------- Qdrant (向量与片段)
  |-- AgentService ------------------ DeepSeek / 本地兜底
  |-- ContinuousAudioService -------- Redis (队列状态)
  |-- SpeechService ----------------- 本地 generated-audio WAV
```

Agent 实际使用的工具：

1. `knowledge_search`：Qdrant 检索当前项目资料。
2. `web_search`：本地检索为空或低置信度时的网络补充。

Agent 的工具调用、候选数量、命中数量和回答过程会写入 `events`，可通过 Trace 接口查询。

## 4. 目录说明

```text
audiorag-java/
├─ src/main/java/com/bedouyu/audiorag/
│  ├─ agent/          Agent、LLM、网络搜索
│  ├─ audio/          语音合成与连续播放队列
│  ├─ auth/           注册、登录、Token
│  ├─ data/           MySQL 数据访问
│  ├─ knowledge/      文件入库与转写
│  ├─ learning/       不重复学习知识点计划
│  ├─ project/        项目级删除清理
│  ├─ rag/            切片、BGE 向量、Qdrant、上下文选择
│  ├─ storage/        MinIO 对象存储
│  └─ web/            REST 控制器
├─ src/main/resources/
│  ├─ application.yml 配置默认值
│  └─ static/         前端页面、PWA 清单、Service Worker
├─ scripts/
│  ├─ start-java.ps1  启动 Java 服务
│  └─ synthesize.ps1  调用 Windows System.Speech 输出 WAV
├─ data/generated-audio/ 运行时生成的音频文件
├─ pom.xml
└─ HANDOVER.md
```

## 5. 运行环境

### 本机工具路径

- JDK：`D:\AI-Toolchain\jdk-17.0.20.1+1`
- Maven：`D:\AI-Toolchain\apache-maven-3.9.16`
- Maven 本地仓库：`D:\AI-Toolchain\m2`
- BGE 模型：`D:\AI-Toolchain\models\bge-small-zh-v1.5`

### Docker 依赖

容器名称和端口：

| 服务 | 容器 | 端口 | 用途 |
|---|---|---:|---|
| MySQL | `docpilot-mysql` | 3306 | 用户、项目、消息、记录 |
| Redis | `docpilot-redis` | 6379 | 连续播放队列恢复 |
| Qdrant | `docpilot-qdrant` | 6333 | 向量检索 |
| MinIO API | `docpilot-minio` | 9000 | 原始资料对象存储 |
| MinIO Console | `docpilot-minio` | 9001 | 对象存储管理页面 |

启动容器：

```powershell
docker start docpilot-mysql docpilot-redis docpilot-qdrant docpilot-minio
```

启动服务：

```powershell
cd D:\desktop\AI\AI\audiorag-java
$env:JAVA_HOME = "D:\AI-Toolchain\jdk-17.0.20.1+1"
& "D:\AI-Toolchain\apache-maven-3.9.16\bin\mvn.cmd" -DskipTests package
.\scripts\start-java.ps1
```

验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

访问地址：

- 电脑：`http://127.0.0.1:8000/`
- 手机：`http://<电脑局域网IP>:8000/`
- MinIO 管理页：`http://127.0.0.1:9001/`

## 6. 配置与密钥

配置文件为 `src/main/resources/application.yml`。本地开发默认值可直接运行；生产或对外演示前必须替换所有默认密码与密钥。

| 配置项 | 说明 |
|---|---|
| `AUDIORAG_LLM_API_KEY` | DeepSeek 或其他 OpenAI 兼容模型的密钥 |
| `AUDIORAG_LLM_BASE_URL` | 默认 `https://api.deepseek.com` |
| `AUDIORAG_LLM_MODEL` | 默认 `deepseek-chat` |
| `AUDIORAG_TRANSCRIPTION_URL` | 可选，音频转写服务地址 |
| `AUDIORAG_TRANSCRIPTION_API_KEY` | 可选，转写服务密钥 |
| `DOCPILOT_MYSQL_*` | MySQL 连接信息 |
| `DOCPILOT_MINIO_*` | MinIO 连接信息 |
| `AUDIORAG_AUDIO_DIR` | WAV 音频输出目录 |

MinIO 开发账号为 `docpilot`；密码配置在环境变量或容器配置中。不要将真实模型密钥、生产数据库密码或新的 MinIO 密码提交到 Git。

## 7. 主要接口

| 方法 | 地址 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录 |
| GET | `/api/v1/me` | 当前用户 |
| GET/POST | `/api/v1/projects` | 项目列表/创建 |
| DELETE | `/api/v1/projects/{projectId}` | 删除项目及关联资源 |
| GET | `/api/v1/projects/{projectId}/records` | 项目记录 |
| POST | `/api/v1/knowledge/audio` | 上传并入库 |
| GET | `/api/v1/projects/{projectId}/knowledge` | 知识文件列表 |
| POST | `/api/v1/chat` | Agent 问答 |
| GET | `/api/v1/sessions/{sessionId}/trace` | Agent 执行轨迹 |
| POST | `/api/v1/lessons` | 单节课程与音频 |
| POST | `/api/v1/continuous-audio` | 创建连续课程 |
| GET | `/api/v1/continuous-audio/{queueId}` | 查询队列状态 |
| POST | `/api/v1/continuous-audio/{queueId}/refill` | 触发下一批语音预取 |
| GET | `/health` | 服务与依赖健康状态 |

除注册、登录和健康检查外，接口均需携带 `Authorization: Bearer <token>`。

## 8. 连续课程实现说明

相关代码：

- `agent/AgentService.java`：`createCourse` 一次检索、一次生成完整讲稿。
- `agent/LlmGenerationService.java`：`course` Prompt，要求按自然段、避免重复。
- `audio/ContinuousAudioService.java`：切分讲稿，Redis 保存完整切分结果，只预合成两段音频。
- `static/index.html`：连续课程时长选择、播放预取和 Media Session 控制。

当前参数：

- 课程总时长：30 或 60 分钟。
- 每段：5 分钟。
- 初始预合成：2 段。
- 剩余 2 段以内或音频结束前触发后续合成。

注意：完整 60 分钟讲稿的单次模型输出较长，需留意模型的最大输出 Token 限制和超时；当前 `RestClient` 读取超时为 60 秒。

## 9. 已知限制与建议

1. 当前语音使用 Windows 本机 TTS，稳定但自然度有限。可将 `SpeechService` 替换为 Azure Neural TTS 或火山引擎 TTS，播放器和队列逻辑无需重写。
2. 音频上传未配置转写接口时，需同时提供手工转写文本；可接入 Whisper 兼容服务。
3. DeepSeek Key 当前通过运行环境配置；建议改为 `.env` 或系统环境变量，并建立密钥轮换机制。
4. Redis 队列保存期为 7 天；课程音频文件当前保留在本机磁盘，需补充过期清理策略。
5. `learning_points` 的 12 个固定知识点用于避免重复生成。所有点完成后，当前会阻止继续生成；可增加“重置计划”或“按资料新增知识点”的产品能力。
6. 本地服务依赖电脑 IP 和 Docker Desktop；若需稳定手机访问，应给电脑配置 DHCP 地址保留或部署到服务器。
7. 前端目前为单页静态 HTML，后续复杂功能建议逐步拆分为 Vue 或 React 组件，但不应影响现有接口契约。

## 10. 交接检查清单

- Docker Desktop 已启动，四个 `docpilot-*` 容器处于 Up 状态。
- BGE 模型目录存在且可读取。
- `GET /health` 返回 `status: ok`。
- 能注册用户、创建项目、上传文本资料并在 MinIO 中看到原文件。
- 能生成单节课程，浏览器可播放 `/media/...` 音频。
- 连续课程能先出现两条可播放音频，并在接近结束时继续补充。
- 手机与电脑在同一 Wi-Fi，使用局域网 IP 可打开 8000 端口页面。

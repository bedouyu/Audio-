# 共享RAG索引、模型选策略与实验

## 已实现的范围

同一套资料索引支持不同的召回与上下文组合。模型通过现有原生 Function Calling 选择策略，服务端执行并校验；不是额外增加一个“假装选策略”的规则模型。

```text
上传原文件 → MinIO
提取/转写 → 规范化原文 → MySQL documents + canonical chunks
                             └→ 同一 Qdrant collection 的片段向量

问题 + 文档目录 → 主控/研究 Agent 选择工具参数
                    ├→ dense：Qdrant 向量召回
                    ├→ bm25：MySQL canonical chunks 的独立词法索引
                    └→ hybrid：两路召回 + RRF
                            ↓
                    Top-K / 固定邻居 / RSE rank-proxy
                            ↓
                    原文区间恢复、去重、预算 → 带位置的证据
```

### 模型如何选择

`knowledge_catalog` 分页返回文档ID、标题、输入来源类型和索引版本，不发送完整论文/录音文字。类型按文件来源推断（text/markdown/subtitle/transcript/structured_text），不是已经识别的法律、数学等语义类型；标题和任务也供模型判断。

`knowledge_search` 可选参数：

| 参数 | 值与用途 |
|---|---|
| retrieval_strategy | dense / bm25 / hybrid，默认hybrid |
| context_strategy | top_k / neighbors / rse_rank_proxy，默认top_k |
| context_token_budget | 256–2048，本地BGE分词器计数，默认1024 |
| document_ids | 当前项目新版文档ID，最多20个；不填写则项目范围 |
| limit | 最多1–6段证据，默认4 |
| strategy_reason | 300字以内的简短选择依据，不要求私有推理轨迹 |

精确名词/编号可尝试BM25，语义改写可尝试dense，不确定可hybrid；单事实Top-K，依赖前后文neighbors，连续流程/论证可尝试RSE。这些是提示词启发，不是经过业务评测保证的最优策略。调用参数、实际执行、证据位置和警告保存到工具轨迹；前端聊天执行过程与任务运行记录可查看策略卡片。

本次完成了模型可调用的策略入口、参数约束和执行测试，尚未测量真实大模型“选策略准确率”。内置检索实验没有调用生成模型，不应把最优实验条件当成模型已经会准确选择。

## 为什么不用每种方法一套数据库

关键词/向量/混合是召回方法；Top-K/邻居/RSE是查询时的证据组合。它们共享规范化原文、片段顺序和坐标。RSE组合一个较长段落时不需要重新编码、重建一个向量库；从原文坐标恢复即可。这个区分也符合[dsRAG将RSE定义为查询时后处理](https://github.com/D-Star-AI/dsRAG)。

结构字段：

| 存储 | 关键字段 |
|---|---|
| MySQL knowledge_index_documents | document UUID、project_id、title、source、document_type、text_version(SHA256)、index_version、完整规范化原文 |
| MySQL knowledge_index_chunks | point UUID、document_id、ordinal、start/end_offset、token_count、parent_id、原文片段与版本 |
| Qdrant payload | 对应point/document/project标识、版本、序号、坐标、内容、输入类型、切片策略、模型名称与维度 |

`parent_id` 是原文双换行的段落锚点，不是模型推断的语义章节。坐标为规范化原文的UTF-16半开区间，不是原始文件byte或音频时间。MySQL原文和MinIO原文件有不同用途，两者都需要备份。

Qdrant仍使用一个已有collection，以project_id等payload进行逻辑隔离；执行器与知识文件接口仍检查用户归属。payload过滤的能力可见[Qdrant官方说明](https://qdrant.tech/documentation/concepts/filtering/)。不会给模型项目ID参数，也不会因策略切换扩大访问范围。

生产新上传固定采用384-token句子/段落切片、最多48-token重叠；这是共享canonical leaf索引。查询时可改变连续证据段长度，模型不会在查询中重新切片写库。字符窗口、token窗口、句子窗口的入库差异目前在离线实验比较，不把三个重叠版本混入默认生产召回。

将来确需保存不同切片版本，可用新的index_version、原文坐标和独立point ID在同一collection区分，查询时明确过滤版本。不同维度/模型的向量则要设计named vectors或独立模型collection；这属于模型兼容性，不是每个检索方法都需要一个数据库。目前尚未实现多embedding模型并存。

## 三种上下文方式及RSE边界

- Top-K：按排名放入完整片段；相交原文区间合并，避免重叠切片重复文字。
- neighbors：固定±1邻居，按同文档原文坐标合并相接/相交窗口。
- rse_rank_proxy：检索片段效用 `exp(-rank/10)-0.2`，未命中片段效用 `-0.2`；贪心选择最高总效用连续段，最多6片，最低效用0.1，允许跨短负效用间隙，不跨文档，不返回重叠段。

这是参考[dsRAG RSE源实现](https://github.com/D-Star-AI/dsRAG/blob/main/dsrag/rse.py)的明确变体。上游可使用校准重排器相关性值；本项目没有这样的重排器，不能把BM25、RRF或余弦分数直接当概率。当前未实现AutoContext、LLM语义sectioning或Cross-Encoder reranking。

三种方式均以相同渲染头部和正文计算BGE tokens；另控制工具JSON字符预算，省略放不下的完整证据段，而不是隐藏地截断段内证明。证据含文档版本和原文坐标，引用登记也保留位置。该预算不等于DeepSeek等生成模型的精确token数。

## 兼容与运行限制

旧向量不会删除或自动重建。只有旧资料时实际策略为legacy_dense/legacy_top_k，明确说明不支持新版BM25或RSE。混合新旧资料时新版使用结构化策略，旧资料保留独立dense兼容分支，两者未进行可校准的统一重排。最多返回6段且受预算限制，不表示所有旧文档都已阅读。

向量不可用时可降级新版BM25，报告旧文档此次未参与。指定的document_ids不属于当前项目或不在新版索引时拒绝访问。旧版单节课程/连续音频的基础检索入口仍沿用原有AgentTool；自适应工具首先接入主控与研究Agent聊天，不声称所有旧入口已统一。

本机实现的BM25每次从有界MySQL片段集合建轻量词法索引，没有引入Elasticsearch/OpenSearch或持久稀疏倒排索引。上限1000文档/10000片段，单份文字最多200万UTF-16字符；超出明确报错，不静默截断。文档目录/查询目前加载有界完整原文，并非百万级高并发方案。容量计数不是分布式并发配额锁。

入库前分配point UUID，向量分批上传；失败时分别尝试清理三个存储且不覆盖原始异常。MySQL document/chunk保存处于单个本地事务，但这不是MySQL/MinIO/Qdrant分布式事务，也没有后台孤儿数据对账任务。删除同样不是跨系统原子事务。

启动前备份现有数据库。现有ddl-auto=update会新增两张索引表；本次测试使用H2验证映射、项目隔离、入库和删除，不代表已对用户生产MySQL/MinIO/Qdrant完成集成验收。

## 本地真实模型实验

项目根目录运行：

```powershell
./run-rag-experiments.ps1
# 可用自行标注的诊断集
./run-rag-experiments.ps1 -Dataset 'D:\your-data\rag-eval.json'
```

它只启动离线main，读取本地BGE ONNX，不启动Spring或读取private配置，不调用付费API，不上传资料、不触碰现有知识库。

每种切片建立一次独立内存索引，比较：字符900/重叠120、token256/32、句子384/48；每种索引再比较dense、BM25、RRF hybrid；每路召回比较三种上下文组合；每种预算512/1024/2048。18题共1458条条件记录，不是1458道独立问题。

内置人工诊断集6文档18题，dev6/test12。所有参数预先固定，未根据test标签调整。真实本地模型生成向量，exact cosine替代生产Qdrant的ANN搜索；不能把离线延迟当生产Qdrant吞吐或端到端模型延迟。

输出位于`.cache/rag-experiments/<运行时间>/`：

- report.json：逐题完整证据、原文范围、指标、数据集/模型/分词器校验哈希及固定参数。
- summary.csv：按dev/test、切片、召回、上下文策略和预算汇总。
- type-summary.csv：进一步按问题类型分组；保留样本数，少量样本不能推导普适策略。
- README.md：索引超长统计及对照表。

指标包括top5独立文档Recall与文档MRR、标注原文字符覆盖、全部证据覆盖率、标注证据密度和p50/p95检索+组合耗时。文档命中不能代表多处证据已经齐全；相互包含的标注区间合并计算，不重复充当覆盖收益。没有用LLM自评分充当正确率。

标签格式见`backend/src/main/resources/evals/rag-diagnostic.json`：documents含id/text，questions含id/split/type/query及gold(documentId/quote)。quote必须在对应规范化原文唯一出现。真实业务评估应按文档或任务分组划分训练/开发/测试，补充长音频转写、字幕、报告和数学文献，避免用这18题诊断集调参后宣称泛化提升。

本次实测小结见[06-RAG诊断实验结果.md](06-RAG诊断实验结果.md)。

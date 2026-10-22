package com.bedouyu.audiorag.rag.experiment;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.rag.OnnxEmbeddingService;
import com.bedouyu.audiorag.rag.strategy.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.ToIntFunction;

/**
 * 离线真实 ONNX 消融实验，不启动 Spring，不连接生产 MySQL/MinIO/Qdrant，不调用付费模型。
 * 质量标签是独立原文区间，而非某种切片策略产生的 chunk ID。
 */
public final class RagExperimentRunner {
    public record Document(String id, String text) { }
    public record Gold(String documentId, String quote) { }
    public record Question(String id, String split, String type, String query, List<Gold> gold) { }
    public record Dataset(String name, String provenance, List<Document> documents, List<Question> questions) { }
    record Span(String documentId, int start, int end) { }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = options(arguments);
        ObjectMapper json = new ObjectMapper();
        String raw;
        if (options.containsKey("dataset")) raw = Files.readString(Path.of(options.get("dataset")), StandardCharsets.UTF_8);
        else try (var input = RagExperimentRunner.class.getResourceAsStream("/evals/rag-diagnostic.json")) {
            if (input == null) throw new IllegalStateException("缺少实验数据集");
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Dataset dataset = json.readValue(raw, Dataset.class);
        Map<String, String> documents = new TreeMap<>();
        for (Document document : dataset.documents()) {
            if (documents.putIfAbsent(document.id(), document.text().replace("\r\n", "\n").strip()) != null)
                throw new IllegalArgumentException("重复文档ID");
        }
        if (documents.isEmpty() || dataset.questions().isEmpty()) throw new IllegalArgumentException("实验集不能为空");
        Map<String, List<Span>> labels = labels(dataset, documents);
        Path model = Path.of(options.getOrDefault("model-dir", "D:/AI-Toolchain/models/bge-small-zh-v1.5"));
        Path output = Path.of(options.getOrDefault("output-dir", ".cache/rag-experiments/" + System.currentTimeMillis()));
        Files.createDirectories(output);
        List<Map<String, Object>> details = new ArrayList<>();
        List<Map<String, Object>> indexes = new ArrayList<>();
        try (var embedding = new OnnxEmbeddingService(model.toString(), json)) {
            ToIntFunction<String> counter = embedding::contentTokenCount;
            embedding.embed("预热模型"); // 与质量样本无关的预热，不计入查询延迟。
            for (ChunkingStrategy strategy : List.of(new CharacterWindowChunker(), new TokenWindowChunker(), new SentenceTokenChunker())) {
                long indexStart = System.nanoTime();
                List<TextSegment> chunks = new ArrayList<>();
                documents.forEach((id, text) -> chunks.addAll(strategy.split(id, text, counter)));
                List<float[]> vectors = chunks.stream().map(chunk -> embedding.embed(chunk.text())).toList();
                var lexical = new Bm25Ranker(chunks);
                indexes.add(Map.of("chunking", strategy.id(), "chunks", chunks.size(),
                        "truncated_embedding_chunks", chunks.stream().filter(chunk -> chunk.tokenCount() > 510).count(),
                        "index_ms", milliseconds(indexStart), "dimensions", vectors.get(0).length));
                System.out.println("Indexed " + strategy.id() + ": " + chunks.size() + " chunks");
                for (Question question : dataset.questions()) {
                    long start = System.nanoTime();
                    float[] queryVector = embedding.embed(question.query());
                    var dense = HybridRanking.rankDense(chunks, vectors, queryVector, 50);
                    double denseMs = milliseconds(start);
                    start = System.nanoTime();
                    var bm25 = lexical.rank(question.query(), 50);
                    double lexicalMs = milliseconds(start);
                    Map<String, List<RankedChunk>> routes = new LinkedHashMap<>();
                    routes.put("dense", dense); routes.put("bm25", bm25);
                    routes.put("hybrid", HybridRanking.fuse(dense, bm25, 50));
                    for (var route : routes.entrySet()) {
                        for (int budget : List.of(512, 1024, 2048)) {
                            for (SegmentAssembler assembler : List.of(new TopKSegmentAssembler(),
                                    new NeighborWindowAssembler(), new RseSegmentAssembler())) {
                                long assemblyStart = System.nanoTime();
                                var context = assembler.assemble(documents, chunks, route.getValue(), budget, counter);
                                Map<String, Object> metrics = new LinkedHashMap<>(metrics(route.getValue(), context, labels.get(question.id())));
                                metrics.put("question_id", question.id()); metrics.put("split", question.split());
                                metrics.put("question_type", question.type()); metrics.put("query", question.query());
                                metrics.put("chunking", strategy.id()); metrics.put("retrieval", route.getKey());
                                metrics.put("assembly", assembler.id()); metrics.put("token_budget", budget);
                                metrics.put("context_tokens", context.stream().mapToInt(EvidenceSegment::contextTokens).sum());
                                metrics.put("query_retrieval_ms", "dense".equals(route.getKey()) ? denseMs
                                        : "bm25".equals(route.getKey()) ? lexicalMs : denseMs + lexicalMs);
                                metrics.put("assembly_ms", milliseconds(assemblyStart));
                                metrics.put("evidence", context); // 报告在本地，便于人工核查，不发给模型。
                                details.add(metrics);
                            }
                        }
                    }
                }
            }
        }
        var summaries = summaries(details, false);
        var byType = summaries(details, true);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("created_at", Instant.now().toString()); report.put("dataset", dataset.name());
        report.put("provenance", dataset.provenance()); report.put("dataset_sha256", Hashing.sha256(raw));
        report.put("model_sha256", fileHash(model.resolve("model_optimized.onnx")));
        report.put("tokenizer_sha256", fileHash(model.resolve("tokenizer.json")));
        report.put("document_count", documents.size()); report.put("question_count", dataset.questions().size());
        report.put("candidate_depth", 50); report.put("rrf_k", 60); report.put("rse", "rank_proxy,max_chunks=6,decay=10,penalty=.2,min=.1");
        report.put("index_statistics", indexes); report.put("summaries", summaries); report.put("by_question_type", byType);
        report.put("per_query", details);
        report.put("limitations", List.of("人工编写诊断集，不是公开全量基准或用户业务准确率。",
                "只评估检索/证据覆盖，不评估LLM回答正确率或模型选策略准确率。",
                "BGE token预算只用于相同实验条件，不等于生成模型token数。",
                "RSE是rank-proxy变体，没有dsRAG的校准reranker、AutoContext或LLM语义sectioning。",
                "hybrid计时为共享两路单次测量之和，不含独立重跑，非并发生产SLA。"));
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(), report);
        Files.writeString(output.resolve("summary.csv"), csv(summaries), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("type-summary.csv"), csv(byType), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("README.md"), markdown(dataset, indexes, summaries), StandardCharsets.UTF_8);
        System.out.println("Completed " + details.size() + " conditions. Local reports: " + output.toAbsolutePath());
    }

    private static Map<String, String> options(String[] arguments) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < arguments.length; i += 2) {
            if (i + 1 >= arguments.length || !Set.of("--dataset", "--model-dir", "--output-dir").contains(arguments[i]))
                throw new IllegalArgumentException("参数格式：--dataset file --model-dir directory --output-dir directory");
            result.put(arguments[i].substring(2), arguments[i + 1]);
        }
        return result;
    }
    private static Map<String, List<Span>> labels(Dataset dataset, Map<String, String> documents) {
        Map<String, List<Span>> result = new HashMap<>();
        for (Question question : dataset.questions()) {
            if (!Set.of("dev", "test").contains(question.split()) || question.gold().isEmpty())
                throw new IllegalArgumentException("问题必须有dev/test分组与原文证据");
            List<Span> spans = new ArrayList<>();
            for (Gold gold : question.gold()) {
                String text = documents.get(gold.documentId());
                int start = text == null ? -1 : text.indexOf(gold.quote());
                if (gold.quote().isBlank() || start < 0 || start != text.lastIndexOf(gold.quote()))
                    throw new IllegalArgumentException("标签必须是唯一原文引句：" + question.id());
                spans.add(new Span(gold.documentId(), start, start + gold.quote().length()));
            }
            if (result.putIfAbsent(question.id(), mergeSpans(spans)) != null) throw new IllegalArgumentException("重复问题ID");
        }
        return result;
    }
    static Map<String, Object> metrics(List<RankedChunk> candidates, List<EvidenceSegment> segments, List<Span> gold) {
        gold = mergeSpans(gold); // 标签可能互相包含，不能重复计算覆盖和密度。
        Set<String> wanted = new HashSet<>(); gold.forEach(span -> wanted.add(span.documentId()));
        List<String> rankedDocs = new ArrayList<>(new LinkedHashSet<>(candidates.stream().map(hit -> hit.chunk().documentId()).toList()));
        long hitDocs = rankedDocs.stream().limit(5).filter(wanted::contains).count();
        double reciprocalRank = 0;
        for (int i = 0; i < rankedDocs.size(); i++) if (wanted.contains(rankedDocs.get(i))) { reciprocalRank = 1.0 / (i + 1); break; }
        int goldChars = 0, covered = 0;
        boolean all = true;
        for (Span span : gold) {
            int length = span.end() - span.start();
            int cover = segments.stream().filter(segment -> span.documentId().equals(segment.documentId()))
                    .mapToInt(segment -> Math.max(0, Math.min(span.end(), segment.end()) - Math.max(span.start(), segment.start()))).sum();
            covered += Math.min(length, cover); goldChars += length; all &= cover >= length;
        }
        int chars = segments.stream().mapToInt(segment -> segment.end() - segment.start()).sum();
        return Map.of("document_recall_at_5", (double) hitDocs / wanted.size(), "document_mrr", reciprocalRank,
                "gold_span_coverage", (double) covered / goldChars, "all_evidence_covered", all ? 1.0 : 0.0,
                "evidence_density", chars == 0 ? 0.0 : (double) covered / chars, "context_characters", chars);
    }
    private static List<Span> mergeSpans(List<Span> spans) {
        List<Span> ordered = spans.stream().sorted(Comparator.comparing(Span::documentId)
                .thenComparingInt(Span::start).thenComparingInt(Span::end)).toList();
        List<Span> merged = new ArrayList<>();
        for (Span span : ordered) {
            if (!merged.isEmpty()) {
                Span previous = merged.get(merged.size() - 1);
                if (previous.documentId().equals(span.documentId()) && span.start() <= previous.end()) {
                    merged.set(merged.size() - 1, new Span(span.documentId(), previous.start(), Math.max(previous.end(), span.end())));
                    continue;
                }
            }
            merged.add(span);
        }
        return List.copyOf(merged);
    }
    private static List<Map<String, Object>> summaries(List<Map<String, Object>> details, boolean byQuestionType) {
        Map<String, List<Map<String, Object>>> groups = new TreeMap<>();
        List<String> fields = new ArrayList<>(List.of("split", "chunking", "retrieval", "assembly", "token_budget"));
        if (byQuestionType) fields.add("question_type");
        for (var detail : details) {
            String key = fields.stream()
                    .map(field -> String.valueOf(detail.get(field))).reduce((a, b) -> a + ":" + b).orElseThrow();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(detail);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var samples : groups.values()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            for (String field : fields) summary.put(field, samples.get(0).get(field));
            summary.put("questions", samples.size());
            for (String metric : List.of("document_recall_at_5", "document_mrr", "gold_span_coverage", "all_evidence_covered", "evidence_density", "context_tokens"))
                summary.put(metric, samples.stream().mapToDouble(row -> ((Number) row.get(metric)).doubleValue()).average().orElse(0));
            var latency = samples.stream().mapToDouble(row -> ((Number) row.get("query_retrieval_ms")).doubleValue()
                    + ((Number) row.get("assembly_ms")).doubleValue()).sorted().toArray();
            summary.put("p50_ms", latency[(int) Math.ceil(latency.length * .5) - 1]);
            summary.put("p95_ms", latency[(int) Math.ceil(latency.length * .95) - 1]);
            result.add(summary);
        }
        return result;
    }
    private static String csv(List<Map<String, Object>> summaries) {
        List<String> fields = new ArrayList<>(summaries.get(0).keySet());
        StringBuilder result = new StringBuilder(String.join(",", fields)).append('\n');
        for (var row : summaries) result.append(fields.stream().map(field -> String.valueOf(row.get(field)))
                .reduce((a, b) -> a + "," + b).orElseThrow()).append('\n');
        return result.toString();
    }
    private static String markdown(Dataset dataset, List<Map<String, Object>> indexes, List<Map<String, Object>> summaries) {
        StringBuilder text = new StringBuilder("# 本地RAG诊断实验\n\n").append(dataset.provenance())
                .append("\n\n只评估检索和证据覆盖，不等于真实业务准确率；所有参数预先固定，未用test标签调参。\n\n")
                .append("## 索引\n\n");
        indexes.forEach(index -> text.append("- ").append(index).append('\n'));
        text.append("\n## Test，1024 BGE tokens\n\n|切片|召回|上下文|证据覆盖|全部证据覆盖|证据密度|p95 ms|\n|---|---|---|---:|---:|---:|---:|\n");
        summaries.stream().filter(row -> "test".equals(row.get("split")) && Integer.valueOf(1024).equals(row.get("token_budget")))
                .forEach(row -> text.append(String.format(Locale.ROOT, "|%s|%s|%s|%.3f|%.3f|%.3f|%.1f|%n",
                        row.get("chunking"), row.get("retrieval"), row.get("assembly"), row.get("gold_span_coverage"),
                        row.get("all_evidence_covered"), row.get("evidence_density"), row.get("p95_ms"))));
        return text.append("\n完整逐题证据与校验哈希见report.json；所有预算、dev/test及召回组合见summary.csv。\n").toString();
    }
    private static double milliseconds(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static String fileHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length); }
        return HexFormat.of().formatHex(digest.digest());
    }
}

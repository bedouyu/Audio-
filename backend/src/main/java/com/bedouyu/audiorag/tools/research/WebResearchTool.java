package com.bedouyu.audiorag.tools.research;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.service.content.AudioIntent;
import com.bedouyu.audiorag.tools.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Native adapter preserves live source dates and makes fresh-news retrieval explicit. */
@Component
public class WebResearchTool implements FunctionTool {
    private final WebSearchService web;
    public WebResearchTool(WebSearchService web) { this.web = web; }
    public FunctionDefinition definition() {
        return new FunctionDefinition("web_search", "联网查找公开资料。时政、新闻和最新消息必须 fresh=true，按 from_date/through_date 日期范围检索；只接受可核对发布时间的本次新闻摘要。返回发布时间、检索时间与来源，不能把摘要当全文。",
                ToolSchemas.object(Map.of("query", ToolSchemas.string(1000), "limit", ToolSchemas.integer(1, 6),
                        "fresh", Map.of("type", "boolean"), "from_date", ToolSchemas.string(10),
                        "through_date", ToolSchemas.string(10)), "query"));
    }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        if (!web.enabled()) throw new IllegalStateException("联网检索未启用，不能生成需要时效来源的内容");
        String query = args.path("query").asText();
        var intent = AudioIntent.resolve(query, 5);
        boolean fresh = args.path("fresh").asBoolean(false) || intent.freshEvidenceRequired();
        LocalDate from = date(args, "from_date", intent.fromDate());
        LocalDate through = date(args, "through_date", intent.throughDate());
        if (through.isBefore(from) || through.isAfter(intent.asOfDate())) throw new IllegalArgumentException("新闻检索日期范围无效或包含未来日期");
        int limit = Math.max(1, Math.min(6, args.path("limit").asInt(4)));
        var hits = fresh ? web.searchFresh(query + " after:" + from.minusDays(1) + " before:" + through.plusDays(1), limit, from, through)
                : web.search(query, limit);
        context.assertActive();
        var evidence = context.evidence.register(hits);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", evidence.isEmpty() ? "NO_RESULTS" : "OK");
        result.put("evidence", evidence); result.put("hit_count", evidence.size()); result.put("fresh", fresh);
        result.put("evidence_kind", fresh ? "live-news-search-summary" : "live-web-search-summary");
        if (fresh) {
            result.put("from_date", from.toString()); result.put("through_date", through.toString());
            result.put("warning", evidence.isEmpty() ? "没有符合发布日期范围的本次新闻证据，不能宣称已完成最新消息播报" : "这是带日期的实时搜索摘要，未证明已读取全文或完成独立事实核查");
        }
        return result;
    }
    private LocalDate date(JsonNode args, String key, LocalDate fallback) {
        String value = args.path(key).asText("");
        if (value.isBlank()) return fallback;
        try { return LocalDate.parse(value); }
        catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("日期必须为 YYYY-MM-DD：" + key); }
    }
}

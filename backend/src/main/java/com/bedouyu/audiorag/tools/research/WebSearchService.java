package com.bedouyu.audiorag.tools.research;

import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.ObjectMapper;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

@Service
public class WebSearchService {
    private final RestClient client;
    private final boolean enabled;

    @Autowired public WebSearchService(RestClient.Builder builder,
            @Value("${audiorag.web-search.enabled}") boolean enabled) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(4000);
        requests.setReadTimeout(6000);
        this.client = builder.requestFactory(requests).build();
        this.enabled = enabled;
    }
    WebSearchService(RestClient client, boolean enabled) { this.client = client; this.enabled = enabled; }

    public List<SearchHit> search(String query, int limit) {
        return search(query, limit, false, null, null);
    }

    /** A live news request never reuses the local index or undated search snippets. */
    public List<SearchHit> searchFresh(String query, int limit, LocalDate from, LocalDate through) {
        if (from == null || through == null || through.isBefore(from)) throw new IllegalArgumentException("新闻检索时间范围无效");
        return search(query, limit, true, from, through);
    }

    private List<SearchHit> search(String query, int limit, boolean news, LocalDate from, LocalDate through) {
        if (!enabled) {
            return List.of();
        }
        try {
            Instant fetchedAt = Instant.now();
            String searchText = news ? newsQuery(query) : query;
            // cn.bing.com 的新闻路径会返回普通网页；新闻 RSS 使用已验证的 www 地址。
            var reply = client.get().uri(uriBuilder -> {
                var uri = uriBuilder.scheme("https").host(news ? "www.bing.com" : "cn.bing.com")
                        .path(news ? "/news/search" : "/search")
                        .queryParam("format", "rss").queryParam("q", searchText);
                if (news) uri.queryParam("qft", "sortbydate=\"1\"");
                return uri.build();
            })
                    .accept(MediaType.APPLICATION_RSS_XML, MediaType.APPLICATION_XML, MediaType.TEXT_XML)
                    .retrieve().toEntity(byte[].class);
            byte[] response = reply.getBody();
            if (response == null || response.length == 0) {
                throw new WebSearchException(WebSearchException.Kind.PROTOCOL, "联网检索返回空响应，不能当作没有相关资料", null);
            }
            MediaType contentType = reply.getHeaders().getContentType();
            String prefix = new String(response, 0, Math.min(response.length, 512), StandardCharsets.UTF_8)
                    .replace("\uFEFF", "").stripLeading().toLowerCase(java.util.Locale.ROOT);
            if (contentType != null && MediaType.TEXT_HTML.isCompatibleWith(contentType)
                    || prefix.startsWith("<!doctype html") || prefix.startsWith("<html"))
                throw new WebSearchException(WebSearchException.Kind.PROTOCOL,
                        "联网检索服务返回了普通网页，未提供 RSS 新闻或资料，请稍后重试", null);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException failure) throws SAXException { throw failure; }
                @Override public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
            });
            var document = parser.parse(new ByteArrayInputStream(response));
            if (!"rss".equalsIgnoreCase(document.getDocumentElement().getTagName()))
                throw new WebSearchException(WebSearchException.Kind.PROTOCOL, "联网检索响应不是预期RSS格式", null);
            NodeList items = document.getElementsByTagName("item");
            List<SearchHit> hits = new ArrayList<>();
            var seen = new HashSet<String>();
            for (int index = 0; index < items.getLength() && hits.size() < limit; index++) {
                Element item = (Element) items.item(index);
                String title = text(item, "title");
                String description = text(item, "description");
                String link = publisherLink(text(item, "link"));
                String publishedRaw = text(item, "pubDate");
                if (publishedRaw.isBlank()) publishedRaw = text(item, "dc:date");
                Instant published = publicationTime(publishedRaw);
                if (news && (published == null || published.isAfter(fetchedAt.plusSeconds(60))
                        || published.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().isBefore(from)
                        || published.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().isAfter(through))) continue;
                URI source;
                try { source = URI.create(link); }
                catch (IllegalArgumentException invalid) { continue; }
                if (source.getHost() == null || !("https".equalsIgnoreCase(source.getScheme()) || "http".equalsIgnoreCase(source.getScheme()))) continue;
                String canonical = source.normalize().toString().replaceAll("#.*$", "");
                if (!description.isBlank() && !link.isBlank() && seen.add(canonical)) {
                    var metadata = new ObjectMapper().createObjectNode();
                    metadata.put("fetched_at", fetchedAt.toString()); metadata.put("source_host", source.getHost());
                    metadata.put("evidence_kind", news ? "live-news-search-summary" : "live-web-search-summary");
                    if (published != null) metadata.put("published_at", published.toString());
                    hits.add(new SearchHit("web-" + Integer.toUnsignedString(link.hashCode()), title,
                            description, link, 0.5, metadata));
                }
            }
            return hits;
        } catch (WebSearchException failure) { throw failure; }
        catch (ResourceAccessException unavailable) {
            throw new WebSearchException(WebSearchException.Kind.TRANSIENT, "联网检索连接或读取超时，请稍后明确重试", unavailable);
        } catch (RestClientResponseException response) {
            int status = response.getStatusCode().value();
            var kind = status >= 500 || status == 408 || status == 429
                    ? WebSearchException.Kind.TRANSIENT : WebSearchException.Kind.REQUEST_REJECTED;
            throw new WebSearchException(kind, "联网检索服务拒绝或暂不可用（HTTP " + status + "）", response);
        } catch (Exception malformed) {
            throw new WebSearchException(WebSearchException.Kind.PROTOCOL, "联网检索响应无法解析，请核查服务格式", malformed);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** News RSS does not support web-search date operators; publication dates are checked below. */
    private String newsQuery(String query) {
        String topic = query.replaceAll("(?i)(?<!\\S)(?:after|before):\\d{4}-\\d{2}-\\d{2}(?=\\s|$)", " ").strip();
        topic = topic.replaceFirst("^(?:请\\s*|麻烦\\s*)?(?:(?:帮我|给我|为我)\\s*)?(?:整理|梳理|汇总|总结|播报|介绍|讲讲)\\s*", "");
        topic = topic.replaceFirst("(?s)[，,。；;]\\s*(?:(?:并|再|然后)\\s*)?"
                + "(?:(?:用|使用|以)\\s*(?:(?:简单|通俗|简明|清晰|自然|慢速)的?\\s*)*"
                + "(?:中英双语|中文|汉语|普通话|英文|英语|双语)\\s*)?"
                + "(?:做成|制作|生成|整理成).*?(?:简报|音频|播客|分钟).*", "");
        topic = topic.replaceFirst("^(?:(?:(?:最近|过去|近)\\s*(?:\\d{1,3}|[一二两三四五六七八九十百]+)"
                + "\\s*(?:天|日|周|个月|月)(?:内|以来)?|最近|近期|今天|今日|昨天|昨日|本周|本月)(?:的)?\\s*)+", "");
        topic = topic.replaceFirst("^20\\d{2}[-年/.]\\d{1,2}[-月/.]\\d{1,2}日?\\s*(?:(?:至|到|~|—|-)\\s*20\\d{2}[-年/.]\\d{1,2}[-月/.]\\d{1,2}日?)?\\s*的?", "");
        // Separate news category labels from adjacent Chinese topic text without dropping
        // places/names or changing the complete instruction sent to the generation model.
        topic = topic.replaceAll("(?<=\\p{IsHan})(?=时政|时事|新闻|资讯)", " ");
        topic = topic.replaceAll("\\s+", " ").strip();
        return topic.isBlank() ? query : topic;
    }

    private Instant publicationTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
        catch (DateTimeParseException ignored) { }
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private String publisherLink(String link) {
        try {
            URI uri = URI.create(link);
            String host = uri.getHost();
            if (host != null && (host.equalsIgnoreCase("bing.com") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".bing.com")) && uri.getRawQuery() != null)
                for (String parameter : uri.getRawQuery().split("&")) {
                    String[] parts = parameter.split("=", 2);
                    if (parts.length == 2 && "url".equalsIgnoreCase(parts[0])) {
                        String publisher = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        URI destination = URI.create(publisher);
                        if (destination.getHost() != null && ("http".equalsIgnoreCase(destination.getScheme()) || "https".equalsIgnoreCase(destination.getScheme()))) return publisher;
                    }
                }
        } catch (IllegalArgumentException ignored) { }
        return link;
    }

    private String text(Element element, String tagName) {
        NodeList values = element.getElementsByTagName(tagName);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent().trim();
    }
}

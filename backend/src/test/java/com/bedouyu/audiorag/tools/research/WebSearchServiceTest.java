package com.bedouyu.audiorag.tools.research;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.http.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class WebSearchServiceTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final WebSearchService service = new WebSearchService(builder.build(), true);
    @Test void emptyValidFeedIsNoResults() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://cn.bing.com/search?")))
                .andExpect(queryParam("format", "rss")).andExpect(decodedQueryParam("q", "query"))
                .andRespond(withSuccess("<rss><channel/></rss>", MediaType.APPLICATION_XML));
        assertThat(service.search("query", 3)).isEmpty(); server.verify();
    }
    @Test void serverFailureDoesNotPretendToBeEmptyResultsOrLeakResponse() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private-service-data"));
        assertThatThrownBy(() -> service.search("query", 3)).isInstanceOfSatisfying(WebSearchException.class,
                e -> assertThat(e.kind()).isEqualTo(WebSearchException.Kind.TRANSIENT)).hasMessageNotContaining("private-service-data");
    }
    @Test void htmlAndDtdAreRejectedAsProtocolErrors() {
        server.expect(anything()).andRespond(withSuccess("<html>captcha</html>", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> service.search("query", 3)).isInstanceOfSatisfying(WebSearchException.class,
                e -> assertThat(e.kind()).isEqualTo(WebSearchException.Kind.PROTOCOL));
        server.reset();
        server.expect(anything()).andRespond(withSuccess("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///missing'>]><rss>&x;</rss>", MediaType.APPLICATION_XML));
        assertThatThrownBy(() -> service.search("query", 3)).isInstanceOf(WebSearchException.class);
    }
    @Test void htmlLandingPageWithDoctypeReportsWrongFormatWithoutLeakingBody() {
        server.expect(anything()).andRespond(withSuccess(
                "<!DOCTYPE html><html><head><title>private-provider-page</title></head><body>private-provider-data</body></html>",
                MediaType.TEXT_HTML));
        assertThatThrownBy(() -> service.search("query", 3)).isInstanceOfSatisfying(WebSearchException.class,
                e -> assertThat(e.kind()).isEqualTo(WebSearchException.Kind.PROTOCOL))
                .hasMessageContaining("RSS").hasMessageNotContaining("无法解析")
                .hasMessageNotContaining("private-provider-page").hasMessageNotContaining("private-provider-data");
        server.verify();
    }
    @Test void htmlWithoutContentTypeStillReportsWrongFormatInsteadOfXmlParseFailure() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.OK).body(
                " \n<!doctype html><html><body>private-validation-page</body></html>"));
        assertThatThrownBy(() -> service.search("query", 3)).isInstanceOfSatisfying(WebSearchException.class,
                e -> assertThat(e.kind()).isEqualTo(WebSearchException.Kind.PROTOCOL))
                .hasMessageContaining("RSS").hasMessageNotContaining("无法解析")
                .hasMessageNotContaining("private-validation-page");
        server.verify();
    }
    @Test void validFeedReturnsEvidenceAndDisabledSearchDoesNotCallProvider() {
        server.expect(anything()).andRespond(withSuccess("<rss><channel><item><title>T</title><link>https://example.test</link><description>D</description></item></channel></rss>", MediaType.APPLICATION_XML));
        assertThat(service.search("query", 3)).hasSize(1);
        assertThat(new WebSearchService(builder.build(), false).search("query", 3)).isEmpty(); server.verify();
    }

    @Test void freshNewsRequiresPublicationDatesAndPreservesLiveSourceTimes() {
        Instant now = Instant.now();
        LocalDate day = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        String published = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.atZone(ZoneOffset.UTC));
        String stale = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(864000).atZone(ZoneOffset.UTC));
        String xml = "<rss><channel>"
                + item("Current", "https://publisher.test/current", published)
                + item("Duplicate", "https://publisher.test/current", published)
                + item("Old", "https://publisher.test/old", stale)
                + item("Undated", "https://publisher.test/unknown", "")
                + item("Not a web source", "file:///missing", published)
                + "</channel></rss>";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.bing.com/news/search?")))
                .andExpect(queryParam("format", "rss")).andExpect(decodedQueryParam("q", "current news"))
                .andExpect(decodedQueryParam("qft", "sortbydate=\"1\""))
                .andRespond(withSuccess(xml, MediaType.APPLICATION_XML));
        var hits = service.searchFresh("current news", 6, day, day);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).payload().path("published_at").asText()).isNotBlank();
        assertThat(hits.get(0).payload().path("fetched_at").asText()).isNotBlank();
        assertThat(hits.get(0).payload().path("evidence_kind").asText()).isEqualTo("live-news-search-summary");
        server.verify();
    }

    @Test void freshNewsSearchUsesTopicInsteadOfAudioProductionInstructionsOrDateOperators() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.bing.com/news/search?")))
                .andExpect(queryParam("format", "rss")).andExpect(decodedQueryParam("q", "时政"))
                .andExpect(decodedQueryParam("qft", "sortbydate=\"1\""))
                .andRespond(withSuccess("<rss><channel/></rss>", MediaType.APPLICATION_XML));
        assertThat(service.searchFresh("帮我整理最近的时政，做成 10 分钟简报 after:2026-10-12 before:2026-10-21",
                6, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 20))).isEmpty();
        server.verify();
    }

    @Test void freshNewsQueryCleanupPreservesNamedTopic() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.bing.com/news/search?")))
                .andExpect(queryParam("format", "rss")).andExpect(decodedQueryParam("q", "欧盟人工智能政策"))
                .andExpect(decodedQueryParam("qft", "sortbydate=\"1\""))
                .andRespond(withSuccess("<rss><channel/></rss>", MediaType.APPLICATION_XML));
        assertThat(service.searchFresh("请汇总最近的欧盟人工智能政策，做成 5 分钟音频 after:2026-10-12 before:2026-10-21",
                6, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 20))).isEmpty();
        server.verify();
    }

    @Test void numericDateScopeAndLanguageProductionTailAreRemovedBeforeTheActualNewsRequest() {
        Instant now = Instant.now();
        LocalDate day = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        String published = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.atZone(ZoneOffset.UTC));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.bing.com/news/search?")))
                .andExpect(queryParam("format", "rss"))
                .andExpect(decodedQueryParam("q", "国际 时政 新闻"))
                .andExpect(decodedQueryParam("qft", "sortbydate=\"1\""))
                .andRespond(withSuccess("<rss><channel>"
                        + item("Current international event", "https://publisher.test/international", published)
                        + item("Undated international event", "https://publisher.test/undated", "")
                        + "</channel></rss>", MediaType.APPLICATION_XML));
        String instruction = "整理最近7天的国际时政新闻，用中文做成1分钟简报，列出2条有日期来源支持的事件，"
                + "区分事实和评论，并说明来源检索的限制。 after:2026-10-14 before:2026-10-22";
        var hits = service.searchFresh(instruction, 6, day.minusDays(6), day);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).source()).isEqualTo("https://publisher.test/international");
        assertThat(hits.get(0).payload().path("published_at").asText()).isNotBlank();
        server.verify();
    }

    @Test void newsTopicBoundariesPreservePlacesNamedSubjectsAndExistingSpaces() {
        String[][] cases = {
                {"请汇总过去 7 天的中东时政新闻，用通俗中文制作1分钟播客", "中东 时政 新闻"},
                {"麻烦帮我整理最近七天的欧盟时事新闻，使用英语生成2分钟音频", "欧盟 时事 新闻"},
                {"整理最近7天的国际 时政 新闻，用中文做成1分钟简报", "国际 时政 新闻"},
                {"请整理最近3天乌克兰外交政策，做成5分钟简报", "乌克兰外交政策"},
                {"请汇总最近7天的G7国际新闻，用中文做成1分钟简报", "G7国际 新闻"}
        };
        for (String[] entry : cases) {
            server.reset();
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.bing.com/news/search?")))
                    .andExpect(queryParam("format", "rss"))
                    .andExpect(decodedQueryParam("q", entry[1]))
                    .andExpect(decodedQueryParam("qft", "sortbydate=\"1\""))
                    .andRespond(withSuccess("<rss><channel/></rss>", MediaType.APPLICATION_XML));
            assertThat(service.searchFresh(entry[0] + " after:2026-10-14 before:2026-10-22",
                    6, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 21))).as(entry[0]).isEmpty();
            server.verify();
        }
    }

    @Test void generalWebSearchPreservesOriginalQuery() {
        String query = "请汇总最近的欧盟人工智能政策，做成 5 分钟音频 after:2026-10-12 before:2026-10-21";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://cn.bing.com/search?")))
                .andExpect(queryParam("format", "rss")).andExpect(decodedQueryParam("q", query))
                .andRespond(withSuccess("<rss><channel/></rss>", MediaType.APPLICATION_XML));
        assertThat(service.search(query, 6)).isEmpty();
        server.verify();
    }

    @Test void freshSearchUnwrapsPublisherLinksAndDoesNotSubstituteFetchDateForMissingPublicationDate() {
        Instant now = Instant.now();
        LocalDate day = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        String published = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.atZone(ZoneOffset.UTC));
        server.expect(anything()).andExpect(decodedQueryParam("qft", "sortbydate=\"1\""))
                .andRespond(withSuccess("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<rss version=\"2.0\" xmlns:News=\"https://www.bing.com/news\"><channel>"
                + "<item><title>Wrapped</title>"
                + "<link>https://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;url=https%3A%2F%2Fpublisher.test%2Fstory</link>"
                + "<description><![CDATA[Reported & verified summary]]></description>"
                + "<pubDate>" + published + "</pubDate><News:Source>Publisher</News:Source></item>"
                + item("No date", "https://publisher.test/no-date", "") + "</channel></rss>", MediaType.APPLICATION_XML));
        var hits = service.searchFresh("news", 6, day, day);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).source()).isEqualTo("https://publisher.test/story");
        assertThat(hits.get(0).content()).isEqualTo("Reported & verified summary");
        assertThat(hits.get(0).payload().path("source_host").asText()).isEqualTo("publisher.test");
        assertThat(hits.get(0).payload().path("published_at").asText()).isEqualTo(
                ZonedDateTime.parse(published, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString());
        server.verify();
    }

    private RequestMatcher decodedQueryParam(String name, String expected) {
        return request -> {
            String rawQuery = request.getURI().getRawQuery();
            var values = Arrays.stream((rawQuery == null ? "" : rawQuery).split("&"))
                    .map(parameter -> parameter.split("=", 2))
                    .filter(parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name))
                    .map(parts -> URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8))
                    .toList();
            assertThat(values).as("decoded query parameter %s", name).containsExactly(expected);
        };
    }

    private String item(String title, String link, String date) {
        return "<item><title>" + title + "</title><link>" + link + "</link><description>Reported summary</description>"
                + (date.isBlank() ? "" : "<pubDate>" + date + "</pubDate>") + "</item>";
    }
}

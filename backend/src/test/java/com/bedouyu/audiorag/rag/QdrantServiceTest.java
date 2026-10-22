package com.bedouyu.audiorag.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.SocketTimeoutException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class QdrantServiceTest {
    private final OnnxEmbeddingService embeddings = mock(OnnxEmbeddingService.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final QdrantService qdrant = new QdrantService(builder, embeddings, "127.0.0.1", 6333, "knowledge");
    private static final String COLLECTION = "http://127.0.0.1:6333/collections/knowledge";
    private static final String CONFIG = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3}}}}}";

    private void embed() { when(embeddings.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f}); }
    private void collection() {
        server.expect(requestTo(COLLECTION)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(CONFIG, MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 400, 422, 408, 500, 502, 503})
    void classifiesHttpFailuresWithoutExposingResponseBody(int status) {
        embed(); collection();
        server.expect(requestTo(COLLECTION + "/points/search")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("private server detail"));
        var expected = status >= 500 || status == 408 ? VectorRetrievalException.Kind.TRANSIENT
                : status == 401 || status == 403 ? VectorRetrievalException.Kind.AUTHENTICATION
                : status == 404 ? VectorRetrievalException.Kind.COLLECTION_MISSING : VectorRetrievalException.Kind.SCHEMA;
        assertThatThrownBy(() -> qdrant.search("query", 4, 7))
                .isInstanceOfSatisfying(VectorRetrievalException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(expected);
                    assertThat(failure.isTransient()).isEqualTo(expected == VectorRetrievalException.Kind.TRANSIENT);
                    assertThat(failure.getMessage()).doesNotContain("private server detail");
                });
        server.verify();
    }
    @Test void classifiesTransportTimeoutAsTransient() {
        embed();
        server.expect(requestTo(COLLECTION)).andRespond(withException(new SocketTimeoutException("timeout")));
        assertThatThrownBy(() -> qdrant.search("query", 4, 7))
                .isInstanceOfSatisfying(VectorRetrievalException.class, failure -> assertThat(failure.isTransient()).isTrue());
        server.verify();
    }
    @Test void localEmbeddingFailureDoesNotCallTheVectorService() {
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("broken model"));
        assertThatThrownBy(() -> qdrant.search("query", 4, 7))
                .isInstanceOfSatisfying(VectorRetrievalException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(VectorRetrievalException.Kind.EMBEDDING));
        server.verify();
    }
    @Test void dimensionMismatchIsExplicitAndDoesNotSearchOrRebuildCollection() {
        when(embeddings.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f}); collection();
        assertThatThrownBy(() -> qdrant.search("query", 4, 7))
                .isInstanceOfSatisfying(VectorRetrievalException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(VectorRetrievalException.Kind.DIMENSION_MISMATCH))
                .hasMessageContaining("显式迁移");
        server.verify();
    }
    @Test void malformedCollectionSchemaIsNotAnOfflineCondition() {
        embed();
        server.expect(requestTo(COLLECTION)).andRespond(withSuccess("{\"result\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> qdrant.search("query", 4, 7))
                .isInstanceOfSatisfying(VectorRetrievalException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(VectorRetrievalException.Kind.SCHEMA));
        server.verify();
    }
    @Test void malformedSearchResponseCannotSilentlyBecomeZeroHits() {
        embed(); collection();
        server.expect(requestTo(COLLECTION + "/points/search"))
                .andRespond(withSuccess("{\"result\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> qdrant.search("query", 4, 7))
                .isInstanceOfSatisfying(VectorRetrievalException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(VectorRetrievalException.Kind.PROTOCOL));
        server.verify();
    }
    @Test void ingestionCannotWriteAVectorWithWrongDimension() {
        when(embeddings.embed(anyString())).thenReturn(new float[]{0.1f}); collection();
        assertThatThrownBy(() -> qdrant.addDocuments(7, "title", "source", List.of("text")))
                .isInstanceOfSatisfying(VectorRetrievalException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(VectorRetrievalException.Kind.DIMENSION_MISMATCH));
        server.verify();
    }
}

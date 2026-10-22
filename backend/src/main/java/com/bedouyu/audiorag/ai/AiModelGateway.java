package com.bedouyu.audiorag.ai;
import com.bedouyu.audiorag.model.dto.ModelMessage;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import com.bedouyu.audiorag.service.content.AudioGenerationRequest;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Agent 面向接口；更换模型供应商不会影响音频、检索或数据库层。 */
public interface AiModelGateway {
    Optional<String> answer(String question, List<SearchHit> evidence, List<ModelMessage> history, Consumer<String> token);
    Optional<String> lesson(String topic, String audience, int minutes, List<SearchHit> evidence);
    Optional<String> course(String topic, String audience, int minutes, List<SearchHit> evidence);
    /** Legacy adapters may return empty; creative requests must never fall back to a Chinese lesson. */
    default Optional<String> audio(AudioGenerationRequest request, List<SearchHit> evidence) { return Optional.empty(); }
    boolean configured();
}

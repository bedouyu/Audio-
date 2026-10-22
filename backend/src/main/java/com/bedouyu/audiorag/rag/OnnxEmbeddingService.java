package com.bedouyu.audiorag.rag;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OnnxEmbeddingService implements AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final BertTokenizer tokenizer;
    private final String modelName;

    public OnnxEmbeddingService(@Value("${audiorag.embedding-model-dir}") String modelDirectory,
            ObjectMapper objectMapper) throws IOException, OrtException {
        Path directory = Path.of(modelDirectory);
        modelName = directory.getFileName().toString();
        tokenizer = new BertTokenizer(directory.resolve("tokenizer.json"), objectMapper);
        environment = OrtEnvironment.getEnvironment();
        try (var options = new OrtSession.SessionOptions()) {
            session = environment.createSession(directory.resolve("model_optimized.onnx").toString(), options);
        }
    }

    public float[] embed(String text) {
        long[] inputIds = tokenizer.encode(text);
        long[][] ids = new long[][]{inputIds};
        long[][] mask = new long[][]{filled(inputIds.length, 1L)};
        long[][] tokenTypes = new long[][]{filled(inputIds.length, 0L)};
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, ids);
                OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
                OnnxTensor typesTensor = OnnxTensor.createTensor(environment, tokenTypes);
                OrtSession.Result result = session.run(Map.of(
                        "input_ids", idsTensor,
                        "attention_mask", maskTensor,
                        "token_type_ids", typesTensor))) {
            float[][][] hiddenState = (float[][][]) result.get(0).getValue();
            return normalize(hiddenState[0][0]);
        } catch (OrtException exception) {
            throw new IllegalStateException("BGE 向量生成失败", exception);
        }
    }

    public boolean ready() {
        return session != null;
    }

    /** 用当前本地模型的分词器计数，不把截断后的长度误当成原文长度。 */
    public int contentTokenCount(String text) {
        return tokenizer.contentTokenCount(text);
    }
    public String modelName() { return modelName; }

    @PreDestroy
    public void close() throws OrtException {
        session.close();
    }

    private static long[] filled(int size, long value) {
        long[] values = new long[size];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static float[] normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        float[] normalized = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = norm == 0 ? vector[index] : (float) (vector[index] / norm);
        }
        return normalized;
    }
}

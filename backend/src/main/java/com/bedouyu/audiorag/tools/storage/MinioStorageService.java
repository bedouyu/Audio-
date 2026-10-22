package com.bedouyu.audiorag.tools.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MinioStorageService {
    private final MinioClient client;
    private final String bucket;

    public MinioStorageService(@Value("${audiorag.minio.endpoint}") String endpoint,
            @Value("${audiorag.minio.access-key}") String accessKey,
            @Value("${audiorag.minio.secret-key}") String secretKey,
            @Value("${audiorag.minio.bucket}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    void ensureBucket() throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    public StoredObject put(long projectId, String filename, String contentType, byte[] data) {
        String safeName = sanitize(filename);
        String objectName = "projects/" + projectId + "/" + UUID.randomUUID() + "-" + safeName;
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectName)
                    .stream(new ByteArrayInputStream(data), (long) data.length, -1L)
                    .contentType(contentType == null ? "application/octet-stream" : contentType).build());
            return new StoredObject(bucket, objectName, safeName, data.length);
        } catch (Exception exception) {
            throw new IllegalStateException("原文件保存到 MinIO 失败", exception);
        }
    }

    public GetObjectResponse get(String objectName) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build());
        } catch (Exception exception) {
            throw new IllegalStateException("从 MinIO 读取原文件失败", exception);
        }
    }

    public void remove(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return;
        }
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
        } catch (Exception exception) {
            throw new IllegalStateException("删除 MinIO 原文件失败", exception);
        }
    }

    public boolean ready() {
        try {
            return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception exception) {
            return false;
        }
    }

    private String sanitize(String filename) {
        String value = filename == null || filename.isBlank() ? "document.bin" : filename;
        return value.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
    }

    public record StoredObject(String bucket, String objectName, String filename, long size) {
    }
}

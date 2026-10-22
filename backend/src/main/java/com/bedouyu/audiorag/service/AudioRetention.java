package com.bedouyu.audiorag.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 换音色时旧音频不立即删除：先挪进 retired/ 并在文件名上写下删除时间，
 * 到点由定时清扫移走。这样能听回旧版本，又不会无限堆积。
 * 过期时间写在文件名里，服务重启后依然有效，不依赖额外数据表。
 */
@Component
public class AudioRetention {
    private static final Logger log = LoggerFactory.getLogger(AudioRetention.class);
    private static final String RETIRED = "retired";

    private final Path root;
    private final Path retired;
    private final Duration retention;

    public AudioRetention(@Value("${audiorag.generated-audio-dir:../data/generated-audio}") String directory,
                          @Value("${audiorag.audio.retention-hours:8}") long retentionHours) {
        if (retentionHours < 1 || retentionHours > 24 * 30)
            throw new IllegalArgumentException("音频保留时长必须在 1 到 720 小时之间");
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.retired = root.resolve(RETIRED);
        this.retention = Duration.ofHours(retentionHours);
    }

    /** 把已被替换的音频挪进待清理目录；文件不存在时静默跳过（例如上次已经清理过）。 */
    public void retire(String audioId) {
        if (audioId == null || !audioId.matches("[0-9a-fA-F-]{36}")) return;
        Path source = root.resolve(audioId + ".wav");
        if (!Files.isRegularFile(source)) return;
        long deadline = Instant.now().plus(retention).getEpochSecond();
        Path target = retired.resolve(deadline + "_" + audioId + ".wav");
        try {
            Files.createDirectories(retired);
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.debug("音频 {} 保留至 {}", audioId, Instant.ofEpochSecond(deadline));
        } catch (IOException failure) {
            log.warn("旧音频 {} 未能转为待清理，保持原样：{}", audioId, failure.toString());
        }
    }

    /** 删除所有到期的旧音频；返回删除数量。 */
    @Scheduled(fixedDelayString = "${audiorag.audio.sweep-ms:900000}", initialDelayString = "${audiorag.audio.sweep-ms:900000}")
    public int sweep() {
        if (!Files.isDirectory(retired)) return 0;
        long now = Instant.now().getEpochSecond();
        int removed = 0;
        try (Stream<Path> files = Files.list(retired)) {
            List<Path> candidates = files.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                String name = file.getFileName().toString();
                int split = name.indexOf('_');
                if (split <= 0) continue;
                long deadline;
                try {
                    deadline = Long.parseLong(name.substring(0, split));
                } catch (NumberFormatException invalid) {
                    continue;
                }
                if (deadline > now) continue;
                try {
                    Files.deleteIfExists(file);
                    removed++;
                } catch (IOException failure) {
                    log.warn("清理旧音频 {} 失败：{}", name, failure.toString());
                }
            }
        } catch (IOException failure) {
            log.warn("扫描待清理音频失败：{}", failure.toString());
        }
        if (removed > 0) log.info("已清理 {} 个到期音频", removed);
        return removed;
    }

    /** 启动时先扫一遍，避免停机期间到期的文件一直留着。 */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep();
    }
}

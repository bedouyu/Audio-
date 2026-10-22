package com.bedouyu.audiorag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 换音色后旧音频的延迟清理：过期时间写在文件名里，不依赖数据库。 */
class AudioRetentionTest {
    @TempDir Path directory;

    @Test void retiredAudioIsMovedWithItsDeadlineAndKeptUntilThen() throws IOException {
        String audioId = UUID.randomUUID().toString();
        Path wav = directory.resolve(audioId + ".wav");
        Files.write(wav, new byte[]{1, 2, 3});
        var retention = new AudioRetention(directory.toString(), 8);

        retention.retire(audioId);

        assertThat(wav).doesNotExist();
        List<Path> retired;
        try (var files = Files.list(directory.resolve("retired"))) {
            retired = files.toList();
        }
        assertThat(retired).hasSize(1);
        long deadline = Long.parseLong(retired.get(0).getFileName().toString().split("_")[0]);
        assertThat(deadline).isBetween(Instant.now().plusSeconds(7 * 3600).getEpochSecond(),
                Instant.now().plusSeconds(9 * 3600).getEpochSecond());
        // 还没到点，清扫不能删。
        assertThat(retention.sweep()).isZero();
        assertThat(retired.get(0)).exists();
    }

    @Test void sweepRemovesOnlyExpiredFiles() throws IOException {
        Path retired = Files.createDirectories(directory.resolve("retired"));
        long now = Instant.now().getEpochSecond();
        Path expired = retired.resolve((now - 60) + "_" + UUID.randomUUID() + ".wav");
        Path fresh = retired.resolve((now + 3600) + "_" + UUID.randomUUID() + ".wav");
        Files.write(expired, new byte[]{1});
        Files.write(fresh, new byte[]{1});
        var retention = new AudioRetention(directory.toString(), 8);

        assertThat(retention.sweep()).isEqualTo(1);
        assertThat(expired).doesNotExist();
        assertThat(fresh).exists();
    }

    @Test void retireIgnoresMalformedOrMissingFiles() {
        var retention = new AudioRetention(directory.toString(), 8);

        retention.retire("not-a-uuid");
        retention.retire(UUID.randomUUID().toString());

        assertThat(directory.resolve("retired")).doesNotExist();
    }

    @Test void retentionWindowMustBeSane() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioRetention(directory.toString(), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new AudioRetention(directory.toString(), 721));
    }
}

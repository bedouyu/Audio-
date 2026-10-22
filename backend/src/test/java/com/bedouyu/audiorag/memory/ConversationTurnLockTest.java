package com.bedouyu.audiorag.memory;

import org.junit.jupiter.api.Test;
import com.bedouyu.audiorag.common.ApiException;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class ConversationTurnLockTest {
    @Test void sameSessionCannotInterleaveButOtherSessionsCanProgress() {
        var lock = new ConversationTurnLock();
        lock.enter("a"); lock.enter("b");
        assertThatThrownBy(() -> lock.enter("a")).isInstanceOfSatisfying(ApiException.class,
                conflict -> assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT)).hasMessageContaining("已有任务");
        lock.leave("a"); assertThatCode(() -> lock.enter("a")).doesNotThrowAnyException();
        lock.leave("a"); lock.leave("b");
    }
}

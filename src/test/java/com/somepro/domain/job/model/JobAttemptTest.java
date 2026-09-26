package com.somepro.domain.job.model;

import com.somepro.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JobAttempt 领域规则单测（纯领域，不依赖 Spring / DB）。
 */
class JobAttemptTest {

    @Test
    void startShouldInitRunningAttempt() {
        JobAttempt attempt = JobAttempt.start(9L, 1, " WK-001 ");

        assertEquals(9L, attempt.getJobId());
        assertEquals(1, attempt.getAttemptNo());
        assertEquals("WK-001", attempt.getWorkerCode());
        assertEquals(AttemptStatus.RUNNING, attempt.getStatus());
        assertNotNull(attempt.getStartedAt());
        assertNull(attempt.getFinishedAt());
    }

    @Test
    void startShouldValidateRequiredFields() {
        assertThrows(BizException.class, () -> JobAttempt.start(null, 1, "WK-001"));
        assertThrows(BizException.class, () -> JobAttempt.start(9L, null, "WK-001"));
        assertThrows(BizException.class, () -> JobAttempt.start(9L, 0, "WK-001"));
        assertThrows(BizException.class, () -> JobAttempt.start(9L, 1, null));
        assertThrows(BizException.class, () -> JobAttempt.start(9L, 1, " "));
    }

    @Test
    void finishingShouldBuildTerminalSnapshot() {
        JobAttempt attempt = JobAttempt.finishing(9L, 2, AttemptStatus.FAILED, "解码失败");

        assertEquals(9L, attempt.getJobId());
        assertEquals(2, attempt.getAttemptNo());
        assertEquals(AttemptStatus.FAILED, attempt.getStatus());
        assertEquals("解码失败", attempt.getErrorMsg());
        assertNotNull(attempt.getFinishedAt());
    }

    @Test
    void finishingShouldRejectRunningOrNull() {
        // 收尾只能是 SUCCESS/FAILED，RUNNING 不是终态
        assertThrows(BizException.class, () -> JobAttempt.finishing(9L, 1, AttemptStatus.RUNNING, null));
        assertThrows(BizException.class, () -> JobAttempt.finishing(9L, 1, null, null));
    }

    @Test
    void ofShouldParseCaseInsensitively() {
        assertEquals(AttemptStatus.SUCCESS, AttemptStatus.of(" success "));
        assertThrows(BizException.class, () -> AttemptStatus.of("DONE"));
        assertThrows(BizException.class, () -> AttemptStatus.of(" "));
    }
}

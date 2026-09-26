package com.somepro.domain.job.model;

import com.somepro.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JobAttempt 领域规则单测（纯领域，不依赖 Spring / DB）。
 */
class JobAttemptTest {

    @Test
    void startShouldInitRunningAttempt() {
        LocalDateTime now = LocalDateTime.now();

        JobAttempt attempt = JobAttempt.start(9L, 1, " WK-001 ", now);

        assertEquals(9L, attempt.getJobId());
        assertEquals(1, attempt.getAttemptNo());
        assertEquals("WK-001", attempt.getWorkerCode());
        assertEquals(AttemptStatus.RUNNING, attempt.getStatus());
        assertEquals(now, attempt.getStartedAt());
    }

    @Test
    void startShouldDefaultStartedAtToNow() {
        JobAttempt attempt = JobAttempt.start(9L, 1, "WK-001", null);

        assertNotNull(attempt.getStartedAt());
    }

    @Test
    void startShouldValidateRequiredFields() {
        // 必须归属任务、第几次跑从 1 起、必须记下哪台节点领的
        assertThrows(BizException.class, () -> JobAttempt.start(null, 1, "WK-001", null));
        assertThrows(BizException.class, () -> JobAttempt.start(9L, null, "WK-001", null));
        assertThrows(BizException.class, () -> JobAttempt.start(9L, 0, "WK-001", null));
        assertThrows(BizException.class, () -> JobAttempt.start(9L, 1, null, null));
        assertThrows(BizException.class, () -> JobAttempt.start(9L, 1, "  ", null));
    }
}

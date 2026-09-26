package com.somepro.domain.job.model;

import com.somepro.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TranscodeJob 领域规则单测（纯领域，不依赖 Spring / DB）。
 */
class TranscodeJobTest {

    @Test
    void submitShouldInitPendingJob() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, " 技术部 ", null);

        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(TranscodeJob.DEFAULT_PRIORITY, job.getPriority());
        assertEquals(0, job.getAttemptCount());
        assertEquals(TranscodeJob.DEFAULT_MAX_ATTEMPTS, job.getMaxAttempts());
        assertEquals(0, job.getProgress());
        assertEquals("技术部", job.getOwnerDept());
        assertNotNull(job.getSubmittedAt());
    }

    @Test
    void submitShouldValidateRequiredFields() {
        assertThrows(BizException.class, () -> TranscodeJob.submit(null, 2L, "技术部", 1));
        assertThrows(BizException.class, () -> TranscodeJob.submit(1L, null, "技术部", 1));
        assertThrows(BizException.class, () -> TranscodeJob.submit(1L, 2L, " ", 1));
        assertThrows(BizException.class, () -> TranscodeJob.submit(1L, 2L, "技术部", 0));
        assertThrows(BizException.class, () -> TranscodeJob.submit(1L, 2L, "技术部", 100));
    }

    @Test
    void cancelShouldRequireReason() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);

        assertThrows(BizException.class, () -> job.cancel(null));
        assertThrows(BizException.class, () -> job.cancel("  "));
    }

    @Test
    void cancelShouldOnlyAllowPending() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);
        job.setStatus(JobStatus.RUNNING);

        BizException e = assertThrows(BizException.class, () -> job.cancel("提错了"));
        assertEquals("只有待处理（PENDING）的任务才能撤销，当前状态：RUNNING", e.getMessage());
    }

    @Test
    void cancelShouldMarkCancelledWithReason() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);

        job.cancel(" 提错档位了 ");

        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertEquals("提错档位了", job.getErrorMsg());
        assertNotNull(job.getFinishedAt());
    }

    // ============ 领取：PENDING → RUNNING，并产生执行记录 ============

    @Test
    void claimShouldMarkRunningAndStartAttempt() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);
        job.setId(9L);

        JobAttempt attempt = job.claim(" WK-001 ");

        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertEquals(1, job.getAttemptCount());
        assertNotNull(job.getStartedAt());
        // 执行记录：第几次跑、哪台节点、几点开始
        assertEquals(9L, attempt.getJobId());
        assertEquals(1, attempt.getAttemptNo());
        assertEquals("WK-001", attempt.getWorkerCode());
        assertEquals(AttemptStatus.RUNNING, attempt.getStatus());
        assertNotNull(attempt.getStartedAt());
    }

    @Test
    void claimShouldRejectBlankWorkerCode() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);
        job.setId(9L);

        assertThrows(BizException.class, () -> job.claim(null));
        assertThrows(BizException.class, () -> job.claim("  "));
        // 校验失败不能改变任务状态
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(0, job.getAttemptCount());
    }

    @Test
    void claimShouldOnlyAllowPending() {
        // 已被领走的、已出结果的、已取消的，再来领都要挡回去
        for (JobStatus status : new JobStatus[]{JobStatus.RUNNING, JobStatus.SUCCESS,
                JobStatus.FAILED, JobStatus.CANCELLED}) {
            TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);
            job.setStatus(status);

            BizException e = assertThrows(BizException.class, () -> job.claim("WK-001"));
            assertEquals("任务不在待处理状态，不能领取，当前状态：" + status, e.getMessage());
        }
    }

    // ============ 进度：0-100 整数、只能往前、只有 RUNNING 能收 ============

    private TranscodeJob runningJob() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);
        job.setId(9L);
        job.claim("WK-001");
        return job;
    }

    @Test
    void reportProgressShouldAdvanceMonotonically() {
        TranscodeJob job = runningJob();

        job.reportProgress(30);
        assertEquals(30, job.getProgress());
        job.reportProgress(30);   // 重复上报同值：幂等放行
        assertEquals(30, job.getProgress());
        job.reportProgress(100);
        assertEquals(100, job.getProgress());
    }

    @Test
    void reportProgressShouldRejectOutOfRange() {
        TranscodeJob job = runningJob();

        assertThrows(BizException.class, () -> job.reportProgress(null));
        assertThrows(BizException.class, () -> job.reportProgress(-1));
        assertThrows(BizException.class, () -> job.reportProgress(101));
        assertEquals(0, job.getProgress());
    }

    @Test
    void reportProgressShouldRejectRegression() {
        TranscodeJob job = runningJob();
        job.reportProgress(60);

        BizException e = assertThrows(BizException.class, () -> job.reportProgress(59));
        assertEquals("进度不能倒退：当前进度 60，上报 59", e.getMessage());
        // 被挡回的上报不能改动已记进度
        assertEquals(60, job.getProgress());
    }

    @Test
    void reportProgressShouldRejectWhenNotRunning() {
        // 没被领走的任务不该收到进度
        TranscodeJob pending = TranscodeJob.submit(1L, 2L, "技术部", 1);
        assertThrows(BizException.class, () -> pending.reportProgress(10));

        // 已出结果的任务也不该再收到进度
        TranscodeJob finished = runningJob();
        finished.completeSuccess("/out/a.mp4");
        assertThrows(BizException.class, () -> finished.reportProgress(10));
    }

    // ============ 结果：SUCCESS/FAILED，终态后一切上报都被挡 ============

    @Test
    void completeSuccessShouldFinishJobAndAttempt() {
        TranscodeJob job = runningJob();
        job.reportProgress(80);

        JobAttempt attempt = job.completeSuccess(" /out/a.mp4 ");

        assertEquals(JobStatus.SUCCESS, job.getStatus());
        assertEquals(100, job.getProgress());
        assertEquals("/out/a.mp4", job.getOutputPath());
        assertNotNull(job.getFinishedAt());
        // 执行记录同步收尾
        assertEquals(AttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals(1, attempt.getAttemptNo());
        assertNotNull(attempt.getFinishedAt());
    }

    @Test
    void completeFailureShouldRecordReason() {
        TranscodeJob job = runningJob();

        JobAttempt attempt = job.completeFailure(" 解码失败 ");

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals("解码失败", job.getErrorMsg());
        assertNotNull(job.getFinishedAt());
        assertEquals(AttemptStatus.FAILED, attempt.getStatus());
        assertEquals("解码失败", attempt.getErrorMsg());
        assertNotNull(attempt.getFinishedAt());
    }

    @Test
    void completeFailureShouldRequireReason() {
        TranscodeJob job = runningJob();

        assertThrows(BizException.class, () -> job.completeFailure(null));
        assertThrows(BizException.class, () -> job.completeFailure("  "));
        assertEquals(JobStatus.RUNNING, job.getStatus());
    }

    @Test
    void completeShouldOnlyAllowRunning() {
        // 没被领走的任务不能出结果
        TranscodeJob pending = TranscodeJob.submit(1L, 2L, "技术部", 1);
        assertThrows(BizException.class, () -> pending.completeSuccess(null));
        assertThrows(BizException.class, () -> pending.completeFailure("x"));
    }

    @Test
    void terminalJobShouldRejectAnyFurtherReport() {
        // 任务一旦出了结果，后面再怎么报都不该把它重新改一遍
        TranscodeJob job = runningJob();
        job.completeSuccess("/out/a.mp4");

        assertThrows(BizException.class, () -> job.reportProgress(50));
        assertThrows(BizException.class, () -> job.completeSuccess("/out/b.mp4"));
        assertThrows(BizException.class, () -> job.completeFailure("又失败了"));
        assertThrows(BizException.class, () -> job.claim("WK-002"));

        // 状态保持第一次的结果，不被后续上报覆盖
        assertEquals(JobStatus.SUCCESS, job.getStatus());
        assertEquals(100, job.getProgress());
        assertEquals("/out/a.mp4", job.getOutputPath());
    }
}

package com.somepro.application.job;

import com.somepro.common.exception.BizException;
import com.somepro.domain.job.model.JobStatus;
import com.somepro.domain.job.model.TranscodeJob;
import com.somepro.domain.job.repository.JobAttemptRepository;
import com.somepro.domain.job.repository.TranscodeJobRepository;
import com.somepro.domain.media.model.AssetStatus;
import com.somepro.domain.media.model.MediaAsset;
import com.somepro.domain.media.repository.MediaAssetRepository;
import com.somepro.domain.profile.model.ProfileStatus;
import com.somepro.domain.profile.model.TranscodeProfile;
import com.somepro.domain.profile.repository.TranscodeProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TranscodeJobAppService 编排规则单测：仓储全部打桩，不依赖 Spring / DB。
 */
class TranscodeJobAppServiceTest {

    private TranscodeJobRepository jobRepository;
    private MediaAssetRepository assetRepository;
    private TranscodeProfileRepository profileRepository;
    private JobAttemptRepository attemptRepository;
    private TranscodeJobAppService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(TranscodeJobRepository.class);
        assetRepository = mock(MediaAssetRepository.class);
        profileRepository = mock(TranscodeProfileRepository.class);
        attemptRepository = mock(JobAttemptRepository.class);
        service = new TranscodeJobAppService(jobRepository, assetRepository, profileRepository,
                attemptRepository);
    }

    private MediaAsset readyAsset() {
        MediaAsset asset = MediaAsset.register("MA-2026-0001", "a.mp4", "VIDEO",
                "mp4", 1024L, 60000L, null, "技术部");
        asset.setId(1L);
        return asset;
    }

    private TranscodeProfile enabledProfile() {
        TranscodeProfile profile = TranscodeProfile.register("PF-001", "高清", "VIDEO",
                "mp4", 1920, 1080, 4000, 128);
        profile.setId(2L);
        return profile;
    }

    private void stubAssetAndProfile(MediaAsset asset, TranscodeProfile profile) {
        when(assetRepository.findById(1L)).thenReturn(Mono.just(asset));
        when(profileRepository.findById(2L)).thenReturn(Mono.just(profile));
    }

    @Test
    void submitShouldFailWhenAssetMissing() {
        when(assetRepository.findById(1L)).thenReturn(Mono.empty());

        assertThrows(BizException.class, () -> service.submit(1L, 2L, "技术部", 1).block());
        verify(jobRepository, never()).submitNew(any());
    }

    @Test
    void submitShouldFailWhenAssetDoneOrDisabled() {
        MediaAsset done = readyAsset();
        done.setStatus(AssetStatus.DONE);
        when(assetRepository.findById(1L)).thenReturn(Mono.just(done));
        assertThrows(BizException.class, () -> service.submit(1L, 2L, "技术部", 1).block());

        MediaAsset disabled = readyAsset();
        disabled.setStatus(AssetStatus.DISABLED);
        when(assetRepository.findById(1L)).thenReturn(Mono.just(disabled));
        assertThrows(BizException.class, () -> service.submit(1L, 2L, "技术部", 1).block());

        verify(jobRepository, never()).submitNew(any());
    }

    @Test
    void submitShouldFailWhenDeptMismatch() {
        stubAssetAndProfile(readyAsset(), enabledProfile());

        assertThrows(BizException.class, () -> service.submit(1L, 2L, "别的部门", 1).block());
        verify(jobRepository, never()).submitNew(any());
    }

    @Test
    void submitShouldFailWhenProfileMissingOrDisabled() {
        when(assetRepository.findById(1L)).thenReturn(Mono.just(readyAsset()));
        when(profileRepository.findById(2L)).thenReturn(Mono.empty());
        assertThrows(BizException.class, () -> service.submit(1L, 2L, "技术部", 1).block());

        TranscodeProfile disabled = enabledProfile();
        disabled.setStatus(ProfileStatus.DISABLED);
        stubAssetAndProfile(readyAsset(), disabled);
        assertThrows(BizException.class, () -> service.submit(1L, 2L, "技术部", 1).block());

        verify(jobRepository, never()).submitNew(any());
    }

    @Test
    void submitShouldFailWhenMediaTypeMismatch() {
        MediaAsset audio = MediaAsset.register("MA-2026-0002", "a.mp3", "AUDIO",
                "mp3", 512L, 30000L, null, "技术部");
        audio.setId(1L);
        stubAssetAndProfile(audio, enabledProfile());

        assertThrows(BizException.class, () -> service.submit(1L, 2L, "技术部", 1).block());
        verify(jobRepository, never()).submitNew(any());
    }

    @Test
    void submitShouldFailWhenActiveJobExists() {
        stubAssetAndProfile(readyAsset(), enabledProfile());
        when(jobRepository.existsActiveByAssetAndProfile(1L, 2L)).thenReturn(Mono.just(Boolean.TRUE));

        BizException e = assertThrows(BizException.class,
                () -> service.submit(1L, 2L, "技术部", 1).block());
        assertEquals("该素材在此档位下已有未完成的转码任务（待处理或处理中），请勿重复提交", e.getMessage());
        verify(jobRepository, never()).submitNew(any());
    }

    @Test
    void submitShouldPersistPendingJob() {
        stubAssetAndProfile(readyAsset(), enabledProfile());
        when(jobRepository.existsActiveByAssetAndProfile(1L, 2L)).thenReturn(Mono.just(Boolean.FALSE));
        when(jobRepository.submitNew(any())).thenAnswer(inv -> {
            TranscodeJob job = inv.getArgument(0);
            job.setId(99L);
            job.setJobNo("TJ-2026-0001");
            return Mono.just(job);
        });

        TranscodeJob job = service.submit(1L, 2L, "技术部", null).block();

        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(TranscodeJob.DEFAULT_PRIORITY, job.getPriority());
        assertEquals("TJ-2026-0001", job.getJobNo());
        verify(jobRepository).submitNew(any());
    }

    @Test
    void cancelShouldFailWhenJobMissing() {
        when(jobRepository.findById(9L)).thenReturn(Mono.empty());

        assertThrows(BizException.class, () -> service.cancel(9L, "提错了").block());
        verify(jobRepository, never()).cancelIfPending(any());
    }

    @Test
    void cancelShouldPersistCancellation() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);
        job.setId(9L);
        job.setJobNo("TJ-2026-0001");
        when(jobRepository.findById(9L)).thenReturn(Mono.just(job));
        when(jobRepository.cancelIfPending(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        TranscodeJob cancelled = service.cancel(9L, "提错档位了").block();

        assertEquals(JobStatus.CANCELLED, cancelled.getStatus());
        assertEquals("提错档位了", cancelled.getErrorMsg());
        verify(jobRepository).cancelIfPending(any());
    }

    // ============ 领取：PENDING → RUNNING，素材进转码中，记执行记录 ============

    private TranscodeJob pendingJob() {
        TranscodeJob job = TranscodeJob.submit(1L, 2L, "技术部", 1);
        job.setId(9L);
        job.setJobNo("TJ-2026-0001");
        return job;
    }

    private TranscodeJob runningJob() {
        TranscodeJob job = pendingJob();
        job.claim("WK-001");
        return job;
    }

    @Test
    void claimShouldFailWhenJobMissing() {
        when(jobRepository.findById(9L)).thenReturn(Mono.empty());

        assertThrows(BizException.class, () -> service.claim(9L, "WK-001").block());
        verify(jobRepository, never()).claimIfPending(any(), any(), any());
    }

    @Test
    void claimShouldPersistRunningJobWithAttemptAndTranscodingAsset() {
        TranscodeJob job = pendingJob();
        MediaAsset asset = readyAsset();
        when(jobRepository.findById(9L)).thenReturn(Mono.just(job));
        when(assetRepository.findById(1L)).thenReturn(Mono.just(asset));
        when(jobRepository.claimIfPending(any(), any(), any()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        TranscodeJob claimed = service.claim(9L, "WK-001").block();

        // 任务进处理中
        assertEquals(JobStatus.RUNNING, claimed.getStatus());
        assertEquals(1, claimed.getAttemptCount());
        // 落库时素材跟着进转码中、执行记录一并带上
        verify(jobRepository).claimIfPending(any(),
                org.mockito.ArgumentMatchers.argThat(a ->
                        a.getAttemptNo() == 1 && "WK-001".equals(a.getWorkerCode())
                                && a.getStartedAt() != null),
                org.mockito.ArgumentMatchers.argThat(a -> a.getStatus() == AssetStatus.TRANSCODING));
    }

    @Test
    void claimShouldRejectAlreadyClaimedOrFinished() {
        // 已被领走的（RUNNING）再来领要挡回去
        TranscodeJob running = runningJob();
        when(jobRepository.findById(9L)).thenReturn(Mono.just(running));
        when(assetRepository.findById(1L)).thenReturn(Mono.just(readyAsset()));
        assertThrows(BizException.class, () -> service.claim(9L, "WK-002").block());

        // 已出结果的再来领也要挡回去
        TranscodeJob finished = runningJob();
        finished.completeSuccess("/out/a.mp4");
        when(jobRepository.findById(9L)).thenReturn(Mono.just(finished));
        assertThrows(BizException.class, () -> service.claim(9L, "WK-002").block());

        verify(jobRepository, never()).claimIfPending(any(), any(), any());
    }

    // ============ 进度：只能往前；没被领走 / 已出结果的不该收到进度 ============

    @Test
    void reportProgressShouldAdvance() {
        TranscodeJob job = runningJob();
        when(jobRepository.findById(9L)).thenReturn(Mono.just(job));
        when(jobRepository.advanceProgress(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        TranscodeJob updated = service.reportProgress(9L, 45).block();

        assertEquals(45, updated.getProgress());
        verify(jobRepository).advanceProgress(any());
    }

    @Test
    void reportProgressShouldRejectPendingJob() {
        // 没被领走的任务不该收到进度
        when(jobRepository.findById(9L)).thenReturn(Mono.just(pendingJob()));

        assertThrows(BizException.class, () -> service.reportProgress(9L, 10).block());
        verify(jobRepository, never()).advanceProgress(any());
    }

    @Test
    void reportProgressShouldRejectRegression() {
        TranscodeJob job = runningJob();
        job.reportProgress(60);
        when(jobRepository.findById(9L)).thenReturn(Mono.just(job));

        assertThrows(BizException.class, () -> service.reportProgress(9L, 30).block());
        verify(jobRepository, never()).advanceProgress(any());
    }

    // ============ 结果：成功素材留转码中，失败退回可转码；终态后不再变 ============

    @Test
    void reportSuccessShouldKeepAssetTranscoding() {
        TranscodeJob job = runningJob();
        when(jobRepository.findById(9L)).thenReturn(Mono.just(job));
        when(jobRepository.finishIfRunning(any(), any(), isNull()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        TranscodeJob finished = service.reportResult(9L, "SUCCESS", "/out/a.mp4", null).block();

        assertEquals(JobStatus.SUCCESS, finished.getStatus());
        assertEquals(100, finished.getProgress());
        assertNotNull(finished.getFinishedAt());
        // 成功：素材留在转码中等人审（asset 传 null，不动素材）
        verify(jobRepository).finishIfRunning(any(),
                org.mockito.ArgumentMatchers.argThat(a -> a.getStatus().name().equals("SUCCESS")),
                isNull());
        verify(assetRepository, never()).save(any());
    }

    @Test
    void reportFailureShouldReturnAssetToReady() {
        TranscodeJob job = runningJob();
        MediaAsset asset = readyAsset();
        asset.startTranscoding();
        when(jobRepository.findById(9L)).thenReturn(Mono.just(job));
        when(assetRepository.findById(1L)).thenReturn(Mono.just(asset));
        when(jobRepository.finishIfRunning(any(), any(), any()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        TranscodeJob finished = service.reportResult(9L, "FAILED", null, "解码失败").block();

        assertEquals(JobStatus.FAILED, finished.getStatus());
        assertEquals("解码失败", finished.getErrorMsg());
        assertNotNull(finished.getFinishedAt());
        // 失败：素材退回可转码，回头还能再提
        verify(jobRepository).finishIfRunning(any(), any(),
                org.mockito.ArgumentMatchers.argThat(a -> a.getStatus() == AssetStatus.READY));
    }

    @Test
    void reportResultShouldRejectInvalidResult() {
        when(jobRepository.findById(9L)).thenReturn(Mono.just(runningJob()));

        assertThrows(BizException.class, () -> service.reportResult(9L, "RUNNING", null, null).block());
        assertThrows(BizException.class, () -> service.reportResult(9L, "DONE", null, null).block());
        verify(jobRepository, never()).finishIfRunning(any(), any(), any());
    }

    @Test
    void reportResultShouldRejectFinishedJobWithoutSideEffects() {
        // 任务一旦出了结果，后面再怎么报都不该把它重新改一遍，执行记录也别再跟着多出来
        TranscodeJob job = runningJob();
        job.completeSuccess("/out/a.mp4");
        when(jobRepository.findById(9L)).thenReturn(Mono.just(job));

        assertThrows(BizException.class, () -> service.reportResult(9L, "FAILED", null, "又失败了").block());
        assertThrows(BizException.class, () -> service.reportProgress(9L, 99).block());

        verify(jobRepository, never()).finishIfRunning(any(), any(), any());
        verify(jobRepository, never()).advanceProgress(any());
        // 任务保持第一次的结果不变
        assertEquals(JobStatus.SUCCESS, job.getStatus());
        assertEquals("/out/a.mp4", job.getOutputPath());
    }

    // ============ 执行记录查询 ============

    @Test
    void listAttemptsShouldFailWhenJobMissing() {
        when(jobRepository.findById(9L)).thenReturn(Mono.empty());

        assertThrows(BizException.class, () -> service.listAttempts(9L).block());
        verify(attemptRepository, never()).listByJobId(any());
    }

    @Test
    void listAttemptsShouldReturnRecords() {
        TranscodeJob job = runningJob();
        when(jobRepository.findById(9L)).thenReturn(Mono.just(job));
        when(attemptRepository.listByJobId(9L)).thenReturn(Mono.just(List.of(
                com.somepro.domain.job.model.JobAttempt.start(9L, 1, "WK-001"))));

        var attempts = service.listAttempts(9L).block();

        assertEquals(1, attempts.size());
        assertEquals("WK-001", attempts.get(0).getWorkerCode());
        assertEquals(1, attempts.get(0).getAttemptNo());
    }
}

package com.somepro.domain.job.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 转码任务聚合根（job 上下文）：一个素材按一个档位转一次。
 *
 * 纯领域对象：只描述业务与不变量，不带任何持久化注解（表映射在基础设施层的 TranscodeJobPO）。
 *
 * 不变量：
 * - 必须指定素材 id、档位 id、归属部门；优先级越小越先做（1-99，缺省 5）；
 * - 新提交的任务一律 PENDING（待处理，等节点来领），attemptCount=0、progress=0、maxAttempts=3，
 *   submittedAt 记提交时刻；
 * - 状态机：PENDING → RUNNING → SUCCESS/FAILED；PENDING 还可撤销为 CANCELLED。
 *   一旦到了 SUCCESS/FAILED/CANCELLED 就是终态，之后任何上报都不允许再改动它；
 * - 撤销只能发生在 PENDING（还没被节点领走），且必须写明撤销原因；
 *   表里没有单独的取消原因列，原因落在 errorMsg；
 * - 任务编号 jobNo 形如 TJ-2026-0001，由仓储按年顺序分配（应用层不给编号）。
 */
@Getter
@Setter
public class TranscodeJob extends BaseEntity {

    /** 缺省优先级（越小越先做）。 */
    public static final int DEFAULT_PRIORITY = 5;

    /** 缺省最多尝试次数。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private Long id;

    /** 任务编号，全局唯一（形如 TJ-2026-0001），由仓储在落库时分配。 */
    private String jobNo;

    /** 素材 id（t_media_asset.id）。 */
    private Long assetId;

    /** 档位 id（t_transcode_profile.id）。 */
    private Long profileId;

    /** 归属部门（冗余自素材，配额与台账按它归集）。 */
    private String ownerDept;

    /** 优先级，越小越先做。 */
    private Integer priority;

    private JobStatus status;

    /** 已尝试次数。 */
    private Integer attemptCount;

    /** 最多尝试次数。 */
    private Integer maxAttempts;

    /** 进度 0-100。 */
    private Integer progress;

    /** 产出文件路径。 */
    private String outputPath;

    /** 最近一次失败原因；撤销时也用它记录撤销原因（表里无单独的取消原因列）。 */
    private String errorMsg;

    /** 提交时刻。 */
    private LocalDateTime submittedAt;

    /** 开始处理时刻。 */
    private LocalDateTime startedAt;

    /** 结束时刻（成功/失败/取消）。 */
    private LocalDateTime finishedAt;

    /** 审核结果（PASS/REJECT），本聚合当前不推进，留给后续审核流程。 */
    private String reviewResult;
    private String reviewComment;
    private String reviewBy;
    private LocalDateTime reviewTime;

    /** 工厂方法：提交转码任务。新任务一律 PENDING，并保证初始不变量。 */
    public static TranscodeJob submit(Long assetId, Long profileId, String ownerDept, Integer priority) {
        TranscodeJob job = new TranscodeJob();
        job.setAssetId(assetId);
        job.setProfileId(profileId);
        job.setOwnerDept(ownerDept);
        job.setPriority(priority == null ? DEFAULT_PRIORITY : priority);
        job.setStatus(JobStatus.PENDING);
        job.setAttemptCount(0);
        job.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        job.setProgress(0);
        job.setSubmittedAt(LocalDateTime.now());
        job.validate();
        return job;
    }

    /**
     * 领域行为：撤销任务。
     *
     * - 必须写明撤销原因；
     * - 只有 PENDING（还压在待处理里、没被节点领走）的任务可撤；
     *   RUNNING/SUCCESS/FAILED/CANCELLED 都不允许再撤。
     * - 原因落 errorMsg，finishedAt 记撤销时刻。
     *
     * 注意：这里只校验「当前看到的状态」；并发下可能刚被节点领走，
     * 所以仓储落库时还要带 status=PENDING 的条件再兜一道（乐观条件更新）。
     */
    public void cancel(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException("撤销原因不能为空");
        }
        if (status != JobStatus.PENDING) {
            throw new BizException("只有待处理（PENDING）的任务才能撤销，当前状态：" + status);
        }
        this.status = JobStatus.CANCELLED;
        this.errorMsg = reason.trim();
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * 领域行为：节点领取任务。
     *
     * - 只有 PENDING（待处理）的任务能领；已被领走（RUNNING）、已出结果（SUCCESS/FAILED）、
     *   已取消（CANCELLED）的一律挡回；
     * - 领取后任务变 RUNNING，attemptCount+1，startedAt 记开始时刻；
     * - 返回本次执行记录（第几次跑、哪台节点、几点开始），由仓储与任务状态同事务落库。
     *
     * 注意：这里只校验「当前看到的状态」；几个节点同时抢同一任务时，
     * 仓储落库带 status=PENDING 的条件更新只放一台过去（乐观条件更新兜底）。
     */
    public JobAttempt claim(String workerCode) {
        if (status != JobStatus.PENDING) {
            throw new BizException("任务不在待处理状态，不能领取，当前状态：" + status);
        }
        if (workerCode == null || workerCode.isBlank()) {
            throw new BizException("节点编号不能为空");
        }
        this.status = JobStatus.RUNNING;
        this.attemptCount = this.attemptCount + 1;
        this.startedAt = LocalDateTime.now();
        return JobAttempt.start(this.id, this.attemptCount, workerCode);
    }

    /**
     * 领域行为：节点上报进度。
     *
     * - 只有 RUNNING（处理中）的任务能收进度：没被领走的（PENDING）、已出结果的、
     *   已取消的都不该收到进度；
     * - 进度是 0-100 的整数，且只能往前：比当前已记录的进度小一律挡回
     *   （与当前值相等视为重复上报，幂等放行）。
     */
    public void reportProgress(Integer progress) {
        if (progress == null || progress < 0 || progress > 100) {
            throw new BizException("进度需为 0-100 的整数：" + progress);
        }
        if (status != JobStatus.RUNNING) {
            throw new BizException("任务不在处理中，不能上报进度，当前状态：" + status);
        }
        if (progress < this.progress) {
            throw new BizException("进度不能倒退：当前进度 " + this.progress + "，上报 " + progress);
        }
        this.progress = progress;
    }

    /**
     * 领域行为：节点上报成功结果。
     *
     * 只有 RUNNING 能出结果；成功后 progress 补满 100，finishedAt 记结束时刻。
     * 素材侧不动（留在转码中等人审）。返回当前尝试的收尾快照，由仓储同事务落库。
     */
    public JobAttempt completeSuccess(String outputPath) {
        requireRunning("上报结果");
        this.status = JobStatus.SUCCESS;
        this.progress = 100;
        this.outputPath = (outputPath == null || outputPath.isBlank()) ? null : outputPath.trim();
        this.finishedAt = LocalDateTime.now();
        return JobAttempt.finishing(this.id, this.attemptCount, AttemptStatus.SUCCESS, null);
    }

    /**
     * 领域行为：节点上报失败结果。
     *
     * 只有 RUNNING 能出结果；失败原因必填，finishedAt 记结束时刻。
     * 素材侧由应用层退回可转码（READY），之后可重新提交新任务；
     * 本任务就此终结，后续上报一律被状态校验挡回。
     */
    public JobAttempt completeFailure(String errorMsg) {
        requireRunning("上报结果");
        if (errorMsg == null || errorMsg.isBlank()) {
            throw new BizException("失败原因不能为空");
        }
        this.status = JobStatus.FAILED;
        this.errorMsg = errorMsg.trim();
        this.finishedAt = LocalDateTime.now();
        return JobAttempt.finishing(this.id, this.attemptCount, AttemptStatus.FAILED, this.errorMsg);
    }

    /** 出结果的前置校验：只有处理中（RUNNING）的任务能出结果。 */
    private void requireRunning(String action) {
        if (status != JobStatus.RUNNING) {
            throw new BizException("任务不在处理中，不能" + action + "，当前状态：" + status);
        }
    }

    /** 聚合不变量：提交时要过这道校验。 */
    private void validate() {
        if (assetId == null) {
            throw new BizException("素材不能为空");
        }
        if (profileId == null) {
            throw new BizException("转码档位不能为空");
        }
        if (ownerDept == null || ownerDept.isBlank()) {
            throw new BizException("归属部门不能为空");
        }
        this.ownerDept = ownerDept.trim();
        if (priority < 1 || priority > 99) {
            throw new BizException("优先级需在 1-99 之间（越小越先做）");
        }
    }
}

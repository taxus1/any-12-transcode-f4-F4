package com.somepro.domain.job.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务执行尝试（job 上下文，转码任务聚合的子实体）：每次节点真正领到任务、跑一遍，记一条。
 *
 * 纯领域对象：只描述业务与不变量，不带任何持久化注解（表映射在基础设施层的 JobAttemptPO）。
 *
 * 不变量：
 * - 必须归属一个任务（jobId），attemptNo 从 1 起（第几次跑）；
 * - 必须记下是哪台节点领的（workerCode）与几点开始（startedAt）；
 * - 开出来一律 RUNNING；任务出结果时同一条记录收成 SUCCESS / FAILED，
 *   只更新不新增 —— 任务到了终态后不会再多出新的执行记录。
 */
@Getter
@Setter
public class JobAttempt extends BaseEntity {

    private Long id;

    /** 转码任务 id（t_transcode_job.id）。 */
    private Long jobId;

    /** 第几次尝试，从 1 起（与任务的 attemptCount 对齐）。 */
    private Integer attemptNo;

    /** 执行的转码节点编号（哪台节点领的）。 */
    private String workerCode;

    private AttemptStatus status;

    /** 失败错误码（预留，当前流程不填）。 */
    private String errorCode;

    /** 失败说明。 */
    private String errorMsg;

    /** 开始时刻（几点开始跑的）。 */
    private LocalDateTime startedAt;

    /** 结束时刻。 */
    private LocalDateTime finishedAt;

    /**
     * 工厂方法：节点领到任务时开一条执行记录。
     * 新记录一律 RUNNING，并保证初始不变量。
     */
    public static JobAttempt start(Long jobId, Integer attemptNo, String workerCode, LocalDateTime startedAt) {
        if (jobId == null) {
            throw new BizException("执行记录必须归属一个转码任务");
        }
        if (attemptNo == null || attemptNo < 1) {
            throw new BizException("尝试次数必须从 1 起：" + attemptNo);
        }
        if (workerCode == null || workerCode.isBlank()) {
            throw new BizException("节点编号不能为空");
        }
        JobAttempt attempt = new JobAttempt();
        attempt.setJobId(jobId);
        attempt.setAttemptNo(attemptNo);
        attempt.setWorkerCode(workerCode.trim());
        attempt.setStatus(AttemptStatus.RUNNING);
        attempt.setStartedAt(startedAt == null ? LocalDateTime.now() : startedAt);
        return attempt;
    }
}

package com.somepro.domain.job.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务执行尝试（job 上下文）：节点每领取一次任务，就落一条执行记录。
 *
 * 纯领域对象：只描述业务与不变量，不带任何持久化注解（表映射在基础设施层的 JobAttemptPO）。
 *
 * 不变量：
 * - 必须指明所属任务 id、第几次尝试（attemptNo 从 1 起，与任务的 attemptCount 对应）、
 *   领取节点 workerCode；
 * - 创建即 RUNNING，startedAt 记领取时刻；
 * - 收尾只发生一次：RUNNING → SUCCESS/FAILED，finishedAt 记结束时刻。
 *   任务出终态后，后续上报一律被任务侧的状态条件挡回，执行记录因此不会再被触碰、
 *   也不会再新增（新记录只在领取时产生，而领取只放 PENDING 的任务）。
 */
@Getter
@Setter
public class JobAttempt extends BaseEntity {

    private Long id;

    /** 所属任务 id（t_transcode_job.id）。 */
    private Long jobId;

    /** 第几次尝试，从 1 起。 */
    private Integer attemptNo;

    /** 领取任务的转码节点编号。 */
    private String workerCode;

    private AttemptStatus status;

    /** 失败说明（成功时为空）。 */
    private String errorMsg;

    /** 开始时刻（领取时刻）。 */
    private LocalDateTime startedAt;

    /** 结束时刻。 */
    private LocalDateTime finishedAt;

    /** 工厂方法：节点领取任务时创建，初始 RUNNING。 */
    public static JobAttempt start(Long jobId, Integer attemptNo, String workerCode) {
        if (jobId == null) {
            throw new BizException("执行记录必须归属一个任务");
        }
        if (attemptNo == null || attemptNo < 1) {
            throw new BizException("尝试次数需从 1 起：" + attemptNo);
        }
        if (workerCode == null || workerCode.isBlank()) {
            throw new BizException("节点编号不能为空");
        }
        JobAttempt attempt = new JobAttempt();
        attempt.setJobId(jobId);
        attempt.setAttemptNo(attemptNo);
        attempt.setWorkerCode(workerCode.trim());
        attempt.setStatus(AttemptStatus.RUNNING);
        attempt.setStartedAt(LocalDateTime.now());
        return attempt;
    }

    /**
     * 工厂方法：任务出结果时，当前这次尝试的「收尾快照」。
     *
     * 不是从库里查出的完整实体：只携带收尾要落的三个字段（status / errorMsg / finishedAt），
     * 仓储按 (jobId, attemptNo) 定位到库里那条 RUNNING 记录做条件更新，
     * startedAt、workerCode 保持领取时记下的值不动。
     */
    public static JobAttempt finishing(Long jobId, Integer attemptNo, AttemptStatus result, String errorMsg) {
        if (result == null || result == AttemptStatus.RUNNING) {
            throw new BizException("执行结果只支持 SUCCESS/FAILED");
        }
        JobAttempt attempt = new JobAttempt();
        attempt.setJobId(jobId);
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus(result);
        attempt.setErrorMsg(errorMsg);
        attempt.setFinishedAt(LocalDateTime.now());
        return attempt;
    }
}

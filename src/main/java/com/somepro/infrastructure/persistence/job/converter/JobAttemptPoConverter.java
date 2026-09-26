package com.somepro.infrastructure.persistence.job.converter;

import com.somepro.domain.job.model.AttemptStatus;
import com.somepro.domain.job.model.JobAttempt;
import com.somepro.infrastructure.persistence.job.po.JobAttemptPO;

/**
 * JobAttemptPO（表）↔ JobAttempt（领域）转换器（基础设施层）。
 *
 * 这是 PO 与领域模型之间**唯一**的转换入口：仓储适配器进去转 PO 落库、出来转回领域对象，
 * 领域层和接口层都不应看到 JobAttemptPO。
 *
 * status 枚举 ↔ VARCHAR 在这里互转；审计字段与 delFlag 也一并搬运（同 TranscodeJobPoConverter 的约定）。
 */
public final class JobAttemptPoConverter {

    private JobAttemptPoConverter() {
    }

    public static JobAttemptPO toPo(JobAttempt domain) {
        JobAttemptPO po = new JobAttemptPO();
        po.setId(domain.getId());
        po.setJobId(domain.getJobId());
        po.setAttemptNo(domain.getAttemptNo());
        po.setWorkerCode(domain.getWorkerCode());
        po.setStatus(domain.getStatus() == null ? null : domain.getStatus().name());
        po.setErrorMsg(domain.getErrorMsg());
        po.setStartedAt(domain.getStartedAt());
        po.setFinishedAt(domain.getFinishedAt());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static JobAttempt toDomain(JobAttemptPO po) {
        JobAttempt domain = new JobAttempt();
        domain.setId(po.getId());
        domain.setJobId(po.getJobId());
        domain.setAttemptNo(po.getAttemptNo());
        domain.setWorkerCode(po.getWorkerCode());
        domain.setStatus(po.getStatus() == null ? null : AttemptStatus.valueOf(po.getStatus()));
        domain.setErrorMsg(po.getErrorMsg());
        domain.setStartedAt(po.getStartedAt());
        domain.setFinishedAt(po.getFinishedAt());
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}

package com.somepro.interfaces.rest.job.converter;

import com.somepro.domain.job.model.JobAttempt;
import com.somepro.interfaces.rest.job.vo.JobAttemptVO;

/**
 * JobAttempt（领域）→ JobAttemptVO（对外）转换器（用户接口层）。
 *
 * 接口层是唯一做领域对象 → VO 转换的地方：Controller 不许直接把领域对象塞进 Result 返回，
 * 否则 delFlag / createBy / updateBy 等内部字段会被无意识序列化出去。
 */
public final class JobAttemptVoConverter {

    private JobAttemptVoConverter() {
    }

    public static JobAttemptVO toVo(JobAttempt domain) {
        return new JobAttemptVO(
                domain.getId(),
                domain.getJobId(),
                domain.getAttemptNo(),
                domain.getWorkerCode(),
                domain.getStatus() == null ? null : domain.getStatus().name(),
                domain.getErrorCode(),
                domain.getErrorMsg(),
                domain.getStartedAt(),
                domain.getFinishedAt(),
                domain.getCreateTime());
    }
}

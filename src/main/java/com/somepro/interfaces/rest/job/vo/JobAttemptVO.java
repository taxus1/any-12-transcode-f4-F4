package com.somepro.interfaces.rest.job.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务执行尝试对外返回对象（VO，用户接口层）—— 不可变 record。
 *
 * 只暴露允许外部看到的字段。刻意不含：
 * - delFlag：内部软删状态
 * - createBy / updateBy / updateTime：内部审计字段
 */
public record JobAttemptVO(Long id, Long jobId, Integer attemptNo, String workerCode,
                           String status, String errorMsg,
                           LocalDateTime startedAt, LocalDateTime finishedAt,
                           LocalDateTime createTime)
        implements Serializable {
}

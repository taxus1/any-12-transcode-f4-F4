package com.somepro.interfaces.rest.job.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务执行尝试对外返回对象（VO，用户接口层）—— 不可变 record。
 *
 * 只暴露允许外部看到的字段：第几次跑、哪台节点领的、几点开始、几点结束、结果与失败说明。
 * 刻意不含 delFlag / createBy / updateBy / updateTime 等内部字段。
 */
public record JobAttemptVO(Long id, Long jobId, Integer attemptNo, String workerCode, String status,
                           String errorCode, String errorMsg,
                           LocalDateTime startedAt, LocalDateTime finishedAt,
                           LocalDateTime createTime)
        implements Serializable {
}

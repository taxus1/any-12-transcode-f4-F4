package com.somepro.domain.job.model;

import com.somepro.common.exception.BizException;

/**
 * 执行尝试状态（领域枚举）：RUNNING 执行中 / SUCCESS 成功 / FAILED 失败。
 *
 * 节点每领取一次任务就落一条执行记录，创建即 RUNNING；任务出结果时同步收尾为 SUCCESS/FAILED。
 * 与 JobStatus 的区别：JobStatus 多了 PENDING（待处理）与 CANCELLED（已取消）——
 * 执行记录从被创建那一刻起就只在「跑 → 出结果」这一段生命周期里，没有待处理一说。
 */
public enum AttemptStatus {

    RUNNING, SUCCESS, FAILED;

    /** 按名字解析（大小写不敏感）；非法值抛业务异常。 */
    public static AttemptStatus of(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("执行状态不能为空（RUNNING/SUCCESS/FAILED）");
        }
        try {
            return AttemptStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("执行状态只支持 RUNNING/SUCCESS/FAILED：" + value);
        }
    }
}

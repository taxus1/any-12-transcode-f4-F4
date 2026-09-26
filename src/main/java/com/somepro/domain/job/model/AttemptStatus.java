package com.somepro.domain.job.model;

import com.somepro.common.exception.BizException;

/**
 * 任务执行尝试状态（领域枚举）：RUNNING 执行中 / SUCCESS 成功 / FAILED 失败。
 *
 * 节点领到任务时开一条 RUNNING 记录（见 JobAttempt.start），
 * 任务出结果时同一条记录跟着收成 SUCCESS / FAILED（只更新，不新增）。
 */
public enum AttemptStatus {

    RUNNING, SUCCESS, FAILED;

    /** 按名字解析（大小写不敏感）；非法值抛业务异常。 */
    public static AttemptStatus of(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("执行记录状态不能为空（RUNNING/SUCCESS/FAILED）");
        }
        try {
            return AttemptStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("执行记录状态只支持 RUNNING/SUCCESS/FAILED：" + value);
        }
    }
}

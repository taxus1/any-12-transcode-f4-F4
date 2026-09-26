package com.somepro.infrastructure.persistence.job.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.somepro.infrastructure.persistence.base.BasePO;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * t_job_attempt 表的持久化对象（PO，基础设施层）。
 *
 * 只描述「表长什么样」：字段与列一一对应，不放任何业务规则（规则在领域对象 JobAttempt）。
 * status 在表里是 VARCHAR，这里用 String；与领域枚举的互转见 JobAttemptPoConverter。
 *
 * 表里的 error_code 列为后续按错误码统计预留，当前流程不填，故这里不映射。
 *
 * ID 策略 IdType.INPUT：由应用层用雪花算法分配后传入，与仓储适配器里的 IdUtil 一致。
 */
@Getter
@Setter
@TableName("t_job_attempt")
public class JobAttemptPO extends BasePO {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("job_id")
    private Long jobId;

    @TableField("attempt_no")
    private Integer attemptNo;

    @TableField("worker_code")
    private String workerCode;

    @TableField("status")
    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}

package com.somepro.domain.job.repository;

import com.somepro.domain.job.model.JobAttempt;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 任务执行尝试的仓储端口：由领域层定义，基础设施层实现（端口-适配器）。
 *
 * 执行记录的「写」不在这里：创建随领取、收尾随结果，都由 TranscodeJobRepository
 * 在各自的事务里一并落库（保证任务状态与执行记录同生共死）。这里只提供查询。
 */
public interface JobAttemptRepository {

    /** 按任务查全部执行记录（按尝试次数升序）：第几次跑、哪台节点、几点开始/结束。 */
    Mono<List<JobAttempt>> listByJobId(Long jobId);
}

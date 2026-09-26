package com.somepro.domain.job.repository;

import com.somepro.domain.job.model.JobAttempt;
import com.somepro.domain.job.model.TranscodeJob;
import com.somepro.domain.media.model.MediaAsset;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 转码任务聚合的仓储端口：由领域层定义，基础设施层实现（端口-适配器）。
 *
 * 分页条件全部可空：一个条件都不填时就是全量分页（表里早先录的数据也要能翻出来）。
 */
public interface TranscodeJobRepository {

    /**
     * 落库一条新任务（提交用例专用）：
     * - 在事务里锁住素材行（FOR UPDATE），锁内复查「同素材同档位无未完成任务」，
     *   挡住并发重复提交；
     * - 分配任务编号（TJ-年份-四位序号，按年递增），uk_job_no 唯一索引兜底，撞号自动重试。
     */
    Mono<TranscodeJob> submitNew(TranscodeJob job);

    Mono<TranscodeJob> findById(Long id);

    /** 同素材同档位是否已有未完成任务（PENDING/RUNNING）。 */
    Mono<Boolean> existsActiveByAssetAndProfile(Long assetId, Long profileId);

    Mono<PageResult<TranscodeJob>> page(int pageNum, int pageSize, String jobNo, String status,
                                        String ownerDept, Long assetId, Long profileId);

    /**
     * 撤销落库（乐观条件更新）：仅当库里仍是 PENDING 才更新为 CANCELLED，
     * 防止「查出来是待处理 → 节点同时领走 → 又被撤销」的并发窗口。
     */
    Mono<TranscodeJob> cancelIfPending(TranscodeJob job);

    /**
     * 领取落库（乐观条件更新 + 事务）：
     * 仅当库里仍是 PENDING 才把任务改为 RUNNING —— 几个节点同时抢同一任务时只放一台，
     * 其余影响 0 行报错回滚，不会重复领取、也不会重复插执行记录；
     * 同一事务里把素材置为转码中、插入本次执行记录。
     * asset 是已经过领域行为改好状态的素材对象。
     */
    Mono<TranscodeJob> claimIfPending(TranscodeJob job, JobAttempt attempt, MediaAsset asset);

    /**
     * 进度落库（乐观条件更新）：仅当库里仍是 RUNNING 且已记进度不超过新进度才更新，
     * 挡住「终态后又来报进度」与「并发下后到的小进度把大进度冲掉」。
     */
    Mono<TranscodeJob> advanceProgress(TranscodeJob job);

    /**
     * 结果落库（乐观条件更新 + 事务）：
     * 仅当库里仍是 RUNNING 才把任务改为终态（SUCCESS/FAILED）—— 任务一旦出了结果，
     * 后续上报都在这里影响 0 行被挡回，任务不会被重改，执行记录也不会再新增；
     * 同事务把当前执行记录收尾（仅 RUNNING 的尝试可收尾）。
     * asset 不为 null 时（失败）把素材退回可转码；为 null（成功）时素材不动，留在转码中等人审。
     */
    Mono<TranscodeJob> finishIfRunning(TranscodeJob job, JobAttempt attempt, MediaAsset asset);
}

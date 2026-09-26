package com.somepro.infrastructure.persistence.job;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.job.model.AttemptStatus;
import com.somepro.domain.job.model.JobAttempt;
import com.somepro.domain.job.model.JobStatus;
import com.somepro.domain.job.model.TranscodeJob;
import com.somepro.domain.job.repository.TranscodeJobRepository;
import com.somepro.domain.media.model.MediaAsset;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.job.converter.JobAttemptPoConverter;
import com.somepro.infrastructure.persistence.job.converter.TranscodeJobPoConverter;
import com.somepro.infrastructure.persistence.job.po.JobAttemptPO;
import com.somepro.infrastructure.persistence.job.po.TranscodeJobPO;
import com.somepro.infrastructure.persistence.media.MediaAssetMapper;
import com.somepro.infrastructure.persistence.media.converter.MediaAssetPoConverter;
import com.somepro.infrastructure.persistence.media.po.MediaAssetPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 转码任务仓储适配器：用 MyBatis-Plus 实现领域仓储端口（基础设施层）。
 *
 * 约定同 DemoItemRepositoryImpl：
 * - 所有 DB 调用必须经 {@link #blocking} 桥接到 boundedElastic，严禁在 event-loop 上跑 JDBC；
 * - Mapper 只认 {@link TranscodeJobPO}，领域层只认 {@link TranscodeJob}，两者在本类里互转；
 * - 软删除交给 @TableLogic，不手写 del_flag 条件；
 * - 分页统一用 PageHelper.startPage()，finally 里必须 clearPage()。
 *
 * 本模块特有的两道并发防线：
 * 1. 提交时事务内 SELECT ... FOR UPDATE 锁住素材行，锁内复查「同素材同档位无未完成任务」，
 *    同一素材的并发提交因此串行，不会重复落库；
 * 2. 任务编号 uk_job_no 唯一索引兜底，撞号（不同素材并发取到同一序号）时重取编号重试。
 */
@Repository
public class TranscodeJobRepositoryImpl implements TranscodeJobRepository {

    /** 任务编号撞号时的最大重试次数。 */
    private static final int JOB_NO_MAX_RETRY = 5;

    private static final String DUPLICATE_MSG = "该素材在此档位下已有未完成的转码任务（待处理或处理中），请勿重复提交";

    private final TranscodeJobMapper transcodeJobMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final JobAttemptMapper jobAttemptMapper;
    private final TransactionTemplate transactionTemplate;

    public TranscodeJobRepositoryImpl(TranscodeJobMapper transcodeJobMapper,
                                      MediaAssetMapper mediaAssetMapper,
                                      JobAttemptMapper jobAttemptMapper,
                                      PlatformTransactionManager transactionManager) {
        this.transcodeJobMapper = transcodeJobMapper;
        this.mediaAssetMapper = mediaAssetMapper;
        this.jobAttemptMapper = jobAttemptMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<TranscodeJob> submitNew(TranscodeJob job) {
        return blocking(() -> transactionTemplate.execute(status -> {
            // ① 锁住素材行（FOR UPDATE）：同一素材的并发提交在此串行，
            //    配合 ② 的锁内复查，挡住「同素材同档位」的并发重复提交
            MediaAssetPO asset = mediaAssetMapper.selectOne(Wrappers.<MediaAssetPO>lambdaQuery()
                    .eq(MediaAssetPO::getId, job.getAssetId())
                    .last("FOR UPDATE"));
            if (asset == null) {
                throw new BizException("素材不存在：" + job.getAssetId());
            }
            // ② 锁内复查：同素材同档位是否已有未完成任务（待处理/处理中）
            if (countActive(job.getAssetId(), job.getProfileId()) > 0) {
                throw new BizException(DUPLICATE_MSG);
            }
            // ③ 分配任务编号并落库；uk_job_no 兜底，撞号时重取编号重试
            TranscodeJobPO po = TranscodeJobPoConverter.toPo(job);
            po.setId(IdUtil.getSnowflakeNextId());
            insertWithFreshJobNo(po);
            // insert 后框架会回填审计字段，转回领域对象一并返回
            return TranscodeJobPoConverter.toDomain(po);
        }));
    }

    @Override
    public Mono<TranscodeJob> findById(Long id) {
        return blocking(() -> {
            TranscodeJobPO po = transcodeJobMapper.selectById(id);
            // 返回 null 时 Mono.fromCallable 会自动转成空信号
            return po == null ? null : TranscodeJobPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Boolean> existsActiveByAssetAndProfile(Long assetId, Long profileId) {
        return blocking(() -> countActive(assetId, profileId) > 0);
    }

    @Override
    public Mono<PageResult<TranscodeJob>> page(int pageNum, int pageSize, String jobNo, String status,
                                               String ownerDept, Long assetId, Long profileId) {
        return this.<PageResult<TranscodeJob>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                // 条件全部可空：一个都不填时不拼任何条件，全量分页（早先录的数据也能翻出来）
                LambdaQueryWrapper<TranscodeJobPO> wrapper = Wrappers.<TranscodeJobPO>lambdaQuery()
                        .eq(StrUtil.isNotBlank(jobNo), TranscodeJobPO::getJobNo, jobNo)
                        .eq(StrUtil.isNotBlank(status), TranscodeJobPO::getStatus, status)
                        .eq(StrUtil.isNotBlank(ownerDept), TranscodeJobPO::getOwnerDept, ownerDept)
                        .eq(assetId != null, TranscodeJobPO::getAssetId, assetId)
                        .eq(profileId != null, TranscodeJobPO::getProfileId, profileId)
                        .orderByDesc(TranscodeJobPO::getId);
                List<TranscodeJobPO> rows = transcodeJobMapper.selectList(wrapper);
                // 命中分页插件时返回的是 com.github.pagehelper.Page，可直接取总数
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<TranscodeJob> content = rows.stream()
                        .map(TranscodeJobPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                // 分页插件靠 ThreadLocal 传递分页参数，必须清理，否则污染线程池里的下一次调用
                PageHelper.clearPage();
            }
        });
    }

    @Override
    public Mono<TranscodeJob> cancelIfPending(TranscodeJob job) {
        return blocking(() -> {
            TranscodeJobPO update = new TranscodeJobPO();
            update.setStatus(job.getStatus().name());
            update.setErrorMsg(job.getErrorMsg());
            update.setFinishedAt(job.getFinishedAt());
            // 乐观条件更新：只有库里仍是 PENDING 的行才会被改掉，
            // 防止「查出来是待处理 → 节点同时领走 → 又被撤销」的并发窗口。
            // update(entity, wrapper) 会走 MetaObjectHandler 填充 updateBy/updateTime。
            int rows = transcodeJobMapper.update(update, Wrappers.<TranscodeJobPO>lambdaUpdate()
                    .eq(TranscodeJobPO::getId, job.getId())
                    .eq(TranscodeJobPO::getStatus, JobStatus.PENDING.name()));
            if (rows == 0) {
                throw new BizException("任务已被节点领取或已结束，无法撤销：" + job.getJobNo());
            }
            return TranscodeJobPoConverter.toDomain(transcodeJobMapper.selectById(job.getId()));
        });
    }

    @Override
    public Mono<TranscodeJob> claimIfPending(TranscodeJob job, JobAttempt attempt, MediaAsset asset) {
        return blocking(() -> transactionTemplate.execute(status -> {
            // ① 乐观条件更新：只有库里仍是 PENDING 的行才会被改成 RUNNING。
            //    几个节点同时抢同一任务时，只有一台的 UPDATE 命中（影响 1 行），
            //    其余影响 0 行 → 抛异常回滚：不会重复领取，也不会重复插执行记录。
            TranscodeJobPO update = new TranscodeJobPO();
            update.setStatus(job.getStatus().name());
            update.setStartedAt(job.getStartedAt());
            update.setAttemptCount(job.getAttemptCount());
            int rows = transcodeJobMapper.update(update, Wrappers.<TranscodeJobPO>lambdaUpdate()
                    .eq(TranscodeJobPO::getId, job.getId())
                    .eq(TranscodeJobPO::getStatus, JobStatus.PENDING.name()));
            if (rows == 0) {
                throw new BizException("任务已被其他节点领取或已结束，领取失败：" + job.getJobNo());
            }
            // ② 素材跟着进转码中（领域行为已在应用层改过状态，这里只负责落库）
            mediaAssetMapper.updateById(MediaAssetPoConverter.toPo(asset));
            // ③ 记执行记录：第几次跑、哪台节点、几点开始；
            //    uk_job_attempt(job_id, attempt_no) 唯一索引兜底，同事务内与任务状态同生共死
            JobAttemptPO attemptPo = JobAttemptPoConverter.toPo(attempt);
            attemptPo.setId(IdUtil.getSnowflakeNextId());
            jobAttemptMapper.insert(attemptPo);
            return TranscodeJobPoConverter.toDomain(transcodeJobMapper.selectById(job.getId()));
        }));
    }

    @Override
    public Mono<TranscodeJob> advanceProgress(TranscodeJob job) {
        return blocking(() -> {
            TranscodeJobPO update = new TranscodeJobPO();
            update.setProgress(job.getProgress());
            // 乐观条件更新：仍是 RUNNING 且库里进度不超过新进度才落。
            // 挡住两类越界：① 任务已出终态后又来的进度；② 并发下后到的小进度把大进度冲掉。
            int rows = transcodeJobMapper.update(update, Wrappers.<TranscodeJobPO>lambdaUpdate()
                    .eq(TranscodeJobPO::getId, job.getId())
                    .eq(TranscodeJobPO::getStatus, JobStatus.RUNNING.name())
                    .le(TranscodeJobPO::getProgress, job.getProgress()));
            if (rows == 0) {
                throw new BizException("任务不在处理中或已有更新的进度，进度上报被拒绝：" + job.getJobNo());
            }
            return TranscodeJobPoConverter.toDomain(transcodeJobMapper.selectById(job.getId()));
        });
    }

    @Override
    public Mono<TranscodeJob> finishIfRunning(TranscodeJob job, JobAttempt attempt, MediaAsset asset) {
        return blocking(() -> transactionTemplate.execute(status -> {
            // ① 乐观条件更新：只有库里仍是 RUNNING 的行才能出结果。
            //    任务一旦到了终态，之后任何结果上报都在这里影响 0 行被挡回，任务不会被重改。
            TranscodeJobPO update = new TranscodeJobPO();
            update.setStatus(job.getStatus().name());
            update.setProgress(job.getProgress());
            update.setOutputPath(job.getOutputPath());
            update.setErrorMsg(job.getErrorMsg());
            update.setFinishedAt(job.getFinishedAt());
            int rows = transcodeJobMapper.update(update, Wrappers.<TranscodeJobPO>lambdaUpdate()
                    .eq(TranscodeJobPO::getId, job.getId())
                    .eq(TranscodeJobPO::getStatus, JobStatus.RUNNING.name()));
            if (rows == 0) {
                throw new BizException("任务不在处理中，结果上报被拒绝：" + job.getJobNo());
            }
            // ② 当前执行记录收尾：只落 status / errorMsg / finishedAt，且仅 RUNNING 的尝试可收尾。
            //    ① 已成功说明任务刚才还在跑，对应尝试必为 RUNNING，此处必然命中；
            //    任务到终态后领取只放 PENDING，不会再有新的执行记录，旧记录也不会再被触碰。
            JobAttemptPO attemptUpdate = new JobAttemptPO();
            attemptUpdate.setStatus(attempt.getStatus().name());
            attemptUpdate.setErrorMsg(attempt.getErrorMsg());
            attemptUpdate.setFinishedAt(attempt.getFinishedAt());
            jobAttemptMapper.update(attemptUpdate, Wrappers.<JobAttemptPO>lambdaUpdate()
                    .eq(JobAttemptPO::getJobId, attempt.getJobId())
                    .eq(JobAttemptPO::getAttemptNo, attempt.getAttemptNo())
                    .eq(JobAttemptPO::getStatus, AttemptStatus.RUNNING.name()));
            // ③ 失败时素材退回可转码；asset 为 null（成功）时素材不动，留在转码中等人审
            if (asset != null) {
                mediaAssetMapper.updateById(MediaAssetPoConverter.toPo(asset));
            }
            return TranscodeJobPoConverter.toDomain(transcodeJobMapper.selectById(job.getId()));
        }));
    }

    /** 同素材同档位的未完成任务数（PENDING/RUNNING）。 */
    private long countActive(Long assetId, Long profileId) {
        Long count = transcodeJobMapper.selectCount(Wrappers.<TranscodeJobPO>lambdaQuery()
                .eq(TranscodeJobPO::getAssetId, assetId)
                .eq(TranscodeJobPO::getProfileId, profileId)
                .in(TranscodeJobPO::getStatus, JobStatus.PENDING.name(), JobStatus.RUNNING.name()));
        return count == null ? 0 : count;
    }

    /** 取号并插入；撞 uk_job_no（并发取到同一序号）时重取编号重试。 */
    private void insertWithFreshJobNo(TranscodeJobPO po) {
        for (int attempt = 1; ; attempt++) {
            po.setJobNo(nextJobNo());
            try {
                transcodeJobMapper.insert(po);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= JOB_NO_MAX_RETRY) {
                    throw new BizException("任务编号生成冲突，请稍后重试");
                }
            }
        }
    }

    /**
     * 任务编号：TJ-年份-四位序号，按年递增（如 TJ-2026-0001）。
     *
     * 取当前最大编号 +1；按 LENGTH 再按字典序倒排，序号超过 4 位（10000+）时也不会取错最大值。
     * REGEXP 限定数字后缀，挡住历史脏数据干扰取号。
     */
    private String nextJobNo() {
        String prefix = "TJ-" + LocalDate.now().getYear() + "-";
        TranscodeJobPO latest = transcodeJobMapper.selectOne(Wrappers.<TranscodeJobPO>lambdaQuery()
                .likeRight(TranscodeJobPO::getJobNo, prefix)
                .apply("job_no REGEXP {0}", prefix + "[0-9]+")
                .last("ORDER BY LENGTH(job_no) DESC, job_no DESC LIMIT 1"));
        int next = 1;
        if (latest != null) {
            next = Integer.parseInt(latest.getJobNo().substring(prefix.length())) + 1;
        }
        return prefix + String.format("%04d", next);
    }

    /**
     * 阻塞 DB 调用 → 响应式链路的桥接器。
     *
     * 1. 先在响应式线程上从 Reactor Context 取操作人（切线程后就取不到了）
     * 2. 再切到 boundedElastic 执行 JDBC
     * 3. 把操作人放进 AuditContextHolder，供 MetaObjectHandler 填充 createBy / updateBy
     */
    private <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}

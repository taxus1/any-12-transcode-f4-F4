package com.somepro.infrastructure.persistence.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.somepro.domain.job.model.JobAttempt;
import com.somepro.domain.job.repository.JobAttemptRepository;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.job.converter.JobAttemptPoConverter;
import com.somepro.infrastructure.persistence.job.po.JobAttemptPO;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 任务执行尝试的仓储适配器：用 MyBatis-Plus 实现领域仓储端口（基础设施层）。
 *
 * 只提供查询；执行记录的创建与收尾由 TranscodeJobRepositoryImpl
 * 在领取 / 出结果的事务里一并落库（任务状态与执行记录必须同生共死，不能分两个事务）。
 *
 * 约定同 TranscodeJobRepositoryImpl：所有 DB 调用必须经 {@link #blocking} 桥接到
 * boundedElastic，严禁在 event-loop 上跑 JDBC；软删除交给 @TableLogic。
 */
@Repository
public class JobAttemptRepositoryImpl implements JobAttemptRepository {

    private final JobAttemptMapper jobAttemptMapper;

    public JobAttemptRepositoryImpl(JobAttemptMapper jobAttemptMapper) {
        this.jobAttemptMapper = jobAttemptMapper;
    }

    @Override
    public Mono<List<JobAttempt>> listByJobId(Long jobId) {
        return blocking(() -> jobAttemptMapper.selectList(Wrappers.<JobAttemptPO>lambdaQuery()
                        .eq(JobAttemptPO::getJobId, jobId)
                        .orderByAsc(JobAttemptPO::getAttemptNo))
                .stream()
                .map(JobAttemptPoConverter::toDomain)
                .collect(Collectors.toList()));
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

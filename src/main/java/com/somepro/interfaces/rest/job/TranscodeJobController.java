package com.somepro.interfaces.rest.job;

import com.somepro.application.job.TranscodeJobAppService;
import com.somepro.common.Result;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.job.converter.JobAttemptVoConverter;
import com.somepro.interfaces.rest.job.converter.TranscodeJobVoConverter;
import com.somepro.interfaces.rest.job.vo.JobAttemptVO;
import com.somepro.interfaces.rest.job.vo.TranscodeJobVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 转码任务接口（用户接口层）：提交 / 撤销 / 领取 / 进度上报 / 结果上报 / 查看 / 分页。
 *
 * 只做协议适配（参数解析、VO 转换、返回包装），业务编排交给应用层：
 * - 统一返回 Mono<Result<T>>；
 * - 分页透传 pageNum/pageSize，不要写死；查询条件全部可空，一个都不填就是全量分页；
 * - ⚠️ 不直接返回领域对象：一律经 TranscodeJobVoConverter 转成 VO，
 *   否则 delFlag / createBy / updateBy 等内部字段会被序列化出去。
 */
@RestController
@RequestMapping("/api/transcode/job")
public class TranscodeJobController {

    private final TranscodeJobAppService transcodeJobAppService;

    public TranscodeJobController(TranscodeJobAppService transcodeJobAppService) {
        this.transcodeJobAppService = transcodeJobAppService;
    }

    /** 提交任务：一个素材按一个档位转一次；提出来是 PENDING，priority 不传默认 5（越小越先做）。 */
    @PostMapping
    public Mono<Result<TranscodeJobVO>> submit(@RequestParam Long assetId,
                                               @RequestParam Long profileId,
                                               @RequestParam String ownerDept,
                                               @RequestParam(required = false) Integer priority) {
        return transcodeJobAppService.submit(assetId, profileId, ownerDept, priority)
                .map(TranscodeJobVoConverter::toVo)
                .map(Result::ok);
    }

    /** 撤销任务：只有 PENDING 可撤；reason 必填（不传或空白由领域层报错）。 */
    @PostMapping("/{id}/cancel")
    public Mono<Result<TranscodeJobVO>> cancel(@PathVariable Long id,
                                               @RequestParam(required = false) String reason) {
        return transcodeJobAppService.cancel(id, reason)
                .map(TranscodeJobVoConverter::toVo)
                .map(Result::ok);
    }

    /**
     * 节点领取任务：PENDING → RUNNING，素材跟着进转码中，并记一条执行记录。
     * 同一任务同一时刻只放一台节点；已被领走 / 已出结果 / 已取消的再来领会被明确挡回。
     */
    @PostMapping("/{id}/claim")
    public Mono<Result<TranscodeJobVO>> claim(@PathVariable Long id,
                                              @RequestParam String workerCode) {
        return transcodeJobAppService.claim(id, workerCode)
                .map(TranscodeJobVoConverter::toVo)
                .map(Result::ok);
    }

    /** 节点上报进度：0-100 的整数、只能往前；任务没在处理中或进度倒退都会被挡回。 */
    @PostMapping("/{id}/progress")
    public Mono<Result<TranscodeJobVO>> reportProgress(@PathVariable Long id,
                                                       @RequestParam Integer progress) {
        return transcodeJobAppService.reportProgress(id, progress)
                .map(TranscodeJobVoConverter::toVo)
                .map(Result::ok);
    }

    /**
     * 节点上报结果：result=SUCCESS（可带 outputPath）/ FAILED（必须带 errorMsg）。
     * 任务一旦出了结果，后续上报一律被挡回，任务与执行记录都不会再变。
     */
    @PostMapping("/{id}/result")
    public Mono<Result<TranscodeJobVO>> reportResult(@PathVariable Long id,
                                                     @RequestParam String result,
                                                     @RequestParam(required = false) String outputPath,
                                                     @RequestParam(required = false) String errorMsg) {
        return transcodeJobAppService.reportResult(id, result, outputPath, errorMsg)
                .map(TranscodeJobVoConverter::toVo)
                .map(Result::ok);
    }

    /** 任务的执行记录列表：第几次跑、哪台节点领的、几点开始/结束。 */
    @GetMapping("/{id}/attempts")
    public Mono<Result<List<JobAttemptVO>>> attempts(@PathVariable Long id) {
        return transcodeJobAppService.listAttempts(id)
                .map(attempts -> attempts.stream()
                        .map(JobAttemptVoConverter::toVo)
                        .collect(Collectors.toList()))
                .map(Result::ok);
    }

    @GetMapping("/{id}")
    public Mono<Result<TranscodeJobVO>> get(@PathVariable Long id) {
        return transcodeJobAppService.get(id)
                .map(TranscodeJobVoConverter::toVo)
                .map(Result::ok);
    }

    /** 分页翻查：任务编号精确，状态/部门/素材/档位精确；条件都可空，行里带任务编号。 */
    @GetMapping("/page")
    public Mono<Result<PageVO<TranscodeJobVO>>> page(@RequestParam(defaultValue = "1") int pageNum,
                                                     @RequestParam(defaultValue = "20") int pageSize,
                                                     @RequestParam(required = false) String jobNo,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String ownerDept,
                                                     @RequestParam(required = false) Long assetId,
                                                     @RequestParam(required = false) Long profileId) {
        return transcodeJobAppService.page(pageNum, pageSize, jobNo, status,
                        ownerDept, assetId, profileId)
                .map(TranscodeJobVoConverter::toPageVo)
                .map(Result::ok);
    }
}

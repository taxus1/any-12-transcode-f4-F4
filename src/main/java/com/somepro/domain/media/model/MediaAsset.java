package com.somepro.domain.media.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 素材聚合根（media 上下文）：一条上传进来的原始音视频/图片文件档案。
 *
 * 纯领域对象：只描述业务与不变量，不带任何持久化注解（表映射在基础设施层的 MediaAssetPO）。
 *
 * 不变量：
 * - 素材编号 assetCode 必填且全局唯一（唯一性由应用层查重 + 数据库 uk_asset_code 兜底）；
 * - 文件名、媒体类型、归属部门必填；
 * - 时长只对音视频有意义，图片没有时长，可以不填；
 * - 新登记的素材状态一律 READY（可转码）。
 */
@Getter
@Setter
public class MediaAsset extends BaseEntity {

    private Long id;

    /** 素材编号，全局唯一（形如 MA-2026-0001）。 */
    private String assetCode;

    /** 上传时的原始文件名（带扩展名）。 */
    private String fileName;

    private MediaType mediaType;

    /** 扩展名（mp3/mp4/png 这类）。 */
    private String fileExt;

    /** 文件大小（字节）。 */
    private Long sizeBytes;

    /** 时长（毫秒）；图片没有时长，可以不填。 */
    private Long durationMs;

    /** 内容校验值。 */
    private String checksum;

    /** 归属部门：配额与台账按它归集。 */
    private String ownerDept;

    private AssetStatus status;

    /** 工厂方法：登记素材。新素材一律 READY（可转码），并保证初始不变量。 */
    public static MediaAsset register(String assetCode, String fileName, String mediaType,
                                      String fileExt, Long sizeBytes, Long durationMs,
                                      String checksum, String ownerDept) {
        MediaAsset asset = new MediaAsset();
        asset.setAssetCode(assetCode);
        asset.setFileName(fileName);
        asset.setMediaType(MediaType.of(mediaType));
        asset.setFileExt(fileExt);
        asset.setSizeBytes(sizeBytes);
        asset.setDurationMs(durationMs);
        asset.setChecksum(checksum);
        asset.setOwnerDept(ownerDept);
        asset.setStatus(AssetStatus.READY);
        asset.validate();
        return asset;
    }

    /**
     * 领域行为：修改登记信息（编号允许改，改后的查重由应用层负责）。
     * status 传 null/空白表示不动当前状态。
     */
    public void revise(String assetCode, String fileName, String mediaType, String fileExt,
                       Long sizeBytes, Long durationMs, String checksum, String ownerDept,
                       String status) {
        this.assetCode = assetCode;
        this.fileName = fileName;
        this.mediaType = MediaType.of(mediaType);
        this.fileExt = fileExt;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.checksum = checksum;
        this.ownerDept = ownerDept;
        if (status != null && !status.isBlank()) {
            this.status = AssetStatus.of(status);
        }
        validate();
    }

    /**
     * 领域行为：素材进入转码中（任务被节点领取时）。
     *
     * READY → TRANSCODING；同一素材不同档位的任务可能并行，已在 TRANSCODING 的幂等放行；
     * DONE（已完成）/ DISABLED（已停用）不允许再进转码。
     */
    public void startTranscoding() {
        if (status == AssetStatus.DONE) {
            throw new BizException("素材已完成转码，不能再进入转码中：" + id);
        }
        if (status == AssetStatus.DISABLED) {
            throw new BizException("素材已停用，不能进入转码中：" + id);
        }
        this.status = AssetStatus.TRANSCODING;
    }

    /**
     * 领域行为：转码失败，素材退回可转码（READY），之后可重新提交。
     *
     * 只有 TRANSCODING 能退回；其他状态说明素材已被别的流程改动，直接挡回。
     */
    public void returnToReady() {
        if (status != AssetStatus.TRANSCODING) {
            throw new BizException("只有转码中（TRANSCODING）的素材才能退回可转码，当前状态：" + status);
        }
        this.status = AssetStatus.READY;
    }

    /** 聚合不变量：任何进入/离开领域的状态都要过这道校验。 */
    private void validate() {
        if (assetCode == null || assetCode.isBlank()) {
            throw new BizException("素材编号不能为空");
        }
        this.assetCode = assetCode.trim();
        if (fileName == null || fileName.isBlank()) {
            throw new BizException("文件名不能为空");
        }
        this.fileName = fileName.trim();
        if (mediaType == null) {
            throw new BizException("媒体类型不能为空（AUDIO/VIDEO/IMAGE）");
        }
        if (ownerDept == null || ownerDept.isBlank()) {
            throw new BizException("归属部门不能为空");
        }
        this.ownerDept = ownerDept.trim();
        if (sizeBytes != null && sizeBytes < 0) {
            throw new BizException("文件大小不能为负数");
        }
        if (durationMs != null && durationMs < 0) {
            throw new BizException("时长不能为负数");
        }
    }
}

package com.somepro.domain.media.model;

import com.somepro.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MediaAsset 转码状态流转单测（纯领域，不依赖 Spring / DB）。
 */
class MediaAssetTest {

    private MediaAsset readyAsset() {
        MediaAsset asset = MediaAsset.register("MA-2026-0001", "a.mp4", "VIDEO",
                "mp4", 1024L, 60000L, null, "技术部");
        asset.setId(1L);
        return asset;
    }

    @Test
    void startTranscodingShouldMoveReadyToTranscoding() {
        MediaAsset asset = readyAsset();

        asset.startTranscoding();

        assertEquals(AssetStatus.TRANSCODING, asset.getStatus());
    }

    @Test
    void startTranscodingShouldBeIdempotentWhenAlreadyTranscoding() {
        // 同素材不同档位的任务可能并行：已在转码中的素材再进转码，幂等放行
        MediaAsset asset = readyAsset();
        asset.startTranscoding();

        asset.startTranscoding();

        assertEquals(AssetStatus.TRANSCODING, asset.getStatus());
    }

    @Test
    void startTranscodingShouldRejectDoneAndDisabled() {
        MediaAsset done = readyAsset();
        done.setStatus(AssetStatus.DONE);
        assertThrows(BizException.class, done::startTranscoding);

        MediaAsset disabled = readyAsset();
        disabled.setStatus(AssetStatus.DISABLED);
        assertThrows(BizException.class, disabled::startTranscoding);
    }

    @Test
    void returnToReadyShouldMoveTranscodingBackToReady() {
        MediaAsset asset = readyAsset();
        asset.startTranscoding();

        asset.returnToReady();

        assertEquals(AssetStatus.READY, asset.getStatus());
    }

    @Test
    void returnToReadyShouldRejectNonTranscoding() {
        // 只有转码中的素材能退回；其他状态说明素材已被别的流程改动
        MediaAsset asset = readyAsset();
        assertThrows(BizException.class, asset::returnToReady);
        assertEquals(AssetStatus.READY, asset.getStatus());
    }
}

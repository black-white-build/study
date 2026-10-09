package com.heartpilot.module.agent.service;

import java.time.Instant;
import java.util.List;

/** 氛围图检索服务。 按任务目标从免费图库（如 Unsplash）检索氛围图片，用于在行动报告/页面中配图， 并记录图片来源与许可信息，确保商用合规。 */
public interface AmbienceImageService {
    /**
     * 检索氛围图片。
     *
     * @param objective 任务目标，作为图片检索关键词
     * @param limit 期望返回的图片数量
     * @return 检索结果（图片列表、来源状态、提示等）
     */
    SearchResult search(String objective, int limit);

    /**
     * 氛围图检索结果。
     *
     * @param provider 图片来源提供方
     * @param query 实际使用的检索词
     * @param images 图片列表
     * @param sourceStatus 来源数据状态（实时/降级等）
     * @param notice 额外提示
     * @param searchedAt 检索时间
     */
    public record SearchResult(
            String provider,
            String query,
            List<AmbienceImage> images,
            String sourceStatus,
            String notice,
            Instant searchedAt) {
        // 紧凑构造器：images 为 null 时兜底为空列表，避免空指针
        public SearchResult {
            images = images == null ? List.of() : images;
        }
    }

    /**
     * 单张氛围图及其版权信息。
     *
     * @param id 图片 ID
     * @param imageUrl 原图地址
     * @param thumbnailUrl 缩略图地址
     * @param pageUrl 图片详情页地址（版权归属页）
     * @param photographer 摄影师署名
     * @param photographerUrl 摄影师主页
     * @param alt 图片替代文本
     * @param averageColor 主色调（用于前端占位渲染）
     * @param width 原图宽
     * @param height 原图高
     * @param licenseName 许可协议名称
     * @param licenseUrl 许可协议链接
     * @param attributionRequired 是否必须署名
     * @param usageNotice 使用注意事项
     * @param licenseCheckedAt 许可信息核验时间
     */
    public record AmbienceImage(
            long id,
            String imageUrl,
            String thumbnailUrl,
            String pageUrl,
            String photographer,
            String photographerUrl,
            String alt,
            String averageColor,
            int width,
            int height,
            String licenseName,
            String licenseUrl,
            boolean attributionRequired,
            String usageNotice,
            Instant licenseCheckedAt) {}
}

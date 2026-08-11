package com.heartpilot.module.agent.service;

import java.time.Instant;
import java.util.List;

public interface AmbienceImageService {
    SearchResult search(String objective, int limit);

    public record SearchResult(
            String provider,
            String query,
            List<AmbienceImage> images,
            String sourceStatus,
            String notice,
            Instant searchedAt) {
        public SearchResult {
            images = images == null ? List.of() : images;
        }
    }

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

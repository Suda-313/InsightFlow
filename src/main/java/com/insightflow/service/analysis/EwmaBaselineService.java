package com.insightflow.service.analysis;

import com.insightflow.entity.IssueBaselineProfile;
import com.insightflow.repository.IssueBaselineProfileRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

/**
 * EWMA 基线服务：按日增量更新基线并分类，对齐参考项目 baseline.py。
 */
@Component
public class EwmaBaselineService {

    private final IssueBaselineProfileRepository profileRepository;
    private final double alpha;
    private final int minHistoryDays;
    private final double surgeZ;
    private final int surgeMin;
    private final double chronicBaseline;
    private final int longtailMax;

    public EwmaBaselineService(IssueBaselineProfileRepository profileRepository,
                               double alpha, int minHistoryDays,
                               double surgeZ, int surgeMin,
                               double chronicBaseline, int longtailMax) {
        this.profileRepository = profileRepository;
        this.alpha = alpha;
        this.minHistoryDays = minHistoryDays;
        this.surgeZ = surgeZ;
        this.surgeMin = surgeMin;
        this.chronicBaseline = chronicBaseline;
        this.longtailMax = longtailMax;
    }

    /**
     * 按日增量更新 EWMA 基线并返回最新 profile。
     *
     * @param workspaceId 一级租户隔离键
     * @param issueId     主题目录内部主键
     * @param bucketStart 当前桶起始时间
     * @param todayCount  当日反馈数
     * @return 更新后的基线 profile
     */
    public IssueBaselineProfile update(Long workspaceId, Long issueId,
                                       OffsetDateTime bucketStart, int todayCount) {
        return profileRepository.findByWorkspaceIdAndIssueId(workspaceId, issueId)
                .map(existing -> {
                    if (existing.isSameBucket(bucketStart)) {
                        return existing;
                    }
                    String classification = classify(existing, todayCount);
                    existing.updateEwma(alpha, todayCount, bucketStart, minHistoryDays, classification);
                    return profileRepository.save(existing);
                })
                .orElseGet(() -> {
                    IssueBaselineProfile created = IssueBaselineProfile.create(
                            workspaceId, issueId, bucketStart, todayCount, minHistoryDays);
                    created.setInitialClassification(classifyNew(todayCount));
                    return profileRepository.save(created);
                });
    }

    /**
     * 二维象限分类（对齐 baseline.py）。
     *
     * @param profile    当前基线（调用前未更新）
     * @param todayCount 当日反馈数
     * @return surge / escalating / chronic / longtail / normal
     */
    public String classify(IssueBaselineProfile profile, int todayCount) {
        int activeDays = profile.getActiveBuckets();
        double ewma = profile.getBaselineEwma();
        double std = Math.max(profile.baselineStddev(), 1.0);
        double z = (todayCount - ewma) / std;

        if (activeDays < minHistoryDays) {
            if (todayCount >= surgeMin) {
                return "surge";
            }
            if (todayCount <= longtailMax) {
                return "longtail";
            }
            return "normal";
        }

        if (ewma >= chronicBaseline && z < surgeZ) {
            return "chronic";
        }
        if (z >= surgeZ && todayCount >= surgeMin) {
            return ewma >= chronicBaseline ? "escalating" : "surge";
        }
        if (todayCount <= longtailMax && ewma < chronicBaseline) {
            return "longtail";
        }
        return "normal";
    }

    private String classifyNew(int todayCount) {
        if (todayCount >= surgeMin) {
            return "surge";
        }
        if (todayCount <= longtailMax) {
            return "longtail";
        }
        return "normal";
    }

    public double getAlpha() {
        return alpha;
    }

    public int getMinHistoryDays() {
        return minHistoryDays;
    }

    public double getSurgeZ() {
        return surgeZ;
    }

    public int getSurgeMin() {
        return surgeMin;
    }
}

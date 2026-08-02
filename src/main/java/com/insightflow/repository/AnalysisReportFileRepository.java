package com.insightflow.repository;

import com.insightflow.entity.AnalysisReportFile;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 分析报告与来源文件关联的持久化端口。
 */
public interface AnalysisReportFileRepository extends JpaRepository<AnalysisReportFile, Long> {
}

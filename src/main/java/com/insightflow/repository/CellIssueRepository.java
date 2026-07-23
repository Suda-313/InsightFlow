package com.insightflow.repository;

import com.insightflow.entity.CellIssue;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cell-主题计数持久化端口；唯一约束 (data_cell_id, issue_id) 防重复。 */
public interface CellIssueRepository extends JpaRepository<CellIssue, Long> {
    List<CellIssue> findByDataCellId(Long dataCellId);
    List<CellIssue> findByIssueId(Long issueId);
}

package com.insightflow.repository;

import com.insightflow.entity.DataCell;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data Cell 持久化端口；按 projection 查询用于幂等守卫。 */
public interface DataCellRepository extends JpaRepository<DataCell, Long> {
    List<DataCell> findByWorkspaceProjectionIdAndWorkspaceId(Long workspaceProjectionId, Long workspaceId);
}

package com.insightflow.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.insightflow.entity.IssueAlias;
import com.insightflow.entity.IssueCatalog;
import com.insightflow.repository.IssueAliasRepository;
import com.insightflow.repository.IssueCatalogRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** find-or-create 必须幂等；既有主题只刷新末次出现，不重复创建。 */
class IssueCatalogServiceTest {

    /** 主题不存在时，应新建并持久化。 */
    @Test
    void createsWhenAbsent() {
        IssueCatalogRepository catalogRepo = mock(IssueCatalogRepository.class);
        IssueAliasRepository aliasRepo = mock(IssueAliasRepository.class);
        when(catalogRepo.findByWorkspaceIdAndCanonicalKey(7L, "login_failure"))
                .thenReturn(Optional.empty());
        when(catalogRepo.save(any(IssueCatalog.class))).thenAnswer(inv -> inv.getArgument(0));
        IssueCatalogService service = new IssueCatalogService(catalogRepo, aliasRepo);

        IssueCatalog result = service.findOrCreate(7L, "login_failure", "登录失败");

        assertThat(result.getCanonicalKey()).isEqualTo("login_failure");
        verify(catalogRepo).save(any(IssueCatalog.class));
    }

    /** 主题已存在时，应复用原对象、不重复创建/保存。 */
    @Test
    void reusesWhenPresent() {
        IssueCatalogRepository catalogRepo = mock(IssueCatalogRepository.class);
        IssueAliasRepository aliasRepo = mock(IssueAliasRepository.class);
        IssueCatalog existing = IssueCatalog.create(7L, "login_failure", "登录失败");
        when(catalogRepo.findByWorkspaceIdAndCanonicalKey(7L, "login_failure"))
                .thenReturn(Optional.of(existing));
        IssueCatalogService service = new IssueCatalogService(catalogRepo, aliasRepo);

        IssueCatalog result = service.findOrCreate(7L, "login_failure", "登录失败");

        assertThat(result).isSameAs(existing);
        verify(catalogRepo, never()).save(any(IssueCatalog.class));
    }

    /** 同一别名只应写入一次；第二次查询已存在则跳过。 */
    @Test
    void recordsAliasOnlyOnce() {
        IssueCatalogRepository catalogRepo = mock(IssueCatalogRepository.class);
        IssueAliasRepository aliasRepo = mock(IssueAliasRepository.class);
        when(aliasRepo.existsByWorkspaceIdAndNormalizedAlias(7L, "登录失败")).thenReturn(false, true);
        IssueCatalogService service = new IssueCatalogService(catalogRepo, aliasRepo);

        service.recordAliasIfNeeded(7L, 11L, "登录失败");
        service.recordAliasIfNeeded(7L, 11L, "登录失败");

        verify(aliasRepo, times(1)).save(any(IssueAlias.class));
    }
}

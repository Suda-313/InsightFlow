package com.insightflow.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 主题目录工厂必须固定 active 状态并生成 UUIDv7。 */
class IssueCatalogEntityTest {

    @Test
    void createSetsActiveAndUuid() {
        IssueCatalog catalog = IssueCatalog.create(7L, "login_failure", "登录失败");

        assertThat(catalog.getCanonicalKey()).isEqualTo("login_failure");
        assertThat(catalog.getStatus()).isEqualTo("active");
        assertThat(catalog.getFirstSeenAt()).isEqualTo(catalog.getLastSeenAt());
    }
}

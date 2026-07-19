package com.insightflow.importing.application;

import java.util.UUID;

/**
 * 表示给定 Workspace 内不存在请求的文件资源。
 *
 * <p>不区分“文件属于别人”和“文件根本不存在”，避免以错误信息泄漏跨 Workspace 资源存在性。</p>
 */
public class ImportFileNotFoundException extends RuntimeException {

    /**
     * 只回显对外 UUID，不携带内部主键或对象存储路径。
     */
    public ImportFileNotFoundException(UUID publicId) {
        super("Import file not found: " + publicId);
    }
}

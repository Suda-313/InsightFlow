package com.insightflow.importing.application;

import java.util.List;

/**
 * 映射、CSV 结构或任务状态不满足导入前置条件时抛出的受控业务异常。
 *
 * <p>错误只描述字段、列名或行号，不能携带原始反馈文本、邮箱、手机号和对象存储异常详情。</p>
 */
public class ImportValidationException extends RuntimeException {

    /**
     * 可返回给前端的有限字段错误集合，供映射页进行定点提示。
     */
    private final List<FieldError> fieldErrors;

    /**
     * 创建无字段级错误的状态或文件类型校验异常。
     */
    public ImportValidationException(String message) {
        this(message, List.of());
    }

    /**
     * 创建带字段级错误的映射校验异常，列表由服务端生成且不含原始数据。
     */
    public ImportValidationException(String message, List<FieldError> fieldErrors) {
        super(message);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    /**
     * 返回安全错误项，REST 异常处理器将其投影为标准 422 契约。
     */
    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }

    /**
     * 单个字段或映射键的失败原因；不保存用户上传的值。
     */
    public record FieldError(String field, String reason) {
    }
}

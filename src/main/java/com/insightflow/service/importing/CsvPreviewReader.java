package com.insightflow.service.importing;

import com.insightflow.common.exception.ImportValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 使用 Apache Commons CSV 读取受控表头和少量脱敏样例。
 *
 * <p>预览不会持久化原始单元格，也不会返回完整 CSV；它只服务于用户确认字段映射，样本值统一
 * 经 {@link PiiSanitizer} 处理。</p>
 */
@Component
public class CsvPreviewReader {

    /**
     * 复用统一 PII 规则，使预览和真正写库的文本脱敏口径一致。
     */
    private final PiiSanitizer piiSanitizer;

    /**
     * 限制预览行数，避免用户通过预览接口绕过文件访问边界读取完整原文。
     */
    private final int previewRowLimit;

    /**
     * 共享格式支持让预览和异步 Worker 对 BOM、空表头与重复表头使用完全相同的规则。
     */
    private final CsvFormatSupport csvFormatSupport;

    /**
     * 构造读取器；行数来自配置以支持演示环境调整而非散落的魔法数字。
     */
    @Autowired
    public CsvPreviewReader(
            PiiSanitizer piiSanitizer,
            CsvFormatSupport csvFormatSupport,
            @Value("${insightflow.import.preview-row-limit}") int previewRowLimit) {
        this.piiSanitizer = piiSanitizer;
        this.csvFormatSupport = csvFormatSupport;
        this.previewRowLimit = previewRowLimit;
    }

    /**
     * 保留既有直接构造入口，避免已有调用方和测试因内部格式支持抽取而发生二进制不兼容。
     */
    public CsvPreviewReader(PiiSanitizer piiSanitizer, int previewRowLimit) {
        this(piiSanitizer, new CsvFormatSupport(), previewRowLimit);
    }

    /**
     * 读取 UTF-8 CSV 的表头与受控样例；重复/空表头会被解析器拒绝，不能进入映射步骤。
     */
    public CsvPreview preview(InputStream inputStream) {
        try (CSVParser parser = csvFormatSupport.parse(inputStream)) {
            List<String> headers = csvFormatSupport.validateHeaders(parser.getHeaderNames());
            List<Map<String, String>> samples = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (samples.size() >= previewRowLimit) {
                    break;
                }
                Map<String, String> sample = new java.util.LinkedHashMap<>();
                for (int index = 0; index < headers.size(); index++) {
                    sample.put(headers.get(index), piiSanitizer.sanitize(record.get(index)));
                }
                samples.add(sample);
            }
            return new CsvPreview(headers, samples);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ImportValidationException("CSV 无法解析，请确认其为 UTF-8 编码且格式正确。");
        }
    }

    /**
     * API 和映射校验共用的受控预览值对象，不包含原始文件引用。
     */
    public record CsvPreview(List<String> headers, List<Map<String, String>> samples) {
    }
}

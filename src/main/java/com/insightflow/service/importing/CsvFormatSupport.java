package com.insightflow.service.importing;

import com.insightflow.common.exception.ImportValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.stereotype.Component;

/**
 * 集中定义预览与异步 Worker 共用的 UTF-8 CSV 解析和表头校验规则。
 *
 * <p>解析器允许读取重复表头，再由本类给出统一业务错误；这样不会因为库默认值变化而在预览和实际
 * 导入之间产生不同的列覆盖行为。</p>
 */
@Component
public class CsvFormatSupport {

    /**
     * 用固定格式打开 UTF-8 CSV；调用方必须关闭返回的 parser，以同时关闭底层 reader 和输入流。
     */
    public CSVParser parse(InputStream inputStream) throws IOException {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL)
                .get()
                .parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * 归一化并验证表头：去除 BOM 后必须非空且唯一，映射和 Worker 均不能静默覆盖同名列。
     */
    public List<String> validateHeaders(List<String> rawHeaders) {
        List<String> headers = rawHeaders.stream().map(this::stripBom).toList();
        Set<String> seen = new HashSet<>();
        if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank) || headers.stream().anyMatch(header -> !seen.add(header))) {
            throw new ImportValidationException("CSV 表头不能为空且不得重复。");
        }
        return headers;
    }

    /**
     * 根据校验后的同一序列构建列名到位置的索引，记录读取始终按数值位置而不是易覆盖的原始名称。
     */
    public Map<String, Integer> buildHeaderIndexes(List<String> rawHeaders) {
        List<String> headers = validateHeaders(rawHeaders);
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            indexes.put(headers.get(index), index);
        }
        return indexes;
    }

    /**
     * Windows Excel 生成的 UTF-8 CSV 可能在第一个表头带 BOM，统一在进入任何映射前移除。
     */
    private String stripBom(String header) {
        return header != null && header.startsWith("\uFEFF") ? header.substring(1) : header;
    }
}

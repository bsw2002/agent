package org.suvia.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 classpath 下的 PDF 加载为 Spring AI {@link Document} 列表。
 * <p>
 * 约定文件名格式：<b>作者_标题_期刊.pdf</b>（标题中若含下划线，会整体保留在中间段）。
 * Spring AI 1.0.0-M6 的 {@link PdfDocumentReaderConfig} 尚不支持 withAdditionalMetadata，
 * 因此在 {@link PagePdfDocumentReader#get()} 之后，为每个 Document 合并文献元数据。
 * <p>
 * 典型后续步骤（在配置类或独立服务里串联，勿改你现有 Markdown 流程时可另写 Bean）：
 * <ol>
 *   <li>{@code List<Document> raw = myPagePdfDocumentReader.loadPdfsFromClasspath();}</li>
 *   <li>{@code List<Document> chunks = myTokenTextSplitter.splitDocuments(raw);} // 建议 pages-per-document=0 整本再切，避免「一页一块」</li>
 *   <li>{@code vectorStore.add(chunks);} 或先 {@code myKeywordEnricher.enrichDocuments(chunks)} 再写入</li>
 * </ol>
 */
@Component
@Slf4j
public class MyPagePdfDocumentReader {

    /** 向量库/过滤检索时常用的元数据键（英文，便于与多数向量库兼容） */
    public static final String META_AUTHOR = "author";
    public static final String META_TITLE = "title";
    public static final String META_JOURNAL = "journal";
    public static final String META_SOURCE_FILENAME = "source_filename";

    private final ResourcePatternResolver resourcePatternResolver;

    private final PdfTextCleaner pdfTextCleaner = new PdfTextCleaner();

    /**
     * Spring AI：每多少个 PDF 页合并成一个 {@link Document}。<b>0 表示整本 PDF 合并为一条</b>，再交给文本切分器按字数切块，
     * 块数约等于「总字数 ÷ chunk-size」，而不会像 {@code 1} 那样「一页一块」导致 300 页≈300 块。
     * 特大 PDF 若担心内存，可设为 15～30 页一批。
     */
    @Value("${suvia.rag.pdf.pages-per-document:0}")
    private int pagesPerDocument;

    MyPagePdfDocumentReader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 读取 {@code classpath:document/*.pdf}，按 {@link #pagesPerDocument} 合并页后生成 Document，并附加从文件名解析的文献信息。
     * <p>
     * PDF Reader 会写入页码等元数据；此处再写入 author / title / journal，切分后仍会保留在各块上。
     */
    public List<Document> loadPdfsFromClasspath() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.pdf");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                Map<String, String> literature = parseLiteratureFilename(fileName);

                PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0)
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(0)
                                .build())
                        .withPagesPerDocument(pagesPerDocument)
                        .build();

                PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
                List<Document> pageDocs = reader.get();
                int total = pageDocs.size();
                int idx = 0;
                
                for (Document doc : pageDocs) {
                    String cleanedText = pdfTextCleaner.clean(doc.getText());
                    if (cleanedText == null || cleanedText.isBlank()) {
                        continue;
                    }
                   

                    Document cleanedDoc = Document.builder()
                            .id(doc.getId())
                            .text(cleanedText)
                            .metadata(doc.getMetadata())
                            .build();
                    allDocuments.add(mergeLiteratureMetadata(cleanedDoc, fileName, literature));
                }
            }
        } catch (IOException e) {
            log.error("PDF 文档加载失败", e);
        }

        return allDocuments;
    }

    /**
     * 从标准文件名 {@code 作者_标题_期刊.pdf} 解析三段信息。
     * <ul>
     *   <li>第一段：作者</li>
     *   <li>最后一段：期刊</li>
     *   <li>中间所有段（以下划线重新拼接）：标题（允许标题内含下划线）</li>
     * </ul>
     * 若分段不足 3 段，则将整段去掉后缀作为 title，author/journal 为空，避免误解析。
     */
    static Map<String, String> parseLiteratureFilename(String filename) {
        Map<String, String> out = new HashMap<>();
        if (filename == null || filename.isBlank()) {
            out.put(META_AUTHOR, "");
            out.put(META_TITLE, "");
            out.put(META_JOURNAL, "");
            return out;
        }
        String lower = filename.toLowerCase();
        String base = lower.endsWith(".pdf") ? filename.substring(0, filename.length() - 4) : filename;
        String[] parts = base.split("_", -1);
        if (parts.length < 3) {
            out.put(META_AUTHOR, "");
            out.put(META_TITLE, base);
            out.put(META_JOURNAL, "");
            return out;
        }
        String author = parts[0];
        String journal = parts[parts.length - 1];
        String title = String.join("_", Arrays.copyOfRange(parts, 1, parts.length - 1));
        out.put(META_AUTHOR, author);
        out.put(META_TITLE, title);
        out.put(META_JOURNAL, journal);
        return out;
    }

    /**
     * 保留 PDF Reader 原有 metadata（页码等），并合并文献字段；使用新 Document 避免修改不可变 map 带来的问题。
     */
    private static Document mergeLiteratureMetadata(Document doc, String fileName, Map<String, String> literature) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put(META_SOURCE_FILENAME, fileName != null ? fileName : "");
        meta.put(META_AUTHOR, literature.getOrDefault(META_AUTHOR, ""));
        meta.put(META_TITLE, literature.getOrDefault(META_TITLE, ""));
        meta.put(META_JOURNAL, literature.getOrDefault(META_JOURNAL, ""));

        return Document.builder()
                .id(doc.getId())
                .text(doc.getText())
                .metadata(meta)
                .build();
    }
}

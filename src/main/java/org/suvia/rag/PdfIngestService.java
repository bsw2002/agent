package org.suvia.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传成功后：从 MultipartFile 直接读取 PDF -> 清洗正文 -> 语义分割 -> 写入 PgVector
 * （不依赖 classpath:document/*.pdf）
 */
@Service
public class PdfIngestService {

    private final VectorStore vectorStore;
    private final MyTokenTextSplitter tokenTextSplitter;

    private final PdfTextCleaner pdfTextCleaner = new PdfTextCleaner();

    // 和 MyPagePdfDocumentReader 保持一致：0 表示整本合并后再切块（块数更少）
    private final int pagesPerDocument;

    public PdfIngestService(VectorStore vectorStore, MyTokenTextSplitter tokenTextSplitter) {
        this.vectorStore = vectorStore;
        this.tokenTextSplitter = tokenTextSplitter;
        // 这里用固定值也可以；你也可以改成从配置读取（与 MyPagePdfDocumentReader 同一个 key）
        this.pagesPerDocument = 0;
    }

    /**
     * @param file 上传的 PDF 文件
     * @param originalFilename 用于解析 author/title/journal（优先用 originalFilename，避免 MinIO objectName 乱码影响解析）
     */
    public void ingestUploadedPdf(MultipartFile file, String originalFilename) {
        if (file == null || file.isEmpty()) return;

        String filenameForMeta = (originalFilename == null || originalFilename.isBlank())
                ? "unknown.pdf"
                : originalFilename;

        // 解析 author/title/journal
        Map<String, String> literature = MyPagePdfDocumentReader.parseLiteratureFilename(filenameForMeta);

        // 用字节数组构造 Resource，直接喂给 Spring AI 的 PagePdfDocumentReader
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败: " + e.getMessage(), e);
        }

        Resource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filenameForMeta;
            }
        };

        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageTopMargin(0)
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        .withNumberOfTopTextLinesToDelete(0)
                        .build())
                .withPagesPerDocument(pagesPerDocument)
                .build();

        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
        List<Document> rawDocs = reader.get();

        List<Document> cleanedDocs = new ArrayList<>();
        for (Document doc : rawDocs) {
            String cleanedText = pdfTextCleaner.clean(doc.getText());
            if (cleanedText == null || cleanedText.isBlank()) continue;

            Map<String, Object> meta = new HashMap<>(doc.getMetadata());
            meta.put(MyPagePdfDocumentReader.META_SOURCE_FILENAME, filenameForMeta);
            meta.put(MyPagePdfDocumentReader.META_AUTHOR, literature.getOrDefault(MyPagePdfDocumentReader.META_AUTHOR, ""));
            meta.put(MyPagePdfDocumentReader.META_TITLE, literature.getOrDefault(MyPagePdfDocumentReader.META_TITLE, ""));
            meta.put(MyPagePdfDocumentReader.META_JOURNAL, literature.getOrDefault(MyPagePdfDocumentReader.META_JOURNAL, ""));

            Document cleanedDoc = Document.builder()
                    .id(doc.getId())
                    .text(cleanedText)
                    .metadata(meta)
                    .build();

            cleanedDocs.add(cleanedDoc);
        }

        if (cleanedDocs.isEmpty()) return;

        // 语义分割 + 写入向量库
        List<Document> chunks = tokenTextSplitter.splitDocuments(cleanedDocs);
        if (!chunks.isEmpty()) {
            vectorStore.add(chunks);
        }
    }
}
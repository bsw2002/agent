package org.suvia.tools;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.suvia.tools.result.ToolResult;
import org.suvia.tools.security.SafePathResolver;
import org.suvia.tools.security.ToolPolicyViolationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public class PDFGenerationTool {

    private final SafePathResolver paths;

    public PDFGenerationTool(SafePathResolver workspacePaths) {
        this.paths = new SafePathResolver(workspacePaths.root().resolve("pdf"));
    }

    @Tool(description = "Generate a PDF file with given content")
    public ToolResult<Map<String, Object>> generatePDF(
            @ToolParam(description = "Workspace-relative .pdf file name") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        try {
            if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                return ToolResult.denied("INVALID_PDF_PATH", "The output file must use the .pdf extension");
            }
            Path file = paths.resolve(fileName);
            Files.createDirectories(file.getParent());

            try (PdfWriter writer = new PdfWriter(file.toString());
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                document.setFont(font);
                document.add(new Paragraph(content == null ? "" : content));
            }
            return ToolResult.success(Map.of(
                    "path", fileName,
                    "sizeBytes", Files.size(file)
            ));
        } catch (ToolPolicyViolationException e) {
            return ToolResult.denied(e.getCode(), e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("PDF_GENERATION_FAILED", "Unable to generate the PDF", false);
        }
    }
}

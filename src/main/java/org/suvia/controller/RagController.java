package org.suvia.controller;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.suvia.common.BaseResponse;
import org.suvia.common.ResultUtils;
import org.suvia.exception.ErrorCode;
import org.suvia.minio.PdfStorageService;
import org.suvia.rag.PdfIngestService;

@RestController
@RequestMapping("/rag")
public class RagController {

    @Resource
    private PdfStorageService pdfStorageService;

    @Resource
    private PdfIngestService pdfIngestService;

    /**
     * 上传 PDF 到 MinIO：
     * - 先按 SHA-256 查重，重复则不上传、不入库
     * - 不重复则上传并返回 objectName
     */
    @PostMapping("/upload")
    public BaseResponse<String> uploadPdf(@RequestParam("objectName") String objectName,
                                          @RequestParam("file") MultipartFile file) {

        // 1. 先修正 objectName 编码
        String fixedObjectName = org.suvia.util.EncodingUtils.fixEncodingIfNeeded(objectName);
        // 2. MinIO 用修正后的名字
        String savedObjectName = pdfStorageService.uploadIfNotDuplicate(fixedObjectName, file);

        if (savedObjectName == null) {
            return new BaseResponse<>(ErrorCode.OPERATION_ERROR.getCode(), null, "重复文件：已存在，跳过上传与向量入库");
        }
        pdfIngestService.ingestUploadedPdf(file, objectName);
        return ResultUtils.success("上传成功：" + savedObjectName);
    }
}
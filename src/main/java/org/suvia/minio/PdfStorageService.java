package org.suvia.minio;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.minio.messages.Tags;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
public class PdfStorageService {

    private static final String TAG_SHA256 = "sha256"; // MinIO object tag key

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public PdfStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /** 是否存在同名对象（按 objectName 查重，辅助用） */
    public boolean existsByObjectName(String objectName) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(properties.getBucketName())
                            .object(objectName)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            // NoSuchKey / NoSuchObject => 不存在
            return false;
        } catch (Exception e) {
            throw new RuntimeException("MinIO statObject 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算文件 SHA-256（会读取整个流）。
     * 注意：MultipartFile.getInputStream() 可重复调用，每次都会给你一个新流。
     */
    public String sha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(in, md)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) {
                    // 读完即可，DigestInputStream 会更新 md
                }
            }
            byte[] digest = md.digest();
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("计算 SHA-256 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查重：bucket 内是否已有对象的 tag.sha256 == sha256。
     * 实现方式：遍历 bucket（适合文献量不大时）。若后续数据多，建议改为“hash->objectName”的索引表（Postgres）。
     */
    public boolean existsBySha256(String sha256) {
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                .bucket(properties.getBucketName())
                .recursive(true)
                .build());

            for (Result<Item> r : results) {
                Item item = r.get();
                String objectName = item.objectName();

                Tags tags;
                try {
                    tags = minioClient.getObjectTags(
                            GetObjectTagsArgs.builder()
                                    .bucket(properties.getBucketName())
                                    .object(objectName)
                                    .build()
                    );
                } catch (Exception tagEx) {
                    // 没 tag 或读不了 tag 就跳过
                    continue;
                }

                Map<String, String> map = tags.get();
                if (map != null && sha256.equalsIgnoreCase(map.get(TAG_SHA256))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException("MinIO 按 SHA-256 查重失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传并写入 sha256 tag。
     * @return 实际写入的 objectName
     */
    public String uploadIfNotDuplicate(String objectName, MultipartFile file) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file 不能为空");
        }

        // 1) 先算 SHA-256
        String sha256 = sha256(file);

        // 2) 先按 SHA-256 查重（重复则不做任何操作）
        if (existsBySha256(sha256)) {
            return null; // 用 null 表示重复
        }

        // 3) 再按 objectName 查重（避免同名覆盖）
        if (existsByObjectName(objectName)) {
            return null; // 同名也视为重复/已存在
        }

        // 4) 上传
        try (InputStream in = file.getInputStream()) {
            ObjectWriteResponse resp = minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(properties.getBucketName())
                            .object(objectName)
                            .contentType(file.getContentType() != null ? file.getContentType() : "application/pdf")
                            .stream(in, file.getSize(), -1)
                            .build()
            );

            // 5) 写 tag：sha256
            minioClient.setObjectTags(
                    SetObjectTagsArgs.builder()
                            .bucket(properties.getBucketName())
                            .object(objectName)
                            .tags(Tags.newObjectTags(Map.of(TAG_SHA256, sha256)))
                            .build()
            );

            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("MinIO 上传失败: " + e.getMessage(), e);
        }
    }
}
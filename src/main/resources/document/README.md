# 本地文献目录

将仅用于本地初始化或调试的 PDF 放在此目录。应用会读取 `classpath:document/*.pdf`，但 PDF 文件默认被 Git 忽略，避免意外公开包含个人信息或受版权保护的文献。

知识库也可以通过 `POST /api/rag/upload` 上传 PDF 到 MinIO 并完成向量化。

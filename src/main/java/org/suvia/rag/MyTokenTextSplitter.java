package org.suvia.rag;

import org.checkerframework.checker.units.qual.C;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class MyTokenTextSplitter {


    private final SemanticTextSplitter semanticSplitter;

    MyTokenTextSplitter(SemanticTextSplitter semanticSplitter) {
        this.semanticSplitter = semanticSplitter;
    }


    /**
     * 推荐：中文文献/PDF 使用，按字符与段落切分，元数据会随 {@link Document} 传递到各块。
     */
    public List<Document> splitDocuments(List<Document> documents) {
        return semanticSplitter.apply(documents);
    }

    /**
     * 英文或需与 OpenAI token 对齐的场景可使用默认 {@link TokenTextSplitter}（与 Spring AI 内置默认参数一致）。
     */
    public List<Document> splitDocumentsByEnglishTokenizer(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(documents);
    }

    /**
     * 自定义 token 大小；参数含义与 {@link TokenTextSplitter#TokenTextSplitter(int, int, int, int, boolean)} 一致。
     * 建议使用 {@link TokenTextSplitter#builder()} 并不要把 chunkSize（token）设得过小（建议 ≥ 400，与库默认 800 接近），
     * minChunkLengthToEmbed 不宜过小（库默认 5，若设为 10 一般可接受，但若与极小 chunk 组合仍可能异常）。
     */
    public List<Document> splitCustomized(List<Document> documents) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        return splitter.apply(documents);
    }
}

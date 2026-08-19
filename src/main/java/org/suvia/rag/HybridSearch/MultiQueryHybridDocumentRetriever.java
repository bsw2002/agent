package org.suvia.rag.HybridSearch;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.suvia.demo.rag.myMultiQueryExpander;

import java.util.ArrayList;
import java.util.List;

public class MultiQueryHybridDocumentRetriever implements DocumentRetriever {

    private final HybridRrfDocumentRetriever hybrid;
    private final myMultiQueryExpander expander;
    private final HybridSearchProperties props;

    public MultiQueryHybridDocumentRetriever(
            HybridRrfDocumentRetriever hybrid,
            myMultiQueryExpander expander,
            HybridSearchProperties props
    ) {
        this.hybrid = hybrid;
        this.expander = expander;
        this.props = props;
    }

    @Override
    public List<Document> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }

        List<List<Document>> rankedLists = new ArrayList<>();
        rankedLists.add(hybrid.retrieve(query));

        List<Query> expanded = expander.expand(query);
        if (expanded != null) {
            for (Query sub : expanded) {
                if (sub != null && sub.text() != null && !sub.text().isBlank()) {
                    rankedLists.add(hybrid.retrieve(sub));
                }
            }
        }

        return RrfMergeUtil.fuse(rankedLists, props.getRrfK(), props.getRrfOutputTopK());
    }
}
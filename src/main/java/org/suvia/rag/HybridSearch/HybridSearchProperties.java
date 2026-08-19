package org.suvia.rag.HybridSearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suvia.rag.hybrid")
public class HybridSearchProperties {

    private String schemaName = "public";
    private String tableName = "vector_store";

    private int vectorTopK = 15;
    private int keywordTopK = 15;
    private int rrfOutputTopK = 8;
    private int rrfK = 60;
    private double similarityThreshold = 0.0;
    private boolean multiQueryEnabled = false;

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public int getVectorTopK() {
        return vectorTopK;
    }

    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    public int getKeywordTopK() {
        return keywordTopK;
    }

    public void setKeywordTopK(int keywordTopK) {
        this.keywordTopK = keywordTopK;
    }

    public int getRrfOutputTopK() {
        return rrfOutputTopK;
    }

    public void setRrfOutputTopK(int rrfOutputTopK) {
        this.rrfOutputTopK = rrfOutputTopK;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public boolean isMultiQueryEnabled() {
        return multiQueryEnabled;
    }

    public void setMultiQueryEnabled(boolean multiQueryEnabled) {
        this.multiQueryEnabled = multiQueryEnabled;
    }
}
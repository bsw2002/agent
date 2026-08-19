package org.suvia.demo.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.rag.Query;
import org.springframework.boot.test.context.SpringBootTest;


import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SUVIA_RUN_LIVE_TESTS", matches = "true")
class myMultiQueryExpanderTest {

    @Resource
    private myMultiQueryExpander multiQueryExpander;

    @Test
    void expand() {
        Query query = new Query("谁是suvia啊哈哈哈哈哈？");
        List<Query> queries = multiQueryExpander.expand(query);
        assertNotNull(queries);
    }
}

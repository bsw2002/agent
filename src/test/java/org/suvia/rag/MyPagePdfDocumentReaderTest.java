package org.suvia.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SUVIA_RUN_LIVE_TESTS", matches = "true")
class MyPagePdfDocumentReaderTest {

    @Test
    void loadPdfsFromClasspath() {

    }
}

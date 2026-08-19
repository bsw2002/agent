package org.suvia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SUVIA_RUN_LIVE_TESTS", matches = "true")
class AiAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}

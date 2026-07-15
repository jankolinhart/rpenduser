package com.reelypops.rpenduser;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifies the Spring application context loads correctly.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RpEndUserApplicationTests {

    @Test
    void contextLoads() {
        // Spring Boot context must load without errors
    }
}

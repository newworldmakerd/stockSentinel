package com.stocksentinel;

import com.stocksentinel.common.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {
    @Test void healthIsAvailable() throws Exception {
        MockMvcBuilders.standaloneSetup(new HealthController()).build()
            .perform(get("/api/v1/health"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
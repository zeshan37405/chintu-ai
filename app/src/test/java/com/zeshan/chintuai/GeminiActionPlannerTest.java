package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public final class GeminiActionPlannerTest {
    @Test
    public void disablesThinkingForLowLatencyPhonePlanning() throws Exception {
        JSONObject request = GeminiActionPlanner.buildRequest("فیس بک کھولو");
        JSONObject generation = request.getJSONObject("generationConfig");

        assertEquals(0, generation.getJSONObject("thinkingConfig")
                .getInt("thinkingBudget"));
        assertEquals(800, generation.getInt("maxOutputTokens"));
    }
}

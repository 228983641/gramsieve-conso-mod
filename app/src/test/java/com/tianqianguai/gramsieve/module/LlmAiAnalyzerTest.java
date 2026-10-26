package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LlmAiAnalyzerTest {
    @Test
    public void parseModelIdsReadsOpenAiCompatibleData() {
        List<String> ids = LlmAiAnalyzer.parseModelIds(
                "{\"object\":\"list\",\"data\":["
                        + "{\"id\":\"gpt-4o-mini\",\"object\":\"model\"},"
                        + "{\"id\":\"qwen-plus\",\"object\":\"model\"}"
                        + "]}"
        );

        assertEquals(Arrays.asList("gpt-4o-mini", "qwen-plus"), ids);
    }

    @Test
    public void parseModelIdsRemovesDuplicatesAndTrimsIds() {
        List<String> ids = LlmAiAnalyzer.parseModelIds(
                "{\"data\":["
                        + "{\"id\":\"  qwen-plus  \"},"
                        + "{\"id\":\"qwen-plus\"},"
                        + "{\"id\":\"deepseek-chat\"}"
                        + "]}"
        );

        assertEquals(Arrays.asList("qwen-plus", "deepseek-chat"), ids);
    }

    @Test
    public void parseModelIdsReturnsEmptyForBlankOrMissingIds() {
        assertEquals(Collections.emptyList(), LlmAiAnalyzer.parseModelIds(null));
        assertEquals(Collections.emptyList(), LlmAiAnalyzer.parseModelIds("  "));
        assertEquals(Collections.emptyList(), LlmAiAnalyzer.parseModelIds("{\"data\":[]}"));
        assertTrue(LlmAiAnalyzer.parseModelIds("{\"data\":[{\"object\":\"model\"}]}").isEmpty());
    }

    @Test
    public void parseModelIdsFallsBackToAnyIdWhenDataArrayIsAbsent() {
        List<String> ids = LlmAiAnalyzer.parseModelIds(
                "{\"models\":[{\"id\":\"local-model\"}]}"
        );

        assertEquals(Collections.singletonList("local-model"), ids);
    }
}

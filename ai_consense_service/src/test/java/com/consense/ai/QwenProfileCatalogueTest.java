package com.consense.ai;

import com.consense.config.ConsenseProperties;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline catalogue RED drafted against the current three-argument compatibility constructor. */
class QwenProfileCatalogueTest {
    @Test void bothExplicitQwenProfilesExistWithoutReplacingTheLocalDeployment() {
        ConsenseProperties properties=new ConsenseProperties();
        LlmClient local=mock(LlmClient.class),miniMax=mock(LlmClient.class);
        LlmProfiles profiles=new LlmProfiles(properties,local,miniMax);
        assertTrue(profiles.supported("qwen"));
        assertTrue(profiles.supported("qwen-flash"));
        assertEquals("qwen3.8-27b",profiles.resolve("qwen").getIdentity().getModel());
        assertEquals("qwen3.8-flash",profiles.resolve("qwen-flash").getIdentity().getModel());
        assertSame(local,profiles.resolve(null).getClient());
        assertSame(local,profiles.resolve("local").getClient());
        assertSame(miniMax,profiles.resolve("minimax-cn").getClient());
        assertNull(profiles.resolve("unrecognised-provider"));
        assertFalse(profiles.supported("unrecognised-provider"));
        verifyNoInteractions(local,miniMax);
    }

    @Test void catalogueKeepsFourDistinctSafeIdsAndTheExistingDefault() {
        LlmProfiles profiles=new LlmProfiles(new ConsenseProperties(),mock(LlmClient.class),mock(LlmClient.class));
        Map<String,Object> metadata=profiles.metadata();
        assertEquals("local",metadata.get("defaultProfile"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> choices=(List<Map<String,Object>>)metadata.get("profiles");
        Set<Object> ids=new LinkedHashSet<>();
        for(Map<String,Object> choice:choices){ids.add(choice.get("id"));assertFalse(choice.containsKey("apiKey"));}
        assertEquals(new LinkedHashSet<>(Arrays.asList("local","minimax-cn","qwen","qwen-flash")),ids);
        assertEquals(4,choices.size());
    }
}

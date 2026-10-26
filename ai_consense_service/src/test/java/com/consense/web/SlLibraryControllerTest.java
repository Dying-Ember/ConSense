package com.consense.web;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlLibraryControllerTest {
    @Test void unavailableLibraryDoesNotStartAnythingAndConfiguredBuildIsOneShot(){
        HttpSupport http=mock(HttpSupport.class);SlLibraryController unavailable=new SlLibraryController(http,"");assertEquals("unconfigured",unavailable.status().getData().path("job").path("status").asText());assertThrows(BizException.class,unavailable::build);verifyNoInteractions(http);
        when(http.postJsonBoundedWithoutReplay("http://127.0.0.1:18181/sl-library/build","{}",15_000,null,4*1024*1024,null)).thenReturn("{\"job\":{\"status\":\"running\"}}");
        assertEquals("running",new SlLibraryController(http,"http://127.0.0.1:18181/").build().getData().path("job").path("status").asText());verify(http,times(1)).postJsonBoundedWithoutReplay("http://127.0.0.1:18181/sl-library/build","{}",15_000,null,4*1024*1024,null);
    }
    @Test void searchRequiresExplicitUniqueVersionsAndNeverAcceptsAPathOrArbitraryBackend(){
        HttpSupport http=mock(HttpSupport.class);SlLibraryController controller=new SlLibraryController(http,"http://localhost:18181");SlLibraryController.SearchRequest query=new SlLibraryController.SearchRequest();query.setQuery("COM7 operation force");
        for(List<String> ids:Arrays.asList(Collections.<String>emptyList(),Arrays.asList("batch02","batch02"),Arrays.asList("../private"),Arrays.asList((String)null))){query.setEditionIds(ids);assertThrows(BizException.class,()->controller.search(query));}verifyNoInteractions(http);
        for(String url:Arrays.asList("https://example.com:18181","http://127.0.0.1:18181/private","http://name:secret@127.0.0.1:18181","http://127.0.0.1:18181?path=x"))assertThrows(IllegalArgumentException.class,()->new SlLibraryController(http,url));
        query.setEditionIds(Arrays.asList("competition-batch02"));when(http.postJsonBoundedWithoutReplay(eq("http://localhost:18181/sl-library/search"),anyString(),eq(120_000L),isNull(),eq(4*1024*1024),isNull())).thenReturn("{\"hits\":[]}");assertTrue(controller.search(query).getData().path("hits").isArray());verify(http).postJsonBoundedWithoutReplay(eq("http://localhost:18181/sl-library/search"),contains("competition-batch02"),eq(120_000L),isNull(),eq(4*1024*1024),isNull());
    }
}

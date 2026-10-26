package com.consense.service.vetting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VettingInspectionAvailabilityTest {
    @TempDir Path workspace;
    private static final String DATASET="native-v3-full44";
    private static final String RESULT="tmp/vetting_foundation_hardening/full44-native-v3-index-20261003T132419Z/actual/result.json";

    private void unavailable(Map<String,Object> result){
        assertEquals("unavailable",result.get("status"));
        assertEquals("saved_experiment_not_present_on_this_host",result.get("unavailableReason"));
        assertEquals(Collections.emptyList(),result.get("items"));assertEquals(0,result.get("total"));
    }

    @Test void absentRegisteredSnapshotReturnsExplicitUnavailableLists()throws Exception{
        VettingInspectionService service=new VettingInspectionService(workspace);
        unavailable(service.documents(DATASET,1,30));
        unavailable(service.chunks(DATASET,1,30,null,null,null));
        unavailable(service.pages(DATASET,"document",1,30));
        assertTrue((Boolean)service.catalog().get("readOnly"));
        assertEquals(0,service.catalog().get("freshModelCalls"));
        assertEquals(0,service.catalog().get("freshVectorQueries"));
    }

    @Test void absentSnapshotDetailsAndOriginalHaveReadableNotFound()throws Exception{
        VettingInspectionService service=new VettingInspectionService(workspace);
        for(org.junit.jupiter.api.function.Executable action:new org.junit.jupiter.api.function.Executable[]{
                ()->service.chunk(DATASET,"chunk"),()->service.page(DATASET,"document",1),
                ()->service.original(DATASET,"document")}){
            ResponseStatusException failure=assertThrows(ResponseStatusException.class,action);
            assertEquals(HttpStatus.NOT_FOUND,failure.getStatus());
            assertEquals("Saved experiment is unavailable on this host",failure.getReason());
        }
    }

    @Test void existingCorruptSnapshotStillFailsIdentityValidation()throws Exception{
        Path result=workspace.resolve(RESULT);Files.createDirectories(result.getParent());Files.writeString(result,"{}");
        VettingInspectionService service=new VettingInspectionService(workspace);
        assertThrows(IOException.class,()->service.documents(DATASET,1,30));
        assertThrows(IOException.class,()->service.chunks(DATASET,1,30,null,null,null));
        assertThrows(IOException.class,()->service.pages(DATASET,"document",1,30));
        assertThrows(IOException.class,()->service.chunk(DATASET,"chunk"));
    }

    @Test void unknownDatasetStillReturnsNotFoundRatherThanAnUnavailableEmptySuccess(){
        VettingInspectionService service=new VettingInspectionService(workspace);
        ResponseStatusException failure=assertThrows(ResponseStatusException.class,()->service.documents("unknown",1,30));
        assertEquals(HttpStatus.NOT_FOUND,failure.getStatus());assertEquals("Unknown dataset",failure.getReason());
    }
}

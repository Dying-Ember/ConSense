package com.consense.service.drafting;

import com.consense.domain.DraftVariable;
import com.consense.domain.Project;
import com.consense.repository.DraftVariableRepository;
import com.consense.repository.ProjectRepository;
import com.consense.web.dto.DraftingDtos.VariableVO;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import javax.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true", "consense.llm.enabled=false",
        "consense.ocr.enabled=false", "consense.vector.provider=memory"})
@ActiveProfiles("h2")
class DraftingInputInitializationTest {
    private static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:h2:mem:input_init_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        r.add("consense.storage-root",()->Paths.get("target","input-init-uploads",DB).toAbsolutePath().toString());
    }
    @Autowired DraftingService service;
    @Autowired ProjectRepository projects;
    @Autowired DraftVariableRepository variables;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test void freshAndWarmReadsUseBoundedQueriesAndKeepEveryInputUnadopted() {
        Project p=new Project();p.setId("input-init-"+UUID.randomUUID());p.setNameZhHans("Fictional initialization test");projects.save(p);
        Statistics stats=entityManagerFactory.unwrap(SessionFactory.class).getStatistics();stats.clear();
        List<VariableVO> fresh=service.listVariables(p.getId());
        long freshQueries=stats.getQueryExecutionCount();
        assertEquals(DraftBlueprint.INPUTS.size(),fresh.size());
        assertTrue(fresh.stream().allMatch(v->!v.isConfirmed()&&!v.isManuallyEdited()&&v.getValue().isEmpty()));
        assertTrue(freshQueries<=15,"Fresh input initialization used "+freshQueries+" queries for "+fresh.size()+" fields; avoid querying once per field");
        stats.clear();
        assertEquals(com.consense.common.JsonUtils.write(fresh),com.consense.common.JsonUtils.write(service.listVariables(p.getId())));
        long warmQueries=stats.getQueryExecutionCount();
        assertTrue(warmQueries<=15,"Warm input reads still query once per field");
        System.out.println("INPUT_INITIALIZATION_QUERY_COUNTS fresh="+freshQueries+" warm="+warmQueries);
    }

    @Test void bulkLookupPreservesLegacyAdoptionAndUnrelatedRows() {
        Project p=new Project();p.setId("input-migration-"+UUID.randomUUID());p.setNameZhHans("Fictional migration test");projects.save(p);
        DraftVariable legacy=new DraftVariable();legacy.setProjectId(p.getId());legacy.setVarKey("subcontractArrangement");
        legacy.setScope("BASE");legacy.setValueText("NSC");legacy.setConfirmed(true);variables.save(legacy);
        DraftVariable unrelated=new DraftVariable();unrelated.setProjectId(p.getId());unrelated.setVarKey("_historicalValue");
        unrelated.setScope("FILE");unrelated.setValueText("preserve this historical value");variables.save(unrelated);
        List<VariableVO> result=service.listVariables(p.getId());
        VariableVO adopted=result.stream().filter(v->"subcontractArrangement".equals(v.getKey())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("NSC",adopted.getValue());assertTrue(adopted.isConfirmed());assertTrue(adopted.isManuallyEdited());
        DraftVariable kept=variables.findByProjectIdAndVarKey(p.getId(),"_historicalValue").orElseThrow(AssertionError::new);
        assertEquals("FILE",kept.getScope());assertEquals("preserve this historical value",kept.getValueText());
        assertEquals(DraftBlueprint.INPUTS.size(),result.size());
    }
}

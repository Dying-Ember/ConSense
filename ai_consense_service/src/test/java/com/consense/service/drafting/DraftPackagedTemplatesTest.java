package com.consense.service.drafting;
import com.consense.web.dto.DraftingDtos.UploadResultVO;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DraftPackagedTemplatesTest {
    @TempDir Path temp;
    private void originals() throws Exception {
        Path words=Paths.get("/mnt/d/R&D/ConSense/Ref/2026-09-04_Documents to Shortlisted Candidates (Contract Advisory)/2a_Standard documents");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(words.resolve("01_Notes to Tenderers (NTT).docx"))
                && Files.isRegularFile(words.resolve("02_Special Conditions of Tender (SCT).docx"))
                && Files.isRegularFile(words.resolve("06_Special Conditions of Contract (SCC).docx"))
                && Files.isRegularFile(Paths.get("/mnt/c/Users/Administrator/Downloads/email from Smart QS Hackathon Team/07_Special Conditions of Contract (SCC).pdf")),
                "Real packaged template fixtures are unavailable");
        for(String name:Arrays.asList("01_Notes to Tenderers (NTT).docx","02_Special Conditions of Tender (SCT).docx","06_Special Conditions of Contract (SCC).docx"))Files.copy(words.resolve(name),temp.resolve(name));
        Files.copy(Paths.get("/mnt/c/Users/Administrator/Downloads/email from Smart QS Hackathon Team/07_Special Conditions of Contract (SCC).pdf"),temp.resolve("07_Special Conditions of Contract (SCC).pdf"));
    }
    @Test void fourVerifiedAssetsUseExistingUploadPath() throws Exception {
        originals();DraftingService service=mock(DraftingService.class);
        when(service.uploadTemplates(eq("new-project"),anyList())).thenReturn(new UploadResultVO(1,1,0,Collections.emptyList()));
        new DraftPackagedTemplates(service,temp.toString(),new Transactions().transactionManager()).onProjectCreated(new DraftPackagedTemplates.ProjectCreated("new-project"));
        ArgumentCaptor<List> captured=ArgumentCaptor.forClass(List.class);verify(service,times(3)).uploadTemplates(eq("new-project"),captured.capture());
        assertEquals(3,captured.getAllValues().size());
        ArgumentCaptor<MultipartFile> pdfCapture=ArgumentCaptor.forClass(MultipartFile.class);
        verify(service).savePackagedPdfAuxiliary(eq("new-project"),pdfCapture.capture());
        MultipartFile word=(MultipartFile)captured.getAllValues().get(2).get(0),pdf=pdfCapture.getValue();
        assertEquals("06_Special Conditions of Contract (SCC).docx",word.getOriginalFilename());
        assertEquals("07_Special Conditions of Contract (SCC).pdf",pdf.getOriginalFilename());
        assertNotEquals(DraftTemplateRouting.sha(word.getBytes()),DraftTemplateRouting.sha(pdf.getBytes()));
    }
    @Test void changedSccDoesNotBlockOtherDefaultsOrPromotePdf() throws Exception {
        originals();Files.write(temp.resolve("06_Special Conditions of Contract (SCC).docx"),new byte[]{1,2,3});
        DraftingService service=mock(DraftingService.class);
        when(service.uploadTemplates(eq("new-project"),anyList())).thenReturn(new UploadResultVO(1,1,0,Collections.emptyList()));
        new DraftPackagedTemplates(service,temp.toString(),new Transactions().transactionManager()).onProjectCreated(new DraftPackagedTemplates.ProjectCreated("new-project"));
        ArgumentCaptor<List> captured=ArgumentCaptor.forClass(List.class);verify(service,times(2)).uploadTemplates(eq("new-project"),captured.capture());
        assertEquals(2,captured.getAllValues().size());
        for(List files:captured.getAllValues())for(Object file:files)assertFalse(((MultipartFile)file).getOriginalFilename().contains("SCC"));
        verify(service).savePackagedPdfAuxiliary(eq("new-project"),any(MultipartFile.class));
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions {
        @org.springframework.context.annotation.Bean org.springframework.transaction.PlatformTransactionManager transactionManager() {
            org.h2.jdbcx.JdbcDataSource data=new org.h2.jdbcx.JdbcDataSource();data.setURL("jdbc:h2:mem:default_template_event;DB_CLOSE_DELAY=-1");
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(data);
        }
    }
    @Test void listenerRunsAfterCommitAndNeverOnRollback() throws Exception {
        originals();DraftingService service=mock(DraftingService.class);
        when(service.uploadTemplates(anyString(),anyList())).thenReturn(new UploadResultVO(1,1,0,Collections.emptyList()));
        try(org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(DraftPackagedTemplates.class,()->new DraftPackagedTemplates(service,temp.toString(),new Transactions().transactionManager()));
            context.refresh();
            org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            tx.executeWithoutResult(status->{context.publishEvent(new DraftPackagedTemplates.ProjectCreated("committed"));verifyNoInteractions(service);});
            verify(service,times(3)).uploadTemplates(eq("committed"),anyList());verify(service).savePackagedPdfAuxiliary(eq("committed"),any(MultipartFile.class));reset(service);
            tx.executeWithoutResult(status->{context.publishEvent(new DraftPackagedTemplates.ProjectCreated("rolled-back"));status.setRollbackOnly();});
            verifyNoInteractions(service);
        }
    }

}

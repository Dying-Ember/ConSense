package com.consense.service.drafting;

import com.consense.web.dto.DraftingDtos.UploadResultVO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.multipart.MultipartFile;

/** Installs verified package defaults only when a project is first created. */
@Component
public class DraftPackagedTemplates {
    private static final Logger log=LoggerFactory.getLogger(DraftPackagedTemplates.class);
    private static final Map<String,String> EXPECTED;
    static {
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("01_Notes to Tenderers (NTT).docx","60bd8796aa828863c7edfe8617b7a5c56ee383afe8ba87a089e820f0fd9a982c");
        sources.put("02_Special Conditions of Tender (SCT).docx","7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4");
        sources.put("06_Special Conditions of Contract (SCC).docx","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208");
        sources.put("07_Special Conditions of Contract (SCC).pdf","1bebab1afbc0faaf8aa9c0b9e6c51b023c24e53a094d4987d746fdeb65b30741");
        EXPECTED=Collections.unmodifiableMap(sources);
    }
    public static final class ProjectCreated {
        public final String projectId;
        public ProjectCreated(String projectId){this.projectId=projectId;}
    }
    private final DraftingService drafting;
    private final String directory;
    private final PlatformTransactionManager transactions;
    public DraftPackagedTemplates(DraftingService drafting,@Value("${consense.drafting.packaged-template-dir:}") String directory,PlatformTransactionManager transactions){this.drafting=drafting;this.directory=directory;this.transactions=transactions;}

    @TransactionalEventListener
    public void onProjectCreated(ProjectCreated event) {
        if(directory==null||directory.trim().isEmpty())return;
        try {
            List<MultipartFile> files=new ArrayList<>();Path root=Paths.get(directory);
            MultipartFile pdf=null;
            for(Map.Entry<String,String> source:EXPECTED.entrySet()) {
                String name=source.getKey();
                try {
                    byte[] bytes=Files.readAllBytes(root.resolve(name));
                    if(!source.getValue().equals(DraftTemplateRouting.sha(bytes)))throw new IOException("Packaged template identity mismatch");
                    if(name.endsWith(".pdf"))pdf=new PackagedFile(name,bytes);
                    else files.add(new PackagedFile(name,bytes));
                } catch(IOException failure) { log.warn("Packaged template unavailable or changed: {}",name); }
            }
            TransactionTemplate independent=new TransactionTemplate(transactions);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            for(MultipartFile file:files) {
                try {
                    UploadResultVO result=independent.execute(status->drafting.uploadTemplates(event.projectId,Collections.singletonList(file)));
                    if(result==null||result.getFailed()!=0||result.getParsed()!=1)log.warn("Packaged template incomplete: {}",file.getOriginalFilename());
                } catch(Exception failure) { log.warn("Packaged template failed: {} ({})",file.getOriginalFilename(),failure.getClass().getSimpleName()); }
            }
            if(pdf!=null) {
                MultipartFile auxiliary=pdf;
                try { independent.executeWithoutResult(status->{try { drafting.savePackagedPdfAuxiliary(event.projectId,auxiliary); } catch(IOException failure){throw new IllegalStateException(failure);} }); }
                catch(Exception failure){log.warn("Packaged PDF reference failed: {}",failure.getClass().getSimpleName());}
            }
        } catch(Exception failure) {
            log.warn("Packaged templates unavailable for new project: {}",failure.getClass().getSimpleName());
        }
    }
    private static final class PackagedFile implements MultipartFile {
        private final String name;private final byte[] bytes;
        PackagedFile(String name,byte[] bytes){this.name=name;this.bytes=bytes;}
        public String getName(){return "files";}
        public String getOriginalFilename(){return name;}
        public String getContentType(){return name.endsWith(".pdf")?"application/pdf":"application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
        public boolean isEmpty(){return bytes.length==0;}
        public long getSize(){return bytes.length;}
        public byte[] getBytes(){return bytes.clone();}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(File dest)throws IOException{Files.write(dest.toPath(),bytes);}
    }
}

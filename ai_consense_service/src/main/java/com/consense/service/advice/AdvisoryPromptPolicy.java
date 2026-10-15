package com.consense.service.advice;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The confirmed business policy is shared by runtime and editable factory defaults. */
public final class AdvisoryPromptPolicy {
    private static final String TEXT=load();
    private AdvisoryPromptPolicy() {}
    public static String text(){return TEXT;}
    private static String load(){
        try(InputStream input=new ClassPathResource("advice/contract-system-20261014.md").getInputStream()) {
            return StreamUtils.copyToString(input,StandardCharsets.UTF_8);
        }catch(Exception error){throw new IllegalStateException("Cannot load confirmed Advisory policy",error);}
    }
}

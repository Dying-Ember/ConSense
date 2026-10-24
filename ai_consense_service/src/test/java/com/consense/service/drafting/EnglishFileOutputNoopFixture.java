package com.consense.service.drafting;

import com.consense.service.export.EnglishFileOutput;
import static org.mockito.Mockito.*;

/** Source, adoption and renderer tests isolate language decisions at their existing consumer boundary. */
final class EnglishFileOutputNoopFixture {
    private EnglishFileOutputNoopFixture() { }
    static void configure(EnglishFileOutput output){
        when(output.docx(any(byte[].class),anyString(),any(Runnable.class))).thenAnswer(call->{
            ((Runnable)call.getArgument(2)).run();return ((byte[])call.getArgument(0)).clone();
        });
        when(output.text(anyString(),anyString(),any(Runnable.class))).thenAnswer(call->{
            ((Runnable)call.getArgument(2)).run();return call.getArgument(0);
        });
    }
}

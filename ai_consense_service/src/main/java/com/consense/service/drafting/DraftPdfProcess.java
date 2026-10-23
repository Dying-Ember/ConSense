package com.consense.service.drafting;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/** Shared bounded external-process boundary; only descendants of this launch are stopped. */
final class DraftPdfProcess {
    private static final Semaphore PROCESS_SLOT=new Semaphore(1,true);
    private DraftPdfProcess() { }
    static boolean supported() {try{Process.class.getMethod("toHandle");return true;}catch(NoSuchMethodException absent){return false;}}
    static void checkDeadline(long deadline) {
        if(Thread.currentThread().isInterrupted())throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Conversion interrupted.");
        if(System.nanoTime()>=deadline)throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,"The configured document renderer exceeded its timeout.");
    }
    static String run(DraftPdfRenderProfile profile,List<String> command,Path work,long deadline,int expectedExit)throws IOException {
        checkDeadline(deadline);
        try {
            if(!PROCESS_SLOT.tryAcquire(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))
                throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,"The configured document renderer exceeded its timeout while queued.");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Conversion interrupted.",interrupted);}
        try {
        checkDeadline(deadline);
        Path temporary=Files.createDirectories(work.resolve("tmp")).toAbsolutePath();
        ProcessBuilder builder=new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
        // Renderer scratch files must not depend on an unusable desktop TEMP/TMP directory.
        for(String name:new String[]{"TEMP","TMP","TMPDIR"})builder.environment().put(name,temporary.toString());
        Process process=builder.start();
        ByteArrayOutputStream captured=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try(InputStream stream=process.getInputStream()) {byte[] buffer=new byte[4096];int count;
            while((count=stream.read(buffer))!=-1)synchronized(captured){int remaining=profile.getMaxOutputBytes()-captured.size();if(remaining>0)captured.write(buffer,0,Math.min(count,remaining));}
        }catch(IOException ignored) { }},"draft-pdf-output");reader.setDaemon(true);reader.start();
        try {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0||!process.waitFor(remaining,TimeUnit.NANOSECONDS)) {
                stopOwned(process);reader.join(1000);
                throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_TIMEOUT,"The configured document renderer exceeded its timeout.");
            }
            reader.join(1000);String output;
            synchronized(captured){output=new String(captured.toByteArray(),StandardCharsets.UTF_8);}
            if(process.exitValue()!=expectedExit)throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Renderer exit "+process.exitValue()+": "+output);
            return output;
        }catch(InterruptedException interrupted){stopOwned(process);Thread.currentThread().interrupt();throw new DraftPdfConversionException(DraftPdfConversionException.Code.CONVERSION_FAILED,"Conversion interrupted.",interrupted);}
        }finally{PROCESS_SLOT.release();}
    }
    private static void stopOwned(Process process) {
        try {
            Class<?> handles=Class.forName("java.lang.ProcessHandle");Object handle=Process.class.getMethod("toHandle").invoke(process);
            try(Stream<?> children=(Stream<?>)handles.getMethod("descendants").invoke(handle)) {
                children.forEach(child->{try{handles.getMethod("destroyForcibly").invoke(child);}catch(Exception ignored){ }});
            }
        }catch(Exception unavailableOnJava8) { }
        process.destroyForcibly();
        try{process.waitFor(1000,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}

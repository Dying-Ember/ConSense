package com.consense.service;

import com.consense.common.Utf8;
import com.consense.config.ConsenseProperties;
import com.consense.domain.SourceDocument;
import com.consense.service.drafting.DraftReviewSources;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalStorageMappingTest {
    @TempDir Path folder;

    @Test void exactHistoricalPathReadsOnlyTheRegisteredBytes() throws Exception {
        Path root=Files.createDirectory(folder.resolve("files"));
        Files.write(root.resolve("1.docx"),Utf8.bytes("original"));
        Path mapping=folder.resolve("map.json");
        String old="D:\\old\\uploads\\project-a\\VETTING_PACKAGE\\source.docx";
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("path","1.docx");entry.put("sha256",Utf8.sha256("original"));
        new ObjectMapper().writeValue(mapping.toFile(),Collections.singletonMap(old,entry));
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());
        StorageService storage=new StorageService(properties,mapping);
        assertArrayEquals(Utf8.bytes("original"),storage.read(old));
        assertTrue(storage.matchesStoredBytes(old,Utf8.sha256("original")));
        Path upload=Files.write(root.resolve("new.docx"),Utf8.bytes("new upload"));
        assertArrayEquals(Utf8.bytes("new upload"),storage.read(upload.toAbsolutePath().toString()));
        assertThrows(RuntimeException.class,()->storage.read("1.docx"));
        assertThrows(RuntimeException.class,()->storage.read(old.replace("project-a","project-b")));
        Files.write(root.resolve("1.docx"),Utf8.bytes("changed!"));
        assertFalse(storage.matchesStoredBytes(old,Utf8.sha256("original")));
        assertThrows(RuntimeException.class,()->storage.read(old));
    }

    @Test void rejectsDuplicateEntriesAndEscapingTargets() throws Exception {
        Path root=Files.createDirectory(folder.resolve("files"));
        Path outside=folder.resolve("outside");Files.write(outside,Utf8.bytes("original"));
        Path mapping=folder.resolve("map.json");String old="D:\\old\\original.docx";
        Files.writeString(mapping,"{}");
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());
        StorageService storage=new StorageService(properties,mapping);
        for(String target:Arrays.asList("../outside","/outside","folder/../outside","C:/outside")){
            Map<String,Object> entry=new LinkedHashMap<>();entry.put("path",target);entry.put("sha256",Utf8.sha256("original"));
            new ObjectMapper().writeValue(mapping.toFile(),Collections.singletonMap(old,entry));
            assertThrows(RuntimeException.class,()->storage.read(old));
        }
        Files.writeString(mapping,"{\"D:\\\\old\\\\original.docx\":{},\"D:\\\\old\\\\original.docx\":{}}");
        assertThrows(RuntimeException.class,()->storage.read(old));
    }

    @Test void rejectsSymbolicLinkEscapingStorageRoot() throws Exception {
        Path root=Files.createDirectory(folder.resolve("files"));
        Path outside=folder.resolve("outside");Files.write(outside,Utf8.bytes("original"));
        Path mapping=folder.resolve("map.json");String old="D:\\old\\original.docx";
        try { Files.createSymbolicLink(root.resolve("link"),outside); }
        catch(UnsupportedOperationException|java.io.IOException|SecurityException unavailable){
            assumeTrue(false,"Symbolic links unavailable: "+unavailable.getClass().getSimpleName());
        }
        assertTrue(Files.isSymbolicLink(root.resolve("link")));
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("path","link");entry.put("sha256",Utf8.sha256("original"));
        new ObjectMapper().writeValue(mapping.toFile(),Collections.singletonMap(old,entry));
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());
        StorageService storage=new StorageService(properties,mapping);
        assertThrows(RuntimeException.class,()->storage.read(old));
    }

    @Test void draftingRejectsReplacementAfterResolutionEvenWithoutSourceHash() throws Exception {
        Path root=Files.createDirectory(folder.resolve("files")), file=root.resolve("1.docx");
        Files.write(file,Utf8.bytes("original"));Path mapping=folder.resolve("map.json");
        String old="D:\\old\\uploads\\project-a\\template.docx";
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("path","1.docx");entry.put("sha256",Utf8.sha256("original"));
        new ObjectMapper().writeValue(mapping.toFile(),Collections.singletonMap(old,entry));
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());
        StorageService storage=new StorageService(properties,mapping){
            @Override public Path resolveStoredPath(String path) throws java.io.IOException {
                Path resolved=super.resolveStoredPath(path);Files.write(resolved,Utf8.bytes("changed!"));return resolved;
            }
        };
        SourceDocument source=new SourceDocument();source.setId(1L);source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        source.setStoragePath(old);source.setSourceSha256(null);
        assertThrows(RuntimeException.class,()->new DraftReviewSources(storage,properties).bindSource(source));
    }

    @Test void secondDigestCannotAcceptBytesOutsideTheMappedPin() throws Exception {
        Path root=Files.createDirectory(folder.resolve("files")),file=root.resolve("mapped.docx");
        Files.write(file,Utf8.bytes("original-A"));Path mapping=folder.resolve("map.json");
        String old="D:\\old\\mapped.docx";
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("path","mapped.docx");entry.put("sha256",Utf8.sha256("original-A"));
        new ObjectMapper().writeValue(mapping.toFile(),Collections.singletonMap(old,entry));
        String mapSha=Utf8.sha256(Files.readString(mapping));
        StorageService.Resolved first=StorageService.resolve(root,old,mapping,mapSha);
        Files.write(file,Utf8.bytes("changed-B"));
        assertThrows(java.io.IOException.class,()->StorageService.verifiedResolvedSha(first));
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(root.toString());
        StorageService storage=new StorageService(props,mapping,mapSha);
        assertFalse(storage.matchesStoredBytes(old,Utf8.sha256("changed-B")));
    }

    @Test void draftingNoMapRejectsAliasRedirectAfterRootCheck() throws Exception {
        Path root=Files.createDirectory(folder.resolve("files")),inside=root.resolve("inside.docx"),alias=root.resolve("alias.docx"),outside=folder.resolve("outside.docx");
        Files.write(inside,Utf8.bytes("original-A"));Files.write(outside,Utf8.bytes("outside-B"));
        try {Files.createSymbolicLink(alias,inside);}
        catch(UnsupportedOperationException|java.io.IOException|SecurityException unavailable) {
            assumeTrue(false,"Symbolic links unavailable: "+unavailable.getClass().getSimpleName());
        }
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(root.toString());
        java.util.concurrent.atomic.AtomicBoolean redirected=new java.util.concurrent.atomic.AtomicBoolean();
        StorageService storage=new StorageService(props,(Path)null){
            @Override public Path resolveStoredPath(String path) throws java.io.IOException {
                Path resolved=super.resolveStoredPath(path);Files.delete(alias);Files.createSymbolicLink(alias,outside);redirected.set(true);return resolved;
            }
        };
        SourceDocument source=new SourceDocument();source.setId(1L);source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        source.setStoragePath(alias.toString());source.setSourceSha256(null);
        assertThrows(RuntimeException.class,()->new DraftReviewSources(storage,props).bindSource(source));
        assertTrue(redirected.get(),"The fixture must actually redirect the alias before asserting rejection");
        assertArrayEquals(Utf8.bytes("outside-B"),storage.read(outside.toString())); // General no-map read remains compatible.
        assertThrows(RuntimeException.class,()->storage.readWithinStorage(outside.toString()));
    }

    @Test void draftingNoMapRejectsAliasRedirectToAnotherRootFile() throws Exception {
        Path root=Files.createDirectory(folder.resolve("files")),inside=root.resolve("inside.docx"),alias=root.resolve("alias.docx"),other=root.resolve("other.docx");
        Files.write(inside,Utf8.bytes("original-A"));Files.write(other,Utf8.bytes("other-B"));
        try {Files.createSymbolicLink(alias,inside);}
        catch(UnsupportedOperationException|java.io.IOException|SecurityException unavailable) {
            assumeTrue(false,"Symbolic links unavailable: "+unavailable.getClass().getSimpleName());
        }
        ConsenseProperties props=new ConsenseProperties();props.setStorageRoot(root.toString());
        java.util.concurrent.atomic.AtomicBoolean redirected=new java.util.concurrent.atomic.AtomicBoolean();
        StorageService storage=new StorageService(props,(Path)null){
            @Override public Path resolveStoredPath(String path) throws java.io.IOException {
                Path resolved=super.resolveStoredPath(path);Files.delete(alias);Files.createSymbolicLink(alias,other);redirected.set(true);return resolved;
            }
        };
        SourceDocument source=new SourceDocument();source.setId(1L);source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        source.setStoragePath(alias.toString());source.setSourceSha256(null);
        assertThrows(RuntimeException.class,()->new DraftReviewSources(storage,props).bindSource(source));
        assertTrue(redirected.get(),"The fixture must redirect to the other root file before asserting rejection");
    }

    @Test void changingExternallyPinnedMapRejectsSubsequentReads() throws Exception {
        Path root=Files.createDirectory(folder.resolve("files"));Files.write(root.resolve("1.docx"),Utf8.bytes("original"));
        Path mapping=folder.resolve("map.json");String old="D:\\old\\1.docx";
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("path","1.docx");entry.put("sha256",Utf8.sha256("original"));
        new ObjectMapper().writeValue(mapping.toFile(),Collections.singletonMap(old,entry));
        String pin=Utf8.sha256(Files.readString(mapping));ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());
        assertThrows(RuntimeException.class,()->new StorageService(properties,mapping,"0".repeat(64)));
        StorageService storage=new StorageService(properties,mapping,pin);
        assertArrayEquals(Utf8.bytes("original"),storage.read(old));
        Files.writeString(mapping,"{}");assertThrows(RuntimeException.class,()->storage.read(old));
    }

    @Test void unmappedWindowsPathCannotReadAWorkingDirectoryDecoy() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));
        Path root=Files.createDirectory(folder.resolve("files"));
        Files.write(root.resolve("D:\\old\\project-a\\source.docx"),Utf8.bytes("decoy"));
        Files.write(root.resolve("new-upload.docx"),Utf8.bytes("new upload"));
        Path mapping=folder.resolve("map.json");Files.writeString(mapping,"{}");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",classpath,UnmappedWindowsProbe.class.getName(),root.toString(),mapping.toString())
                .directory(root.toFile()).redirectErrorStream(true).start();
        if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){
            process.destroyForcibly();fail("probe did not exit");
        }
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),output);
    }

    public static class UnmappedWindowsProbe {
        public static void main(String[] args) throws Exception {
            Path root=Paths.get(args[0]);ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());
            StorageService storage=new StorageService(properties,Paths.get(args[1]));
            try {storage.read("D:\\old\\project-a\\source.docx");throw new AssertionError("Unmapped Windows path read the decoy");}
            catch(com.consense.common.BizException expected) { }
            if(!Arrays.equals(Utf8.bytes("new upload"),storage.read(root.resolve("new-upload.docx").toString())))
                throw new AssertionError("Native absolute upload did not read correctly");
        }
    }
}

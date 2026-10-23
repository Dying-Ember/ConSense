package com.consense.service;

import com.consense.common.Utf8;
import com.consense.config.ConsenseProperties;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StorageServiceVerificationTest {
    @TempDir Path folder;

    @Test void sharedStoredDigestReadsEveryByteAndRejectsSameSizeTimestampPreservingChanges() throws Exception {
        Path root=Files.createDirectory(folder.resolve("uploads")),file=root.resolve("source.txt");
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());StorageService storage=new StorageService(properties);
        char[] value=new char[1024*1024];Arrays.fill(value,'x');value[8191]='中';String text=new String(value);Files.write(file,Utf8.bytes(text));
        String expected=Utf8.sha256(text);FileTime time=Files.getLastModifiedTime(file);long size=Files.size(file);
        assertEquals(expected,StorageService.storedSha256(root,file.toString()));assertTrue(storage.matchesStoredBytes(file.toString(),expected));
        value[value.length-1]='y';Files.write(file,Utf8.bytes(new String(value)));Files.setLastModifiedTime(file,time);assertEquals(size,Files.size(file));
        assertNotEquals(expected,StorageService.storedSha256(root,file.toString()));assertFalse(storage.matchesStoredBytes(file.toString(),expected));
    }

    @Test void bothStoredVerificationEntrypointsRejectOutsideMissingAndDirectoryPaths() throws Exception {
        Path root=Files.createDirectory(folder.resolve("uploads")),outside=Files.write(folder.resolve("outside.txt"),new byte[]{1,2,3});
        ConsenseProperties properties=new ConsenseProperties();properties.setStorageRoot(root.toString());StorageService storage=new StorageService(properties);
        for(Path path:Arrays.asList(outside,root.resolve("../outside.txt"),root.resolve("missing"),root)){
            assertThrows(java.io.IOException.class,()->StorageService.storedSha256(root,path.toString()));
            assertFalse(storage.matchesStoredBytes(path.toString(),Utf8.sha256("anything")));
        }
        assertFalse(storage.matchesStoredBytes(outside.toString(),null));assertFalse(storage.matchesStoredBytes(outside.toString(),"invalid"));
    }
}

package com.consense.service;

import com.consense.common.BizException;
import com.consense.config.ConsenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 上传文件落盘：data/uploads/{projectId}/{category}/{uuid}_{原文件名}
 */
@Slf4j
@Service
public class StorageService {

    private final Path root;
    private final Path historicalMap;
    private final String historicalMapSha;

    @Autowired
    public StorageService(ConsenseProperties props) {
        this(props, configuredMap(), configuredMapSha());
    }

    StorageService(ConsenseProperties props, Path historicalMap) {
        this(props,historicalMap,null);
    }

    StorageService(ConsenseProperties props, Path historicalMap,String historicalMapSha) {
        this.root = Paths.get(props.getStorageRoot()).toAbsolutePath().normalize();
        this.historicalMap = historicalMap;
        this.historicalMapSha = historicalMapSha;
        try {
            if(historicalMap!=null)mapBytes(historicalMap,historicalMapSha);
            Files.createDirectories(root);
            log.info("文件存储目录: {}", root);
        } catch (IOException e) {
            throw new BizException("无法创建存储目录 " + root + ": " + e.getMessage());
        }
    }

    public StoredFile store(String projectId, String category, MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String safeName = original.replaceAll("[\\\\/:*?\"<>|]", "_");
        String key = UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
        Path dir = root.resolve(projectId).resolve(category);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(key);
            InputStream in = file.getInputStream();
            try {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                in.close();
            }
            return new StoredFile(original, target.toString(), file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BizException("保存文件失败: " + e.getMessage());
        }
    }

    public byte[] read(String storagePath) {
        try {
            if(historicalMap==null)return Files.readAllBytes(Paths.get(storagePath));
            return readWithinStorage(storagePath);
        } catch (IOException e) {
            throw new BizException("读取文件失败: " + e.getMessage());
        }
    }

    /** Read a root-checked original, retaining any mapped byte pin on the returned bytes. */
    public byte[] readWithinStorage(String storagePath) {
        return readWithinStorage(storagePath,null);
    }

    /** If supplied, keep the actual read bound to the caller's earlier resolved path. */
    public byte[] readWithinStorage(String storagePath,Path expectedPath) {
        try {
            Resolved original=resolve(root,storagePath,historicalMap,historicalMapSha);
            if(expectedPath!=null&&!expectedPath.equals(original.path))throw new IOException("Stored original path changed");
            byte[] bytes;
            try(InputStream stream=Files.newInputStream(original.path,java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                bytes=stream.readAllBytes();
            }
            if(original.sha!=null&&!original.sha.equals(sha256(bytes)))throw new IOException("Stored original changed");
            return bytes;
        } catch(IOException e) {
            throw new BizException("读取文件失败: " + e.getMessage());
        }
    }

    /** Fresh byte verification; file size and timestamp cannot establish source identity. */
    public boolean matchesStoredBytes(String storagePath,String expectedSha256) {
        if(expectedSha256==null||!expectedSha256.matches("[a-f0-9]{64}"))return false;
        try {
            return expectedSha256.equals(verifiedResolvedSha(resolve(root,storagePath,historicalMap,historicalMapSha)));
        }catch(Exception unavailable){return false;}
    }

    /** Shared fresh verification for stored originals; never trust timestamps or cached hashes. */
    public static String storedSha256(Path storageRoot,String storagePath) throws IOException {
        return verifiedResolvedSha(resolve(storageRoot,storagePath,configuredMap(),configuredMapSha()));
    }

    public static Path resolveStoredPath(Path storageRoot,String storagePath) throws IOException {
        return resolve(storageRoot,storagePath,configuredMap(),configuredMapSha()).path;
    }

    public Path resolveStoredPath(String storagePath) throws IOException {
        return resolve(root,storagePath,historicalMap,historicalMapSha).path;
    }

    private static Path configuredMap() {
        String value=System.getenv("CONSENSE_LEGACY_STORAGE_MAP");
        String sha=configuredMapSha();
        if(value==null||value.trim().isEmpty()){
            if(sha!=null)throw new IllegalArgumentException("Historical storage map configuration is incomplete");
            return null;
        }
        Path path=Paths.get(value);
        if(!path.isAbsolute()||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Historical storage map configuration is incomplete");
        return path;
    }

    private static String configuredMapSha() {
        String value=System.getenv("CONSENSE_LEGACY_STORAGE_MAP_SHA256");
        return value==null||value.trim().isEmpty()?null:value;
    }

    private static byte[] mapBytes(Path map,String expected) throws IOException {
        byte[] raw;
        try(InputStream stream=Files.newInputStream(map,java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)){raw=stream.readNBytes(1024*1024+1);}
        if(raw.length>1024*1024||expected!=null&&!expected.equals(sha256(raw)))throw new IOException("Historical storage map identity differs");
        return raw;
    }

    static final class Resolved {
        final Path path;final String sha;
        Resolved(Path path,String sha){this.path=path;this.sha=sha;}
    }

    static Resolved resolve(Path root,String storagePath,Path map,String expectedMapSha) throws IOException {
        String expected=null;Path candidate;
        if(map!=null){
            ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode entries=mapper.readTree(mapBytes(map,expectedMapSha));
            if(entries==null||!entries.isObject())throw new IOException("Invalid historical storage map");
            java.util.Set<String> targets=new java.util.HashSet<>();
            java.util.Iterator<java.util.Map.Entry<String,JsonNode>> rows=entries.fields();
            JsonNode selected=null;
            while(rows.hasNext()){
                java.util.Map.Entry<String,JsonNode> row=rows.next();JsonNode value=row.getValue();
                if(!row.getKey().matches("^[A-Za-z]:[\\\\/].+")||!value.isObject()||value.size()!=2
                        ||!value.path("path").isTextual()||!value.path("sha256").isTextual()
                        ||!value.path("sha256").asText().matches("[a-f0-9]{64}"))throw new IOException("Invalid historical storage map entry");
                String target=value.path("path").asText();
                if(target.isEmpty()||target.contains("\\")||target.contains(":")||target.startsWith("/")
                        ||java.util.Arrays.asList(target.split("/",-1)).stream().anyMatch(part->part.isEmpty()||part.equals(".")||part.equals(".."))
                        ||!targets.add(target))throw new IOException("Invalid historical storage target");
                if(row.getKey().equals(storagePath))selected=value;
            }
            if(selected!=null){candidate=root.resolve(selected.path("path").asText());expected=selected.path("sha256").asText();}
            else {
                candidate=Paths.get(storagePath);
                if(!candidate.isAbsolute())throw new IOException("Relative or unregistered historical storage path is not permitted");
            }
        }else candidate=Paths.get(storagePath);
        Path original=candidate.toAbsolutePath().normalize().toRealPath();
        if(!original.startsWith(root.toRealPath())||!Files.isRegularFile(original))throw new IOException("Stored original unavailable");
        if(expected!=null&&!expected.equals(sha256(original)))throw new IOException("Stored original changed");
        return new Resolved(original,expected);
    }

    static String verifiedResolvedSha(Resolved original) throws IOException {
        String actual=sha256(original.path);
        if(original.sha!=null&&!original.sha.equals(actual))throw new IOException("Stored original changed");
        return actual;
    }

    private static String sha256(Path original) throws IOException {
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream stream=Files.newInputStream(original,java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1)digest.update(buffer,0,count);}
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));return hex.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    private static String sha256(byte[] bytes) {
        try {byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format("%02x",b&255));return hex.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    /** 落盘结果（构造器签名与原 record 一致） */
    public static final class StoredFile {

        private final String originalName;
        private final String path;
        private final long size;
        private final String contentType;

        public StoredFile(String originalName, String path, long size, String contentType) {
            this.originalName = originalName;
            this.path = path;
            this.size = size;
            this.contentType = contentType;
        }

        public String getOriginalName() {
            return originalName;
        }

        public String getPath() {
            return path;
        }

        public long getSize() {
            return size;
        }

        public String getContentType() {
            return contentType;
        }
    }
}

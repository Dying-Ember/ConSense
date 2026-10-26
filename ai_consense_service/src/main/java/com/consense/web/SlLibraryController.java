package com.consense.web;

import com.consense.ai.HttpSupport;
import com.consense.common.ApiResponse;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

/** Browser entry for the existing local SL corpus/vector owner. Source paths are configured by that owner. */
@RestController
@RequestMapping("/api/oer/sl-library")
public class SlLibraryController {
    private final HttpSupport http;
    private final String base;
    public SlLibraryController(HttpSupport http,@Value("${consense.sl-library.base-url:}") String configured){
        this.http=http;this.base=localBase(configured);
    }
    static String localBase(String value){
        if(value==null||value.trim().isEmpty())return "";
        URI uri=URI.create(value.trim());
        if(!"http".equals(uri.getScheme())||!Arrays.asList("127.0.0.1","localhost","[::1]").contains(uri.getHost())||uri.getPort()<1
                ||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||"/".equals(uri.getPath())))throw new IllegalArgumentException("SL library requires a configured loopback HTTP service");
        return value.trim().replaceFirst("/+$","");
    }
    @GetMapping("/status") public ApiResponse<JsonNode> status(){
        if(base.isEmpty()){
            com.fasterxml.jackson.databind.node.ObjectNode status=JsonUtils.mapper().createObjectNode();status.putArray("versions");status.put("retrievalAvailable",false);status.putObject("job").put("status","unconfigured").put("message","The SL library service is not configured.");return ApiResponse.ok(status);
        }
        return ApiResponse.ok(read(http.getBounded(base+"/sl-library/status",5_000,4*1024*1024)));
    }
    @PostMapping("/build") public ApiResponse<JsonNode> build(){
        requireConfigured();return ApiResponse.ok(read(http.postJsonBoundedWithoutReplay(base+"/sl-library/build","{}",15_000,null,4*1024*1024,null)));
    }
    @Data public static class SearchRequest { private String query;private List<String> editionIds;private int limit=10; }
    @PostMapping("/search") public ApiResponse<JsonNode> search(@RequestBody SearchRequest request){
        if(request==null||request.getQuery()==null||request.getQuery().trim().isEmpty()||request.getQuery().length()>4000||request.getLimit()<1||request.getLimit()>10
                ||request.getEditionIds()==null||request.getEditionIds().isEmpty()||request.getEditionIds().size()>7||new HashSet<>(request.getEditionIds()).size()!=request.getEditionIds().size()
                ||request.getEditionIds().stream().anyMatch(id->id==null||!id.matches("[A-Za-z0-9_.-]{1,100}")))throw new BizException(4001,"Enter a query and select one or more SL versions.");
        requireConfigured();return ApiResponse.ok(read(http.postJsonBoundedWithoutReplay(base+"/sl-library/search",JsonUtils.write(request),120_000,null,4*1024*1024,null)));
    }
    private void requireConfigured(){if(base.isEmpty())throw new BizException(4201,"The SL library service is not configured.");}
    private static JsonNode read(String raw){JsonNode value=JsonUtils.parse(raw);if(value==null||!value.isObject())throw new BizException(4201,"The SL library returned an invalid response.");return value;}
}

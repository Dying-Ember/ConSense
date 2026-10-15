package com.consense.service.vetting;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Competition classes are internal metadata; legacy F2 codes keep their original meaning. */
public enum VettingIssueType {
    REFERENCE("reference", "REF", "f2-iii"),
    CONFLICT("conflict", "CONF", "f2-viii"),
    LANGUAGE("language", "LANG", "f2-ix"),
    RISK("risk", "RISK", "f2-ii"),
    GAP("gap", "GAP", "gap"),
    SUBSTANTIVE("substantive", "SUB", "substantive"),
    REQUIREMENT("requirement", "REQ", "requirement");

    private final String group, code, storedType;
    VettingIssueType(String group,String code,String storedType) { this.group=group;this.code=code;this.storedType=storedType; }
    public String getGroup() { return group; }
    public String getCode() { return code; }
    public String getStoredType() { return storedType; }
    public static List<String> groups() { return Arrays.stream(values()).map(VettingIssueType::getGroup).collect(Collectors.toList()); }
    public static VettingIssueType fromGroup(String group) {
        for(VettingIssueType type:values())if(type.group.equals(group))return type;
        return null;
    }
    public static VettingIssueType require(String type) {
        VettingIssueType known=fromGroup(type);
        if(known!=null)return known;
        if("f2-iii".equals(type)||"f2-iv".equals(type))return REFERENCE;
        if(Arrays.asList("f2-i","f2-vi","f2-viii").contains(type))return CONFLICT;
        if("f2-v".equals(type)||"f2-ix".equals(type))return LANGUAGE;
        if("f2-ii".equals(type)||"f2-vii".equals(type))return RISK;
        throw new IllegalArgumentException("Unknown Vetting issue type: "+type);
    }
    public static String storedType(String type) {
        VettingIssueType known=require(type);
        return type.startsWith("f2-")?type:known.storedType;
    }
}

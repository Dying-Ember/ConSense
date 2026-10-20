package com.consense.domain;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;

/** Private action receipt: reservations are consumed before dispatch and never refunded. */
@Getter @Setter @Entity @Table(name="qwen_generation_action")
public class QwenGenerationAction {
    @Id @Column(length=128) private String id;
    @Version private Long revision;
    @Column(length=64,nullable=false) private String identitySha256;
    @Column(nullable=false) private int maxPreflights,maxAnswers,maxTotal;
    @Column(nullable=false) private long lifetimeMs,deadlineEpochMs;
    @Column(nullable=false) private int preflights,answers,total;
    @Lob @Column(nullable=false,columnDefinition="LONGTEXT") private String reservationsJson="[]";
}

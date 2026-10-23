package com.consense.domain;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** One bounded task and its delta receipt, never a duplicate full source or graph. */
@Entity @Getter @Setter @Table(name="draft_automation_item",uniqueConstraints=@UniqueConstraint(name="uk_draft_auto_task",columnNames={"run_id","task_id"}))
public class DraftAutomationItem {
    @Id @Column(length=64) private String id;
    @Column(name="run_id",length=64,nullable=false) private String runId;
    @Column(name="task_id",length=64,nullable=false) private String taskId;
    @Column(name="task_order",nullable=false) private int taskOrder;
    @Column(length=24,nullable=false) private String status;
    @Lob @Column(name="task_json",columnDefinition="LONGTEXT",nullable=false) private String taskJson;
    @Lob @Column(name="receipt_json",columnDefinition="LONGTEXT") private String receiptJson;
}

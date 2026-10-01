package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 批次-探头绑定关系（只追加历史行，替换不改旧行原值）。
 * 每个批次同一时刻至多一条 ACTIVE（库级唯一约束保证）；
 * 替换时旧行置 REPLACED 并由新行 previous_binding_id 串起前后关系。
 */
@TableName("t_cc_batch_probe_binding")
public class BatchProbeBinding extends BaseEntity {
    private Long batchId;
    private Long probeId;
    /** 绑定时采信的有效证书 */
    private Long certId;
    private LocalDateTime bindTime;
    /** 主动解绑时间（未发生替换的移除） */
    private LocalDateTime unbindTime;
    /** 被新探头替换的时间 */
    private LocalDateTime replaceTime;
    /** 前序绑定（替换链条） */
    private Long previousBindingId;
    private String reason;
    /** ACTIVE / REPLACED / UNBOUND */
    private String status;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public Long getCertId() { return certId; }
    public void setCertId(Long certId) { this.certId = certId; }
    public LocalDateTime getBindTime() { return bindTime; }
    public void setBindTime(LocalDateTime bindTime) { this.bindTime = bindTime; }
    public LocalDateTime getUnbindTime() { return unbindTime; }
    public void setUnbindTime(LocalDateTime unbindTime) { this.unbindTime = unbindTime; }
    public LocalDateTime getReplaceTime() { return replaceTime; }
    public void setReplaceTime(LocalDateTime replaceTime) { this.replaceTime = replaceTime; }
    public Long getPreviousBindingId() { return previousBindingId; }
    public void setPreviousBindingId(Long previousBindingId) { this.previousBindingId = previousBindingId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

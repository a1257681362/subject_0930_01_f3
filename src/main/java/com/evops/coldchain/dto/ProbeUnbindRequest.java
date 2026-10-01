package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 主动解除批次-探头关联请求（置 UNBOUND，链条保留） */
public class ProbeUnbindRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    private String reason;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

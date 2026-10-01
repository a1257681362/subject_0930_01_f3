package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/**
 * 车载探头校准凭证关联请求（绑定/替换共用）。
 * 同一批次重复关联同一探头为幂等请求，只返回既有绑定，不产生第二条结果。
 */
public class ProbeBindRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotNull(message = "探头ID不能为空")
    private Long probeId;
    /** 替换原因（替换时建议填写，留档于旧绑定行） */
    private String reason;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 校准证书作废请求（作废只置 VOID，不删除证书文件与历史绑定） */
public class CertificateVoidRequest {
    @NotBlank(message = "作废原因不能为空")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

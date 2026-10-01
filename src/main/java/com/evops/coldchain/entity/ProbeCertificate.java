package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * 探头校准证书（每次上传追加一行）。
 * 文件内容入库保存，上传时计算 SHA-256 摘要；作废只置 VOID + 作废审计字段，不删行不删内容。
 * 仅 create_by/create_time 由框架填充，其余字段显式维护，故不复用 CreateOnlyEntity。
 */
@TableName("t_cc_probe_certificate")
public class ProbeCertificate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long probeId;
    private String certNo;
    private String fileName;
    private String contentType;
    private Long fileSize;
    /** 证书内容的 SHA-256 十六进制摘要 */
    private String sha256;
    /** 证书文件二进制内容（下载用）；对外 JSON 视图由 @JsonIgnore 剥离，不在列表/详情中返回 */
    @JsonIgnore
    private byte[] content;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    /** VALID / VOID */
    private String status;
    private String voidReason;
    private LocalDateTime voidTime;
    private Long voidBy;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public String getCertNo() { return certNo; }
    public void setCertNo(String certNo) { this.certNo = certNo; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public LocalDateTime getVoidTime() { return voidTime; }
    public void setVoidTime(LocalDateTime voidTime) { this.voidTime = voidTime; }
    public Long getVoidBy() { return voidBy; }
    public void setVoidBy(Long voidBy) { this.voidBy = voidBy; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}

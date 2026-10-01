package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车载温度探头档案。
 * 建档时登记序列号（全局唯一）、量程、校准机构与当前校准有效期；
 * 是否可用于发运以其存在“有效期内且未作废”的证书为准（见 ProbeService）。
 */
@TableName("t_cc_temperature_probe")
public class TemperatureProbe extends BaseEntity {
    private String probeNo;
    private String serialNo;
    private String model;
    /** 量程下限 ℃ */
    private BigDecimal measureMin;
    /** 量程上限 ℃ */
    private BigDecimal measureMax;
    /** 校准机构 */
    private String calibOrg;
    /** 校准有效期至（建档登记的当前凭证有效期） */
    private LocalDateTime validUntil;
    /** ACTIVE / REVOKED */
    private String status;
    private String remark;

    public String getProbeNo() { return probeNo; }
    public void setProbeNo(String probeNo) { this.probeNo = probeNo; }
    public String getSerialNo() { return serialNo; }
    public void setSerialNo(String serialNo) { this.serialNo = serialNo; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public BigDecimal getMeasureMin() { return measureMin; }
    public void setMeasureMin(BigDecimal measureMin) { this.measureMin = measureMin; }
    public BigDecimal getMeasureMax() { return measureMax; }
    public void setMeasureMax(BigDecimal measureMax) { this.measureMax = measureMax; }
    public String getCalibOrg() { return calibOrg; }
    public void setCalibOrg(String calibOrg) { this.calibOrg = calibOrg; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}

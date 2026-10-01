package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车载温度探头建档请求：
 * 登记序列号（全局唯一）、量程上下限、校准机构与当前校准有效期。
 */
public class ProbeCreateRequest {
    @NotBlank(message = "探头序列号不能为空")
    private String serialNo;
    private String model;
    @NotNull(message = "量程下限不能为空")
    private BigDecimal measureMin;
    @NotNull(message = "量程上限不能为空")
    private BigDecimal measureMax;
    @NotBlank(message = "校准机构不能为空")
    private String calibOrg;
    @NotNull(message = "校准有效期不能为空")
    private LocalDateTime validUntil;
    private String remark;

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
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}

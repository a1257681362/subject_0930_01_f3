package com.evops.coldchain.controller;

import com.evops.coldchain.dto.CertificateVoidRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.ProbeUnbindRequest;
import com.evops.coldchain.entity.BatchProbeBinding;
import com.evops.coldchain.entity.ProbeCertificate;
import com.evops.coldchain.entity.TemperatureProbe;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.service.ProbeService;
import com.evops.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车载温度探头校准凭证管理入口（V3）：
 * 探头建档（序列号/量程/校准机构/有效期）-> 上传校准证书（生成 SHA-256 摘要）
 * -> 发运前“车载探头校准凭证关联”绑定在效探头（重复请求幂等，不产生第二条）
 * -> 证书下载 / 作废（逐次写审计流水）。
 * 过期、已作废、无有效证书的设备在绑定与发运入口一律拦截。
 */
@RestController
@RequestMapping("/api/cold-chain/probes")
public class ProbeController {

    private final ProbeService probeService;

    public ProbeController(ProbeService probeService) {
        this.probeService = probeService;
    }

    /** 探头建档：序列号、量程、校准机构、有效期 */
    @PostMapping
    public ApiResponse<TemperatureProbe> create(@Valid @RequestBody ProbeCreateRequest req) {
        return ApiResponse.ok(probeService.createProbe(req));
    }

    /**
     * 探头列表（页面下拉/表格共用）：每条携带 usable（当前是否有未作废且在效的证书）
     * 与 currentCertificate 元数据，过期/无证书设备页面据此禁选、红标；接口侧绑定仍会再次硬拦截。
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> views = new ArrayList<>();
        for (TemperatureProbe probe : probeService.listProbes()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("probe", probe);
            ProbeCertificate cert = probeService.currentValidCertificate(probe.getId(), now);
            view.put("usable", cert != null && !CcConstants.REVOKED.equals(probe.getStatus()));
            view.put("currentCertificate", cert);
            views.add(view);
        }
        return ApiResponse.ok(views);
    }

    /** 探头详情：档案 + 全部证书（证书内容字节不随 JSON 返回，下载走专用接口） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("probe", probeService.requireProbe(id));
        data.put("certificates", probeService.listCertificates(id));
        return ApiResponse.ok(data);
    }

    /**
     * 上传校准证书（multipart/form-data）：file 为证书文件，certNo/validFrom/validUntil 为表单项。
     * 落库后生成 SHA-256 摘要并写审计流水。
     */
    @PostMapping("/{id}/certificates")
    public ApiResponse<ProbeCertificate> uploadCertificate(
            @PathVariable("id") Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "certNo", required = false) String certNo,
            @RequestParam(value = "validFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validFrom,
            @RequestParam("validUntil")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validUntil) {
        return ApiResponse.ok(probeService.uploadCertificate(id, file, certNo, validFrom, validUntil));
    }

    /** 下载证书原始字节（Content-Disposition 附件）；每次下载写一条审计流水 */
    @GetMapping("/certificates/{certId}/download")
    public ResponseEntity<byte[]> download(@PathVariable("certId") Long certId) {
        ProbeCertificate cert = probeService.downloadCertificate(certId);
        String encodedName;
        try {
            encodedName = URLEncoder.encode(cert.getFileName(), "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            encodedName = "cert-" + cert.getId();
        }
        MediaType mediaType = cert.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(cert.getContentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cert-" + cert.getId() + "\"; filename*=UTF-8''" + encodedName)
                .contentType(mediaType)
                .contentLength(cert.getFileSize())
                .body(cert.getContent());
    }

    /** 作废证书：只置 VOID 并记录原因/人/时间，不删除文件；写审计流水 */
    @PostMapping("/certificates/{certId}/void")
    public ApiResponse<ProbeCertificate> voidCertificate(@PathVariable("certId") Long certId,
                                                         @Valid @RequestBody CertificateVoidRequest req) {
        return ApiResponse.ok(probeService.voidCertificate(certId, req.getReason()));
    }

    /**
     * 车载探头校准凭证关联（绑定/替换）。
     * 已绑定同一探头且采信证书不变时幂等返回既有绑定，重复请求不产生第二条结果；
     * 探头/证书变化则置旧绑定 REPLACED 并保留前后关系。过期设备在此被拦截。
     */
    @PostMapping("/bindings")
    public ApiResponse<BatchProbeBinding> bind(@Valid @RequestBody ProbeBindRequest req) {
        return ApiResponse.ok(probeService.bindOrReplace(req));
    }

    /** 主动解绑（置 UNBOUND，链条保留） */
    @PostMapping("/bindings/unbind")
    public ApiResponse<BatchProbeBinding> unbind(@Valid @RequestBody ProbeUnbindRequest req) {
        return ApiResponse.ok(probeService.unbind(req.getBatchId(), req.getReason()));
    }

    /** 批次探头绑定链条（ACTIVE + 全部 REPLACED/UNBOUND 历史） */
    @GetMapping("/bindings")
    public ApiResponse<List<BatchProbeBinding>> bindings(@RequestParam("batchId") Long batchId) {
        return ApiResponse.ok(probeService.listBindingChain(batchId));
    }
}

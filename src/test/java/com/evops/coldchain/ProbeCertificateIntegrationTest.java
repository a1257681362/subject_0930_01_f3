package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.BatchProbeBinding;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.ProbeCertificate;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TemperatureProbe;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.BatchProbeBindingMapper;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.ProbeCertificateMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车载温度探头校准凭证管理验收测试（V3，真实 HTTP 入口 + 落库追溯）。
 *
 * 验收主线：
 *  冷链药品运输先提交有效样本（建档探头+上传在效证书+派车），再重复请求
 *  “车载探头校准关联”，两次返回必须是同一条结果（库级唯一 + 服务幂等）；
 *  过期/作废/无证书设备在关联与发运入口被拦截；替换探头保留前后关系且历史温度不回写；
 *  证书上传生成 SHA-256 摘要，下载/作废逐次写审计流水；页面与列表接口标记过期设备不可用。
 *
 * 随机端口真实启服务，Shiro HTTP Basic 完整生效；不回滚，验证真实持久化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProbeCertificateIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired private ProbeService probeService;
    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private TransportBatchService batchService;

    @Autowired private BatchProbeBindingMapper bindingMapper;
    @Autowired private ProbeCertificateMapper certificateMapper;
    @Autowired private TemperatureLogMapper temperatureMapper;
    @Autowired private OpLogMapper opLogMapper;

    // ====================================================================
    // 1) 有效样本 + 重复请求“车载探头校准凭证关联”：不产生第二条结果
    // ====================================================================
    @Test
    void repeatedBindRequest_isIdempotent_noSecondRow() {
        Long batchId = prepareDispatchedBatch();
        TemperatureProbe probe = createProbeWithCert("SN-IDEMP-" + System.nanoTime(),
                LocalDateTime.now().plusYears(1), "idemp-cert");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", batchId);
        body.put("probeId", probe.getId());

        // 第一次关联
        JsonNode first = post("/probes/bindings", body);
        assertTrue(first.path("success").asBoolean(), first.toString());
        long firstBindingId = first.path("data").path("id").asLong();
        assertEquals(CcConstants.BIND_ACTIVE, first.path("data").path("status").asText());

        // 第二次完全相同的关联请求：返回同一条，不产生第二条结果
        JsonNode second = post("/probes/bindings", body);
        assertTrue(second.path("success").asBoolean(), second.toString());
        assertEquals(firstBindingId, second.path("data").path("id").asLong(),
                "重复关联必须返回既有绑定，不能产生第二条结果");
        assertEquals(CcConstants.BIND_ACTIVE, second.path("data").path("status").asText());

        // 落库铁证：该批次绑定表只有一行 ACTIVE，总数也只有一行
        List<BatchProbeBinding> rows = bindingMapper.selectList(
                new QueryWrapper<BatchProbeBinding>().eq("batch_id", batchId));
        assertEquals(1, rows.size(), "重复关联后绑定表仍只能有一行");
        assertEquals(CcConstants.BIND_ACTIVE, rows.get(0).getStatus());
        assertEquals(probe.getId(), rows.get(0).getProbeId());
    }

    // ====================================================================
    // 2) 过期/无证书/已作废设备：关联与发运双入口拦截
    // ====================================================================
    @Test
    void expired_voidOrMissingCert_devicesBlockedAtBindAndShip() {
        Long batchId = prepareDispatchedBatch();

        // 2.1 建档登记了一个已过有效期的探头、且上传的也是过期证书 -> 关联被拦截
        TemperatureProbe expired = createProbeWithCert("SN-EXPIRED-" + System.nanoTime(),
                LocalDateTime.now().minusDays(1), "expired-cert");
        JsonNode bindExpired = post("/probes/bindings", bindBody(batchId, expired.getId()));
        assertStop(bindExpired, "已过有效期");
        assertEquals(0, bindingMapper.selectCount(
                new QueryWrapper<BatchProbeBinding>().eq("batch_id", batchId)));

        // 2.2 建档了探头但从未上传任何证书 -> 关联被拦截
        TemperatureProbe noCert = createProbeOnly("SN-NOCERT-" + System.nanoTime(),
                LocalDateTime.now().plusYears(1));
        JsonNode bindNoCert = post("/probes/bindings", bindBody(batchId, noCert.getId()));
        assertStop(bindNoCert, "尚无有效校准证书");

        // 2.3 未绑定任何探头 -> 发运硬拦截
        JsonNode shipNoBind = post("/batches/" + batchId + "/ship", new LinkedHashMap<>());
        assertStop(shipNoBind, "凭证关联");

        // 2.4 绑定在效探头后把采信证书作废 -> 发运仍拦截（过期/作废设备不得发运）
        TemperatureProbe probe = createProbeWithCert("SN-VOID-" + System.nanoTime(),
                LocalDateTime.now().plusYears(1), "void-cert");
        JsonNode bindOk = post("/probes/bindings", bindBody(batchId, probe.getId()));
        assertTrue(bindOk.path("success").asBoolean(), bindOk.toString());
        Long certId = bindOk.path("data").path("certId").asLong();

        ProbeCertificate voided = probeService.voidCertificate(certId, "发证机构通知撤销");
        assertEquals(CcConstants.CERT_VOID, voided.getStatus());
        assertNotNull(voided.getVoidTime());

        JsonNode shipVoidCert = post("/batches/" + batchId + "/ship", new LinkedHashMap<>());
        assertStop(shipVoidCert, "已作废");

        // 2.5 列表接口把过期/无证书设备标记为不可用（页面据此禁选红标）
        JsonNode list = get("/probes");
        assertTrue(list.path("success").asBoolean(), list.toString());
        boolean expiredMarkedUnusable = false;
        for (JsonNode item : list.path("data")) {
            if (item.path("probe").path("id").asLong() == expired.getId()) {
                assertFalse(item.path("usable").asBoolean(), "过期探头 usable 必须为 false");
                expiredMarkedUnusable = true;
            }
        }
        assertTrue(expiredMarkedUnusable, "列表应返回过期探头并标记不可用");
    }

    // ====================================================================
    // 3) 替换探头：保留前后关系，历史温度记录不回写
    // ====================================================================
    @Test
    void replaceProbe_keepsChainAndNeverRewritesHistory() {
        Long batchId = prepareDispatchedBatch();
        TemperatureProbe p1 = createProbeWithCert("SN-OLD-" + System.nanoTime(),
                LocalDateTime.now().plusYears(1), "old-cert");
        TemperatureProbe p2 = createProbeWithCert("SN-NEW-" + System.nanoTime(),
                LocalDateTime.now().plusYears(2), "new-cert");

        // 绑定旧探头并发运、上报一条温度
        probeService.bindOrReplace(serviceBind(batchId, p1.getId()));
        batchService.ship(batchId);
        reportTemp(batchId, LocalDateTime.now().minusHours(2), new BigDecimal("5.10"));

        TemperatureLog t1 = temperatureMapper.selectList(
                new QueryWrapper<TemperatureLog>().eq("batch_id", batchId)).get(0);
        assertEquals(p1.getId(), t1.getProbeId(), "首条温度应挂在旧探头上");

        // 替换为新探头（带原因）
        Map<String, Object> replaceBody = bindBody(batchId, p2.getId());
        replaceBody.put("reason", "旧探头读数漂移，更换备用探头");
        JsonNode replaced = post("/probes/bindings", replaceBody);
        assertTrue(replaced.path("success").asBoolean(), replaced.toString());
        long newBindingId = replaced.path("data").path("id").asLong();

        // 链条：两行，旧 ACTIVE -> REPLACED 且新行 previousBindingId 指回旧行
        List<BatchProbeBinding> chain = bindingMapper.selectList(
                new QueryWrapper<BatchProbeBinding>().eq("batch_id", batchId).orderByAsc("id"));
        assertEquals(2, chain.size());
        BatchProbeBinding oldRow = chain.get(0);
        BatchProbeBinding newRow = chain.get(1);
        assertEquals(CcConstants.BIND_REPLACED, oldRow.getStatus());
        assertNotNull(oldRow.getReplaceTime());
        assertEquals("旧探头读数漂移，更换备用探头", oldRow.getReason());
        assertEquals(CcConstants.BIND_ACTIVE, newRow.getStatus());
        assertEquals(oldRow.getId(), newRow.getPreviousBindingId(), "新绑定必须指回前序绑定");
        assertEquals(newBindingId, newRow.getId());

        // 批次当前探头切换为新探头
        TransportBatch batch = batchService.requireBatch(batchId);
        assertEquals(p2.getId(), batch.getProbeId());

        // 再上报温度 -> 挂新探头；历史温度行仍挂旧探头，绝不回写
        reportTemp(batchId, LocalDateTime.now().minusHours(1), new BigDecimal("5.30"));
        List<TemperatureLog> temps = temperatureMapper.selectList(
                new QueryWrapper<TemperatureLog>().eq("batch_id", batchId).orderByAsc("id"));
        assertEquals(2, temps.size());
        assertEquals(p1.getId(), temps.get(0).getProbeId(), "历史温度不回写：仍挂旧探头");
        assertEquals(p2.getId(), temps.get(1).getProbeId(), "替换后温度挂新探头");

        // 台账可看到完整替换链条
        JsonNode ledger = get("/batches/" + batchId + "/ledger");
        assertTrue(ledger.path("success").asBoolean(), ledger.toString());
        JsonNode chainJson = ledger.path("data").path("probe").path("bindingChain");
        assertEquals(2, chainJson.size());
        assertEquals(p2.getId(), ledger.path("data").path("probe").path("currentProbe").path("id").asLong());
    }

    // ====================================================================
    // 4) 证书上传生成摘要；下载/作废逐次写审计流水
    // ====================================================================
    @Test
    void certUploadHasDigest_downloadAndVoidWriteAuditRows() {
        TemperatureProbe probe = createProbeOnly("SN-AUDIT-" + System.nanoTime(),
                LocalDateTime.now().plusYears(1));
        byte[] content = "audit-certificate-body".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "audit.pdf", "application/pdf", content);
        ProbeCertificate cert = probeService.uploadCertificate(probe.getId(), file, "CERT-AUDIT",
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusYears(1));

        // 摘要：SHA-256 为 64 位十六进制且与内容对应
        assertNotNull(cert.getSha256());
        assertEquals(64, cert.getSha256().length());
        assertEquals(cert.getSha256(), sha256(content));
        assertEquals(content.length, cert.getFileSize().intValue());

        long probeId = probe.getId();
        long downloadsBefore = countAudit(CcConstants.ACT_CERT_DOWNLOAD, probeId);

        // 经 HTTP 下载两次：原始字节一致，且每次都写一条审计流水
        ResponseEntity<byte[]> r1 = http.withBasicAuth("dispatcher", "dispatcher123").getForEntity(
                "http://localhost:" + port + BASE + "/probes/certificates/" + cert.getId() + "/download",
                byte[].class);
        assertEquals(HttpStatus.OK, r1.getStatusCode());
        assertArrayEquals(content, r1.getBody());
        assertTrue(r1.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("audit.pdf"));

        ResponseEntity<byte[]> r2 = http.withBasicAuth("dispatcher", "dispatcher123").getForEntity(
                "http://localhost:" + port + BASE + "/probes/certificates/" + cert.getId() + "/download",
                byte[].class);
        assertArrayEquals(content, r2.getBody());
        assertEquals(downloadsBefore + 2, countAudit(CcConstants.ACT_CERT_DOWNLOAD, probeId),
                "每次下载都必须写审计流水");

        // 上传/作废各有审计；作废再下载仍留档且同样写流水
        assertEquals(1, countAudit(CcConstants.ACT_CERT_UPLOAD, probeId));
        probeService.voidCertificate(cert.getId(), "证书信息登记错误");
        assertEquals(1, countAudit(CcConstants.ACT_CERT_VOID, probeId));
        assertEquals(CcConstants.CERT_VOID, certificateMapper.selectById(cert.getId()).getStatus());
    }

    // ====================================================================
    // 5) 管理页面可达（页面侧对过期设备禁选红标）
    // ====================================================================
    @Test
    void probeManagementPage_reachableWithBasicAuth() {
        ResponseEntity<String> page = http.withBasicAuth("dispatcher", "dispatcher123").getForEntity(
                "http://localhost:" + port + "/ui/cold-chain/probes", String.class);
        assertEquals(HttpStatus.OK, page.getStatusCode());
        assertTrue(page.getBody().contains("车载温度探头校准凭证管理"));
        assertTrue(page.getBody().contains("过期"), "页面应包含过期设备提示/拦截逻辑");
    }

    // ---------------- 辅助 ----------------

    private long prepareDispatchedBatch() {
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("探头验收药品-" + System.nanoTime());
        dc.setApprovalNo("国药准字Z" + Math.abs(System.nanoTime() % 100000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("探头验收医院");
        oc.setShipQty(5);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("探A·冷" + Math.abs(System.nanoTime() % 100000));
        vr.setCarrierName("探头验收物流");
        Vehicle vehicle = vehicleService.create(vr);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicle.getId());
        return batchService.dispatch(dr).getId();
    }

    private TemperatureProbe createProbeOnly(String serialNo, LocalDateTime validUntil) {
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo(serialNo);
        pc.setModel("TP-IT");
        pc.setMeasureMin(new BigDecimal("-40.00"));
        pc.setMeasureMax(new BigDecimal("80.00"));
        pc.setCalibOrg("验收计量院");
        pc.setValidUntil(validUntil);
        return probeService.createProbe(pc);
    }

    private TemperatureProbe createProbeWithCert(String serialNo, LocalDateTime validUntil, String contentTag) {
        TemperatureProbe probe = createProbeOnly(serialNo, validUntil);
        MockMultipartFile file = new MockMultipartFile(
                "file", serialNo + ".pdf", "application/pdf",
                ("cert-body-" + contentTag + "-" + serialNo).getBytes());
        probeService.uploadCertificate(probe.getId(), file, "CERT-" + Math.abs(serialNo.hashCode()),
                validUntil.minusYears(1), validUntil);
        return probe;
    }

    private com.evops.coldchain.dto.ProbeBindRequest serviceBind(Long batchId, Long probeId) {
        com.evops.coldchain.dto.ProbeBindRequest req = new com.evops.coldchain.dto.ProbeBindRequest();
        req.setBatchId(batchId);
        req.setProbeId(probeId);
        return req;
    }

    private void reportTemp(Long batchId, LocalDateTime time, BigDecimal temp) {
        com.evops.coldchain.dto.TempReportRequest req =
                new com.evops.coldchain.dto.TempReportRequest();
        req.setBatchId(batchId);
        req.setRecordTime(time);
        req.setTemperature(temp);
        req.setRecordedBy("车载温控仪");
        batchService.reportTemperature(req);
    }

    private Map<String, Object> bindBody(Long batchId, Long probeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("probeId", probeId);
        return m;
    }

    private long countAudit(String action, Long probeId) {
        return opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("action", action)
                .eq("entity_type", CcConstants.ENT_PROBE)
                .eq("entity_id", probeId));
    }

    private static String sha256(byte[] content) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            byte[] raw = d.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertStop(JsonNode resp, String keyword) {
        assertFalse(resp.path("success").asBoolean(), "应被拒绝: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private JsonNode post(String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            String resp = http.withBasicAuth("dispatcher", "dispatcher123")
                    .postForObject("http://localhost:" + port + BASE + path, entity, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode get(String path) {
        try {
            String resp = http.withBasicAuth("dispatcher", "dispatcher123")
                    .getForObject("http://localhost:" + port + BASE + path, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

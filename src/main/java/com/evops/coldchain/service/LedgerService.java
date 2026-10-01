package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.BatchProbeBinding;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionEvidence;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.ProbeCertificate;
import com.evops.coldchain.entity.Route;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.RouteVersion;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TemperatureProbe;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.DrugMapper;
import com.evops.coldchain.mapper.ExceptionEvidenceMapper;
import com.evops.coldchain.mapper.ProbeCertificateMapper;
import com.evops.coldchain.mapper.RouteMapper;
import com.evops.coldchain.mapper.RouteVersionMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TemperatureProbeMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 台账聚合查询：一个批次一张完整台账——
 * 批次（含档案快照）、发货单、车辆、温度留痕、异常+凭证、全链路操作日志，
 * 并对照药品档案当前版本，直观呈现“按原版本核对、档案可先行变更”。
 */
@Service
public class LedgerService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private DrugMapper drugMapper;
    @Resource
    private RouteMapper routeMapper;
    @Resource
    private RouteVersionMapper routeVersionMapper;
    @Resource
    private ExceptionEvidenceMapper evidenceMapper;
    @Resource
    private TemperatureProbeMapper probeMapper;
    @Resource
    private ProbeCertificateMapper certificateMapper;
    @Resource
    private TransportBatchService batchService;
    @Resource
    private ProbeService probeService;
    @Resource
    private RouteService routeService;
    @Resource
    private OpLogService opLogService;

    public Map<String, Object> batchLedger(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
        Drug drug = drugMapper.selectById(batch.getDrugId());
        Route route = batch.getRouteId() == null ? null : routeMapper.selectById(batch.getRouteId());
        RouteVersion routeVersion = batch.getRouteVersionId() == null
                ? null : routeVersionMapper.selectById(batch.getRouteVersionId());
        // 批次持有的路线温区分段快照（发运时固化，验收可逐段追到 t_cc_route_segment_snap）
        List<RouteSegmentSnap> routeSegmentSnaps = batch.getRouteVersionId() == null
                ? new ArrayList<>() : routeService.listSnapshotsByBatch(batchId);

        List<TemperatureLog> temps = batchService.listTemperatures(batchId);
        List<ExceptionEvent> exceptions = batchService.listExceptions(batchId);

        List<Map<String, Object>> exceptionList = new ArrayList<>();
        for (ExceptionEvent event : exceptions) {
            List<ExceptionEvidence> evidences = evidenceMapper.selectList(new QueryWrapper<ExceptionEvidence>()
                    .eq("exception_id", event.getId())
                    .orderByAsc("id"));
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("exception", event);
            em.put("evidences", evidences);
            exceptionList.add(em);
        }

        List<OpLog> opLogs = opLogService.listByBatch(batchId);

        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("batch", batch);
        ledger.put("order", order);
        ledger.put("vehicle", vehicle);
        ledger.put("drug", drug);
        // 路线温区分段：版本引用 + 发运时固化的逐段快照（温度判定/责任追溯以此为准）
        if (batch.getRouteVersionId() != null) {
            Map<String, Object> routeInfo = new LinkedHashMap<>();
            routeInfo.put("route", route);
            routeInfo.put("version", routeVersion);
            routeInfo.put("season", batch.getRouteSeason());
            routeInfo.put("frozenVersionNo", batch.getRouteVersionNo());
            routeInfo.put("segmentSnapshots", routeSegmentSnaps);
            ledger.put("route", routeInfo);
        }
        // 版本对照：核对依据以批次快照为准，drug.currentVersion 仅展示档案已演进到的版本
        Map<String, Object> versionCheck = new LinkedHashMap<>();
        versionCheck.put("batchFrozenVersion", batch.getDrugVersionNo());
        versionCheck.put("drugCurrentVersion", drug == null ? null : drug.getCurrentVersion());
        versionCheck.put("checkByFrozenVersion", true);
        ledger.put("versionCheck", versionCheck);

        // 车载探头校准凭证：当前绑定探头（含证书摘要/有效期）+ 完整替换链条
        ledger.put("probe", buildProbeInfo(batchId, batch.getProbeId()));

        ledger.put("temperatures", temps);
        ledger.put("exceptions", exceptionList);
        ledger.put("opLogs", opLogs);
        return ledger;
    }

    /** 组装当前探头、采信证书与完整绑定链条（替换前后关系）；历史温度行各自的 probeId 不回写 */
    private Map<String, Object> buildProbeInfo(Long batchId, Long currentProbeId) {
        List<BatchProbeBinding> chain = probeService.listBindingChain(batchId);
        if (chain.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> chainView = new ArrayList<>();
        for (BatchProbeBinding b : chain) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("binding", b);
            item.put("probe", probeMapper.selectById(b.getProbeId()));
            item.put("certificate", certificateMapper.selectById(b.getCertId()));
            chainView.add(item);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        TemperatureProbe currentProbe = currentProbeId == null ? null : probeMapper.selectById(currentProbeId);
        info.put("currentProbe", currentProbe);
        BatchProbeBinding active = probeService.findActiveBinding(batchId);
        if (active != null) {
            info.put("currentCertificate", certificateMapper.selectById(active.getCertId()));
        }
        info.put("bindingChain", chainView);
        return info;
    }
}

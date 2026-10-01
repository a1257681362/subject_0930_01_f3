package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.entity.BatchProbeBinding;
import com.evops.coldchain.entity.ProbeCertificate;
import com.evops.coldchain.entity.TemperatureProbe;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.BatchProbeBindingMapper;
import com.evops.coldchain.mapper.ProbeCertificateMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TemperatureProbeMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 车载温度探头与校准凭证管理（V3）。
 *
 * 不变量：
 * - 探头序列号全局唯一；建档登记量程、校准机构、有效期。
 * - 证书每次上传追加一行，落库内容字节并生成 SHA-256 摘要；作废只置 VOID，不删行不删文件；
 *   下载、作废逐次写审计流水（t_cc_op_log，实体类型 PROBE）。
 * - 能否用于绑定/发运/在途测温，只看“探头 ACTIVE + 存在未作废且有效期未到的证书”。
 * - 批次同时至多一条 ACTIVE 绑定（库级唯一约束兜底）；重复提交同一关联直接返回既有绑定，
 *   绝不产生第二条结果。
 * - 替换探头：旧绑定置 REPLACED 并由新绑定 previous_binding_id 串起前后关系；
 *   历史温度行保留各自实际采集探头，不回写。
 */
@Service
public class ProbeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Resource
    private TemperatureProbeMapper probeMapper;
    @Resource
    private ProbeCertificateMapper certificateMapper;
    @Resource
    private BatchProbeBindingMapper bindingMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 探头建档 ----------------

    @Transactional
    public TemperatureProbe createProbe(ProbeCreateRequest req) {
        if (req.getMeasureMin().compareTo(req.getMeasureMax()) >= 0) {
            throw new BusinessException("探头量程非法：下限必须低于上限");
        }
        Long dup = probeMapper.selectCount(new QueryWrapper<TemperatureProbe>()
                .eq("serial_no", req.getSerialNo()));
        if (dup != null && dup > 0) {
            throw new BusinessException("探头序列号已登记: " + req.getSerialNo());
        }

        TemperatureProbe probe = new TemperatureProbe();
        probe.setProbeNo(generateProbeNo());
        probe.setSerialNo(req.getSerialNo());
        probe.setModel(req.getModel());
        probe.setMeasureMin(req.getMeasureMin());
        probe.setMeasureMax(req.getMeasureMax());
        probe.setCalibOrg(req.getCalibOrg());
        probe.setValidUntil(req.getValidUntil());
        probe.setStatus(CcConstants.ACTIVE);
        probe.setRemark(req.getRemark());
        probeMapper.insert(probe);

        opLogService.log(CcConstants.ENT_PROBE, probe.getId(), null, CcConstants.ACT_PROBE_CREATE,
                String.format("探头建档：%s 序列号=%s 型号=%s 量程=%s~%s℃ 校准机构=%s 有效期至=%s%s",
                        probe.getProbeNo(), probe.getSerialNo(), nz(probe.getModel()),
                        probe.getMeasureMin(), probe.getMeasureMax(), probe.getCalibOrg(),
                        req.getValidUntil().format(TS),
                        isExpiredAt(req.getValidUntil(), LocalDateTime.now()) ? "（登记时已过有效期，须重新上传有效证书后方可绑定）" : ""));
        return probe;
    }

    public List<TemperatureProbe> listProbes() {
        return probeMapper.selectList(new QueryWrapper<TemperatureProbe>().orderByDesc("id"));
    }

    public TemperatureProbe requireProbe(Long probeId) {
        TemperatureProbe probe = probeMapper.selectById(probeId);
        if (probe == null) {
            throw new BusinessException("温度探头不存在: " + probeId);
        }
        return probe;
    }

    public TemperatureProbe requireActiveProbe(Long probeId) {
        TemperatureProbe probe = requireProbe(probeId);
        if (CcConstants.REVOKED.equals(probe.getStatus())) {
            throw new BusinessException("探头已停用: " + probe.getSerialNo());
        }
        return probe;
    }

    // ---------------- 校准证书：上传（生成摘要）/ 列表 / 下载 / 作废 ----------------

    /**
     * 上传校准证书：内容字节落库，计算 SHA-256 摘要；写审计流水。
     * 证书有效期晚于现有有效期时，同步延展探头档案的当前有效期。
     */
    @Transactional
    public ProbeCertificate uploadCertificate(Long probeId, MultipartFile file, String certNo,
                                              LocalDateTime validFrom, LocalDateTime validUntil) {
        TemperatureProbe probe = requireActiveProbe(probeId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("证书文件不能为空");
        }
        if (validUntil == null) {
            throw new BusinessException("证书有效期不能为空");
        }
        if (validFrom != null && validFrom.isAfter(validUntil)) {
            throw new BusinessException("校准日期不能晚于有效期");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("证书文件读取失败: " + e.getMessage());
        }
        String digest = sha256Hex(content);

        ProbeCertificate cert = new ProbeCertificate();
        cert.setProbeId(probe.getId());
        cert.setCertNo(certNo);
        cert.setFileName(file.getOriginalFilename() == null ? "certificate" : file.getOriginalFilename());
        cert.setContentType(file.getContentType());
        cert.setFileSize((long) content.length);
        cert.setSha256(digest);
        cert.setContent(content);
        cert.setValidFrom(validFrom);
        cert.setValidUntil(validUntil);
        cert.setStatus(CcConstants.CERT_VALID);
        certificateMapper.insert(cert);

        // 探头档案有效期随最新证书延展（只增不减；旧证书行原值不动）
        if (probe.getValidUntil() == null || validUntil.isAfter(probe.getValidUntil())) {
            probe.setValidUntil(validUntil);
            probeMapper.updateById(probe);
        }

        opLogService.log(CcConstants.ENT_PROBE, probe.getId(), null, CcConstants.ACT_CERT_UPLOAD,
                String.format("探头 %s（序列号=%s）上传校准证书：文件=%s，证书号=%s，有效期至=%s，%d 字节，SHA-256=%s",
                        probe.getProbeNo(), probe.getSerialNo(), cert.getFileName(), nz(certNo),
                        validUntil.format(TS), content.length, digest));
        return cert;
    }

    /** 证书列表（content 字段由实体上的 @JsonIgnore 剥离，不随 JSON 输出） */
    public List<ProbeCertificate> listCertificates(Long probeId) {
        requireProbe(probeId);
        return certificateMapper.selectList(new QueryWrapper<ProbeCertificate>()
                .eq("probe_id", probeId)
                .orderByDesc("id"));
    }

    public ProbeCertificate requireCertificate(Long certId) {
        ProbeCertificate cert = certificateMapper.selectById(certId);
        if (cert == null) {
            throw new BusinessException("校准证书不存在: " + certId);
        }
        return cert;
    }

    /**
     * 下载证书：原样返回内容字节，每次下载都写一条审计流水（含下载人、时间）。
     * 已作废证书仍可下载留档，流水中标注其状态。
     */
    @Transactional
    public ProbeCertificate downloadCertificate(Long certId) {
        ProbeCertificate cert = requireCertificate(certId);
        TemperatureProbe probe = requireProbe(cert.getProbeId());
        opLogService.log(CcConstants.ENT_PROBE, cert.getProbeId(), null, CcConstants.ACT_CERT_DOWNLOAD,
                String.format("下载校准证书：证书#%d（文件=%s，探头=%s/序列号=%s，状态=%s，SHA-256=%s）",
                        cert.getId(), cert.getFileName(), probe.getProbeNo(), probe.getSerialNo(),
                        cert.getStatus(), cert.getSha256()));
        return cert;
    }

    /** 作废证书：只置 VOID + 作废原因/时间/人，不删除文件与任何历史记录 */
    @Transactional
    public ProbeCertificate voidCertificate(Long certId, String reason) {
        ProbeCertificate cert = requireCertificate(certId);
        if (CcConstants.CERT_VOID.equals(cert.getStatus())) {
            throw new BusinessException("证书已作废，不能重复作废");
        }
        cert.setStatus(CcConstants.CERT_VOID);
        cert.setVoidReason(reason);
        cert.setVoidTime(LocalDateTime.now());
        cert.setVoidBy(UserContext.currentId());
        certificateMapper.updateById(cert);

        // 重算探头档案有效期：取剩余有效证书的最晚有效期；一张不剩则保留原值（此时设备已不可绑定）
        refreshProbeValidUntil(cert.getProbeId());

        TemperatureProbe probe = requireProbe(cert.getProbeId());
        opLogService.log(CcConstants.ENT_PROBE, cert.getProbeId(), null, CcConstants.ACT_CERT_VOID,
                String.format("作废校准证书：证书#%d（文件=%s，探头=%s/序列号=%s，原有效期至=%s，SHA-256=%s），原因：%s",
                        cert.getId(), cert.getFileName(), probe.getProbeNo(), probe.getSerialNo(),
                        cert.getValidUntil().format(TS), cert.getSha256(), reason));
        return cert;
    }

    private void refreshProbeValidUntil(Long probeId) {
        TemperatureProbe probe = probeMapper.selectById(probeId);
        if (probe == null) {
            return;
        }
        List<ProbeCertificate> valid = certificateMapper.selectList(new QueryWrapper<ProbeCertificate>()
                .eq("probe_id", probeId)
                .eq("status", CcConstants.CERT_VALID)
                .orderByDesc("valid_until"));
        if (!valid.isEmpty()) {
            probe.setValidUntil(valid.get(0).getValidUntil());
            probeMapper.updateById(probe);
        }
    }

    // ---------------- 有效期判定（接口/页面共用的唯一口径） ----------------

    /** 指定时点“仍在有效期内且未作废”的证书；不存在返回 null */
    public ProbeCertificate currentValidCertificate(Long probeId, LocalDateTime at) {
        List<ProbeCertificate> certs = certificateMapper.selectList(new QueryWrapper<ProbeCertificate>()
                .eq("probe_id", probeId)
                .eq("status", CcConstants.CERT_VALID)
                .gt("valid_until", at)
                .orderByDesc("valid_until")
                .orderByDesc("id")
                .last("LIMIT 1"));
        return certs.isEmpty() ? null : certs.get(0);
    }

    public boolean isUsable(Long probeId, LocalDateTime at) {
        TemperatureProbe probe = probeMapper.selectById(probeId);
        return probe != null
                && !CcConstants.REVOKED.equals(probe.getStatus())
                && currentValidCertificate(probeId, at) != null;
    }

    /**
     * 绑定/发运前置校验：探头必须 ACTIVE，且持有未作废、有效期未到的证书。
     * 返回采信的证书；任何一条不满足都以明确停点拒绝（过期设备在此被拦截）。
     */
    @Transactional(readOnly = true)
    public ProbeCertificate requireUsableProbe(Long probeId) {
        LocalDateTime now = LocalDateTime.now();
        TemperatureProbe probe = requireProbe(probeId);
        if (CcConstants.REVOKED.equals(probe.getStatus())) {
            throw new BusinessException(String.format(
                    "探头 %s（序列号=%s）已停用，不能用于绑定/发运", probe.getProbeNo(), probe.getSerialNo()));
        }
        ProbeCertificate cert = currentValidCertificate(probeId, now);
        if (cert == null) {
            long validCount = certificateMapper.selectCount(new QueryWrapper<ProbeCertificate>()
                    .eq("probe_id", probeId)
                    .eq("status", CcConstants.CERT_VALID));
            throw new BusinessException(String.format(
                    "探头 %s（序列号=%s）%s，不能用于绑定/发运；请先上传仍在有效期内的校准证书",
                    probe.getProbeNo(), probe.getSerialNo(),
                    validCount > 0 ? "校准证书已过有效期（有效期至 " + probe.getValidUntil().format(TS) + "）"
                            : "尚无有效校准证书"));
        }
        return cert;
    }

    // ---------------- 批次-探头绑定（幂等关联 / 替换留链 / 解绑） ----------------

    public BatchProbeBinding findActiveBinding(Long batchId) {
        List<BatchProbeBinding> list = bindingMapper.selectList(new QueryWrapper<BatchProbeBinding>()
                .eq("batch_id", batchId)
                .eq("status", CcConstants.BIND_ACTIVE));
        return list.isEmpty() ? null : list.get(0);
    }

    public BatchProbeBinding requireActiveBinding(Long batchId) {
        BatchProbeBinding binding = findActiveBinding(batchId);
        if (binding == null) {
            throw new BusinessException("发运前必须先完成“车载探头校准凭证关联”：批次尚未绑定仍在有效期内的探头");
        }
        return binding;
    }

    /** 绑定链条（含 ACTIVE 与全部 REPLACED/UNBOUND 历史，按时间升序） */
    public List<BatchProbeBinding> listBindingChain(Long batchId) {
        return bindingMapper.selectList(new QueryWrapper<BatchProbeBinding>()
                .eq("batch_id", batchId)
                .orderByAsc("id"));
    }

    /**
     * 车载探头校准凭证关联（绑定/替换/幂等返回）。
     * - 批次必须存在且未签收/未撤回；探头与证书必须在效。
     * - 已有 ACTIVE 绑定且探头、采信证书均未变化：原样返回既有绑定，不插入第二行、不追加日志。
     * - 已有 ACTIVE 绑定但探头或采信证书变化：旧绑定置 REPLACED（previous 链），新绑定指回它。
     * - 无 ACTIVE 绑定：新增 ACTIVE 绑定。库级唯一约束对并发重复提交兜底。
     */
    @Transactional
    public BatchProbeBinding bindOrReplace(ProbeBindRequest req) {
        TransportBatch batch = requireBindableBatch(req.getBatchId());
        ProbeCertificate cert = requireUsableProbe(req.getProbeId());
        TemperatureProbe probe = requireProbe(req.getProbeId());

        BatchProbeBinding existing = findActiveBinding(batch.getId());
        if (existing != null) {
            if (existing.getProbeId().equals(probe.getId()) && existing.getCertId().equals(cert.getId())) {
                // 同一关联重复请求：幂等返回，绝不产生第二条结果
                return existing;
            }
            return replace(batch, existing, probe, cert, req.getReason());
        }

        BatchProbeBinding binding = newActiveBinding(batch.getId(), probe.getId(), cert.getId(), null);
        try {
            bindingMapper.insert(binding);
        } catch (DuplicateKeyException e) {
            // 并发重复关联：唯一约束兜底，重新读取既有 ACTIVE 绑定返回（不产生第二条）
            BatchProbeBinding winner = findActiveBinding(batch.getId());
            if (winner != null) {
                return winner;
            }
            throw e;
        }
        batch.setProbeId(probe.getId());
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_PROBE, probe.getId(), batch.getId(), CcConstants.ACT_PROBE_BIND,
                String.format("批次 %s 关联车载探头：%s（序列号=%s），采信证书#%d（有效期至=%s，SHA-256=%s）",
                        batch.getBatchNo(), probe.getProbeNo(), probe.getSerialNo(), cert.getId(),
                        cert.getValidUntil().format(TS), cert.getSha256()));
        return binding;
    }

    private BatchProbeBinding replace(TransportBatch batch, BatchProbeBinding old, TemperatureProbe probe,
                                      ProbeCertificate cert, String reason) {
        TemperatureProbe oldProbe = requireProbe(old.getProbeId());
        LocalDateTime now = LocalDateTime.now();

        old.setStatus(CcConstants.BIND_REPLACED);
        old.setReplaceTime(now);
        old.setReason(reason);
        bindingMapper.updateById(old);

        BatchProbeBinding fresh = newActiveBinding(batch.getId(), probe.getId(), cert.getId(), old.getId());
        fresh.setReason(reason);
        bindingMapper.insert(fresh);

        batch.setProbeId(probe.getId());
        batchMapper.updateById(batch);

        boolean sameProbe = old.getProbeId().equals(probe.getId());
        opLogService.log(CcConstants.ENT_PROBE, probe.getId(), batch.getId(), CcConstants.ACT_PROBE_REPLACE,
                String.format("批次 %s 替换车载探头：%s（序列号=%s，证书#%d）→ %s（序列号=%s，证书#%d，有效期至=%s）%s；旧绑定#%d 置 REPLACED，历史温度记录不回写",
                        batch.getBatchNo(),
                        oldProbe.getProbeNo(), oldProbe.getSerialNo(), old.getCertId(),
                        probe.getProbeNo(), probe.getSerialNo(), cert.getId(), cert.getValidUntil().format(TS),
                        sameProbe ? "（同一探头更新采信证书）" : "",
                        old.getId()));
        return fresh;
    }

    /** 主动解绑（未发运前取消等）：ACTIVE 置 UNBOUND，保留链条；批次发运仍须重新绑定 */
    @Transactional
    public BatchProbeBinding unbind(Long batchId, String reason) {
        TransportBatch batch = requireBindableBatch(batchId);
        BatchProbeBinding binding = requireActiveBinding(batchId);
        binding.setStatus(CcConstants.BIND_UNBOUND);
        binding.setUnbindTime(LocalDateTime.now());
        binding.setReason(reason);
        bindingMapper.updateById(binding);

        // 显式置空批次当前探头（NOT_NULL 默认策略会跳过 null 字段，须用 UpdateWrapper）
        batchMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TransportBatch>()
                .eq(TransportBatch::getId, batch.getId())
                .set(TransportBatch::getProbeId, null));

        TemperatureProbe probe = requireProbe(binding.getProbeId());
        opLogService.log(CcConstants.ENT_PROBE, probe.getId(), batchId, CcConstants.ACT_PROBE_UNBIND,
                String.format("批次 %s 解除探头关联：%s（序列号=%s），原因：%s",
                        batch.getBatchNo(), probe.getProbeNo(), probe.getSerialNo(), nz(reason)));
        return binding;
    }

    /**
     * 发运/在途测温前对“当前活跃绑定”做强校验：
     * 绑定当时采信的证书必须仍然 VALID 且未过有效期，探头未停用。
     * 证书一旦在途过期或被作废，温度上报立即在此被拦截。
     */
    @Transactional(readOnly = true)
    public BatchProbeBinding validateActiveBinding(Long batchId) {
        BatchProbeBinding binding = requireActiveBinding(batchId);
        TemperatureProbe probe = requireProbe(binding.getProbeId());
        if (CcConstants.REVOKED.equals(probe.getStatus())) {
            throw new BusinessException(String.format(
                    "当前绑定探头 %s（序列号=%s）已停用，温度上报被拦截；请替换有效探头后再继续",
                    probe.getProbeNo(), probe.getSerialNo()));
        }
        ProbeCertificate cert = certificateMapper.selectById(binding.getCertId());
        LocalDateTime now = LocalDateTime.now();
        if (cert == null || CcConstants.CERT_VOID.equals(cert.getStatus())) {
            throw new BusinessException(String.format(
                    "批次绑定的探头 %s（序列号=%s）采信证书已作废，操作被拦截；请重新关联有效校准凭证",
                    probe.getProbeNo(), probe.getSerialNo()));
        }
        if (!cert.getValidUntil().isAfter(now)) {
            throw new BusinessException(String.format(
                    "批次绑定的探头 %s（序列号=%s）校准证书已于 %s 过期，操作被拦截；请替换仍在有效期内的探头",
                    probe.getProbeNo(), probe.getSerialNo(), cert.getValidUntil().format(TS)));
        }
        return binding;
    }

    private TransportBatch requireBindableBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能关联探头: " + batch.getBatchNo());
        }
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能再变更探头关联: " + batch.getBatchNo());
        }
        return batch;
    }

    private BatchProbeBinding newActiveBinding(Long batchId, Long probeId, Long certId, Long previousBindingId) {
        BatchProbeBinding binding = new BatchProbeBinding();
        binding.setBatchId(batchId);
        binding.setProbeId(probeId);
        binding.setCertId(certId);
        binding.setPreviousBindingId(previousBindingId);
        binding.setBindTime(LocalDateTime.now());
        binding.setStatus(CcConstants.BIND_ACTIVE);
        return binding;
    }

    // ---------------- 工具 ----------------

    private boolean isExpiredAt(LocalDateTime validUntil, LocalDateTime at) {
        return !validUntil.isAfter(at);
    }

    private String generateProbeNo() {
        return String.format("TC%08d", sequenceMapper.nextProbeNo());
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(content);
            char[] out = new char[raw.length * 2];
            for (int i = 0; i < raw.length; i++) {
                int v = raw[i] & 0xFF;
                out[i * 2] = HEX_CHARS[v >>> 4];
                out[i * 2 + 1] = HEX_CHARS[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

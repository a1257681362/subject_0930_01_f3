# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 冷链药品运输台账（V1）

在骨架上新增 `com.evops.coldchain` 模块，跑通建档 → 派车 → 发运 → 签收 → 异常标记；
结算与监管报送留待后续版本。

### 核心规则

- **药品档案版本化**：建档生成 V1；变更只追加新版本行，旧版本批准文号/温区/包装原值永不修改。
- **批次按原版本核对**：发货单创建即锁定档案版本，派车时把该版本（批准文号、储存温区上下限、
  包装规格、版本号）快照冗余到运输批次。档案之后即使先变更，在途批次核对、签收一律以快照为准。
- **稳定批次号**：由 H2 序列生成（`PC年+6位序列`），数据库层唯一、单调、重启不复用。
- **途中温度按时间留痕**：以批次快照温区判定是否越限，越限自动追加温度异常事件。
- **异常只追加**：异常事件、凭证（附件/照片/说明）全部 INSERT-only，不覆盖、不更新、不删除；
  处理闭环只追加处理说明，不改原描述与凭证。
- **签收才闭环**：签收需按快照核对实物批准文号、包装规格、数量，且无未处理（OPEN）异常。
- **撤回不删数据**：仅置 `REVOKED` 作废标记；批次、温度、异常、凭证、日志原始行全部保留。
- **关键操作留痕**：建档/变更/派车/发运/温度上报/异常标记/凭证追加/异常处理/签收/撤回
  统一写入 `t_cc_op_log`，记录操作者账号、操作者 ID 与时间（MyBatis-Plus 自动填充）。

### 表结构（详见 schema.sql）

`t_cc_drug` / `t_cc_drug_version`（档案+版本）、`t_cc_vehicle`、`t_cc_shipment_order`、
`t_cc_transport_batch`（含档案快照）、`t_cc_temperature_log`、`t_cc_exception`、
`t_cc_exception_evidence`、`t_cc_op_log`，以及 `seq_cc_drug_no/seq_cc_order_no/seq_cc_batch_no` 序列。
V2 另增路线温区分段四张表（见下节）与 `seq_cc_route_no` 序列。

### 接口（均在 `/api/cold-chain` 下，HTTP Basic 认证）

内置账号：`dispatcher/dispatcher123`（调度）、`carrier/carrier123`（在途）、
`receiver/receiver123`（签收）、`bootstrap/bootstrap`。

| 动作 | 方法与路径 |
| --- | --- |
| 药品建档 | `POST /drugs` |
| 档案变更（追加版本） | `POST /drugs/{id}/versions` |
| 查看档案版本 | `GET /drugs/{id}/versions` |
| 车辆登记 / 列表 | `POST /vehicles` / `GET /vehicles` |
| 创建发货单（锁版本） | `POST /orders` |
| 派车（生成批次+快照） | `POST /batches/dispatch` |
| 发运 | `POST /batches/{id}/ship` |
| 温度上报 | `POST /batches/temperatures` |
| 异常标记 | `POST /batches/exceptions` |
| 追加异常凭证 | `POST /batches/exceptions/evidence` |
| 异常处理闭环 | `POST /batches/exceptions/{id}/resolve` |
| 按快照核对签收 | `POST /batches/sign` |
| 撤回（作废不删） | `POST /batches/revoke` |
| 批次完整台账 | `GET /batches/{id}/ledger` |

示例：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/drugs \
  -d '{"drugName":"HPV疫苗","approvalNo":"国药准字S20200011","tempMin":2,"tempMax":8,"packageSpec":"0.5ml/支"}'
```

### 验证

`mvn test` 运行 `ColdChainFlowIntegrationTest`，覆盖全链路及关键不变量：
档案先变更后批次仍按 V1 核对、越限自动异常、凭证追加不覆盖、OPEN 异常拦截签收、
按原版本签收闭环、撤回不删记录、批次号稳定唯一、全链路操作者/时间留痕。

## 冷链运输 V2：路线温区分段

在 V1 台账上增加“按季节维护的路线温区分段”。同一路线可按春/夏/秋/冬（及 GENERAL 通用）
各维护一套版本，每个版本由若干相邻分段组成，每段指定**允许温度、越限缓冲时长（分钟）和责任人**。

### 核心规则

- **分段相邻无空档/无重叠**：分段按里程 `[fromKm, toKm)` 从 0 连续覆盖到路线 `total_km`；
  相邻段必须首尾相接（首段从 0 起、末段到总里程止）。温区倒置、缓冲为负、责任人为空、
  超出总里程一律拒绝，并返回明确停点说明。
- **季节版本只追加**：草稿 `DRAFT` 可反复编排（仅草稿分段允许重排）；发布后置 `EFFECTIVE`，
  同季节当前在效版本被热替换为 `SUPERSEDED`，历史版本与分段行原值永不修改。
- **发布前展示差异**：`GET /routes/versions/{id}/diff` 按段序号给出
  `ADDED/REMOVED/MODIFIED/UNCHANGED`；发布响应也回带差异。
- **新版本只影响未发运批次**：派车只登记走哪条路线；**发运（批次开始执行）瞬间**才按
  “当前季节 + 当前时间”选取生效版本（当季未配置回退 GENERAL），把其全部分段**逐段快照**到
  `t_cc_route_segment_snap`。因此派车后、发运前发布的新版本仍会作用于该批；一旦发运，版本
  热替换/撤回即与本批次脱钩，在途、签收继续引用原快照。
- **按分段温区 + 缓冲时长判定越限**：温度上报需带 `mileageKm`，按里程命中快照分段温区；
  同一段内连续越限未超过该段缓冲时长只留痕、不开异常，超过缓冲才自动开温度异常并定位到该段
  责任人；中途回正常或切换分段会打断连续越限计时。
- **撤回只能撤销尚未生效的版本**：`DRAFT`，或已发布但生效时间窗未到且**从未被批次引用**的
  版本可撤回为 `REVOKED`；已生效或已被批次固化快照的版本拒绝撤回（只能再发新版本调整）。
- 预约生效（未来 `effectiveFrom`）的版本，其时间窗不得与同季节其他生效版本重叠。
- 建档/草稿/发布/撤回与派车均写 `t_cc_op_log`（实体类型 `ROUTE`）。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-2`）

- 新增 `t_cc_route`（路线主档）、`t_cc_route_version`（季节版本）、
  `t_cc_route_segment`（版本分段，只追加）、`t_cc_route_segment_snap`（批次逐段快照）、
  序列 `seq_cc_route_no`。
- `t_cc_transport_batch` 增 `route_id/route_version_id/route_season/route_version_no`；
  `t_cc_temperature_log` 增 `mileage_km/segment_seq`；`t_cc_exception` 增 `segment_seq`。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 路线建档 / 列表 / 详情 | `POST /routes` / `GET /routes` / `GET /routes/{id}` |
| 按季节编排分段草稿 | `POST /routes/{id}/versions/draft` |
| 查看版本分段 | `GET /routes/versions/{versionId}/segments` |
| 发布前分段差异 | `GET /routes/versions/{versionId}/diff` |
| 发布（热替换/预约生效） | `POST /routes/versions/publish` |
| 撤回未生效版本 | `POST /routes/versions/withdraw` |
| 派车携带路线（逐段快照） | `POST /batches/dispatch`（body 增加 `routeId`） |
| 分段温度上报（带里程） | `POST /batches/temperatures`（body 增加 `mileageKm`） |

示例（两段连续覆盖 0~100km 的通用季节版本）：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/routes \
  -d '{"routeName":"沪杭冷链线","origin":"上海中心仓","destination":"杭州市一院","totalKm":100.00}'

curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/routes/1/versions/draft \
  -d '{"season":"GENERAL","changeReason":"秋冬季分段",
       "segments":[
         {"fromKm":0,"toKm":50,"tempMin":2,"tempMax":8,"bufferMinutes":30,"ownerName":"王冷链","ownerPhone":"13800000001"},
         {"fromKm":50,"toKm":100,"tempMin":0,"tempMax":5,"bufferMinutes":60,"ownerName":"李干线","ownerPhone":"13800000002"}]}'
```

### V2 验收

`RouteTemperatureSegmentIntegrationTest` 以真实 HTTP 入口（TestRestTemplate + Basic 认证）驱动，
再用 Mapper 直查物理表，确保配置结果**一路追到温区分段与批次快照落库**：
路线建档→季节草稿→发布前差异→发布热替换→派车登记路线→发运逐段快照→按里程分段温区/缓冲判定；
并验证空档/重叠/未覆盖全程/倒置/越界/温区非法/缓冲为负/责任人为空等**失败停点**返回明确说明，
新版本只影响未派车批次、已派车批次持原快照、撤回仅限未生效未被引用版本、季节优先与 GENERAL 回退、
预约时间窗重叠拒绝。

## 冷链运输 V3：车载温度探头校准凭证管理

在 V1/V2 台账上为“车载温度探头”建立校准凭证闭环：探头建档登记**序列号、量程、校准机构、有效期**，
校准证书逐次上传并生成摘要；**发运前必须绑定仍在有效期内的探头**；替换探头保留前后关系，
历史温度记录不回写；接口与页面双侧拦截过期设备；证书下载/作废逐次写审计流水。

### 核心规则

- **探头建档**：序列号全局唯一（`uk_cc_probe_serial`），登记量程上下限（`measure_min < measure_max`）、
  校准机构与当前校准有效期。建档允许登记已过期设备，但**关联/发运/测温**三处一律拦截。
- **证书只追加、作废不删文件**：证书以二进制落库 `t_cc_probe_certificate`，上传时计算
  **SHA-256 摘要**（64 位十六进制）并记录大小/MIME/有效期；作废只置 `VOID` + 作废原因/时间/人，
  不删除文件与历史绑定。是否可用只看“探头 ACTIVE + 存在未作废且 `valid_until` 晚于当前时刻的证书”。
- **发运前必须绑定**：已派车批次在发运（`/batches/{id}/ship`）前必须先完成
  “车载探头校准凭证关联”；发运瞬间再次校验采信证书仍 VALID 且未过期，否则硬拦截。
- **关联幂等，重复请求不产生第二条结果**：同一批次同一时刻至多一条 `ACTIVE` 绑定——
  库级用 H2 生成列唯一约束 `uk_cc_active_binding`（仅 ACTIVE 行取 `batch_id`，历史行为 NULL 不冲突）
  兜底并发；服务层对“同探头+同采信证书”的重复请求直接返回既有绑定，不插入第二行、不追加日志。
- **替换保留前后关系**：换探头时旧绑定置 `REPLACED`（记替换时间/原因），新绑定以
  `previous_binding_id` 指回旧绑定，形成完整链条。
- **历史温度不回写**：每条温度留痕记录实际采集探头 `probe_id`；替换后新温度挂新探头，
  历史行保留旧探头原值，绝不更新。
- **在途证书失效即拦截**：温度上报时再次校验当前绑定探头的采信证书仍 VALID 且未过期；
  证书在途过期或被作废，后续温度上报立即被停点拦截。
- **接口与页面双拦截**：`GET /probes` 列表对每个探头返回 `usable` 标记；管理页
  （`/ui/cold-chain/probes`，HTTP Basic）对过期/无证书/作废设备**红标且下拉禁选**，接口侧仍硬校验。
- **审计流水**：建档/证书上传/下载/作废/绑定/替换/解绑统一写 `t_cc_op_log`（实体类型 `PROBE`），
  **下载与作废逐次写流水**（含下载人、作废原因）。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-3`）

- 新增 `t_cc_temperature_probe`（探头档案）、`t_cc_probe_certificate`（证书，含 BLOB 内容与 SHA-256）、
  `t_cc_batch_probe_binding`（批次-探头绑定及替换链，含生成列唯一约束），
  序列 `seq_cc_probe_no`（探头编号 `TC`+8 位）。
- `t_cc_transport_batch` 增 `probe_id`（当前探头）；`t_cc_temperature_log` 增 `probe_id`（实际采集探头）。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 探头建档（序列号/量程/机构/有效期） | `POST /probes` |
| 探头列表（含 usable 在效标记） | `GET /probes` |
| 探头详情（档案+全部证书） | `GET /probes/{id}` |
| 上传校准证书（multipart，生成 SHA-256） | `POST /probes/{id}/certificates` |
| 下载证书（逐次写审计） | `GET /probes/certificates/{certId}/download` |
| 作废证书（置 VOID，写审计） | `POST /probes/certificates/{certId}/void` |
| 车载探头校准凭证关联（幂等/替换） | `POST /probes/bindings` |
| 主动解绑 | `POST /probes/bindings/unbind` |
| 批次探头绑定链条 | `GET /probes/bindings?batchId=` |
| 探头凭证管理页面 | `GET /ui/cold-chain/probes`（浏览器） |

示例（建档 → 上传证书 → 发运前关联 → 发运）：

```bash
# 1) 探头建档（有效期至未来）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/probes \
  -d '{"serialNo":"SN20261001","model":"TP-208","measureMin":-40,"measureMax":80,
       "calibOrg":"省计量科学研究院","validUntil":"2027-10-01T00:00:00"}'

# 2) 上传校准证书（multipart，落库后返回 sha256 摘要）
curl -u dispatcher:dispatcher123 -X POST \
  http://localhost:8080/api/cold-chain/probes/1/certificates \
  -F 'file=@/path/to/calib.pdf' -F 'certNo=JL-2026-0001' \
  -F 'validFrom=2026-10-01T00:00:00' -F 'validUntil=2027-10-01T00:00:00'

# 3) 发运前“车载探头校准凭证关联”（重复提交同一 body 返回同一条，不产生第二条结果）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' -X POST \
  http://localhost:8080/api/cold-chain/probes/bindings \
  -d '{"batchId":1,"probeId":1}'

# 4) 发运（未绑定或证书已过期/作废会被拦截）
curl -u dispatcher:dispatcher123 -X POST http://localhost:8080/api/cold-chain/batches/1/ship
```

### V3 验收

`ProbeCertificateIntegrationTest` 以真实 HTTP 入口驱动并直查物理表，覆盖：
冷链药品运输先提交有效样本后**重复请求关联两次只返回同一条、绑定表只有一行 ACTIVE**；
过期证书/无证书/证书作废设备在关联与发运入口被拦截；未绑定不得发运；
替换探头旧绑定置 REPLACED、新绑定 `previous_binding_id` 串链、历史温度不回写而新温度挂新探头；
证书上传 SHA-256 摘要正确、HTTP 下载字节一致且**每次下载/作废都写审计流水**；管理页可达且含过期拦截。
既有 V1/V2 套件同步在各批次发运前补做探头关联。

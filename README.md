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

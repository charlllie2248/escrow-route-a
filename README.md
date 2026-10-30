# Escrow 路线 A 后端

本项目是《区块链技术与应用》大作业路线 A 的实现，使用 Spring Boot 和 Web3j 为 Escrow 托管合约提供六个 REST 接口。后端专用账户作为仲裁人，买家与卖家通过 MetaMask 和 Remix 操作合约。

本轮验收已完成两条业务流程：订单 0 正常成交，最终为 `Completed`；订单 1 发起争议后退款，最终为 `Refunded`。两笔订单金额均为 30 个 CxlToken，精度为 18。

## 环境

- WSL Ubuntu 22.04
- JDK 21
- Gradle 8.13，使用项目自带的 Wrapper
- Spring Boot 3.2.3
- Web3j 4.14.0
- POTOS Testnet，RPC 为 `https://rpc-testnet.potos.hk`

## 编译

```bash
bash gradlew classes
```

提交副本已执行上面的完整构建，Solidity 编译、Java 合约封装生成和 Java 编译均通过，结果为 `BUILD SUCCESSFUL`。项目已包含生成的 Java 合约封装；如果本机已有 Java 依赖缓存，只想编译现有封装，可执行 `bash gradlew classes --offline -x generateContractWrappers -x compileSolidity -x nodeSetup`，该命令也已验证通过。修改 Solidity 合约后，需要重新生成：

```bash
bash gradlew generateContractWrappers
bash gradlew classes
```

首次构建需要下载 Gradle 和 Maven 依赖。

## 配置与启动

`src/main/resources/application.properties` 中的私钥配置为：

```properties
web3j.private-key=${WEB3J_PRIVATE_KEY}
```

启动前在 Ubuntu Bash 中输入本机测试账户私钥，输入内容不会显示在终端：

```bash
read -r -s -p "Backend private key: " WEB3J_PRIVATE_KEY
printf '\n'
export WEB3J_PRIVATE_KEY
bash gradlew bootRun
```

服务默认监听 `http://localhost:8080`。仲裁已有的本轮合约需要使用该合约的仲裁账户。其他测试账户可以查询已有合约，或部署以自己为仲裁人的新合约。构建本项目不需要私钥。

## 本轮公开地址

| 项目 | 值 |
| --- | --- |
| 后端仲裁账户 | `0xd41B05DCE1C1Ac4A7fC1DC4C792fD799cf140D12` |
| 买家 | `0x434F295aeBf8948170Cd8f180CDCD99BF4Fba66A` |
| 卖家 HHT | `0x97810ea00F6Fa7de74ca2Abc71ccDb62C68FEcA0` |
| 本轮 Escrow | `0x3d52d4F4F35AE4afaC0b23AFA73621F8D052BAF3` |
| 部署区块 | `2671843` |
| CxlToken | `0x5FB9538A8B7b2087a7E556622e45A07425F3a401` |

区块浏览器：[POTOS Testnet Explorer](https://scan-testnet.potos.hk/)。

## 六个接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/escrow/deploy` | 部署新 Escrow，当前后端账户成为仲裁人 |
| POST | `/api/escrow/load?address=...&fromBlock=...` | 加载已部署合约并设置事件扫描起始区块 |
| GET | `/api/escrow/address` | 查询当前合约地址与仲裁人 |
| GET | `/api/escrow/orders/{id}` | 读取订单详情和实时状态 |
| GET | `/api/escrow/orders` | 从 OrderCreated 事件重建订单列表 |
| POST | `/api/escrow/orders/{id}/resolve?sellerWins=false` | 对争议订单执行仲裁 |

后端重启后，内存中的加载信息会丢失。在另一终端加载本轮合约：

```bash
API=http://localhost:8080/api/escrow
curl -sS -w '\n' -X POST "$API/load" \
  --data-urlencode "address=0x3d52d4F4F35AE4afaC0b23AFA73621F8D052BAF3" \
  --data-urlencode "fromBlock=2671843"
curl -sS -w '\n' "$API/address"
curl -sS -w '\n' "$API/orders/0"
curl -sS -w '\n' "$API/orders/1"
```

订单列表每批扫描 10 个区块，先读取一次最新区块，再逐批调用 `eth_getLogs`。列表包含创建事件的数据，实时状态由详情接口查询。随着部署至今的区块跨度增加，完整扫描可能较慢。不要通过重新部署或重复交易来恢复已有记录。

`sellerWins=true` 表示卖家胜诉，转账给订单卖家；`sellerWins=false` 表示退款给订单买家。只有仲裁人可以对 `Disputed` 订单执行 `resolve`。

## 代码与验收记录

- `src/main/java/com/wetech/demo/web3j/service/EscrowService.java`：部署、加载、详情、分批事件查询及仲裁。
- `src/main/java/com/wetech/demo/web3j/controller/EscrowController.java`：六个接口和异常回执处理。
- `src/main/resources/contracts`：本组使用的 Solidity 合约。
- `docs/ZBH_交易哈希清单.txt`：八笔必做交易及两笔补充记录。
- `docs/验收原始数据`：保存的接口响应，含仲裁失败、成功退款及最终状态。

失败测试对当时为 `Paid` 的订单 0 执行仲裁，得到链上失败回执 `status=0x0`、`gasUsed=25884`。对已争议订单 1 执行 `resolve(1,false)` 成功，`gasUsed=62909`。随后买家确认订单 0，卖家本轮 CxlToken 余额从 30 个增加到 60 个。

这些是已经完成的历史验收记录，无需再次发送上述交易。

## 分工与 AI 使用

- 组长：后端工程、编译与运行、部署与仲裁、买家操作及材料汇总。
- HHT：卖家争议操作、成交前后余额核对、解释题 4A-2 和 4A-3。
- ZBH：浏览器交易与事件日志核对、交易记录和报告材料整理。

Codex 协助生成两份 Java 实现、指导操作及整理材料。本组在实际环境编译并运行接口，使用原始响应、ABI 和链上回执核对结果。具体真实姓名、学号及贡献比例在分组报告中填写。

本项目用于课程本地验收，当前接口未实现登录、角色授权和限流，不应直接作为公开生产仲裁服务使用。

## 来源

工程基于课程使用的 [spring-boot-web3j 示例项目](https://github.com/kyonRay/spring-boot-web3j)继续完成，新增并验证本组的 Escrow 后端实现。提交副本移除了配置类中的默认示例私钥，不继承示例项目的 Git 历史。

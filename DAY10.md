# Day10：订单定时处理、来单提醒、客户催单

## 已实现

- 每分钟取消下单超过 15 分钟且仍未付款的订单，记录取消原因和时间。
- 每天凌晨 1 点处理下单超过 60 分钟且仍在派送中的订单，更新为已完成并填写送达时间。
- 定时更新再次检查数据库中的当前状态，避免覆盖并发支付或取消操作。
- 支付首次成功且事务提交后，通过 `/ws/{sid}` 广播来单消息；重复支付不重复推送，事务回滚不推送。
- `GET /user/order/reminder/{id}`：登录用户可催促自己的已支付、待接单/已接单/派送中订单。不存在、他人、未付款、已完成或已取消订单均返回业务错误。
- 广播跳过已关闭连接；单个连接发送失败不影响其他客户端；同一会话串行发送。

消息与现有管理端弹窗、音频逻辑兼容：

```json
{"type":1,"orderId":123,"content":"订单号：20261102001"}
```

`type=1` 表示来单，`type=2` 表示催单。

## 联调

1. 配置本地 MySQL、Redis 后启动 `SkyApplication`（端口 8080）。支付沿用本项目的本地模拟支付，不发生实际扣款。
2. 打开 `http://localhost:8080/websocket.html` 观察消息，或登录管理端测试弹窗和音频。开发环境管理端已连接 `ws://localhost:8080/ws/`；本地 Nginx 已配置 `/ws/` 代理。自定义前端生产构建需设置 `VUE_APP_SOCKET_URL` 为实际 WebSocket 地址。
3. 用户下单并调用 `POST /user/order/payment`，提交 `orderNumber` 和 `payMethod: 1`；浏览器收到 `type=1`。重复支付不再收到新通知。
4. 携带该用户的登录 token 调用 `GET /user/order/reminder/{id}`，浏览器收到 `type=2`。管理端已有对应的提示音与订单跳转逻辑。

WebSocket 入门示例的每 5 秒纯文本广播默认关闭，避免干扰管理端 JSON 解析。仅在单独学习示例时通过 `sky.websocket.demo-enabled=true` 启用。

## 验证

在仓库根目录执行（本地使用 JDK 8 和 IDEA 自带 Maven）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk1.8.0_202'
& 'D:\IntelliJ IDEA 2023.2.2\plugins\maven\lib\maven3\bin\mvn.cmd' -o -pl sky-server -am '-Dtest=OrderPaymentTest,OrderTaskTest,WebSocketServerTest,DeliveryRangeServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：30 项测试通过，无失败或错误。测试包括支付提交/回滚与幂等、催单归属/状态、定时筛选边界、MyBatis 参数绑定与条件更新 SQL、广播和会话替换，以及原有配送范围回归。

全量 `mvn test` 另有 4 个示例测试错误：`HttpClientTest` 的模拟 Web 环境没有 WebSocket `ServerContainer`；`SpringDataRedisTest` 有 3 项未注入 RedisTemplate 导致空指针。这两个示例未在本次修改范围内。尚未执行真实数据库与浏览器端到端联调。

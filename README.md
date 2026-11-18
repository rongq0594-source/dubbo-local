# dubbo-local

Dubbo 调用优先使用本机实例。应用只引入依赖，沿用原有 Dubbo 注册方式、namespace、协议和远程引用，无需改业务代码或注册配置。

```xml
<dependency>
    <groupId>io.localfirst.dubbo</groupId>
    <artifactId>dubbo-local</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 动态发现

- 启动后扫描 Spring Bean 的普通接口字段 `@DubboReference`，匹配原 ReferenceBean。
- 优先读取 providedBy；未指定时订阅 Dubbo 的接口到应用 mapping。
- 订阅对应注册中心、namespace 下的应用实例。提供者后启动也会触发本机缓存更新。
- 匹配健康、启用且属于本机网卡的实例，再校验接口、group、version、协议。从接口元数据和 `dubbo.endpoints` 读取 RPC 端口，不猜端口，不使用 HTTP 端口。
- 实例下线时移除本机缓存，调用恢复为原 Dubbo 引用；端口或 metadata revision 变化时替换代理。重复通知复用代理。
- 通知在独立后台线程串行处理。每 5 秒额外核对 mapping 和实例列表，处理通知遗漏或元数据延迟发布；刷新失败保留已知代理并记录异常，后续自动重试。
- 请求线程只读缓存，不查询 Nacos。同步调用、CompletionStage 和 Dubbo async 调用完成后，才释放已被替换的旧连接。
- 关闭应用时取消本组件的订阅并释放本机代理，不关闭 Dubbo 共享的注册中心客户端。

通知受 Nacos 推送、网络和元数据可用时间影响，不保证零延迟切换。提供者和消费者无需固定启动顺序；原引用的 check 启动检查仍保留，若没有任何可用提供者且原 check=true，Dubbo 仍可能拒绝启动消费者。

## 边界和日志

默认启用，`local-first.dubbo.enabled=false` 可关闭。显式 url、injvm、泛化引用保留原选址；当前只处理 Spring 管理的非 final 接口字段，支持继承字段及常见单例 AOP 代理。

本机未命中或本机 Invoker 被判定不可用时调用原引用。已发出的本地调用失败不会自动重放到远程，避免写操作重复执行。原远程引用本身发现不到服务的问题不由该组件修复。

直连本机候选不经过注册中心的动态标签/条件路由，有相关隔离规则时需先验证。流式 RPC 未做兼容验证。

INFO 日志仅在本机候选变化时记录“本机引用已缓存”或“本机引用未命中”，包含接口、应用名、RPC endpoint；详细扫描使用 DEBUG。失败保留异常栈，不打印注册认证信息或完整 URL 参数。

## 验证

构建基线为 Java 8 字节码、Boot 2.7.18、Dubbo 3.3.0。Spring、Dubbo 依赖均为 optional，不升级接入方版本。另验证 JDK 21 / Boot 3.2.5 / Dubbo 3.3.1；JDK 17 当前环境未安装，未单独运行。

测试覆盖晚出现的 mapping、晚启动的实例、下线回退、端口变化、重复通知去重、元数据延迟、注册中心故障、订阅清理及在途调用释放；还包含 Spring 注入和 dubbo/tri 的真实本机 RPC 测试。注册通知通过隔离测试替身驱动，未在实际 Nacos 上联调。

```shell
mvn clean install
mvn clean verify '-Ddubbo.version=3.3.1'
```

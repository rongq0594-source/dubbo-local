# Maven Central 发布准备

项目坐标：`io.github.rongq0594-source:dubbo-local:0.1.0`。该 groupId 对应 Central Portal 中已验证的 Namespace，Java 包名保持 `io.localfirst.dubbo`。

许可证沿用仓库的 The Unlicense。POM 已补充项目、开发者和 SCM 地址。

## 本地构建

普通构建不要求 GPG：

```powershell
mvn clean verify
```

发布构建生成主 JAR、源码 JAR、Javadoc JAR 和各文件的 ASCII 签名：

```powershell
mvn clean verify -Prelease '-Dgpg.keyname=完整密钥指纹'
```

如果 GPG 可执行文件没有加入 PATH，可以额外传入 `'-Dgpg.executable=C:\Program Files (x86)\GnuPG\bin\gpg.exe'`，路径以本机安装位置为准。不要将密钥口令写在命令行、POM、Git 或本文档中；由 GPG pinentry 窗口交互输入。

## GPG 初始化（本机操作）

1. 从 https://www.gpg4win.org/ 安装 Gpg4win，重新打开终端，执行 `gpg --version`。
2. 如果已有自己的签名密钥，直接使用，不必重复生成；否则执行 `gpg --full-generate-key`，选择 RSA/RSA、4096 位，填写身份并设置口令。
3. 执行 `gpg --list-secret-keys --keyid-format LONG`，取得完整密钥指纹。
4. 只发布公钥：`gpg --keyserver hkps://keyserver.ubuntu.com --send-keys 完整密钥指纹`。公钥发布是供 Central 验证签名所需的公开操作。
5. 执行前面的 release 构建命令，在 GPG 弹窗中输入口令。

私钥由本机 GPG 管理，不放入项目。备份和保管由密钥所有者负责。

## 预期产物

```text
target/dubbo-local-0.1.0.jar
target/dubbo-local-0.1.0.jar.asc
target/dubbo-local-0.1.0-sources.jar
target/dubbo-local-0.1.0-sources.jar.asc
target/dubbo-local-0.1.0-javadoc.jar
target/dubbo-local-0.1.0-javadoc.jar.asc
target/dubbo-local-0.1.0.pom.asc
```

签名 POM 的文件名/目录以 maven-gpg-plugin 实际输出为准；发布工具会携带项目 POM。验收时应同时确认四类文件（JAR、源码、Javadoc、POM）都有签名。

尚未安装 GPG 时，可先验证打包而跳过签名：

```powershell
mvn clean verify -Prelease '-Dgpg.skip=true'
```

跳过签名的结果不能直接视为 Central 发布成品。执行 `verify` 不会上传，也不会公开发布。

## 上传到 Central

release profile 已配置 central-publishing-maven-plugin，使用 Maven server id `central`，`autoPublish=false`。

1. 在 Central Portal 的账户页面生成 Publisher/User Token，取得生成的 username 和 password；不能使用 GitHub 密码。
2. 将下列 server 合并进本机 Maven 实际使用的 `settings.xml` 的 `<servers>` 中，不覆盖其他 server，不提交凭证到 Git。

```xml
<server>
    <id>central</id>
    <username>Portal 生成的用户名</username>
    <password>Portal 生成的密码</password>
</server>
```

上传命令（CMD 和 PowerShell 都可使用双引号）：

```shell
mvn clean deploy -Prelease "-Dgpg.keyname=完整密钥指纹"
```

上传后打开 https://central.sonatype.com/publishing/deployments ，等待校验通过，再手动点击 Publish。无需配置旧 OSSRH 地址或 distributionManagement。已发布的正式版本不能覆盖，后续修改需升级版本。

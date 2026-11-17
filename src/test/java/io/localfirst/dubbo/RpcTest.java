package io.localfirst.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

public class RpcTest {
    public interface Echo { String echo(String value); }

    @ParameterizedTest
    @ValueSource(strings = {"dubbo", "tri"})
    void cachedProxyMakesRealRpc(String protocol) {
        FrameworkModel framework = new FrameworkModel();
        ModuleModel module = framework.newApplication().newModule();
        ApplicationConfig app = new ApplicationConfig(module.getApplicationModel());
        app.setName("local-cache-test");
        app.setQosEnable(false);
        module.getConfigManager().getApplicationConfigManager().setApplication(app);
        ServiceConfig<Echo> service = new ServiceConfig<Echo>(module);
        LocalCache cache = new LocalCache();
        try {
            service.setInterface(Echo.class);
            service.setRef((Echo) value -> "local:" + value);
            service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
            ProtocolConfig config = new ProtocolConfig(protocol, -1);
            config.setHost("127.0.0.1");
            service.setProtocol(config);
            service.export();
            URL endpoint = service.getExportedUrls().stream()
                    .filter(url -> protocol.equals(url.getProtocol())).findFirst().get();
            // 模拟提供端元数据携带仅在提供端可用的旧 Transporter 扩展。
            endpoint = endpoint.addParameter("transporter", "noHostNetty");
            ReferenceConfig<Echo> reference = new ReferenceConfig<Echo>(module);
            reference.setInterface(Echo.class);
            reference.setTimeout(3000);
            LocalFirstHandler.Target target = cache.connect(reference, endpoint, LocalCache.consumerUrl(reference));
            assertEquals("local:hello", ((Echo) target.proxy).echo("hello"));
        } finally {
            cache.close();
            service.unexport();
            framework.destroy();
        }
    }
}

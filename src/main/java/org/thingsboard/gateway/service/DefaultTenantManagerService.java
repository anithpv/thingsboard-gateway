/**
 * Copyright © 2017 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.gateway.extensions.ExtensionService;
import org.thingsboard.gateway.extensions.file.DefaultFileTailService;
import org.thingsboard.gateway.extensions.http.DefaultHttpService;
import org.thingsboard.gateway.extensions.http.HttpService;
import org.thingsboard.gateway.extensions.mqtt.client.DefaultMqttClientService;
import org.thingsboard.gateway.extensions.opc.DefaultOpcUaService;
import org.thingsboard.gateway.service.conf.TbExtensionConfiguration;
import org.thingsboard.gateway.service.conf.TbGatewayConfiguration;
import org.thingsboard.gateway.service.conf.TbTenantConfiguration;
import org.thingsboard.gateway.service.gateway.GatewayService;
import org.thingsboard.gateway.service.gateway.MqttGatewayService;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by ashvayka on 29.09.17.
 */
@Service
@Slf4j
public class DefaultTenantManagerService implements TenantManagerService {

    @Autowired
    private TbGatewayConfiguration configuration;

    private Map<String, TenantServicesRegistry> gateways;

    private List<HttpService> httpServices;

    @PostConstruct
    public void init() {
        gateways = new HashMap<>();
        httpServices = new ArrayList<>();
        for (TbTenantConfiguration configuration : configuration.getTenants()) {
            String label = configuration.getLabel();
            log.info("[{}] Initializing gateway", configuration.getLabel());
            GatewayService service = new MqttGatewayService(configuration);
            try {
                service.init();
                List<ExtensionService> extensions = new ArrayList<>();
                for (TbExtensionConfiguration extensionConfiguration : configuration.getExtensions()) {
                    log.info("[{}] Initializing extension: [{}]", configuration.getLabel(), extensionConfiguration.getType());
                    ExtensionService extension = createExtensionServiceByType(service, extensionConfiguration);
                    extension.init();
                    if (extensionConfiguration.getType().equals("http")) {
                        httpServices.add((HttpService) extension);
                    }
                    extensions.add(extension);
                }
                gateways.put(label, new TenantServicesRegistry(service, extensions));
            } catch (Exception e) {
                log.info("[{}] Failed to initialize the service ", label, e);
                try {
                    service.destroy();
                } catch (Exception e1) {
                    log.info("[{}] Failed to stop the service ", label, e1);
                }
            }
        }
    }

    @Override
    public void processRequest(String converterId, String token, String body) throws Exception {
        for (HttpService service : httpServices) {
            service.processRequest(converterId, token, body);
        }
    }

    @PreDestroy
    public void stop() {
        for (String label : gateways.keySet()) {
            try {
                TenantServicesRegistry registry = gateways.get(label);
                for (ExtensionService extension : registry.getExtensions()) {
                    try {
                        extension.destroy();
                    } catch (Exception e) {
                        log.info("[{}] Failed to stop the extension ", label, e);
                    }
                }
                registry.getService().destroy();
            } catch (Exception e) {
                log.info("[{}] Failed to stop the service ", label, e);
            }
        }
    }

    private ExtensionService createExtensionServiceByType(GatewayService gateway, TbExtensionConfiguration configuration) {
        switch (configuration.getType()) {
            case "file":
                return new DefaultFileTailService(gateway, configuration.getConfiguration());
            case "opc":
                return new DefaultOpcUaService(gateway, configuration.getConfiguration());
            case "http":
                return new DefaultHttpService(gateway, configuration.getConfiguration());
            case "mqtt":
                return new DefaultMqttClientService(gateway, configuration.getConfiguration());
            default:
                throw new IllegalArgumentException("Extension: " + configuration.getType() + " is not supported!");
        }
    }

}

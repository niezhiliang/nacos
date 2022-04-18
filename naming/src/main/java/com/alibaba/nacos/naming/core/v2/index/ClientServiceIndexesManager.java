/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.naming.core.v2.index;

import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.SmartSubscriber;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.event.client.ClientEvent;
import com.alibaba.nacos.naming.core.v2.event.client.ClientOperationEvent;
import com.alibaba.nacos.naming.core.v2.event.service.ServiceEvent;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client and service index manager.
 *
 * @author xiweng.yy
 */
@Component
public class ClientServiceIndexesManager extends SmartSubscriber {

    // Service与发布clientId
    private final ConcurrentMap<Service, Set<String>> publisherIndexes = new ConcurrentHashMap<>();

    // Service与订阅clientId
    private final ConcurrentMap<Service, Set<String>> subscriberIndexes = new ConcurrentHashMap<>();

    public ClientServiceIndexesManager() {
        NotifyCenter.registerSubscriber(this);
    }

    public Collection<String> getAllClientsRegisteredService(Service service) {
        return publisherIndexes.containsKey(service) ? publisherIndexes.get(service) : new ConcurrentHashSet<>();
    }

    public Collection<String> getAllClientsSubscribeService(Service service) {
        return subscriberIndexes.containsKey(service) ? subscriberIndexes.get(service) : new ConcurrentHashSet<>();
    }

    public Collection<Service> getSubscribedService() {
        return subscriberIndexes.keySet();
    }

    /**
     * Clear the service index without instances.
     *
     * @param service The service of the Nacos.
     */
    public void removePublisherIndexesByEmptyService(Service service) {
        if (publisherIndexes.containsKey(service) && publisherIndexes.get(service).isEmpty()) {
            publisherIndexes.remove(service);
        }
    }

    @Override
    public List<Class<? extends Event>> subscribeTypes() {
        List<Class<? extends Event>> result = new LinkedList<>();
        result.add(ClientOperationEvent.ClientRegisterServiceEvent.class);
        result.add(ClientOperationEvent.ClientDeregisterServiceEvent.class);
        result.add(ClientOperationEvent.ClientSubscribeServiceEvent.class);
        result.add(ClientOperationEvent.ClientUnsubscribeServiceEvent.class);
        result.add(ClientEvent.ClientDisconnectEvent.class);
        return result;
    }

    @Override
    public void onEvent(Event event) {
        // 服务下线事件
        if (event instanceof ClientEvent.ClientDisconnectEvent) {
            handleClientDisconnect((ClientEvent.ClientDisconnectEvent) event);
        } else if (event instanceof ClientOperationEvent) {
            handleClientOperation((ClientOperationEvent) event);
        }
    }

    private void handleClientDisconnect(ClientEvent.ClientDisconnectEvent event) {
        Client client = event.getClient();
        // 移除服务的订阅者
        for (Service each : client.getAllSubscribeService()) {
            removeSubscriberIndexes(each, client.getClientId());
        }
        for (Service each : client.getAllPublishedService()) {
            // 通过clientId移除服务
            removePublisherIndexes(each, client.getClientId());
        }
    }

    private void handleClientOperation(ClientOperationEvent event) {
        Service service = event.getService();
        String clientId = event.getClientId();
        // 注册事件
        if (event instanceof ClientOperationEvent.ClientRegisterServiceEvent) {
            addPublisherIndexes(service, clientId);
        // 注销服务
        } else if (event instanceof ClientOperationEvent.ClientDeregisterServiceEvent) {
            removePublisherIndexes(service, clientId);
        // 订阅服务
        } else if (event instanceof ClientOperationEvent.ClientSubscribeServiceEvent) {
            addSubscriberIndexes(service, clientId);
        // 移除订阅服务
        } else if (event instanceof ClientOperationEvent.ClientUnsubscribeServiceEvent) {
            removeSubscriberIndexes(service, clientId);
        }
    }

    /**
     * 添加Service与发布Client的关系
     * @param service
     * @param clientId
     */
    private void addPublisherIndexes(Service service, String clientId) {
        publisherIndexes.computeIfAbsent(service, (key) -> new ConcurrentHashSet<>());
        publisherIndexes.get(service).add(clientId);
        // 发布服务变更事件 1.通知订阅客户端 2.Nacos集群数据同步 NamingSubscriberServiceV2Impl#onEvent DistroClientDataProcessor#onEvent
        NotifyCenter.publishEvent(new ServiceEvent.ServiceChangedEvent(service, true));
    }

    private void removePublisherIndexes(Service service, String clientId) {
        if (!publisherIndexes.containsKey(service)) {
            return;
        }
        publisherIndexes.get(service).remove(clientId);
        NotifyCenter.publishEvent(new ServiceEvent.ServiceChangedEvent(service, true));
    }

    private void addSubscriberIndexes(Service service, String clientId) {
        subscriberIndexes.computeIfAbsent(service, (key) -> new ConcurrentHashSet<>());
        // Fix #5404, Only first time add need notify event.
        if (subscriberIndexes.get(service).add(clientId)) {
            NotifyCenter.publishEvent(new ServiceEvent.ServiceSubscribedEvent(service, clientId));
        }
    }

    private void removeSubscriberIndexes(Service service, String clientId) {
        if (!subscriberIndexes.containsKey(service)) {
            return;
        }
        subscriberIndexes.get(service).remove(clientId);
        if (subscriberIndexes.get(service).isEmpty()) {
            subscriberIndexes.remove(service);
        }
    }
}

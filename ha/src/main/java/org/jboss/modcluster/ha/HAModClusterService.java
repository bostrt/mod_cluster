/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.modcluster.ha;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.beans.metadata.api.annotations.Inject;
import org.jboss.beans.metadata.api.model.FromContext;
import org.jboss.ha.framework.interfaces.CachableMarshalledValue;
import org.jboss.ha.framework.interfaces.ClusterNode;
import org.jboss.ha.framework.interfaces.DistributedReplicantManager;
import org.jboss.ha.framework.interfaces.HAPartition;
import org.jboss.ha.framework.interfaces.HASingletonElectionPolicy;
import org.jboss.ha.framework.server.EventFactory;
import org.jboss.ha.framework.server.HAServiceEvent;
import org.jboss.ha.framework.server.HAServiceEventFactory;
import org.jboss.ha.framework.server.HAServiceRpcHandler;
import org.jboss.ha.framework.server.HASingletonImpl;
import org.jboss.ha.framework.server.SimpleCachableMarshalledValue;
import org.jboss.modcluster.container.ContainerEventHandler;
import org.jboss.modcluster.container.Context;
import org.jboss.modcluster.container.Connector;
import org.jboss.modcluster.container.Engine;
import org.jboss.modcluster.container.Host;
import org.jboss.modcluster.ModClusterService;
import org.jboss.modcluster.container.Server;
import org.jboss.modcluster.Strings;
import org.jboss.modcluster.Utils;
import org.jboss.modcluster.advertise.AdvertiseListenerFactory;
import org.jboss.modcluster.advertise.impl.AdvertiseListenerFactoryImpl;
import org.jboss.modcluster.config.BalancerConfiguration;
import org.jboss.modcluster.config.MCMPHandlerConfiguration;
import org.jboss.modcluster.config.NodeConfiguration;
import org.jboss.modcluster.config.ha.HAConfiguration;
import org.jboss.modcluster.config.ha.impl.HAModClusterConfig;
import org.jboss.modcluster.ha.rpc.ClusteredMCMPHandlerRpcHandler;
import org.jboss.modcluster.ha.rpc.DefaultRpcResponse;
import org.jboss.modcluster.ha.rpc.MCMPServerDiscoveryEvent;
import org.jboss.modcluster.ha.rpc.ModClusterServiceRpcHandler;
import org.jboss.modcluster.ha.rpc.ModClusterServiceStatus;
import org.jboss.modcluster.ha.rpc.PeerMCMPDiscoveryStatus;
import org.jboss.modcluster.ha.rpc.ResetRequestSourceRpcHandler;
import org.jboss.modcluster.ha.rpc.RpcResponse;
import org.jboss.modcluster.ha.rpc.RpcResponseFilter;
import org.jboss.modcluster.load.LoadBalanceFactorProvider;
import org.jboss.modcluster.load.LoadBalanceFactorProviderFactory;
import org.jboss.modcluster.load.SimpleLoadBalanceFactorProviderFactory;
import org.jboss.modcluster.mcmp.ContextFilter;
import org.jboss.modcluster.mcmp.MCMPConnectionListener;
import org.jboss.modcluster.mcmp.MCMPHandler;
import org.jboss.modcluster.mcmp.MCMPRequest;
import org.jboss.modcluster.mcmp.MCMPRequestFactory;
import org.jboss.modcluster.mcmp.MCMPResponseParser;
import org.jboss.modcluster.mcmp.MCMPServer;
import org.jboss.modcluster.mcmp.MCMPServerState;
import org.jboss.modcluster.mcmp.ResetRequestSource;
import org.jboss.modcluster.mcmp.ResetRequestSource.VirtualHost;
import org.jboss.modcluster.mcmp.impl.DefaultMCMPHandler;
import org.jboss.modcluster.mcmp.impl.DefaultMCMPRequestFactory;
import org.jboss.modcluster.mcmp.impl.DefaultMCMPResponseParser;

/**
 * @author Paul Ferraro
 */
public class HAModClusterService extends HASingletonImpl<HAServiceEvent> implements HAModClusterServiceMBean,
        ContainerEventHandler, LoadBalanceFactorProvider, MCMPConnectionListener, ContextFilter {
    static final Object[] NULL_ARGS = new Object[0];
    static final Class<?>[] NULL_TYPES = new Class[0];
    static final Class<?>[] STRING_TYPES = new Class[] { String.class };
    static final Class<?>[] STOP_TYPES = new Class[] { String.class, Long.TYPE, TimeUnit.class };
    static final Class<?>[] CLUSTER_STATUS_COMPLETE_TYPES = new Class[] { Map.class };
    static final Class<?>[] GET_CLUSTER_COORDINATOR_STATE_TYPES = new Class[] { Set.class };

    // HAModClusterServiceMBean and ContainerEventHandler delegate
    final ClusteredModClusterService service;
    private final HAServiceRpcHandler<HAServiceEvent> rpcHandler;
    final ModClusterServiceRpcHandler<List<RpcResponse<ModClusterServiceStatus>>, MCMPServerState, List<RpcResponse<Boolean>>> rpcStub = new RpcStub();

    final MCMPRequestFactory requestFactory;
    private final MCMPResponseParser responseParser;
    final MCMPHandler localHandler;
    final ClusteredMCMPHandler clusteredHandler;
    final ResetRequestSource resetRequestSource;
    final Map<ClusterNode, MCMPServerDiscoveryEvent> proxyChangeDigest = new ConcurrentHashMap<ClusterNode, MCMPServerDiscoveryEvent>();
    final ModClusterServiceDRMEntry drmEntry;
    final String loadBalancingGroup;
    private final boolean masterPerLoadBalancingGroup;
    private final AtomicReference<Set<CachableMarshalledValue>> replicantView = new AtomicReference<Set<CachableMarshalledValue>>(
            Collections.<CachableMarshalledValue> emptySet());

    volatile int processStatusFrequency = 1;
    volatile int latestLoad;
    volatile int statusCount = 0;

    @Deprecated
    public HAModClusterService(HAPartition partition, HAModClusterConfig config,
            LoadBalanceFactorProvider loadBalanceFactorProvider) {
        this(config, loadBalanceFactorProvider, partition);

        this.deprecatedConstructor(new Class<?>[] { HAPartition.class, HAModClusterConfig.class,
                LoadBalanceFactorProvider.class }, new Class<?>[] { HAModClusterConfig.class, LoadBalanceFactorProvider.class,
                HAPartition.class });
    }

    @Deprecated
    public HAModClusterService(HAPartition partition, HAModClusterConfig config,
            LoadBalanceFactorProvider loadBalanceFactorProvider, HASingletonElectionPolicy electionPolicy) {
        this(config, loadBalanceFactorProvider, partition, electionPolicy);

        this.deprecatedConstructor(new Class<?>[] { HAPartition.class, HAModClusterConfig.class,
                LoadBalanceFactorProvider.class, HASingletonElectionPolicy.class }, new Class<?>[] { HAModClusterConfig.class,
                LoadBalanceFactorProvider.class, HAPartition.class, HASingletonElectionPolicy.class });
    }

    private void deprecatedConstructor(Class<?>[] oldConstructorArgs, Class<?>[] newConstructorArgs) {
        try {
            Constructor<HAModClusterService> oldConstructor = HAModClusterService.class.getConstructor(oldConstructorArgs);
            Constructor<HAModClusterService> newConstructor = HAModClusterService.class.getConstructor(newConstructorArgs);

            this.log.warn(Strings.DEPRECATED.getString(oldConstructor, newConstructor));
        } catch (NoSuchMethodException e) {
            // Oh well...
        }
    }

    public HAModClusterService(HAModClusterConfig config, LoadBalanceFactorProvider loadBalanceFactorProvider,
            HAPartition partition) {
        this(config, loadBalanceFactorProvider, partition, null);
    }

    public HAModClusterService(HAModClusterConfig config, LoadBalanceFactorProvider loadBalanceFactorProvider,
            HAPartition partition, HASingletonElectionPolicy electionPolicy) {
        super(new HAServiceEventFactory());

        this.setHAPartition(partition);
        this.setElectionPolicy(electionPolicy);

        this.rpcHandler = new RpcHandler();
        this.requestFactory = new DefaultMCMPRequestFactory();
        this.responseParser = new DefaultMCMPResponseParser();
        this.resetRequestSource = new ClusteredResetRequestSource(config, config, this.requestFactory, this, this);
        this.localHandler = new DefaultMCMPHandler(config, this.resetRequestSource, this.requestFactory, this.responseParser);
        this.clusteredHandler = new ClusteredMCMPHandlerImpl(this.localHandler, this, this);

        this.drmEntry = new ModClusterServiceDRMEntry(partition.getClusterNode(), null);
        this.service = new ClusteredModClusterService(config, config, config, new SimpleLoadBalanceFactorProviderFactory(
                loadBalanceFactorProvider), this.requestFactory, this.responseParser, this.resetRequestSource,
                this.clusteredHandler, new AdvertiseListenerFactoryImpl());
        this.loadBalancingGroup = config.getLoadBalancingGroup();
        this.masterPerLoadBalancingGroup = config.isMasterPerLoadBalancingGroup();
    }

    protected HAModClusterService(EventFactory<HAServiceEvent> eventFactory, HAConfiguration haConfig,
            NodeConfiguration nodeConfig, BalancerConfiguration balancerConfig, MCMPHandlerConfiguration mcmpConfig,
            LoadBalanceFactorProviderFactory loadBalanceFactorProviderFactory, HAPartition partition,
            HASingletonElectionPolicy electionPolicy, MCMPRequestFactory requestFactory, MCMPResponseParser responseParser,
            ResetRequestSource resetRequestSource, MCMPHandler localHandler, ClusteredMCMPHandler clusteredHandler,
            AdvertiseListenerFactory advertiseListenerFactory) {
        super(eventFactory);

        this.setHAPartition(partition);
        this.setElectionPolicy(electionPolicy);

        this.rpcHandler = new RpcHandler();
        this.requestFactory = requestFactory;
        this.responseParser = responseParser;
        this.resetRequestSource = resetRequestSource;
        this.localHandler = localHandler;
        this.clusteredHandler = clusteredHandler;

        this.drmEntry = new ModClusterServiceDRMEntry(partition.getClusterNode(), null);
        this.service = new ClusteredModClusterService(nodeConfig, balancerConfig, mcmpConfig, loadBalanceFactorProviderFactory,
                requestFactory, responseParser, resetRequestSource, clusteredHandler, advertiseListenerFactory);
        this.loadBalancingGroup = nodeConfig.getLoadBalancingGroup();
        this.masterPerLoadBalancingGroup = haConfig.isMasterPerLoadBalancingGroup();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.modcluster.ha.HAModClusterServiceMBean#getProcessStatusFrequency()
     */
    public int getProcessStatusFrequency() {
        return this.processStatusFrequency;
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ha.HAModClusterServiceMBean#setProcessStatusFrequency(int)
     */
    public void setProcessStatusFrequency(int processStatusFrequency) {
        this.processStatusFrequency = processStatusFrequency;
    }

    public boolean disableDomain() {
        return this.conjoin(this.rpcStub.disable(this.loadBalancingGroup));
    }

    public boolean enableDomain() {
        return this.conjoin(this.rpcStub.enable(this.loadBalancingGroup));
    }

    public boolean stopDomain(long timeout, TimeUnit unit) {
        return this.conjoin(this.rpcStub.stop(this.loadBalancingGroup, timeout, unit));
    }

    private boolean conjoin(List<RpcResponse<Boolean>> responses) {
        boolean success = true;

        for (RpcResponse<Boolean> response : responses) {
            Boolean result = response.getResult();

            success &= ((result == null) || result.booleanValue());
        }

        return success;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.modcluster.mcmp.ContextFilter#getExcludedContexts()
     */
    public Map<Host, Set<String>> getExcludedContexts() {
        return this.service.getExcludedContexts();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.modcluster.mcmp.ContextFilter#isAutoEnableContexts()
     */
    public boolean isAutoEnableContexts() {
        return this.service.isAutoEnableContexts();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.modcluster.mcmp.MCMPConnectionListener#isEstablished()
     */
    public boolean isEstablished() {
        return this.service.isEstablished();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.jboss.modcluster.mcmp.MCMPConnectionListener#connectionEstablished(java.net.InetAddress)
     */
    public void connectionEstablished(InetAddress localAddress) {
        this.service.connectionEstablished(localAddress);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.load.LoadBalanceFactorProvider#getLoadBalanceFactor()
     */
    public int getLoadBalanceFactor(Engine engine) {
        return this.service.getLoadBalanceFactor(engine);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#add(org.jboss.modcluster.Context)
     */
    public void add(Context context) {
        this.service.add(context);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#init(org.jboss.modcluster.Server)
     */
    public void init(Server server) {
        this.service.init(server);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#remove(org.jboss.modcluster.Context)
     */
    public void remove(Context context) {
        this.service.remove(context);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#shutdown()
     */
    public void shutdown() {
        this.service.shutdown();
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#start(org.jboss.modcluster.Context)
     */
    public void start(Context context) {
        this.service.start(context);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#start(org.jboss.modcluster.Server)
     */
    public void start(Server server) {
        this.service.start(server);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#status(org.jboss.modcluster.Engine)
     */
    public void status(Engine engine) {
        this.service.status(engine);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#stop(org.jboss.modcluster.Context)
     */
    public void stop(Context context) {
        this.service.stop(context);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ContainerEventHandler#stop(org.jboss.modcluster.Server)
     */
    public void stop(Server server) {
        this.service.stop(server);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#addProxy(java.lang.String, int)
     */
    public void addProxy(String host, int port) {
        this.service.addProxy(host, port);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#disable()
     */
    public boolean disable() {
        return this.service.disable();
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#disable(java.lang.String, java.lang.String)
     */
    public boolean disableContext(String host, String path) {
        return this.service.disableContext(host, path);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#ping()
     */
    public Map<InetSocketAddress, String> ping() {
        return this.service.ping();
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#ping(java.lang.String)
     */
    public Map<InetSocketAddress, String> ping(String jvmRoute) {
        return this.service.ping(jvmRoute);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#ping(java.lang.String, java.lang.String, int)
     */
    public Map<InetSocketAddress, String> ping(String scheme, String hostname, int port) {
        return this.service.ping(scheme, hostname, port);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#enable()
     */
    public boolean enable() {
        return this.service.enable();
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#enable(java.lang.String, java.lang.String)
     */
    public boolean enableContext(String host, String path) {
        return this.service.enableContext(host, path);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#getProxyConfiguration()
     */
    public Map<InetSocketAddress, String> getProxyConfiguration() {
        return this.service.getProxyConfiguration();
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#getProxyInfo()
     */
    public Map<InetSocketAddress, String> getProxyInfo() {
        return this.service.getProxyInfo();
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#refresh()
     */
    public void refresh() {
        this.service.refresh();
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#removeProxy(java.lang.String, int)
     */
    public void removeProxy(String host, int port) {
        this.service.removeProxy(host, port);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.modcluster.ModClusterServiceMBean#reset()
     */
    public void reset() {
        this.service.reset();
    }

    public boolean stop(long timeout, TimeUnit unit) {
        return this.service.stop(timeout, unit);
    }

    public boolean stopContext(String host, String path, long timeout, TimeUnit unit) {
        return this.service.stopContext(host, path, timeout, unit);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.ha.framework.server.HASingletonImpl#startSingleton()
     */
    public void startSingleton() {
        this.statusCount = this.processStatusFrequency - 1;
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.ha.framework.server.HASingletonImpl#partitionTopologyChanged(java.util.List, int, boolean)
     */
    protected void partitionTopologyChanged(List<?> newReplicants, int newViewId, boolean merge) {
        @SuppressWarnings("unchecked")
        Set<CachableMarshalledValue> replicants = new HashSet<CachableMarshalledValue>(
                (List<CachableMarshalledValue>) newReplicants);
        Set<CachableMarshalledValue> oldReplicants = this.replicantView.getAndSet(replicants);

        super.partitionTopologyChanged(newReplicants, newViewId, merge);

        if (this.isMasterNode()) {
            // Determine dead members
            oldReplicants.removeAll(replicants);

            for (CachableMarshalledValue replicant : oldReplicants) {
                ModClusterServiceDRMEntry entry = this.extractDRMEntry(replicant);

                for (String jvmRoute : entry.getJvmRoutes()) {
                    MCMPRequest request = this.requestFactory.createPingRequest(jvmRoute);
                    Map<MCMPServerState, String> responses = this.localHandler.sendRequest(request);

                    for (Map.Entry<MCMPServerState, String> response : responses.entrySet()) {
                        MCMPServerState proxy = response.getKey();

                        // If ping fails, send REMOVE_APP * on behalf of crashed member
                        if ((proxy.getState() == MCMPServerState.State.OK)
                                && !this.responseParser.parsePingResponse(response.getValue())) {
                            this.log.info(Strings.ENGINE_REMOVE_CRASHED.getString(jvmRoute, proxy.getSocketAddress(),
                                    entry.getPeer()));

                            this.localHandler.sendRequest(this.requestFactory.createRemoveEngineRequest(jvmRoute));
                        }
                    }
                }
            }
        }
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.ha.framework.server.HAServiceImpl#setServiceHAName(java.lang.String)
     */
    @Inject(fromContext = FromContext.NAME)
    public void setServiceHAName(String haName) {
        super.setServiceHAName(haName);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.ha.framework.server.HAServiceImpl#getHAServiceKey()
     */
    public String getHAServiceKey() {
        String name = this.getServiceHAName();

        return ((this.loadBalancingGroup != null) && this.masterPerLoadBalancingGroup) ? name + ":" + this.loadBalancingGroup
                : name;
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.ha.framework.server.HASingletonImpl#getRpcHandler()
     */
    protected HAServiceRpcHandler<HAServiceEvent> getRpcHandler() {
        return this.rpcHandler;
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.ha.framework.server.HAServiceImpl#getReplicant()
     */
    protected Serializable getReplicant() {
        return new SimpleCachableMarshalledValue(this.drmEntry);
    }

    /**
     * {@inhericDoc}
     * 
     * @see org.jboss.ha.framework.server.HASingletonImpl#getElectionCandidates()
     */
    protected List<ClusterNode> getElectionCandidates() {
        return this.findMasterCandidates(this.lookupDRMEntries());
    }

    List<ClusterNode> findMasterCandidates(Collection<ModClusterServiceDRMEntry> candidates) {
        if (candidates == null)
            return null;

        List<ClusterNode> narrowed = new ArrayList<ClusterNode>(candidates.size());
        ModClusterServiceDRMEntry champion = null;

        for (ModClusterServiceDRMEntry candidate : candidates) {
            if (champion == null) {
                champion = candidate;
                narrowed.add(candidate.getPeer());
            } else {
                int compFactor = candidate.compareTo(champion);
                if (compFactor < 0) {
                    // New champ
                    narrowed.clear();
                    champion = candidate;
                    narrowed.add(candidate.getPeer());
                } else if (compFactor == 0) {
                    // As good as our champ
                    narrowed.add(candidate.getPeer());
                }
                // else candidate didn't make the cut; continue
            }
        }

        return narrowed;
    }

    List<ModClusterServiceDRMEntry> lookupDRMEntries() {
        DistributedReplicantManager drm = this.getHAPartition().getDistributedReplicantManager();
        @SuppressWarnings("unchecked")
        List<CachableMarshalledValue> values = (List<CachableMarshalledValue>) drm.lookupReplicants(this.getHAServiceKey());

        if (values == null)
            return null;

        List<ModClusterServiceDRMEntry> entries = new ArrayList<ModClusterServiceDRMEntry>(values.size());

        for (CachableMarshalledValue value : values) {
            entries.add(this.extractDRMEntry(value));
        }

        return entries;
    }

    ModClusterServiceDRMEntry lookupLocalDRMEntry() {
        DistributedReplicantManager drm = this.getHAPartition().getDistributedReplicantManager();

        return this.extractDRMEntry((CachableMarshalledValue) drm.lookupLocalReplicant(this.getHAServiceKey()));
    }

    void updateLocalDRM(ModClusterServiceDRMEntry entry) {
        DistributedReplicantManager drm = this.getHAPartition().getDistributedReplicantManager();

        try {
            drm.add(this.getHAServiceKey(), this.createReplicant(entry));
        } catch (Exception e) {
            throw Utils.convertToUnchecked(e);
        }
    }

    private Serializable createReplicant(ModClusterServiceDRMEntry entry) {
        return new SimpleCachableMarshalledValue(entry);
    }

    private ModClusterServiceDRMEntry extractDRMEntry(CachableMarshalledValue replicant) {
        if (replicant == null)
            return null;

        try {
            Object entry = replicant.get();

            // MODCLUSTER-88: This can happen if service was redeployed, and DRM contains objects from the obsolete classloader
            if (!(entry instanceof ModClusterServiceDRMEntry)) {
                // Force re-deserialization w/current classloader
                replicant.toByteArray();

                entry = replicant.get();
            }

            return (ModClusterServiceDRMEntry) entry;
        } catch (Exception e) {
            throw Utils.convertToUnchecked(e);
        }
    }

    /**
     * Client side stub of ModClusterServiceRpcHandler interface.
     */
    class RpcStub
            implements
            ModClusterServiceRpcHandler<List<RpcResponse<ModClusterServiceStatus>>, MCMPServerState, List<RpcResponse<Boolean>>> {
        public RpcResponse<Map<InetSocketAddress, String>> getProxyConfiguration() {
            try {
                return this.invokeRpc("getProxyConfiguration", NULL_ARGS, NULL_TYPES);
            } catch (Exception e) {
                throw Utils.convertToUnchecked(e);
            }
        }

        public RpcResponse<Map<InetSocketAddress, String>> getProxyInfo() {
            try {
                return this.invokeRpc("getProxyInfo", NULL_ARGS, NULL_TYPES);
            } catch (Exception e) {
                throw Utils.convertToUnchecked(e);
            }
        }

        public RpcResponse<Map<InetSocketAddress, String>> ping(String jvmRoute) {
            try {
                return this.invokeRpc("ping", new Object[] { jvmRoute }, STRING_TYPES);
            } catch (Exception e) {
                throw Utils.convertToUnchecked(e);
            }
        }

        @SuppressWarnings("synthetic-access")
        public void clusterStatusComplete(Map<ClusterNode, PeerMCMPDiscoveryStatus> statuses) {
            try {
                HAModClusterService.this.callMethodOnPartition("clusterStatusComplete", new Object[] { statuses },
                        CLUSTER_STATUS_COMPLETE_TYPES);
            } catch (Exception e) {
                HAModClusterService.this.log.error(Strings.ERROR_STATUS_COMPLETE.getString(), e);
            }
        }

        @SuppressWarnings("synthetic-access")
        public List<RpcResponse<ModClusterServiceStatus>> getClusterCoordinatorState(Set<MCMPServerState> masterList) {
            try {
                return HAModClusterService.this.callMethodOnPartition("getClusterCoordinatorState",
                        new Object[] { masterList }, GET_CLUSTER_COORDINATOR_STATE_TYPES);
            } catch (Exception e) {
                throw Utils.convertToUnchecked(e);
            }
        }

        @SuppressWarnings({ "unchecked", "synthetic-access", "deprecation" })
        private <T> RpcResponse<T> invokeRpc(String methodName, Object[] args, Class<?>[] types) throws Exception {
            List<?> responses = HAModClusterService.this.getHAPartition().callMethodOnCluster(
                    HAModClusterService.this.getHAServiceKey(), methodName, args, types, false, new RpcResponseFilter());

            Throwable thrown = null;

            for (Object obj : responses) {
                if (obj instanceof RpcResponse) {
                    return (RpcResponse<T>) obj;
                } else if (obj instanceof Throwable) {
                    if (thrown == null) {
                        thrown = (Throwable) obj;
                    }
                } else {
                    HAModClusterService.this.log.warn(Strings.ERROR_RPC_UNEXPECTED.getString(obj, methodName));
                }
            }

            if (thrown != null) {
                throw Utils.convertToUnchecked(thrown);
            }

            throw new IllegalStateException(Strings.ERROR_RPC_NO_RESPONSE.getString(methodName));
        }

        /**
         * {@inhericDoc}
         * 
         * @see org.jboss.modcluster.ha.rpc.ModClusterServiceRpcHandler#disable(java.lang.String)
         */
        @SuppressWarnings("synthetic-access")
        public List<RpcResponse<Boolean>> disable(String domain) {
            try {
                return HAModClusterService.this.callMethodOnPartition("disable", new Object[] { domain }, STRING_TYPES);
            } catch (Exception e) {
                throw Utils.convertToUnchecked(e);
            }
        }

        /**
         * {@inhericDoc}
         * 
         * @see org.jboss.modcluster.ha.rpc.ModClusterServiceRpcHandler#enable(java.lang.String)
         */
        @SuppressWarnings("synthetic-access")
        public List<RpcResponse<Boolean>> enable(String domain) {
            try {
                return HAModClusterService.this.callMethodOnPartition("enable", new Object[] { domain }, STRING_TYPES);
            } catch (Exception e) {
                throw Utils.convertToUnchecked(e);
            }
        }

        /**
         * {@inhericDoc}
         * 
         * @see org.jboss.modcluster.ha.rpc.ModClusterServiceRpcHandler#stop(java.lang.String)
         */
        @SuppressWarnings("synthetic-access")
        public List<RpcResponse<Boolean>> stop(String domain, long timeout, TimeUnit unit) {
            try {
                return HAModClusterService.this.callMethodOnPartition("stop", new Object[] { domain, Long.valueOf(timeout),
                        unit }, STOP_TYPES);
            } catch (Exception e) {
                throw Utils.convertToUnchecked(e);
            }
        }
    }

    /**
     * Remote skeleton for all rpc interfaces.
     */
    protected class RpcHandler extends HASingletonImpl<HAServiceEvent>.RpcHandler implements
            ModClusterServiceRpcHandler<RpcResponse<ModClusterServiceStatus>, MCMPServer, RpcResponse<Boolean>>,
            ClusteredMCMPHandlerRpcHandler, ResetRequestSourceRpcHandler<RpcResponse<List<MCMPRequest>>> {
        private final ClusterNode node = HAModClusterService.this.getHAPartition().getClusterNode();
        private final RpcResponse<Void> voidResponse = new DefaultRpcResponse<Void>(this.node);

        @SuppressWarnings("synthetic-access")
        public void clusterStatusComplete(Map<ClusterNode, PeerMCMPDiscoveryStatus> statuses) {
            ClusterNode node = HAModClusterService.this.getHAPartition().getClusterNode();
            PeerMCMPDiscoveryStatus status = statuses.get(node);
            if (status != null) {
                // Notify our handler that discovery events have been processed
                HAModClusterService.this.clusteredHandler.discoveryEventsReceived(status);

                // Notify our handler that any reset requests have been processed
                HAModClusterService.this.clusteredHandler.resetCompleted();

                ModClusterServiceDRMEntry previousStatus = HAModClusterService.this.lookupLocalDRMEntry();
                if (!status.getMCMPServerStates().equals(previousStatus.getMCMPServerStates())) {
                    try {
                        HAModClusterService.this.updateLocalDRM(new ModClusterServiceDRMEntry(node, status
                                .getMCMPServerStates(), previousStatus.getJvmRoutes()));
                    } catch (Exception e) {
                        HAModClusterService.this.log.error(Strings.ERROR_DRM.getString(), e);
                    }
                }
            }
        }

        public RpcResponse<ModClusterServiceStatus> getClusterCoordinatorState(Set<MCMPServer> masterList) {
            // TODO is this the correct response here?
            if (HAModClusterService.this.isMasterNode())
                return null;

            Set<MCMPServerState> ourStates = HAModClusterService.this.clusteredHandler.updateServersFromMasterNode(masterList);

            boolean needReset = HAModClusterService.this.clusteredHandler.isResetNecessary();

            List<MCMPRequest> resetRequests = needReset ? HAModClusterService.this.resetRequestSource
                    .getResetRequests(Collections.<String, Set<ResetRequestSource.VirtualHost>> emptyMap()) : null;

            List<MCMPServerDiscoveryEvent> events = HAModClusterService.this.clusteredHandler.getPendingDiscoveryEvents();

            DefaultRpcResponse<ModClusterServiceStatus> response = new DefaultRpcResponse<ModClusterServiceStatus>(this.node);

            response.setResult(new ModClusterServiceStatus(HAModClusterService.this.latestLoad, ourStates, events,
                    resetRequests));

            if (needReset) {
                HAModClusterService.this.clusteredHandler.resetInitiated();
            }

            return response;
        }

        public RpcResponse<Map<InetSocketAddress, String>> getProxyConfiguration() {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            DefaultRpcResponse<Map<InetSocketAddress, String>> response = new DefaultRpcResponse<Map<InetSocketAddress, String>>(
                    this.node);

            response.setResult(HAModClusterService.this.getProxyConfiguration());

            return response;
        }

        public RpcResponse<Map<InetSocketAddress, String>> getProxyInfo() {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            DefaultRpcResponse<Map<InetSocketAddress, String>> response = new DefaultRpcResponse<Map<InetSocketAddress, String>>(
                    this.node);

            response.setResult(HAModClusterService.this.getProxyInfo());

            return response;
        }

        public RpcResponse<Map<InetSocketAddress, String>> ping(String jvmRoute) {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            DefaultRpcResponse<Map<InetSocketAddress, String>> response = new DefaultRpcResponse<Map<InetSocketAddress, String>>(
                    this.node);

            response.setResult(HAModClusterService.this.ping(jvmRoute));

            return response;
        }

        public RpcResponse<Boolean> isProxyHealthOK() {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            DefaultRpcResponse<Boolean> response = new DefaultRpcResponse<Boolean>(this.node);

            response.setResult(Boolean.valueOf(HAModClusterService.this.localHandler.isProxyHealthOK()));

            return response;
        }

        public RpcResponse<Void> markProxiesInError() {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            HAModClusterService.this.localHandler.markProxiesInError();

            return this.voidResponse;
        }

        public RpcResponse<Void> mcmpServerDiscoveryEvent(MCMPServerDiscoveryEvent event) {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            synchronized (HAModClusterService.this.proxyChangeDigest) {
                InetSocketAddress socketAddress = event.getMCMPServer();

                if (event.isAddition()) {
                    HAModClusterService.this.localHandler.addProxy(socketAddress);
                } else {
                    HAModClusterService.this.localHandler.removeProxy(socketAddress);
                }

                HAModClusterService.this.proxyChangeDigest.put(event.getSender(), event);

                return this.voidResponse;
            }
        }

        public RpcResponse<Void> reset() {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            HAModClusterService.this.localHandler.reset();

            return this.voidResponse;
        }

        public RpcResponse<Map<MCMPServerState, String>> sendRequest(MCMPRequest request) {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            DefaultRpcResponse<Map<MCMPServerState, String>> response = new DefaultRpcResponse<Map<MCMPServerState, String>>(
                    this.node);

            response.setResult(HAModClusterService.this.localHandler.sendRequest(request));

            return response;
        }

        public RpcResponse<Map<MCMPServerState, List<String>>> sendRequests(List<MCMPRequest> requests) {
            if (!HAModClusterService.this.isMasterNode())
                return null;

            DefaultRpcResponse<Map<MCMPServerState, List<String>>> response = new DefaultRpcResponse<Map<MCMPServerState, List<String>>>(
                    this.node);

            response.setResult(HAModClusterService.this.localHandler.sendRequests(requests));

            return response;
        }

        public RpcResponse<List<MCMPRequest>> getResetRequests(Map<String, Set<VirtualHost>> infoResponse) {
            DefaultRpcResponse<List<MCMPRequest>> response = new DefaultRpcResponse<List<MCMPRequest>>(this.node);

            if (HAModClusterService.this.isEstablished()) {
                response.setResult(HAModClusterService.this.resetRequestSource.getResetRequests(infoResponse));
            }

            return response;
        }

        public RpcResponse<Boolean> disable(String domain) {
            DefaultRpcResponse<Boolean> response = new DefaultRpcResponse<Boolean>(this.node);

            if (this.sameDomain(domain)) {
                response.setResult(HAModClusterService.this.service.disable());
            }

            return response;
        }

        public RpcResponse<Boolean> enable(String domain) {
            DefaultRpcResponse<Boolean> response = new DefaultRpcResponse<Boolean>(this.node);

            if (this.sameDomain(domain)) {
                response.setResult(HAModClusterService.this.service.enable());
            }

            return response;
        }

        public RpcResponse<Boolean> stop(String domain, long timeout, TimeUnit unit) {
            DefaultRpcResponse<Boolean> response = new DefaultRpcResponse<Boolean>(this.node);

            if (this.sameDomain(domain)) {
                response.setResult(HAModClusterService.this.service.stop(timeout, unit));
            }

            return response;
        }

        private boolean sameDomain(String domain) {
            return (HAModClusterService.this.loadBalancingGroup != null) ? HAModClusterService.this.loadBalancingGroup
                    .equals(domain) : (domain == null);
        }
    }

    /**
     * ModClusterServiceMBean and ContainerEventHandler delegate
     */
    class ClusteredModClusterService extends ModClusterService {
        public ClusteredModClusterService(NodeConfiguration nodeConfig, BalancerConfiguration balancerConfig,
                MCMPHandlerConfiguration mcmpConfig, LoadBalanceFactorProviderFactory loadBalanceFactorProviderFactory,
                MCMPRequestFactory requestFactory, MCMPResponseParser responseParser, ResetRequestSource resetRequestSource,
                MCMPHandler mcmpHandler, AdvertiseListenerFactory advertiseListenerFactory) {
            super(nodeConfig, balancerConfig, mcmpConfig, loadBalanceFactorProviderFactory, requestFactory, responseParser,
                    resetRequestSource, mcmpHandler, advertiseListenerFactory);
        }

        @Override
        public Map<InetSocketAddress, String> getProxyConfiguration() {
            if (HAModClusterService.this.isMasterNode()) {
                return super.getProxyConfiguration();
            }

            return HAModClusterService.this.rpcStub.getProxyConfiguration().getResult();
        }

        @Override
        public Map<InetSocketAddress, String> getProxyInfo() {
            if (HAModClusterService.this.isMasterNode()) {
                return super.getProxyInfo();
            }

            return HAModClusterService.this.rpcStub.getProxyInfo().getResult();
        }

        @Override
        public Map<InetSocketAddress, String> ping(String jvmRoute) {
            if (HAModClusterService.this.isMasterNode()) {
                return super.ping(jvmRoute);
            }

            return HAModClusterService.this.rpcStub.ping(jvmRoute).getResult();
        }

        @Override
        protected void establishJvmRoute(Engine engine) {
            super.establishJvmRoute(engine);

            HAModClusterService.this.drmEntry.addJvmRoute(engine.getJvmRoute());
            HAModClusterService.this.updateLocalDRM(HAModClusterService.this.drmEntry);
        }

        @Override
        protected void removeAll(Engine engine) {
            super.removeAll(engine);

            HAModClusterService.this.drmEntry.removeJvmRoute(engine.getJvmRoute());
            HAModClusterService.this.updateLocalDRM(HAModClusterService.this.drmEntry);
        }

        @Override
        public void status(Engine engine) {
            this.log.debug(Strings.ENGINE_STATUS.getString(engine));

            if (this.isEstablished()) {
                Connector connector = engine.getProxyConnector();
                if (connector != null && connector.isAvailable()) {
                    HAModClusterService.this.latestLoad = this.getLoadBalanceFactor(engine);
                } else {
                    HAModClusterService.this.latestLoad = -1;
                }
            }

            if (HAModClusterService.this.isMasterNode()) {
                HAModClusterService.this.statusCount = (HAModClusterService.this.statusCount + 1)
                        % HAModClusterService.this.processStatusFrequency;

                if (HAModClusterService.this.statusCount == 0) {
                    this.updateClusterStatus();
                }
            }
        }

        private void updateClusterStatus() {
            Set<MCMPServerState> masterStates = null;
            Map<ClusterNode, MCMPServerDiscoveryEvent> latestEvents = null;
            Map<ClusterNode, ModClusterServiceDRMEntry> nonresponsive = new HashMap<ClusterNode, ModClusterServiceDRMEntry>();
            Map<String, Integer> loadBalanceFactors = new HashMap<String, Integer>();
            Map<ClusterNode, PeerMCMPDiscoveryStatus> statuses = new HashMap<ClusterNode, PeerMCMPDiscoveryStatus>();
            List<MCMPRequest> resetRequests = new ArrayList<MCMPRequest>();
            boolean resync = false;

            do {
                resync = false;

                HAModClusterService.this.localHandler.status();

                synchronized (HAModClusterService.this.proxyChangeDigest) {
                    masterStates = HAModClusterService.this.localHandler.getProxyStates();
                    latestEvents = new HashMap<ClusterNode, MCMPServerDiscoveryEvent>(
                            HAModClusterService.this.proxyChangeDigest);
                }

                List<ModClusterServiceDRMEntry> replicants = HAModClusterService.this.lookupDRMEntries();
                nonresponsive.clear();

                for (ModClusterServiceDRMEntry replicant : replicants) {
                    nonresponsive.put(replicant.getPeer(), replicant);
                }
                nonresponsive.remove(HAModClusterService.this.getHAPartition().getClusterNode());

                // FIXME -- what about our own dropped discovery events if we just became master?
                List<RpcResponse<ModClusterServiceStatus>> responses = HAModClusterService.this.rpcStub
                        .getClusterCoordinatorState(masterStates);

                // Gather up all the reset requests in one list
                // FIXME -- what about our own dropped requests if we just became master?
                resetRequests.clear();

                // Gather all the load balance factors
                loadBalanceFactors.clear();

                // Add our own lbf - it is not returned via getClusterCoordinatorState(...)
                for (String jvmRoute : HAModClusterService.this.drmEntry.getJvmRoutes()) {
                    loadBalanceFactors.put(jvmRoute, Integer.valueOf(HAModClusterService.this.latestLoad));
                }

                // Gather the info on who knows about what proxies
                statuses.clear();

                for (RpcResponse<ModClusterServiceStatus> response : responses) {
                    if (response == null)
                        continue;

                    ClusterNode node = response.getSender();

                    try {
                        ModClusterServiceStatus state = response.getResult();

                        // Check for discovery events we haven't processed
                        MCMPServerDiscoveryEvent latestEvent = latestEvents.get(node);

                        for (MCMPServerDiscoveryEvent event : state.getUnacknowledgedEvents()) {
                            if ((latestEvent == null) || (latestEvent.compareTo(event) < 0)) {
                                InetSocketAddress socketAddress = event.getMCMPServer();
                                if (event.isAddition()) {
                                    HAModClusterService.this.localHandler.addProxy(socketAddress);
                                } else {
                                    HAModClusterService.this.localHandler.removeProxy(socketAddress);
                                }
                                resync = true;
                            }
                        }

                        if (!resync) // don't bother if we are going to start over
                        {
                            statuses.put(node, new PeerMCMPDiscoveryStatus(node, state.getStates(), latestEvent));

                            List<MCMPRequest> toAdd = state.getResetRequests();
                            if (toAdd != null) {
                                resetRequests.addAll(toAdd);
                            }

                            ModClusterServiceDRMEntry removed = nonresponsive.remove(node);
                            if (removed != null) {
                                Integer lbf = Integer.valueOf(state.getLoadBalanceFactor());
                                for (String jvmRoute : removed.getJvmRoutes()) {
                                    loadBalanceFactors.put(jvmRoute, lbf);
                                }
                            }
                        }
                    } catch (Exception e) {
                        this.log.warn(Strings.ERROR_RPC_KNOWN.getString("getClusterCoordinatorState", node), e);

                        // Don't remove from nonresponsive list and we'll pass back an error
                        // status (null server list) to this peer
                    }
                }
            }
            // We picked up previously unknown discovery events; start over
            while (resync);

            // Add error-state objects for non-responsive peers
            Integer lbf = Integer.valueOf(0);
            for (Map.Entry<ClusterNode, ModClusterServiceDRMEntry> entry : nonresponsive.entrySet()) {
                ClusterNode cn = entry.getKey();
                statuses.put(entry.getKey(), new PeerMCMPDiscoveryStatus(cn, null, latestEvents.get(cn)));

                for (String jvmRoute : entry.getValue().getJvmRoutes()) {
                    loadBalanceFactors.put(jvmRoute, lbf);
                }
            }
            // FIXME handle crashed members, gone from DRM

            // Advise the proxies of any reset requests
            HAModClusterService.this.localHandler.sendRequests(resetRequests);

            // Pass along the LBF values
            List<MCMPRequest> statusRequests = new ArrayList<MCMPRequest>();
            for (Map.Entry<String, Integer> entry : loadBalanceFactors.entrySet()) {
                statusRequests.add(HAModClusterService.this.requestFactory.createStatusRequest(entry.getKey(), entry.getValue()
                        .intValue()));
            }

            HAModClusterService.this.localHandler.sendRequests(statusRequests);

            // Advise the members the process is done and that they should update DRM
            this.notifyClusterStatusComplete(masterStates, statuses);
        }

        private void notifyClusterStatusComplete(Set<MCMPServerState> masterList,
                Map<ClusterNode, PeerMCMPDiscoveryStatus> statuses) {
            // Determine who should update DRM first -- us or the rest of the nodes
            Set<ModClusterServiceDRMEntry> allStatuses = new HashSet<ModClusterServiceDRMEntry>(statuses.values());
            ModClusterServiceDRMEntry ourCurrentStatus = HAModClusterService.this.lookupLocalDRMEntry();
            allStatuses.add(ourCurrentStatus);

            ClusterNode node = HAModClusterService.this.getHAPartition().getClusterNode();

            boolean othersFirst = HAModClusterService.this.findMasterCandidates(allStatuses).contains(node);
            ModClusterServiceDRMEntry newStatus = new ModClusterServiceDRMEntry(node, masterList,
                    HAModClusterService.this.drmEntry.getJvmRoutes());
            boolean updated = !newStatus.getMCMPServerStates().equals(ourCurrentStatus.getMCMPServerStates());

            if (othersFirst) {
                HAModClusterService.this.rpcStub.clusterStatusComplete(statuses);
            }

            if (updated) {
                HAModClusterService.this.updateLocalDRM(newStatus);
            }

            if (!othersFirst) {
                HAModClusterService.this.rpcStub.clusterStatusComplete(statuses);
            }
        }
    }
}
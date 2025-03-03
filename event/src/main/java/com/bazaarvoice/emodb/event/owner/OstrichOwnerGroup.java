package com.bazaarvoice.emodb.event.owner;

import com.bazaarvoice.curator.recipes.leader.LeaderService;
import com.bazaarvoice.emodb.common.dropwizard.leader.LeaderServiceTask;
import com.bazaarvoice.emodb.common.dropwizard.lifecycle.ServiceFailureListener;
import com.bazaarvoice.ostrich.HostDiscovery;
import com.bazaarvoice.ostrich.ServiceEndPoint;
import com.bazaarvoice.ostrich.partition.ConsistentHashPartitionFilter;
import com.bazaarvoice.ostrich.partition.PartitionFilter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Service;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * A group of services that should only run when the current server is considered the owner of the specified object.
 * <p>
 * This uses the Ostrich consistent hashing algorithm to determine which objects this server should attempt to own,
 * and a ZooKeeper leader election to ensure that it the exclusive owner of the object.
 */
public class OstrichOwnerGroup<T extends Service> implements OwnerGroup<T> {
    private static final Logger _log = LoggerFactory.getLogger(OstrichOwnerGroup.class);

    private final String _group;
    private final OstrichOwnerFactory<T> _factory;
    private final LoadingCache<String, Optional<LeaderService>> _leaderMap;
    private final CuratorFramework _curator;
    private final HostDiscovery _hostDiscovery;
    private final HostDiscovery.EndPointListener _endPointListener;
    private final String _selfId;
    private final LeaderServiceTask _dropwizardTask;
    private final PartitionFilter _partitionFilter = new ConsistentHashPartitionFilter();
    private final boolean _expireWhenInactive;
    private final MetricRegistry _metricRegistry;

    public OstrichOwnerGroup(String group,
                             OstrichOwnerFactory<T> factory,
                             @Nullable Duration expireWhenInactive,
                             CuratorFramework curator,
                             HostDiscovery hostDiscovery,
                             HostAndPort self,
                             LeaderServiceTask dropwizardTask,
                             MetricRegistry metricRegistry) {
        _group = requireNonNull(group, "group");
        _factory = requireNonNull(factory, "factory");
        _curator = requireNonNull(curator, "curator");
        _hostDiscovery = requireNonNull(hostDiscovery, "hostDiscovery");
        _selfId = requireNonNull(self, "self").toString();
        _dropwizardTask = requireNonNull(dropwizardTask, "dropwizardTask");
        _expireWhenInactive = (expireWhenInactive != null);
        _metricRegistry = metricRegistry;

        // Build a cache of name -> leader service, used to track which objects this server is responsible for.
        CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.newBuilder();
        if (_expireWhenInactive) {
            cacheBuilder.expireAfterAccess(expireWhenInactive.toMillis(), TimeUnit.MILLISECONDS);
        }
        cacheBuilder.removalListener(new RemovalListener<String, Optional<LeaderService>>() {
            @Override
            public void onRemoval(RemovalNotification<String, Optional<LeaderService>> notification) {
                stopService(requireNonNull(notification.getKey()), requireNonNull(notification.getValue()));
            }
        });
        _leaderMap = cacheBuilder.build(new CacheLoader<String, Optional<LeaderService>>() {
            @Override
            public Optional<LeaderService> load(String name) throws Exception {
                return startService(name);
            }
        });

        // Watch for changes to the set of hosts since that affects which objects this server is responsible for.
        _endPointListener = new HostDiscovery.EndPointListener() {
            @Override
            public void onEndPointAdded(ServiceEndPoint endPoint) {
                onOwnersChanged();
            }

            @Override
            public void onEndPointRemoved(ServiceEndPoint endPoint) {
                onOwnersChanged();
            }
        };
        _hostDiscovery.addListener(_endPointListener);
    }

    /**
     * Returns the specified managed service if this server is responsible for the specified object and has won a
     * ZooKeeper-managed leader election.
     *
     * @param name         object name.  Whether this server owns the object is computed by Ostrich using consistent hashing.
     * @param waitDuration the amount of time to wait for this server to win the leader election and for the service
     *                     to startup, if the object is managed by this server.
     */
    @Nullable
    @Override
    public T startIfOwner(String name, Duration waitDuration) {
        long timeoutAt = System.currentTimeMillis() + waitDuration.toMillis();
        LeaderService leaderService = _leaderMap.getUnchecked(name).orElse(null);
        if (leaderService == null || !awaitRunning(leaderService, timeoutAt)) {
            return null;
        }
        Service service;
        for (; ; ) {
            Optional<Service> opt = leaderService.getCurrentDelegateService()
                    .transform(java.util.Optional::of)
                    .or(java.util.Optional.empty());
            if (opt.isPresent()) {
                service = opt.get();
                break;
            }
            if (System.currentTimeMillis() >= timeoutAt) {
                return null;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Throwables.throwIfUnchecked(e);
                throw new RuntimeException(e);
            }
        }
        if (!awaitRunning(service, timeoutAt)) {
            return null;
        }
        //noinspection unchecked
        return (T) service;
    }

    @Override
    public Map<String, T> getServices() {
        Map<String, T> snapshotMap = Maps.newLinkedHashMap();
        for (Map.Entry<String, Optional<LeaderService>> entry : _leaderMap.asMap().entrySet()) {
            String name = entry.getKey();
            Optional<LeaderService> ref = entry.getValue();
            if (!ref.isPresent()) {
                continue;
            }
            Optional<Service> service = ref.get().getCurrentDelegateService()
                    .transform(java.util.Optional::of)
                    .or(java.util.Optional.empty());
            if (!service.isPresent()) {
                continue;
            }
            //noinspection unchecked
            snapshotMap.put(name, (T) service.get());
        }
        return snapshotMap;
    }

    @Override
    public void stop(String name) {
        _leaderMap.invalidate(name);
    }

    @Override
    public void close() {
        _hostDiscovery.removeListener(_endPointListener);
        _leaderMap.invalidateAll();
    }

    private boolean isOwner(String name) {
        // Replicates the calculation performed inside Ostrich to determine which endpoint owns an object.
        Iterable<ServiceEndPoint> endPoints = _hostDiscovery.getHosts();
        if (Iterables.isEmpty(endPoints)) {
            return false;
        }
        ServiceEndPoint owner = Iterables.getOnlyElement(
                _partitionFilter.filter(endPoints, _factory.getContext(name)));
        return _selfId.equals(owner.getId());
    }

    private void onOwnersChanged() {
        // If ownership of an object has changed such that we're now eligible to manage it or now no longer
        // eligible to manage it, start or stop it and update our cache.
        List<String> pending = Lists.newArrayList();
        for (Iterator<Map.Entry<String, Optional<LeaderService>>> it =
             _leaderMap.asMap().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Optional<LeaderService>> entry = it.next();
            if (isOwner(entry.getKey()) != entry.getValue().isPresent()) {
                // Remove the entry from the cache.  This will stop the service (if started).
                it.remove();
                // Add it back once we've finished looping through the entries.
                if (!_expireWhenInactive) {
                    pending.add(entry.getKey());
                }
            }
        }
        for (String name : pending) {
            startIfOwner(name, Duration.ZERO);
        }
    }

    private Optional<LeaderService> startService(final String name) {
        if (!isOwner(name)) {
            return Optional.empty();
        }

        _log.info("Starting owned service {}: {}", _group, name);

        String zkLeaderPath = String.format("/leader/%s/%s", _group.toLowerCase(), name);
        String threadName = String.format("Leader-%s-%s", _group, name);
        String taskName = String.format("%s-%s", _group.toLowerCase(), name);

        LeaderService leaderService = new LeaderService(_curator, zkLeaderPath, _selfId,
                threadName, 1, TimeUnit.MINUTES, new Supplier<Service>() {
            @Override
            public Service get() {
                return _factory.create(name);
            }
        });
        ServiceFailureListener.listenTo(leaderService, _metricRegistry);
        _dropwizardTask.register(taskName, leaderService);
        leaderService.startAsync().awaitRunning();
        return Optional.of(leaderService);
    }

    private void stopService(String name, Optional<? extends Service> ref) {
        if (ref.isPresent()) {
            Service service = ref.get();
            _log.info("Stopping owned service {}: {}", _group, name);
            service.stopAsync().awaitTerminated();
        }
    }

    /**
     * Returns true if the Guava service entered the RUNNING state within the specified time period.
     */
    private static boolean awaitRunning(Service service, long timeoutAt) {
        if (service.isRunning()) {
            return true;
        }
        long waitMillis = timeoutAt - System.currentTimeMillis();
        if (waitMillis <= 0) {
            return false;
        }
        try {
            service.startAsync().awaitRunning(waitMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Fall through
        }
        return service.isRunning();
    }
}

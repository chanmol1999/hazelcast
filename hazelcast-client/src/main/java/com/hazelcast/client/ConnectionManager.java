/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.client;

import com.hazelcast.client.ClientProperties.ClientPropertyName;
import com.hazelcast.core.Member;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static com.hazelcast.core.LifecycleEvent.LifecycleState.*;
import static java.text.MessageFormat.format;

public class ConnectionManager implements MembershipListener {
    private final long TIMEOUT;
    private volatile Connection currentConnection;
    private final AtomicInteger connectionIdGenerator = new AtomicInteger(-1);
    private final List<InetSocketAddress> clusterMembers = new CopyOnWriteArrayList<InetSocketAddress>();
    private final ILogger logger = Logger.getLogger(getClass().getName());
    private final HazelcastClient client;
    private volatile int lastDisconnectedConnectionId = -1;
    private ClientBinder binder;

    private volatile boolean lookingForLiveConnection = false;
    private volatile boolean running = true;

    private final LifecycleServiceClientImpl lifecycleService;

    Timer heartbeatTimer = new Timer();

    public ConnectionManager(HazelcastClient client, LifecycleServiceClientImpl lifecycleService, InetSocketAddress[] clusterMembers, boolean shuffle, long timeout) {
        this.TIMEOUT = timeout;
        this.client = client;
        this.lifecycleService = lifecycleService;
        this.clusterMembers.addAll(Arrays.asList(clusterMembers));
        if (shuffle) {
            Collections.shuffle(this.clusterMembers);
        }
    }

    public ConnectionManager(final HazelcastClient client, LifecycleServiceClientImpl lifecycleService, InetSocketAddress address, long timeout) {
        this.TIMEOUT = timeout;
        this.client = client;
        this.lifecycleService = lifecycleService;
        this.clusterMembers.add(address);
    }

    void scheduleHeartbeatTimerTask() {
        heartbeatTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                long diff = client.getInRunnable().lastReceived - System.currentTimeMillis();
                try {
                    if (diff >= TIMEOUT / 5 && diff < TIMEOUT) {
                        logger.log(Level.FINEST, "Being idle for some time, Doing a getMembers() call to ping the server!");
                        final CountDownLatch latch = new CountDownLatch(1);
                        new Thread(new Runnable() {
                            public void run() {
                                Set<Member> members = client.getCluster().getMembers();
                                if (members != null && members.size() >= 1) {
                                    latch.countDown();
                                }
                            }
                        }).start();
                        if (!latch.await(10000, TimeUnit.MILLISECONDS)) {
                            logger.log(Level.WARNING, "Server didn't respond to client's ping call within 10 seconds!");
                        }
                    } else if (diff >= TIMEOUT) {
                        logger.log(Level.WARNING, "Server didn't respond to client's requests for " + TIMEOUT / 1000 + " seconds. Assuming it is dead, closing the connection!");
                        currentConnection.close();
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (IOException ignored) {
                }
            }
        }, TIMEOUT / 10, TIMEOUT / 10);
    }

    public Connection getInitConnection() throws IOException {
        if (currentConnection == null) {
            synchronized (this) {
                final int attemptsLimit = client.getProperties().getInteger(ClientPropertyName.INIT_CONNECTION_ATTEMPTS_LIMIT);
                final int reconnectionTimeout = client.getProperties().getInteger(ClientPropertyName.RECONNECTION_TIMEOUT);
                currentConnection = lookForLiveConnection(attemptsLimit, reconnectionTimeout);
            }
        }
        return currentConnection;
    }

    public Connection lookForLiveConnection() throws IOException {
        final int attemptsLimit = client.getProperties().getInteger(ClientPropertyName.RECONNECTION_ATTEMPTS_LIMIT);
        final int reconnectionTimeout = client.getProperties().getInteger(ClientPropertyName.RECONNECTION_TIMEOUT);
        return lookForLiveConnection(attemptsLimit, reconnectionTimeout);
    }

    private Connection lookForLiveConnection(final int attemptsLimit,
                                             final int reconnectionTimeout) throws IOException {
        lookingForLiveConnection = true;
        try {
            boolean restored = false;
            int attempt = 0;
            while (currentConnection == null && running && !Thread.interrupted()) {
                final long next = System.currentTimeMillis() + reconnectionTimeout;
                synchronized (this) {
                    if (currentConnection == null) {
                        final Connection connection = searchForAvailableConnection();
                        restored = connection != null;
                        if (restored) {
                            try {
                                bindConnection(connection);
                                currentConnection = connection;
                            } catch (Throwable e) {
                                closeConnection(connection);
                                logger.log(Level.WARNING, "got an exception on getConnection:" + e.getMessage(), e);
                                restored = false;
                            }
                        }
                    }
                }
                if (currentConnection != null) {
                    logger.log(Level.FINE, "Client is connecting to " + currentConnection);
                    lookingForLiveConnection = false;
                    break;
                }
                if (attempt >= attemptsLimit) {
                    break;
                }
                attempt++;
                final long t = next - System.currentTimeMillis();
                logger.log(Level.INFO, format("Unable to get alive cluster connection," +
                        " try in {0} ms later, attempt {1} of {2}.",
                        Math.max(0, t), attempt, attemptsLimit));
                if (t > 0) {
                    try {
                        Thread.sleep(t);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            if (restored) {
                notifyConnectionIsRestored();
            }
        } finally {
            lookingForLiveConnection = false;
        }
        return currentConnection;
    }

    void closeConnection(final Connection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Throwable e) {
            logger.log(Level.INFO, "got an exception on closeConnection "
                    + connection + ":" + e.getMessage(), e);
        }
    }

    public Connection getConnection() throws IOException {
        if (currentConnection == null && running && !lookingForLiveConnection) {
            boolean restored = false;
            synchronized (this) {
                if (currentConnection == null) {
                    Connection connection = searchForAvailableConnection();
                    if (connection != null) {
                        logger.log(Level.FINE, "Client is connecting to " + connection);
                        try {
                            bindConnection(connection);
                            currentConnection = connection;
                        } catch (Throwable e) {
                            closeConnection(connection);
                            logger.log(Level.WARNING, "got an exception on getConnection:" + e.getMessage(), e);
                        }
                    }
                    restored = currentConnection != null;
                }
            }
            if (restored) {
                notifyConnectionIsRestored();
            }
        }
        return currentConnection;
    }

    void notifyConnectionIsRestored() {
        lifecycleService.fireLifecycleEvent(CLIENT_CONNECTION_OPENING);
    }

    void notifyConnectionIsOpened() {
        notify(new Runnable() {
            public void run() {
                lifecycleService.fireLifecycleEvent(CLIENT_CONNECTION_OPENED);
            }
        });
    }

    private void notify(final Runnable target) {
        client.runAsyncAndWait(target);
    }

    void bindConnection(Connection connection) throws IOException {
        binder.bind(connection);
    }

    public void destroyConnection(final Connection connection) {
        boolean lost = false;
        synchronized (this) {
            if (currentConnection != null &&
                    connection != null &&
                    currentConnection.getVersion() == connection.getVersion()) {
                logger.log(Level.WARNING, "Connection to " + currentConnection + " is lost");
                currentConnection = null;
                lost = true;
            }
        }
        if (lost) {
            notify(new Runnable() {
                public void run() {
                    lifecycleService.fireLifecycleEvent(CLIENT_CONNECTION_LOST);
                }
            });
        }
    }

    private void popAndPush(List<InetSocketAddress> clusterMembers) {
        InetSocketAddress address = clusterMembers.remove(0);
        clusterMembers.add(address);
    }

    private Connection searchForAvailableConnection() {
        Connection connection = null;
        popAndPush(clusterMembers);
        int counter = clusterMembers.size();
        while (counter > 0) {
            try {
                connection = getNextConnection();
                break;
            } catch (Exception e) {
                popAndPush(clusterMembers);
                counter--;
            }
        }
        logger.log(Level.FINEST, format("searchForAvailableConnection connection:{0}", connection));
        return connection;
    }

    protected Connection getNextConnection() {
        InetSocketAddress address = clusterMembers.get(0);
        return new Connection(address, connectionIdGenerator.incrementAndGet());
    }

    public synchronized void memberAdded(MembershipEvent membershipEvent) {
        if (!this.clusterMembers.contains(membershipEvent.getMember().getInetSocketAddress())) {
            this.clusterMembers.add(membershipEvent.getMember().getInetSocketAddress());
        }
    }

    public synchronized void memberRemoved(MembershipEvent membershipEvent) {
        this.clusterMembers.remove(membershipEvent.getMember().getInetSocketAddress());
    }

    public synchronized void updateMembers() {
        Set<Member> members = client.getCluster().getMembers();
        clusterMembers.clear();
        for (Member member : members) {
            clusterMembers.add(member.getInetSocketAddress());
        }
    }

    public boolean shouldExecuteOnDisconnect(Connection connection) {
        if (connection == null || lastDisconnectedConnectionId >= connection.getVersion()) {
            return false;
        }
        lastDisconnectedConnectionId = connection.getVersion();
        return true;
    }

    public void setBinder(ClientBinder binder) {
        this.binder = binder;
    }

    List<InetSocketAddress> getClusterMembers() {
        return clusterMembers;
    }

    public void shutdown() {
        logger.log(Level.INFO, getClass().getSimpleName() + " shutdown");
        running = false;
        heartbeatTimer.cancel();
    }
}

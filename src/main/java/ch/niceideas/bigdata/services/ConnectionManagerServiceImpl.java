/*
 * This file is part of the eskimo project referenced at www.eskimo.sh. The licensing information below apply just as
 * well to this individual file than to the Eskimo Project as a whole.
 *
 * Copyright 2019 - 2023 eskimo.sh / https://www.eskimo.sh - All rights reserved.
 * Author : eskimo.sh / https://www.eskimo.sh
 *
 * Eskimo is available under a dual licensing model : commercial and GNU AGPL.
 * If you did not acquire a commercial licence for Eskimo, you can still use it and consider it free software under the
 * terms of the GNU Affero Public License. You can redistribute it and/or modify it under the terms of the GNU Affero
 * Public License  as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * Compliance to each and every aspect of the GNU Affero Public License is mandatory for users who did no acquire a
 * commercial license.
 *
 * Eskimo is distributed as a free software under GNU AGPL in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero Public License for more details.
 *
 * You should have received a copy of the GNU Affero Public License along with Eskimo. If not,
 * see <https://www.gnu.org/licenses/> or write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA, 02110-1301 USA.
 *
 * You can be released from the requirements of the license by purchasing a commercial license. Buying such a
 * commercial license is mandatory as soon as :
 * - you develop activities involving Eskimo without disclosing the source code of your own product, software,
 *   platform, use cases or scripts.
 * - you deploy eskimo as part of a commercial product, platform or software.
 * For more information, please contact eskimo.sh at https://www.eskimo.sh
 *
 * The above copyright notice and this licensing notice shall be included in all copies or substantial portions of the
 * Software.
 */

package ch.niceideas.bigdata.services;

import ch.niceideas.common.json.JsonWrapper;
import ch.niceideas.common.utils.FileException;
import ch.niceideas.bigdata.model.SSHConnection;
import ch.niceideas.bigdata.model.service.proxy.ProxyTunnelConfig;
import ch.niceideas.bigdata.proxy.ProxyManagerService;
import ch.niceideas.bigdata.types.Node;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON, proxyMode = ScopedProxyMode.INTERFACES)
@Profile("!test-connection-manager")
public class ConnectionManagerServiceImpl implements ConnectionManagerService{

    private static final Logger logger = Logger.getLogger(ConnectionManagerServiceImpl.class);

    @Autowired
    private ProxyManagerService proxyManagerService;

    @Autowired
    private ConfigurationService configurationService;

    @Value("${connectionManager.defaultSSHPort}")
    protected int sshPort = 22;

    @Value("${connectionManager.tcpConnectionTimeout}")
    private int tcpConnectionTimeout = 30000;

    @Value("${connectionManager.sshKeyExchangeTimeout}")
    private int sshKeyExchangeTimeout = 20000;

    @Value("${connectionManager.maximumConnectionAge}")
    private int maximumConnectionAge = 600000;

    @Value("${connectionManager.sshOperationTimeout}")
    private int sshOperationTimeout = 120000;

    @Value("${connectionManager.scriptOperationTimeout}")
    private int scriptOperationTimeout = 1800000;

    @Value("${connectionManager.statusOperationTimeout}")
    private int statusOperationTimeout = 1800000;

    private final ReentrantLock connectionMapLock = new ReentrantLock();

    protected final Map<Node, SSHConnection> connectionMap = new HashMap<>();
    protected final Map<SSHConnection, List<LocalPortForwarderWrapper>> portForwardersMap = new HashMap<>();

    protected final Map<Node, Long> connectionAges = new HashMap<>();

    protected String privateSShKeyContent = null;

    protected final List<SSHConnection> connectionsToCloseLazily = new ArrayList<>();

    private final ScheduledExecutorService scheduler;

    public ConnectionManagerServiceImpl() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        logger.info ("Initializing connection closer scheduler ...");
        scheduler.scheduleAtFixedRate(() -> {
            for (SSHConnection connection : connectionsToCloseLazily) {
                logger.info ("Lazily closing connection to " + connection.getHostname());
                closeConnection(connection);
            }
            connectionsToCloseLazily.clear();
        }, maximumConnectionAge, maximumConnectionAge, TimeUnit.MILLISECONDS);
    }

    private void __dumpPortForwardersMap () {
        new HashSet<>(portForwardersMap.entrySet()).forEach(entry -> {
           logger.debug(" - " + entry.getKey().getHostname());
           entry.getValue().forEach(forwarder -> logger.debug("   + " + forwarder.toString()));
        });
        logger.debug("");
    }

    @PreDestroy
    public void destroy() {
        logger.info ("Cancelling connection closer scheduler");
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public SSHConnection getPrivateConnection (Node node) throws ConnectionManagerException {
        try {
            return createConnectionInternal(node, scriptOperationTimeout);
        } catch (IOException | JSONException | FileException | SetupException e) {
            logger.error (e.getMessage());
            logger.debug (e, e);
            throw new ConnectionManagerException(e);
        }
    }

    @Override
    public SSHConnection getSharedConnection (Node node) throws ConnectionManagerException {
        return getConnectionInternal(node);
    }

    private void closeConnection (SSHConnection connection) {
        try {
            logger.info ("Closing connection to " + connection.getHostname());

            for (LocalPortForwarderWrapper localPortForwarder : getForwarders(connection)) {
                localPortForwarder.close();
            }
            portForwardersMap.remove(connection);

            connection.close();
        } catch (Exception e) {
            logger.debug (e, e);
        }
    }

    private void removeConnectionAndRegisterClose(Node node, SSHConnection connection) {
        connectionMap.remove(node);
        connectionAges.remove(node);

        // tunnels should be closed immediately !
        try {
            for (LocalPortForwarderWrapper forwarder : getForwarders(connection)) {
                forwarder.close();
            }
        } catch (Exception e) {
            logger.warn(e.getMessage());
            logger.debug(e, e);
        }
        portForwardersMap.remove(connection);

        connectionsToCloseLazily.add (connection);
    }

    @Override
    public void recreateTunnels(Node host) throws ConnectionManagerException {
        recreateTunnels(getConnectionInternal(host), host);
    }

    @Override
    public void dropTunnelsToBeClosed(Node host) {
        Optional.ofNullable(connectionMap.get(host)).ifPresent(con -> dropTunnelsToBeClosed(con, host));
    }

    private SSHConnection getConnectionInternal (Node node) throws ConnectionManagerException {

        connectionMapLock.lock();

        try {

            SSHConnection connection = connectionMap.get(node);

            // if connection doesn't exist, create it
            if (connection == null) {
                connection = createConnectionInternal(node, statusOperationTimeout);

                recreateTunnels(connection, node);

                connectionMap.put(node, connection);
                connectionAges.put(node, System.currentTimeMillis());
            }

            // otherwise test it and attempt to recreate it if it is down or too old
            else {

                try {

                    Long connectionAge = connectionAges.get(node);

                    if (connectionAge + maximumConnectionAge < System.currentTimeMillis()) {
                        logger.warn ("Previous connection to " + node + " is too old. Recreating ...");
                        removeConnectionAndRegisterClose(node, connection);
                        return getConnectionInternal(node);
                    }

                    try (ConnectionOperationWatchDog ignored = new ConnectionOperationWatchDog(connection)) {
                        //connection.ping(); // this is too buggy !!! Waits for the socket outputStream result like forever and seems impossible to kill
                        connection.sendIgnorePacket();
                    }

                    // update connection age
                    connectionAges.put(node, System.currentTimeMillis());

                } catch (IOException | IllegalStateException e) {
                    logger.warn ("Previous connection to " + node + " got into problems ("+e.getMessage()+"). Recreating ...");
                    removeConnectionAndRegisterClose(node, connection);
                    return getConnectionInternal(node);
                }
            }

            return connection;

        } catch (IOException | JSONException | FileException | SetupException e) {
            logger.error ("When recreating connection to " + node +" - got " + e.getClass() + ":" + e.getMessage());
            logger.debug (e, e);
            throw new ConnectionManagerException(e);

        } finally  {
            connectionMapLock.unlock();
        }
    }

    protected SSHConnection createConnectionInternal(Node node, int operationTimeout) throws IOException, FileException, SetupException {
        logger.info ("Creating connection to " + node);
        SSHConnection connection = new SSHConnection(node, sshPort, operationTimeout);
        return connect(connection);
    }

    protected SSHConnection connect(SSHConnection connection) throws IOException, FileException, SetupException {
        connection.setTCPNoDelay(true);
        connection.connect(null, tcpConnectionTimeout, sshKeyExchangeTimeout); // TCP timeout, Key exchange timeout

        JsonWrapper systemConfig = configurationService.loadSetupConfig();

        if (privateSShKeyContent == null) {
            privateSShKeyContent = (String) systemConfig.getValueForPath("content-ssh-key");
        }

        connection.authenticateWithPublicKey(
                (String) systemConfig.getValueForPath(SetupService.SSH_USERNAME_FIELD),
                privateSShKeyContent.toCharArray(),
                null);

        if (!connection.isAuthenticationComplete()) {
            throw new IOException("Authentication failed");
        }
        return connection;
    }

    protected void dropTunnelsToBeClosed(SSHConnection connection, Node node) {

        // Find out about declared forwarders to be handled (those that need to stay)
        List<ProxyTunnelConfig> keptTunnelConfigs = proxyManagerService.getTunnelConfigForHost(node);

        // close port forwarders that are not declared anymore
        final List<LocalPortForwarderWrapper> currentForwarders = getForwarders(connection);
        List<LocalPortForwarderWrapper> toBeClosed = currentForwarders.stream()
                .filter(forwarder -> notIn (forwarder, keptTunnelConfigs))
                .collect(Collectors.toList());

        for (LocalPortForwarderWrapper forwarder : toBeClosed) {
            try {
                forwarder.close();
            } catch (Exception e) {
                logger.warn(e.getMessage());
                logger.debug(e, e);
            }
            currentForwarders.remove(forwarder);
        }
    }

    protected void recreateTunnels(SSHConnection connection, Node node) throws ConnectionManagerException {

        if (logger.isDebugEnabled()){
            logger.debug("------ BEFORE ---- recreateTunnels (" + node + ") ----------- ");
            __dumpPortForwardersMap();
        }

        final List<LocalPortForwarderWrapper> currentForwarders = getForwarders(connection);

        dropTunnelsToBeClosed(connection, node);

        // Find out about declared forwarders to be handled
        List<ProxyTunnelConfig> tunnelConfigs = proxyManagerService.getTunnelConfigForHost(node);

        // recreate those that need to be recreated
        List<ProxyTunnelConfig> toBeCreated = tunnelConfigs.stream()
                .filter(config -> notIn (config, currentForwarders))
                .collect(Collectors.toList());

        for (ProxyTunnelConfig config : toBeCreated) {
            try {
                currentForwarders.add(createPortForwarder(connection, config));
            } catch (RemoveForwarderException e) {
                logger.warn ("Not trying any further to recreate forwarder for "
                        + config.getService() + " - " + config.getNode() + " - " + config.getRemotePort());
            }
        }

        if (logger.isDebugEnabled()){
            logger.debug("------ AFTER ---- recreateTunnels (" + node + ") ----------- ");
            __dumpPortForwardersMap();
        }
    }

    protected LocalPortForwarderWrapper createPortForwarder(SSHConnection connection, ProxyTunnelConfig config) throws ConnectionManagerException {
        return new LocalPortForwarderWrapper(
                config.getService(), connection, config.getLocalPort(), config.getNode(), config.getRemotePort());
    }

    private List<LocalPortForwarderWrapper> getForwarders(SSHConnection connection) {
        return portForwardersMap.computeIfAbsent(connection, k -> new ArrayList<>());
    }

    private boolean notIn(ProxyTunnelConfig config, List<LocalPortForwarderWrapper> previousForwarders) {
        return previousForwarders.stream().noneMatch(forwarder -> forwarder.matches(config));
    }

    private boolean notIn(LocalPortForwarderWrapper forwarder, List<ProxyTunnelConfig> tunnelConfigs) {
        return tunnelConfigs.stream().noneMatch(forwarder::matches);
    }

    private class ConnectionOperationWatchDog implements AutoCloseable {

        private final ScheduledExecutorService scheduler;

        public ConnectionOperationWatchDog(SSHConnection connection) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.schedule(() -> closeConnection(connection), sshOperationTimeout, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }

}

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

import ch.niceideas.common.utils.FileException;
import ch.niceideas.common.utils.FileUtils;
import ch.niceideas.common.utils.Pair;
import ch.niceideas.common.utils.StringUtils;
import ch.niceideas.bigdata.model.*;
import ch.niceideas.bigdata.model.service.Dependency;
import ch.niceideas.bigdata.model.service.ServiceDefinition;
import ch.niceideas.bigdata.proxy.ProxyManagerService;
import ch.niceideas.bigdata.services.satellite.NodeRangeResolver;
import ch.niceideas.bigdata.services.satellite.NodesConfigurationException;
import ch.niceideas.bigdata.types.Node;
import ch.niceideas.bigdata.types.Service;
import ch.niceideas.bigdata.utils.SchedulerHelper;
import ch.niceideas.bigdata.utils.SystemStatusParser;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
@Profile({"!test-system & !system-under-test"})
public class SystemServiceImpl implements SystemService {

    private static final Logger logger = Logger.getLogger(SystemServiceImpl.class);

    public static final String TMP_PATH_PREFIX = "/tmp/";

    @Autowired
    private ProxyManagerService proxyManagerService;

    @Autowired
    private SetupService setupService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SSHCommandService sshCommandService;

    @Autowired
    private ServicesDefinition servicesDefinition;

    @Autowired
    private NodeRangeResolver nodeRangeResolver;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private KubernetesService kubernetesService;

    @Autowired
    private NodesConfigurationService nodesConfigurationService;

    @Autowired
    private OperationsMonitoringService operationsMonitoringService;

    @Value("${system.failedServicesTriggerCount}")
    private int failedServicesTriggerCount = 5;

    @Value("${connectionManager.statusOperationTimeout}")
    private int statusOperationTimeout = 60000; // ~ 13 minutes (for an individual step)

    @Value("${system.statusFetchThreadCount}")
    private int parallelismStatusThreadCount = 10;

    @Value("${system.packageDistributionPath}")
    protected String packageDistributionPath = "./packages_distrib";

    @Value("${system.servicesSetupPath}")
    protected String servicesSetupPath = "./services_setup";

    @Value("${system.statusUpdatePeriodSeconds}")
    private int statusUpdatePeriodSeconds = 5;

    private final ReentrantLock statusUpdateLock = new ReentrantLock();
    private final ScheduledExecutorService statusRefreshScheduler;
    protected final AtomicReference<SystemStatusWrapper> lastStatus = new AtomicReference<>();
    protected final AtomicReference<Exception> lastStatusException = new AtomicReference<>();

    private final Map<String, Integer> serviceMissingCounter = new ConcurrentHashMap<>();

    // constructor for spring
    public SystemServiceImpl() {
        this (true);
    }
    public SystemServiceImpl(boolean createUpdateScheduler) {
        statusRefreshScheduler = SchedulerHelper.scheduleRunnableOneShot(createUpdateScheduler, statusUpdatePeriodSeconds, this::updateStatus);
    }

    @PreDestroy
    public void destroy() {
        logger.info ("Cancelling status updater scheduler");
        if (statusRefreshScheduler != null) {
            statusRefreshScheduler.shutdownNow();
        }
    }

    @Override
    public void delegateApplyNodesConfig(NodeServiceOperationsCommand command)
            throws NodesConfigurationException {
        nodesConfigurationService.applyNodesConfig(command);
    }

    @Override
    public void showJournal(ServiceDefinition serviceDef, Node node) throws SystemException {
        applyServiceOperation(serviceDef.toService(), node, SimpleOperationCommand.SimpleOperation.SHOW_JOURNAL, () -> {
            if (serviceDef.isKubernetes()) {
                throw new UnsupportedOperationException("Showing kubernetes service journal for " + serviceDef.getName() + SHOULD_NOT_HAPPEN_FROM_HERE);
            } else {
                return sshCommandService.runSSHCommand(node, "sudo journalctl -u " + serviceDef.getName() +" --no-pager");
            }
        });
    }

    @Override
    public void startService(ServiceDefinition serviceDef, Node node) throws SystemException {
        applyServiceOperation(serviceDef.toService(), node, SimpleOperationCommand.SimpleOperation.START, () -> {
            if (serviceDef.isKubernetes()) {
                throw new UnsupportedOperationException("Starting kubernetes service " + serviceDef.getName() + SHOULD_NOT_HAPPEN_FROM_HERE);
            } else {
                return sshCommandService.runSSHCommand(node, "sudo bash -c 'systemctl reset-failed " + serviceDef.getName() + " && systemctl start " + serviceDef.getName() + "'");
            }
        });
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    public void stopService(ServiceDefinition serviceDef, Node node) throws SystemException{
        applyServiceOperation(serviceDef.toService(), node, SimpleOperationCommand.SimpleOperation.STOP, () -> {
            if (serviceDef.isKubernetes()) {
                throw new UnsupportedOperationException("Stopping kubernetes service " + serviceDef.getName() + SHOULD_NOT_HAPPEN_FROM_HERE);
            } else {
                return sshCommandService.runSSHCommand(node, "sudo systemctl stop " + serviceDef.getName());
            }
        });
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    public void restartService(ServiceDefinition serviceDef, Node node) throws SystemException {
        applyServiceOperation(serviceDef.toService(), node, SimpleOperationCommand.SimpleOperation.RESTART, () -> {
            if (serviceDef.isKubernetes()) {
                throw new UnsupportedOperationException("Restarting kubernetes service " + serviceDef.getName() + SHOULD_NOT_HAPPEN_FROM_HERE);
            } else {
                return sshCommandService.runSSHCommand(node, "sudo bash -c 'systemctl reset-failed " + serviceDef.getName() + " && systemctl restart " + serviceDef.getName() + "'");
            }
        });
    }

    @Override
    public void callCommand(String commandId, Service service, Node node) throws SystemException {
        applyServiceOperation(service, node, SimpleOperationCommand.SimpleOperation.COMMAND , () -> {
            ServiceDefinition serviceDef = servicesDefinition.getServiceDefinition(service);

            Command command = Optional.ofNullable(serviceDef.getCommand (commandId))
                    .orElseThrow(() -> new SSHCommandException("Command " + commandId + " is unknown for service " + service));

            SimpleOperationCommand.SimpleOperationId op = new SimpleOperationCommand.SimpleOperationId(SimpleOperationCommand.SimpleOperation.COMMAND, service, node);
            logOperationMessage(op, "Command ID is " + commandId);
            logOperationMessage(op, "Command is " + command.getCommandCall());

            return command.call (node, sshCommandService);
        });
    }

    private void logOperationMessage(OperationId<?> operationId, String message) {
        operationsMonitoringService.addInfo(operationId, new String[]{"\n" + message});
    }

    @Override
    public void applyServiceOperation(Service service, Node node, SimpleOperationCommand.SimpleOperation simpleOp, ServiceOperation<String> operation) throws SystemException {
        String message = simpleOp.getLabel() + " " + service + " on " + node;
        boolean success = false;

        SimpleOperationCommand.SimpleOperationId operationId = new SimpleOperationCommand.SimpleOperationId(simpleOp, service, node);

        try {

            operationsMonitoringService.startCommand(new SimpleOperationCommand(simpleOp, service, node));

            operationsMonitoringService.startOperation(operationId);

            notificationService.addDoing(simpleOp.getLabel() + " " + service + " on " + node);
            logOperationMessage(operationId, message);

            operationsMonitoringService.addInfo(operationId, "Done "
                    + message
                    + "\n-------------------------------------------------------------------------------\n"
                    + operation.call());
            notificationService.addInfo(simpleOp.getLabel() + " " + service + " succeeded on " + node);

            success = true;
        } catch (SSHCommandException | KubernetesException | ServiceDefinitionException | NodesConfigurationException e) {

            operationsMonitoringService.addInfo(operationId, "\nDone : "
                    + message
                    + "\n-------------------------------------------------------------------------------\n"
                    + "--> Completed in error : "
                    + e.getMessage());

            notificationService.addError(message + " failed !");
            operationsMonitoringService.endOperationError(operationId);
            throw new SystemException(e.getMessage(), e);

        } finally {

            operationsMonitoringService.endOperation(operationId);

            operationsMonitoringService.endCommand(success);
        }
    }

    @Override
    public SystemStatusWrapper getStatus() throws StatusExceptionWrapperException {

        // special case at application startup : if the UI request comes before the first status update
        if (lastStatusException.get() == null && lastStatus.get() == null) {
            return new SystemStatusWrapper("{ \"clear\" : \"initializing\"}");
        }

        if (lastStatusException.get() != null) {
            throw new StatusExceptionWrapperException (lastStatusException.get());
        }
        return lastStatus.get();
    }

    @Override
    public void updateStatus() {

        if (statusUpdateLock.isLocked()) {
            logger.warn ("!!! NOT UPDATING STATUS SINCE STATUS UPDATED IS RUNNING ALREADY !!!");
            return;
        }

        try {
            statusUpdateLock.lock();

            // 0. Build returned status
            SystemStatusWrapper systemStatus = SystemStatusWrapper.empty();

            // 1. Load Node Config
            NodesConfigWrapper rawNodesConfig = configurationService.loadNodesConfig();

            // 1.1. Load Node status
            ServicesInstallStatusWrapper servicesInstallationStatus = configurationService.loadServicesInstallationStatus();

            // 1.2 flag services needing restart
            if (rawNodesConfig != null && !rawNodesConfig.isEmpty()) {

                NodesConfigWrapper nodesConfig = nodeRangeResolver.resolveRanges(rawNodesConfig);

                // 2. Build merged status
                final ConcurrentHashMap<String, String> statusMap = new ConcurrentHashMap<>();

                performPooledOperation(
                        nodesConfig.getNodes(), parallelismStatusThreadCount, statusOperationTimeout / 1000,
                        (operation, error) -> {
                            Node node = operation.getValue();

                            statusMap.put(("node_nbr_" + node.getName()), "" + operation.getKey());
                            statusMap.put(("node_address_" + node.getName()), node.getAddress());

                            fetchNodeStatus(nodesConfig, statusMap, operation, servicesInstallationStatus);
                        });

                // fetch kubernetes services status
                try {
                    kubernetesService.fetchKubernetesServicesStatus(statusMap, servicesInstallationStatus);
                } catch (KubernetesException e) {
                    logger.debug(e, e);
                    // workaround : flag all Kubernetes services as KO on kube node
                    Node kubeNode = servicesInstallationStatus.getFirstNode(servicesDefinition.getKubeMasterServiceDef());
                    if (kubeNode != null) {
                        KubernetesServicesConfigWrapper kubeServicesConfig = configurationService.loadKubernetesServicesConfig();
                        for (Service service : servicesDefinition.listKubernetesServices()) {
                            if (kubernetesService.shouldInstall(kubeServicesConfig, service)) {
                                statusMap.put(SystemStatusWrapper.SERVICE_PREFIX + service + "_" + kubeNode.getName(), "KO");
                            }
                        }
                    }
                }

                // fill in systemStatus
                systemStatus = new SystemStatusWrapper(Collections.unmodifiableMap(statusMap));
            }

            // 4. If a service disappeared, post notification
            try {
                checkServiceDisappearance(systemStatus);
            } catch (JSONException e) {
                logger.warn(e, e);
            }

            // 5. Handle status update if a service seem to have disappeared

            // 5.1 Test if any additional node should be check for being live
            Set<Node> systemStatusNodes = systemStatus.getNodes();
            Set<Node> additionalIpToTests = servicesInstallationStatus.getNodes().stream()
                    .filter(ip -> !systemStatusNodes.contains(ip))
                    .collect(Collectors.toSet());

            Set<Node> configuredNodesAndOtherLiveNodes = new HashSet<>(systemStatusNodes);
            for (Node node : additionalIpToTests) {
                // find out if SSH connection to host can succeed
                if (isNodeUp(node)) {
                    configuredNodesAndOtherLiveNodes.add(node);
                }
            }

            handleStatusChanges(servicesInstallationStatus, systemStatus, configuredNodesAndOtherLiveNodes);

            lastStatus.set (systemStatus);
            lastStatusException.set (null);

        } catch (Exception e) {

            logger.error (e, e);

            lastStatusException.set (e);
            // Keeping previous status - last known status

        } finally {
            statusUpdateLock.unlock();
            // reschedule
            if (statusRefreshScheduler != null) {
                statusRefreshScheduler.schedule(this::updateStatus, statusUpdatePeriodSeconds, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public boolean isNodeUp(Node node) {
        try {
            return sshCommandService.runSSHScript(node, "echo OK", false).startsWith("OK");
        } catch (SSHCommandException e) {
            logger.debug (e.getMessage());
            return false;
        }
    }

    @Override
    public <T extends Serializable> void performPooledOperation(
            List<T> operations, int parallelism, long operationWaitTimoutSec, PooledOperation<T> operation)
            throws SystemException {

        final ExecutorService threadPool = Executors.newFixedThreadPool(parallelism);
        final AtomicReference<Exception> error = new AtomicReference<>();

        for (T opToPerform : operations) {

            if (!operationsMonitoringService.isInterrupted()) {
                threadPool.execute(() -> {

                    if (!operationsMonitoringService.isInterrupted() && (error.get() == null)) {

                        try {
                            operation.call(opToPerform, error);
                        } catch (Exception e) {
                            logger.error(e, e);
                            logger.warn("Storing error - " + e.getClass() + ":" + e.getMessage());
                            error.set(e);
                        }
                    }
                });
            }
        }

        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(operationWaitTimoutSec, TimeUnit.SECONDS)) {
                logger.warn ("Could not complete operation within " + operationWaitTimoutSec + " seconds");
            }
        } catch (InterruptedException e) {
            logger.debug (e, e);
        }

        if (error.get() != null) {
            logger.warn ("Throwing " + error.get().getClass() + ":" + error.get().getMessage() + " as SystemException") ;
            throw new SystemException(error.get().getMessage(), error.get());
        }
    }

    protected void fetchNodeStatus
            (NodesConfigWrapper nodesConfig, Map<String, String> statusMap, Pair<Integer, Node> nbrAndPair,
             ServicesInstallStatusWrapper servicesInstallationStatus)
                throws SystemException {

        int nodeNbr = nbrAndPair.getKey();
        Node node = nbrAndPair.getValue();

        // 3.1 Node answers
        try {
            if (!isNodeUp(node)) {

                statusMap.put(("node_alive_" + node.getName()), "KO");

            } else {

                statusMap.put(("node_alive_" + node.getName()), "OK");

                SystemStatusParser parser = new SystemStatusParser(node, sshCommandService, servicesDefinition);

                for (Service service : servicesDefinition.listAllNodesServices()) {

                    // should service be installed on node ?
                    boolean shall = nodesConfig.shouldInstall (service, nodeNbr);

                    // check if service is installed ?
                    // check if service installed using SSH
                    String serviceStatus = parser.getServiceStatus(service);
                    boolean installed = !serviceStatus.equals("NA");
                    boolean running = serviceStatus.equalsIgnoreCase("running");

                    feedInServiceStatus (
                            statusMap, servicesInstallationStatus, node, node,
                            service, shall, installed, running);
                }
            }
        } catch (SSHCommandException | JSONException | ConnectionManagerException e) {
            logger.error(e, e);
            throw new SystemException(e.getMessage(), e);
        }
    }

    @Override
    public void feedInServiceStatus (
            Map<String, String> statusMap,
            ServicesInstallStatusWrapper servicesInstallationStatus,
            Node node,
            Node referenceNode,
            Service service,
            boolean shall,
            boolean installed,
            boolean running) throws ConnectionManagerException {

        if (node == null) {
            throw new IllegalArgumentException("nodeName can't be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service can't be null");
        }

        if (shall) {
            if (!installed) {

                statusMap.put(SystemStatusWrapper.buildStatusFlag(service, node), "NA");

            } else {

                // check if services is running ?
                // check if service running using SSH

                if (!running) {
                    statusMap.put(SystemStatusWrapper.buildStatusFlag (service, node), "KO");

                } else {

                    if (servicesInstallationStatus.isServiceOK (service, referenceNode)) {
                        statusMap.put(SystemStatusWrapper.buildStatusFlag (service, node), "OK");
                    } else {
                        statusMap.put(SystemStatusWrapper.buildStatusFlag (service, node), "restart");
                    }

                    // configure proxy if required
                    proxyManagerService.updateServerForService(service, node);
                }
            }
        } else {
            if (installed) {
                statusMap.put(SystemStatusWrapper.buildStatusFlag (service, node), "TD"); // To Be Deleted
            }
        }
    }

    @Override
    public void runPreUninstallHooks(MessageLogger ml, OperationId<?> operation) throws SystemException {
        ServiceDefinition serviceDef = servicesDefinition.getServiceDefinition(operation.getService());
        if (serviceDef.hasPreUninstallHooks()) {
            try {
                NodesConfigWrapper nodesConfig = nodeRangeResolver.resolveRanges(configurationService.loadNodesConfig());

                ml.addInfo(" - Calling Pre-uninstall Hooks");

                Topology topology = servicesDefinition.getTopology(nodesConfig, configurationService.loadKubernetesServicesConfig(), null);

                for (Dependency dep : serviceDef.getDependencies()) {
                    if (StringUtils.isNotBlank(dep.getPreUninstallHook())) {

                        ml.addInfo(" - Calling Pre-uninstall Hook for dep " + dep.getMasterService().getName());

                        String preUninstallHookCommand = dep.getPreUninstallHook();
                        if (operation instanceof NodeServiceOperationsCommand.ServiceOperationId) {
                            preUninstallHookCommand = preUninstallHookCommand.replace(
                                    "{service.node.address}",
                                    ((NodeServiceOperationsCommand.ServiceOperationId) operation).getNode().getAddress());
                        }

                        Node[] masters = topology.getMasters(servicesDefinition.getServiceDefinition(dep.getMasterService()));

                        for (Node master : masters) {
                            if (isNodeUp(master)) { // best effort basis
                                try {
                                    sshCommandService.runSSHCommand(master, preUninstallHookCommand);
                                } catch (SSHCommandException e) {
                                    logger.debug (e, e);
                                    ml.addInfo(e.getMessage());
                                }
                            }
                        }
                    }
                }
            } catch (NodesConfigurationException | ServiceDefinitionException | SetupException e) {
                ml.addInfo(e.getMessage());
                throw new SystemException(e);
            }
        }
    }

    @Override
    public void handleStatusChanges(
            ServicesInstallStatusWrapper servicesInstallationStatus, SystemStatusWrapper systemStatus,
            Set<Node> configuredNodesAndOtherLiveNodes)
                throws FileException, SetupException {

        // If there is some processing pending, then nothing is reliable, just move on
        if (!operationsMonitoringService.isProcessingPending()) {

            try {

                boolean changes = false;

                for (Pair<Service, Node> installationPairs : servicesInstallationStatus.getAllServiceNodeInstallationPairs()) {

                    Service savedService = installationPairs.getKey();
                    Node node = installationPairs.getValue();
                    Node originalNode = node;

                    // if service is a kubernetes service
                    if (node.equals(Node.KUBERNETES_NODE)) {

                        // if kubernetes is not available, don't do anything
                        Node kubeNode = systemStatus.getFirstNode(servicesDefinition.getKubeMasterServiceDef());
                        if (kubeNode == null) { // if Kubernetes is not found, don't touch anything. Let's wait for it to come back.
                            //notificationService.addError("Kubernetes inconsistency.");
                            //logger.warn("Kubernetes could not be found - not potentially flagging kubernetes services as disappeared as long as kubernetes is not back.");
                            continue;
                        }

                        if (!systemStatus.isServiceOKOnNode(servicesDefinition.getKubeMasterServiceDef(), kubeNode)) {
                            //logger.warn("Kubernetes is not OK - not potentially flagging kubernetes services as disappeared as long as kubernetes is not back.");

                            // reset missing counter on kubernetes services when kube is down
                            configuredNodesAndOtherLiveNodes.forEach(
                                    effNode -> new ArrayList<>(serviceMissingCounter.keySet()).stream()
                                                .filter(flag -> flag.equals(savedService + "-" + effNode))
                                                .forEach(serviceMissingCounter::remove)
                            );
                            continue;
                        }

                        // get first node actually running service
                        node = systemStatus.getFirstNode(savedService);
                        if (node == null) {
                            // if none, consider kubernetes node as DEFAULT node running service
                            node = kubeNode;
                        }
                    }

                    Boolean nodeAlive = systemStatus.isNodeAlive (node);

                    // A. In case target node both configured and up, check services actual statuses before doing anything
                    if (    // nodes is configured and responding (up and running
                            nodeAlive != null && nodeAlive
                           && handleRemoveServiceIfDown(servicesInstallationStatus, systemStatus, savedService, node, originalNode)) {
                        changes = true;
                    }

                    // B. node is not configured anymore (has been removed, but it is still up and responding and it runs services)
                    //    in this case we want to attempt uninstallation, thus not removing services if they are up
                    // => so nothing to do, don't touch anything in installed services registry

                    // c. if node is both down and not configured anymore, we just remove all services whatever their statuses
                    if (!configuredNodesAndOtherLiveNodes.contains(node)
                            && countErrorAndRemoveServices(servicesInstallationStatus, savedService, node, originalNode)) {
                        changes = true;
                    }

                    // D. In other cases, node is configured but down. We don't make any assumption on node down.
                    //    Admin is left with uninstalling it if he wants.
                    // => so nothing to do, don't touch anything in installed services registry
                }

                if (changes) {
                    configurationService.saveServicesInstallationStatus(servicesInstallationStatus);
                }
            } catch (JSONException e) {
                logger.error(e, e);
                // this is no mission critical method, let's silent errors there
            }
        }
    }

    boolean handleRemoveServiceIfDown(
            ServicesInstallStatusWrapper servicesInstallStatus, SystemStatusWrapper systemStatusWrapper,
            Service savedService, Node node, Node originalNode) {

        boolean changes = false;

        if (systemStatusWrapper.isServiceAvailableOnNode(savedService, node)) {
            serviceMissingCounter.remove(savedService + "-" + node);

        } else {
            if (countErrorAndRemoveServices(servicesInstallStatus, savedService, node, originalNode)) {
                changes = true;
            }
        }
        return changes;
    }

    boolean countErrorAndRemoveServices(
            ServicesInstallStatusWrapper servicesInstallationStatus,
            Service savedService, Node node, Node originalNode) {
        boolean changes = false;
        // otherwise count error
        Integer counter = serviceMissingCounter.get(savedService + "-" + node);
        if (counter == null) {
            counter = 0;
            serviceMissingCounter.put(savedService + "-" + node, counter);

        } else {

            counter = counter + 1;

            // if error count > 2 (i.e. 3), consider service uninstalled, remove it from saved status
            if (counter > failedServicesTriggerCount) {

                servicesInstallationStatus.removeInstallationFlag(savedService, originalNode);
                serviceMissingCounter.remove(savedService + "-" + node);
                notificationService.addError(SERVICE_PREFIX + savedService + " on " + node + " vanished!");
                logger.warn (SERVICE_PREFIX + savedService + " on " + node + " has been removed from ServiceInstallationStatus!");

                // unconfigure proxy if required
                proxyManagerService.removeServerForService(savedService, node);
                changes = true;

            } else {
                serviceMissingCounter.put(savedService + "-" + node, counter);
            }
        }
        return changes;
    }

    protected void checkServiceDisappearance(SystemStatusWrapper systemStatus) {
        if (lastStatus.get() != null) {
            for (String serviceStatusFlag : systemStatus.getRootKeys()) {

                // if service is currently not OK but was previously OK
                if (!systemStatus.isServiceStatusFlagOK(serviceStatusFlag) && lastStatus.get().isServiceStatusFlagOK(serviceStatusFlag)) {

                    logger.warn("For service " + serviceStatusFlag + " - previous status was OK and status is " + systemStatus.getValueForPath(serviceStatusFlag));
                    notificationService.addError(SERVICE_PREFIX + SystemStatusWrapper.getService(serviceStatusFlag)
                            + " on " +  Objects.requireNonNull(SystemStatusWrapper.getNode(serviceStatusFlag))
                            + " got into problem");
                }
            }
        }
    }

    @Override
    public void callUninstallScript(MessageLogger ml, SSHConnection connection, Service service) throws SystemException {
        File containerFolder = new File(servicesSetupPath + "/" + service);
        if (!containerFolder.exists()) {
            throw new SystemException("Folder " + servicesSetupPath + "/" + service + " doesn't exist !");
        }

        try {
            File uninstallScriptFile = new File(containerFolder, "uninstall.sh");
            if (uninstallScriptFile.exists()) {
                ml.addInfo(" - Calling uninstall script");
                ml.addInfo(sshCommandService.runSSHScriptPath(connection, uninstallScriptFile.getAbsolutePath()));
            }
        } catch (SSHCommandException e) {
            logger.warn (e, e);
            ml.addInfo (e.getMessage());
        }
    }

    @Override
    public void installationSetup(MessageLogger ml, SSHConnection connection, Node node, Service service) throws SystemException {
        try {
            exec(connection, ml, new String[]{"bash", TMP_PATH_PREFIX + service + "/setup.sh", node.getAddress()});
        } catch (SSHCommandException e) {
            logger.debug (e, e);
            ml.addInfo(e.getMessage());
            throw new SystemException ("Setup.sh script execution for " + service + " on node " + node + " failed.", e);
        }
    }

    @Override
    public void installationCleanup(MessageLogger ml, SSHConnection connection, Service service, String imageName, File tmpArchiveFile) throws SSHCommandException, SystemException {
        exec(connection, ml, "rm -Rf " + TMP_PATH_PREFIX + service);
        exec(connection, ml, "rm -f " + TMP_PATH_PREFIX + service + ".tgz");

        if (StringUtils.isNotBlank(imageName)) {
            try {
                ml.addInfo(" - Deleting docker template image");
                exec(connection, new IgnoreMessageLogger(), "docker image rm eskimo/" + imageName + "_template:latest || true");
            } catch (SSHCommandException e) {
                logger.error(e, e);
                ml.addInfo(e.getMessage());
                // ignroed any further
            }
        }

        try {
            FileUtils.delete (new File (TMP_PATH_PREFIX + tmpArchiveFile.getName() + ".tgz"));
        } catch (FileUtils.FileDeleteFailedException e) {
            logger.error (e, e);
            throw new SystemException(e);
        }
    }

    void exec(SSHConnection connection, MessageLogger ml, String[] setupScript) throws SSHCommandException {
        ml.addInfo(sshCommandService.runSSHCommand(connection, setupScript));
    }

    void exec(SSHConnection connection, MessageLogger ml, String command) throws SSHCommandException {
        ml.addInfo(sshCommandService.runSSHCommand(connection, command));
    }

    @Override
    public NodesStatus discoverAliveAndDeadNodes(Set<Node> allNodes, NodesConfigWrapper nodesConfig) throws SystemException {
        NodesStatus nodeStatus = new NodesStatus();

        // Find out about dead IPs
        Set<Node> nodesToTest = new HashSet<>(allNodes);
        nodesToTest.addAll(nodesConfig.getAllNodes());

        performPooledOperation(new ArrayList<>(nodesToTest), parallelismStatusThreadCount, statusOperationTimeout / 100,
                (node, error) -> {
                    // handle potential interruption request
                    if (!operationsMonitoringService.isInterrupted()) {
                        if (!isNodeUp(node)) {
                            handleNodeDead(nodeStatus, node);
                        } else {
                            // Ensure sudo is possible
                            ensureSudoPossible(nodeStatus, node);
                        }
                    }
                });

        return nodeStatus;
    }

    private void ensureSudoPossible(NodesStatus nodeStatus, Node node) {

        try {
            String result = sshCommandService.runSSHScript(node, "sudo ls", false);
            if (result.contains("a terminal is required to read the password")
                    || result.contains("a password is required")) {
                operationsMonitoringService.addGlobalWarning("Node " + node + " doesn't enable the configured user to use sudo without password");
                notificationService.addError("Node " + node + " sudo problem");
                nodeStatus.addDeadNode(node);
            } else {
                nodeStatus.addLiveNode(node);
            }
        } catch (SSHCommandException e) {
            logger.debug (e.getMessage());
            nodeStatus.addDeadNode(node);
        }
    }

    void handleNodeDead(NodesStatus nodeStatus, Node node) {
        operationsMonitoringService.addGlobalWarning("Node seems dead " + node);
        notificationService.addError("Node " + node + " is dead.");
        nodeStatus.addDeadNode(node);
    }

    @Override
    public File createRemotePackageFolder(MessageLogger ml, SSHConnection connection, Service service, String imageName) throws SystemException, IOException, SSHCommandException {
        // 1. Find container folder, archive and copy there

        // 1.1 Make sure folder exist
        File containerFolder = new File(servicesSetupPath + "/" + service);
        if (!containerFolder.exists()) {
            throw new SystemException("Folder " + servicesSetupPath + "/" + service + " doesn't exist !");
        }

        // 1.2 Create archive

        File tmpArchiveFile = createTempFile(service, ".tgz");
        try {
            FileUtils.delete(tmpArchiveFile);
        } catch (FileUtils.FileDeleteFailedException e) {
            logger.error (e, e);
            throw new SystemException(e);
        }

        FileUtils.createTarFile(servicesSetupPath + "/" + service, tmpArchiveFile);
        if (!tmpArchiveFile.exists()) {
            throw new SystemException("Could not create archive for service " + service + " : " + tmpArchiveFile.getAbsolutePath());
        }

        // 2. copy it over to target node and extract it

        // 2.1
        sshCommandService.copySCPFile(connection, tmpArchiveFile.getAbsolutePath());

        exec(connection, ml, "rm -Rf " + SystemServiceImpl. TMP_PATH_PREFIX + service);
        exec(connection, ml, "rm -f " + SystemServiceImpl.TMP_PATH_PREFIX + service + ".tgz");
        exec(connection, ml, "mv " +  tmpArchiveFile.getName() + " " + SystemServiceImpl.TMP_PATH_PREFIX + service + ".tgz");
        exec(connection, ml, "tar xfz " + SystemServiceImpl.TMP_PATH_PREFIX + service + ".tgz --directory=" + SystemServiceImpl.TMP_PATH_PREFIX);
        exec(connection, ml, "chmod 755 " + SystemServiceImpl.TMP_PATH_PREFIX + service + "/setup.sh");

        // 2.2 delete local archive
        try {
            FileUtils.delete(tmpArchiveFile);
        } catch (FileUtils.FileDeleteFailedException e) {
            logger.error(e, e);
            throw new SystemException("Could not delete archive /tmp/" + service + ".tgz");
        }

        // 3. Copy container image there if any
        if (StringUtils.isNotBlank(imageName)) {
            String imageFileName = setupService.findLastPackageFile(SetupService.DOCKER_TEMPLATE_PREFIX, imageName);

            File containerFile = new File(packageDistributionPath + "/" + imageFileName);
            if (containerFile.exists()) {

                ml.addInfo(" - Copying over docker image " + imageFileName);
                sshCommandService.copySCPFile(connection, packageDistributionPath + "/" + imageFileName);

                exec(connection, ml, new String[]{"mv", imageFileName, SystemServiceImpl.TMP_PATH_PREFIX + service + "/"});

                exec(connection, ml, new String[]{"mv",
                        SystemServiceImpl.TMP_PATH_PREFIX + service + "/" + imageFileName,
                        SystemServiceImpl.TMP_PATH_PREFIX + service + "/" + SetupService.DOCKER_TEMPLATE_PREFIX + imageName + ".tar.gz"});

            } else {
                ml.addInfo(" - (no container found for " + service + "OperationsMonitoringServiceTest - will just invoke setup)");
            }
        }
        return tmpArchiveFile;
    }

    @Override
    public File createTempFile(Service service, String extension) throws IOException {
        return File.createTempFile(service.getName(), extension);
    }
}

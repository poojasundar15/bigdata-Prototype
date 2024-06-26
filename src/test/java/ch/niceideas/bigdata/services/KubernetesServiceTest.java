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

import ch.niceideas.common.utils.ResourceUtils;
import ch.niceideas.common.utils.StreamUtils;
import ch.niceideas.bigdata.BigDataApplication;
import ch.niceideas.bigdata.model.KubernetesOperationsCommand;
import ch.niceideas.bigdata.model.KubernetesServicesConfigWrapper;
import ch.niceideas.bigdata.model.ServicesInstallStatusWrapper;
import ch.niceideas.bigdata.model.SystemStatusWrapper;
import ch.niceideas.bigdata.model.service.proxy.ProxyTunnelConfig;
import ch.niceideas.bigdata.test.StandardSetupHelpers;
import ch.niceideas.bigdata.test.infrastructure.SecurityContextHelper;
import ch.niceideas.bigdata.test.services.*;
import ch.niceideas.bigdata.types.Node;
import ch.niceideas.bigdata.types.Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@ContextConfiguration(classes = BigDataApplication.class)
@SpringBootTest(classes = BigDataApplication.class)
@TestPropertySource("classpath:application-test.properties")
@ActiveProfiles({
        "no-web-stack",
        "test-setup",
        "test-conf",
        "test-operations",
        "test-system",
        "test-operation",
        "test-proxy",
        "test-nodes-conf",
        "test-ssh",
        "test-connection-manager",
        "test-services"})
public class KubernetesServiceTest {

    @Autowired
    private SystemOperationServiceTestImpl systemOperationServiceTest;

    @Autowired
    private ConfigurationServiceTestImpl configurationServiceTest;

    @Autowired
    private ProxyManagerServiceTestImpl proxyManagerServiceTest;

    @Autowired
    private SystemServiceTestImpl systemServiceTest;

    @Autowired
    private NodesConfigurationServiceTestImpl nodesConfigurationServiceTest;

    @Autowired
    private SSHCommandServiceTestImpl sshCommandServiceTest;

    @Autowired
    private ConnectionManagerServiceTestImpl connectionManagerServiceTest;

    @Autowired
    private ServicesDefinition servicesDefinition;

    @Autowired
    private KubernetesService kubernetesService;

    @Autowired
    private OperationsMonitoringServiceTestImpl operationsMonitoringServiceTest;

    @BeforeEach
    public void setUp() throws Exception {
        systemServiceTest.reset();
        systemOperationServiceTest.reset();
        configurationServiceTest.reset();
        configurationServiceTest.saveSetupConfig("{ \"" + SetupService.SSH_USERNAME_FIELD + "\" : \"test\" }");

        proxyManagerServiceTest.setForwarderConfigForHosts(Node.fromName("localhost"), new ArrayList<>(){{
            add (new ProxyTunnelConfig(Service.from("dummyService"), 12345, Node.fromAddress("192.178.10.11"), 5050));
        }});

        nodesConfigurationServiceTest.reset();

        SecurityContextHelper.loginAdmin();

        systemOperationServiceTest.setMockCalls(false);

        connectionManagerServiceTest.dontConnect();

        operationsMonitoringServiceTest.endCommand(true);

        sshCommandServiceTest.reset();
    }

    private List<Service> getInstallations() {

        return systemServiceTest.getAppliedOperations().stream()
                .filter(pair -> {
                    if (pair.getKey() instanceof KubernetesOperationsCommand.KubernetesOperationId) {
                        return ((KubernetesOperationsCommand.KubernetesOperationId) pair.getKey()).getOperation().equals(KubernetesOperationsCommand.KuberneteOperation.INSTALLATION);
                    }
                    return false;
                })
                .map(pair -> ((KubernetesOperationsCommand.KubernetesOperationId)pair.getKey()).getService())
                .collect(Collectors.toList());
    }

    private List<Service> getUninstallations() {
        return systemServiceTest.getAppliedOperations().stream()
                .filter(pair -> {
                    if (pair.getKey() instanceof KubernetesOperationsCommand.KubernetesOperationId) {
                        return ((KubernetesOperationsCommand.KubernetesOperationId) pair.getKey()).getOperation().equals(KubernetesOperationsCommand.KuberneteOperation.UNINSTALLATION);
                    }
                    return false;
                })
                .map(pair -> ((KubernetesOperationsCommand.KubernetesOperationId)pair.getKey()).getService())
                .collect(Collectors.toList());
    }

    @Test
    public void testApplyKubernetesServicesConfig_NoKube() throws Exception {

        ServicesInstallStatusWrapper serviceInstallStatus = StandardSetupHelpers.getStandard2NodesInstallStatus();
        serviceInstallStatus.removeInstallationFlag(Service.from("database-manager"), Node.KUBERNETES_NODE);
        serviceInstallStatus.removeInstallationFlag(Service.from("broker-manager"), Node.KUBERNETES_NODE);

        configurationServiceTest.setStandard2NodesSetup();

        SystemStatusWrapper systemStatus = StandardSetupHelpers.getStandard2NodesSystemStatus();
        systemStatus.removeRootKey("service_cluster-manager_192-168-10-13");
        systemServiceTest.setSystemStatus(systemStatus);

        KubernetesOperationsCommand command = KubernetesOperationsCommand.create (
                servicesDefinition,
                systemServiceTest,
                StandardSetupHelpers.getStandardKubernetesConfig(),
                serviceInstallStatus,
                new KubernetesServicesConfigWrapper(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("KubernetesServiceTest/kubernetes-services-config.json"), StandardCharsets.UTF_8))
        );

        KubernetesException exception = assertThrows(KubernetesException.class, () -> kubernetesService.applyServicesConfig(command));
        assertNotNull(exception);
        assertEquals("Kubernetes doesn't seem to be installed. Kubernetes services configuration is saved but will need to be re-applied when k8s-master is available.", exception.getMessage());

        ServicesInstallStatusWrapper newSserviceInstallStatus = configurationServiceTest.loadServicesInstallationStatus();
        assertFalse(serviceInstallStatus.getJSONObject().similar(newSserviceInstallStatus.getJSONObject()));

        assertEquals(0, getInstallations().size());
        assertEquals(0, getUninstallations().size());
    }

    @Test
    public void testApplyKubernetesServicesConfig_KubeNodeDown() throws Exception {

        ServicesInstallStatusWrapper serviceInstallStatus = StandardSetupHelpers.getStandard2NodesInstallStatus();
        serviceInstallStatus.removeInstallationFlag(Service.from("database-manager"), Node.KUBERNETES_NODE);
        serviceInstallStatus.removeInstallationFlag(Service.from("broker-manager"), Node.KUBERNETES_NODE);

        configurationServiceTest.saveServicesInstallationStatus(ServicesInstallStatusWrapper.empty());
        configurationServiceTest.setStandard2NodesSetup();
        systemServiceTest.setStandard2NodesStatus();

        KubernetesServicesConfigWrapper previousKubeConfig = StandardSetupHelpers.getStandardKubernetesConfig();

        KubernetesOperationsCommand command = KubernetesOperationsCommand.create (
                servicesDefinition,
                systemServiceTest,
                previousKubeConfig,
                serviceInstallStatus,
                new KubernetesServicesConfigWrapper(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("KubernetesServiceTest/kubernetes-services-config.json"), StandardCharsets.UTF_8))
        );

        configurationServiceTest.saveServicesInstallationStatus(serviceInstallStatus);

        configurationServiceTest.saveNodesConfig(StandardSetupHelpers.getStandard2NodesSetup());

        systemServiceTest.setStandard2NodesStatus();

        systemServiceTest.addDeadNode(Node.fromAddress("192.168.10.11"));

        sshCommandServiceTest.setNodeResultBuilder((node, script) -> {
            switch (node.getAddress()) {
                case "192.168.10.11":
                    return "MemTotal:        5969796 kB";
                case "192.168.10.12":
                    return "MemTotal:        5799444 kB";
                default:
                    return "MemTotal:        3999444 kB";
            }
        });

        systemOperationServiceTest.setMockCalls(true);

        KubernetesException kubeEx = assertThrows (KubernetesException.class, () -> kubernetesService.applyServicesConfig(command));
        assertEquals ("The Kube Master node is dead. cannot proceed any further with installation. " +
                "Kubernetes services configuration is saved but will need to be re-applied when k8s-master is available.", kubeEx.getMessage());

        List<Service> installList = getInstallations();
        assertEquals(0, installList.size());

        List<Service> uninstallList = getUninstallations();
        assertEquals(0, uninstallList.size());

        KubernetesServicesConfigWrapper newKubeConfig = configurationServiceTest.loadKubernetesServicesConfig();
        assertNotNull (newKubeConfig);

        //changes have been persisted
        assertFalse (previousKubeConfig.getJSONObject().similar(newKubeConfig.getJSONObject()));
    }

    @Test
    public void testApplyKubernetesServicesConfig () throws Exception {

        ServicesInstallStatusWrapper serviceInstallStatus = StandardSetupHelpers.getStandard2NodesInstallStatus();
        serviceInstallStatus.removeInstallationFlag(Service.from("database-manager"), Node.KUBERNETES_NODE);
        serviceInstallStatus.removeInstallationFlag(Service.from("broker-manager"), Node.KUBERNETES_NODE);

        configurationServiceTest.saveServicesInstallationStatus(ServicesInstallStatusWrapper.empty());
        configurationServiceTest.setStandard2NodesSetup();
        systemServiceTest.setStandard2NodesStatus();

        KubernetesOperationsCommand command = KubernetesOperationsCommand.create (
                servicesDefinition,
                systemServiceTest,
                StandardSetupHelpers.getStandardKubernetesConfig(),
                serviceInstallStatus,
                new KubernetesServicesConfigWrapper(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("KubernetesServiceTest/kubernetes-services-config.json"), StandardCharsets.UTF_8))
        );

        configurationServiceTest.saveServicesInstallationStatus(serviceInstallStatus);

        configurationServiceTest.saveNodesConfig(StandardSetupHelpers.getStandard2NodesSetup());

        systemServiceTest.setStandard2NodesStatus();

        sshCommandServiceTest.setNodeResultBuilder((node, script) -> {
            switch (node.getAddress()) {
                case "192.168.10.11":
                    return "MemTotal:        5969796 kB";
                case "192.168.10.12":
                    return "MemTotal:        5799444 kB";
                default:
                    return "MemTotal:        3999444 kB";
            }
        });

        systemOperationServiceTest.setMockCalls(true);

        kubernetesService.applyServicesConfig(command);

        List<Service> installList = getInstallations();

        assertEquals(2, installList.size());
        assertEquals("" +
                "database-manager,broker-manager", installList.stream().map(Service::getName).collect(Collectors.joining(",")));

        List<Service> uninstallList = getUninstallations();

        assertEquals(4, uninstallList.size());
        assertEquals("cluster-dashboard,database,calculator-runtime,broker", uninstallList.stream().map(Service::getName).collect(Collectors.joining(",")));
    }

    @Test
    public void testUninstallKubernetesService () throws Exception {

        configurationServiceTest.setStandard2NodesInstallStatus();
        systemServiceTest.setStandard2NodesStatus();

        ServicesInstallStatusWrapper serviceInstallStatus = StandardSetupHelpers.getStandard2NodesInstallStatus();

        KubernetesServicesConfigWrapper kubeServicesConfig = new KubernetesServicesConfigWrapper(
                StreamUtils.getAsString(ResourceUtils.getResourceAsStream("KubernetesServiceTest/kubernetes-services-config.json"), StandardCharsets.UTF_8));
        kubeServicesConfig.setValueForPath("database-manager_install", "off");

        KubernetesOperationsCommand.create (
                servicesDefinition,
                systemServiceTest,
                StandardSetupHelpers.getStandardKubernetesConfig(),
                serviceInstallStatus,
                kubeServicesConfig
        );

        kubernetesService.uninstallService(new KubernetesOperationsCommand.KubernetesOperationId(
                KubernetesOperationsCommand.KuberneteOperation.UNINSTALLATION,
                Service.from("database-manager")),
                Node.fromAddress("192.168.10.11"));

        //System.err.println (testSSHCommandScript.toString());

        assertEquals("/usr/local/bin/kubectl get pod --all-namespaces -o wide 2>/dev/null \n" +
                "/usr/local/bin/kubectl get service --all-namespaces -o wide 2>/dev/null \n" +
                "/bin/ls -1 /var/lib/kubernetes/docker_registry/docker/registry/v2/repositories/\n" +
                "eskimo-kubectl uninstall database-manager 192.168.10.11\n", sshCommandServiceTest.getExecutedCommands());
    }

    @Test
    public void testInstallKubernetesService () throws Exception {

        systemServiceTest.setStandard2NodesStatus();

        ServicesInstallStatusWrapper serviceInstallStatus = StandardSetupHelpers.getStandard2NodesInstallStatus();
        serviceInstallStatus.removeInstallationFlag(Service.from("database-manager"), Node.KUBERNETES_NODE);

        KubernetesOperationsCommand command = KubernetesOperationsCommand.create (
                servicesDefinition,
                systemServiceTest,
                StandardSetupHelpers.getStandardKubernetesConfig(),
                serviceInstallStatus,
                new KubernetesServicesConfigWrapper(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("KubernetesServiceTest/kubernetes-services-config.json"), StandardCharsets.UTF_8))
        );

        operationsMonitoringServiceTest.startCommand(command);

        kubernetesService.installService(new KubernetesOperationsCommand.KubernetesOperationId(
                KubernetesOperationsCommand.KuberneteOperation.INSTALLATION,
                Service.from("database-manager")),
                Node.fromAddress("192.168.10.11"));

        String executedCommands = String.join("\n", systemServiceTest.getExecutedActions());
        //System.err.println (executedCommands);

        // Just testing a few commands
        assertTrue(executedCommands.contains("Installation setup  - database-manager - 192.168.10.11 - 192.168.10.11"));
        assertTrue(executedCommands.contains("Installation cleanup  - database-manager - database-manager - 192.168.10.11"));
    }

    @Test
    public void testFetchKubernetesServicesStatusNominal () throws Exception {

        sshCommandServiceTest.setNodeResultBuilder((node, script) -> {
            if (script.startsWith("/usr/local/bin/kubectl get pod")) {
                return "" +
                        "NAMESPACE              NAME                                         READY   STATUS    RESTARTS      AGE   IP              NODE            NOMINATED NODE   READINESS GATES\n" +
                        "eskimo                 database-manager-65d5556459-fjwh9                     1/1     Running     1 (47m ago)   54m   192.168.10.11   192.168.10.11   <none>           <none>\n" +
                        "eskimo                 user-console-65d55564519-fjwh8                     1/1     Error       1 (47m ago)   54m   192.168.10.11   192.168.10.11   <none>           <none>\n";
            } else if (script.startsWith("/usr/local/bin/kubectl get service")) {
                return "" +
                        "NAMESPACE              NAME                        TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)                  AGE   SELECTOR\n" +
                        "eskimo                 database-manager                     ClusterIP   10.254.38.33     <none>        31900/TCP                13h   k8s-app=database-manager\n" +
                        "eskimo                 user-console                      ClusterIP   10.254.41.25     <none>        32000/TCP                13h   k8s-app=kibana\n";
            } else if (script.startsWith("/bin/ls -1")) {
                return "" +
                        "database-manager\n" +
                        "user-console\n";
            } else {
                throw new UnsupportedOperationException();
            }
        });


        final ConcurrentHashMap<String, String> statusMap = new ConcurrentHashMap<>();

        configurationServiceTest.setStandard2NodesInstallStatus();

        configurationServiceTest.setStandardKubernetesConfig();

        kubernetesService.fetchKubernetesServicesStatus(statusMap, configurationServiceTest.loadServicesInstallationStatus());

        assertEquals(7, statusMap.size());
        assertEquals("OK", statusMap.get("service_database-manager_192-168-10-11"));
        assertEquals("KO", statusMap.get("service_user-console_192-168-10-11"));
        assertEquals("NA", statusMap.get("service_broker-manager_192-168-10-11"));
    }

    @Test
    public void testFetchKubernetesServicesStatusKubernetesNodeDown () throws Exception {

        systemServiceTest.setPingError();

        final ConcurrentHashMap<String, String> statusMap = new ConcurrentHashMap<>();

        configurationServiceTest.setStandard2NodesInstallStatus();

        configurationServiceTest.setStandardKubernetesConfig();

        kubernetesService.fetchKubernetesServicesStatus(statusMap, configurationServiceTest.loadServicesInstallationStatus());

        // grafana is away !
        //assertEquals(7, statusMap.size());
        assertEquals(7, statusMap.size());

        assertEquals("KO", statusMap.get("service_database-manager_192-168-10-11"));
        // This one is not referenced anymore in this case
        assertEquals("KO", statusMap.get("service_user-console_192-168-10-11"));
        assertEquals("KO", statusMap.get("service_broker-manager_192-168-10-11"));
    }

    @Test
    public void testShouldInstall () {

        KubernetesServicesConfigWrapper kubeServicesConfig = StandardSetupHelpers.getStandardKubernetesConfig();

        assertTrue (kubernetesService.shouldInstall(kubeServicesConfig, Service.from("database-manager")));
        assertTrue (kubernetesService.shouldInstall(kubeServicesConfig, Service.from("user-console")));
        assertTrue (kubernetesService.shouldInstall(kubeServicesConfig, Service.from("broker-manager")));

        assertFalse (kubernetesService.shouldInstall(kubeServicesConfig, Service.from("tada")));
    }

    @Test
    public void testShowJournalKubernetes () throws Exception {
        configurationServiceTest.setStandard2NodesInstallStatus();
        kubernetesService.showJournal(servicesDefinition.getServiceDefinition(Service.from("database-manager")), Node.fromAddress("192.168.10.11"));
        assertEquals ("Apply service operation  - database-manager - KUBERNETES_NODE - Showing journal", String.join(",", systemServiceTest.getExecutedActions()));
        assertEquals ("eskimo-kubectl logs database-manager 192.168.10.11\n", sshCommandServiceTest.getExecutedCommands());
    }

    @Test
    public void testStartServiceKubernetes () throws Exception {
        configurationServiceTest.setStandard2NodesInstallStatus();
        kubernetesService.startService(servicesDefinition.getServiceDefinition(Service.from("database-manager")), Node.fromAddress("192.168.10.11"));
        assertEquals ("Apply service operation  - database-manager - KUBERNETES_NODE - Starting", String.join(",", systemServiceTest.getExecutedActions()));
        assertEquals ("eskimo-kubectl start database-manager 192.168.10.11\n", sshCommandServiceTest.getExecutedCommands());
    }

    @Test
    public void testStopServiceKubernetes () throws Exception {
        configurationServiceTest.setStandard2NodesInstallStatus();
        kubernetesService.stopService(servicesDefinition.getServiceDefinition(Service.from("database-manager")), Node.fromAddress("192.168.10.11"));
        assertEquals ("Apply service operation  - database-manager - KUBERNETES_NODE - Stopping", String.join(",", systemServiceTest.getExecutedActions()));
        assertEquals ("eskimo-kubectl stop database-manager 192.168.10.11\n", sshCommandServiceTest.getExecutedCommands());
    }

    @Test
    public void testRestartServiceKubernetes() throws Exception {
        configurationServiceTest.setStandard2NodesInstallStatus();
        kubernetesService.restartService(servicesDefinition.getServiceDefinition(Service.from("database-manager")), Node.fromAddress("192.168.10.11"));
        assertEquals ("Apply service operation  - database-manager - KUBERNETES_NODE - Restarting", String.join(",", systemServiceTest.getExecutedActions()));
        assertEquals ("eskimo-kubectl restart database-manager 192.168.10.11\n", sshCommandServiceTest.getExecutedCommands());
    }

}

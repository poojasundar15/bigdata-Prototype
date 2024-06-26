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


package ch.niceideas.bigdata.controlers;

import ch.niceideas.bigdata.BigDataApplication;
import ch.niceideas.bigdata.model.NodesConfigWrapper;
import ch.niceideas.bigdata.model.ServicesInstallStatusWrapper;
import ch.niceideas.bigdata.model.SimpleOperationCommand;
import ch.niceideas.bigdata.services.OperationsMonitoringService;
import ch.niceideas.bigdata.test.StandardSetupHelpers;
import ch.niceideas.bigdata.test.infrastructure.HttpObjectsHelper;
import ch.niceideas.bigdata.test.infrastructure.SecurityContextHelper;
import ch.niceideas.bigdata.test.services.ConfigurationServiceTestImpl;
import ch.niceideas.bigdata.test.services.ConnectionManagerServiceTestImpl;
import ch.niceideas.bigdata.test.services.SSHCommandServiceTestImpl;
import ch.niceideas.bigdata.test.services.SetupServiceTestImpl;
import ch.niceideas.bigdata.types.Node;
import ch.niceideas.bigdata.types.Service;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ContextConfiguration(classes = BigDataApplication.class)
@SpringBootTest(classes = BigDataApplication.class)
@TestPropertySource("classpath:application-test.properties")
@ActiveProfiles({
        "no-web-stack",
        "test-system",
        "test-setup",
        "test-conf",
        "test-connection-manager",
        "test-ssh",
        "test-services"})
public class NodesConfigControllerTest {

    @Autowired
    private NodesConfigController ncc;

    @Autowired
    private ConfigurationServiceTestImpl configurationServiceTest;

    @Autowired
    private SetupServiceTestImpl setupServiceTest;

    @Autowired
    private OperationsMonitoringService operationsMonitoringService;

    @Autowired
    private ConnectionManagerServiceTestImpl connectionManagerServiceTest;

    @Autowired
    private SSHCommandServiceTestImpl sshCommandServiceTest;

    @BeforeEach
    public void testSetup() {

        if (operationsMonitoringService.isProcessingPending()) {
            operationsMonitoringService.endCommand(true);
        }

        setupServiceTest.reset();
        configurationServiceTest.reset();
        configurationServiceTest.setStandard2NodesSetup();
        configurationServiceTest.setStandard2NodesInstallStatus();

        SecurityContextHelper.loginAdmin();

        connectionManagerServiceTest.dontConnect();

        ncc.setDemoMode(false);

        sshCommandServiceTest.setNodeResultBuilder((node, script) -> {
            if (script.equals("echo OK")) {
                return "OK";
            }
            if (script.equals("if [[ -f /etc/debian_version ]]; then echo debian; fi")) {
                return "debian";
            }
            if (script.endsWith("cat /proc/meminfo | grep MemTotal")) {
                return "MemTotal:        9982656 kB";
            }
            return null;
        });
    }

    @Test
    public void testLoadNodesConfig() {

        setupServiceTest.setSetupCompleted();

        System.err.println (ncc.loadNodesConfig());

        assertTrue (new JSONObject("{\n" +
                "    \"cluster-slave2\": \"on\",\n" +
                "    \"cluster-slave1\": \"on\",\n" +
                "    \"cluster-manager\": \"2\",\n" +
                "    \"distributed-filesystem1\": \"on\",\n" +
                "    \"distributed-filesystem2\": \"on\",\n" +
                "    \"node_id1\": \"192.168.10.11\",\n" +
                "    \"distributed-time2\": \"on\",\n" +
                "    \"distributed-time1\": \"on\",\n" +
                "    \"cluster-master\": \"1\",\n" +
                "    \"node_id2\": \"192.168.10.13\"\n" +
                "}").similar(new JSONObject (ncc.loadNodesConfig())));

        setupServiceTest.setSetupError();

        assertEquals ("{\n" +
                "  \"clear\": \"setup\",\n" +
                "  \"processingPending\": false,\n" +
                "  \"status\": \"OK\"\n" +
                "}", ncc.loadNodesConfig());

        setupServiceTest.setSetupCompleted();

        configurationServiceTest.setNodesConfigError();

        assertEquals ("{\n" +
                "  \"error\": \"Test Error\",\n" +
                "  \"status\": \"KO\"\n" +
                "}", ncc.loadNodesConfig());
    }

    /*
     * This tese a problem we've been having where a force reinstall of kafka-cli for instance was removing kafka
     * from being considered install in install status.
     */
    @Test
    public void testReinstallNodesConfig_kafkaCliProblem() throws Exception {

        Map<String, Object> sessionContent = new HashMap<>();

        HttpSession session = HttpObjectsHelper.createHttpSession(sessionContent);

        ServicesInstallStatusWrapper serviceInstallStatus = StandardSetupHelpers.getStandard2NodesInstallStatus();
        serviceInstallStatus.setInstallationFlagOK(Service.from("broker-cli"), Node.fromName("192-168-10-11"));
        serviceInstallStatus.setInstallationFlagOK(Service.from("broker-cli"), Node.fromName("192-168-10-13"));
        configurationServiceTest.saveServicesInstallationStatus(serviceInstallStatus);

        NodesConfigWrapper nodesConfig = StandardSetupHelpers.getStandard2NodesSetup();
        nodesConfig.setValueForPath("broker-cli1", "on");
        nodesConfig.setValueForPath("broker-cli2", "on");
        configurationServiceTest.saveNodesConfig(nodesConfig);

        assertEquals ("{\n" +
                "  \"command\": {\n" +
                "    \"restarts\": [],\n" +
                "    \"uninstallations\": [],\n" +
                "    \"installations\": [\n" +
                "      {\"broker-cli\": \"192.168.10.11\"},\n" +
                "      {\"broker-cli\": \"192.168.10.13\"}\n" +
                "    ]\n" +
                "  },\n" +
                "  \"status\": \"OK\"\n" +
                "}", ncc.reinstallNodesConfig("{\"broker-cli\":\"on\"}", session));

        // now we're expecting that only kafka-cli was removed from the one stored in session

        serviceInstallStatus.removeInstallationFlag(Service.from("broker-cli"), Node.fromName("192-168-10-11"));
        serviceInstallStatus.removeInstallationFlag(Service.from("broker-cli"), Node.fromName("192-168-10-13"));

        //assertEquals(serviceInstallStatus.getFormattedValue(), ((ServicesInstallStatusWrapper)session.getAttribute(NodesConfigController.PENDING_OPERATIONS_STATUS_OVERRIDE)).getFormattedValue());

        assertTrue (serviceInstallStatus.getJSONObject().similar(
                ((ServicesInstallStatusWrapper)session.getAttribute(NodesConfigController.PENDING_OPERATIONS_STATUS_OVERRIDE)).getJSONObject()));
    }

    @Test
    public void testReinstallNodesConfig() {

        Map<String, Object> sessionContent = new HashMap<>();

        HttpSession session = HttpObjectsHelper.createHttpSession(sessionContent);

        assertEquals ("{\n" +
                "  \"command\": {\n" +
                "    \"restarts\": [\n" +
                "      {\"cluster-slave\": \"192.168.10.11\"},\n" +
                "      {\"cluster-slave\": \"192.168.10.13\"},\n" +
                "      {\"cluster-master\": \"192.168.10.11\"},\n" +
                "      {\"user-console\": \"(kubernetes)\"}\n" +
                "    ],\n" +
                "    \"uninstallations\": [],\n" +
                "    \"installations\": [\n" +
                "      {\"distributed-time\": \"192.168.10.11\"},\n" +
                "      {\"distributed-time\": \"192.168.10.13\"},\n" +
                "      {\"distributed-filesystem\": \"192.168.10.11\"},\n" +
                "      {\"distributed-filesystem\": \"192.168.10.13\"}\n" +
                "    ]\n" +
                "  },\n" +
                "  \"status\": \"OK\"\n" +
                "}", ncc.reinstallNodesConfig("{\"distributed-filesystem\":\"on\",\"distributed-time\":\"on\"}", session));

        assertEquals ("{\"status\": \"OK\"}", ncc.applyNodesConfig(session));

        assertTrue(sessionContent.isEmpty());
    }

    @Test
    public void testSaveNodesConfig_demoMode() {

        Map<String, Object> sessionContent = new HashMap<>();

        HttpSession session = HttpObjectsHelper.createHttpSession(sessionContent);

        ncc.setDemoMode(true);

        assertEquals ("{\n" +
                "  \"messages\": \"Unfortunately, re-applying nodes configuration or changing nodes configuration is not possible in DEMO mode.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", ncc.applyNodesConfig(session));
    }

    @Test
    public void testSaveNodesConfig_processingPending() throws Exception {

        Map<String, Object> sessionContent = new HashMap<>();

        HttpSession session = HttpObjectsHelper.createHttpSession(sessionContent);

        operationsMonitoringService.startCommand(new SimpleOperationCommand(
                SimpleOperationCommand.SimpleOperation.COMMAND, Service.from("test"), Node.fromAddress("192.168.10.15")));

        assertEquals ("{\n" +
                "  \"messages\": \"Some backend operations are currently running. Please retry after they are completed.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", ncc.applyNodesConfig(session));
    }

    @Test
    public void testSaveNodesConfig() {

        Map<String, Object> sessionContent = new HashMap<>();

        HttpSession session = HttpObjectsHelper.createHttpSession(sessionContent);

        assertEquals ("{\n" +
                        "  \"command\": {\n" +
                        "    \"restarts\": [\n" +
                        "      {\"distributed-filesystem\": \"192.168.10.11\"},\n" +
                        "      {\"distributed-filesystem\": \"192.168.10.13\"},\n" +
                        "      {\"broker\": \"(kubernetes)\"},\n" +
                        "      {\"broker-manager\": \"(kubernetes)\"},\n" +
                        "      {\"user-console\": \"(kubernetes)\"}\n" +
                        "    ],\n" +
                        "    \"uninstallations\": [{\"cluster-manager\": \"192.168.10.13\"}],\n" +
                        "    \"installations\": [{\"cluster-manager\": \"192.168.10.11\"}]\n" +
                        "  },\n" +
                        "  \"status\": \"OK\"\n" +
                        "}",
                ncc.saveNodesConfig("" +
                "{\"node_id1\":\"192.168.10.11\"," +
                "\"cluster-master\":\"1\"," +
                "\"cluster-manager\":\"1\"," +
                "\"distributed-filesystem1\":\"on\"," +
                "\"cluster-slave1\":\"on\"," +
                "\"distributed-time1\":\"on\"," +
                "\"node_id2\":\"192.168.10.13\"," +
                "\"distributed-filesystem2\":\"on\"," +
                "\"cluster-slave2\":\"on\"," +
                "\"distributed-time2\":\"on\"}", session));

        assertEquals ("{\"status\": \"OK\"}", ncc.applyNodesConfig(session));

        assertTrue(sessionContent.isEmpty());
    }
}

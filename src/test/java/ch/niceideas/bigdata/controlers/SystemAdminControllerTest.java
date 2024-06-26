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
import ch.niceideas.bigdata.model.SimpleOperationCommand;
import ch.niceideas.bigdata.test.infrastructure.SecurityContextHelper;
import ch.niceideas.bigdata.test.services.ConfigurationServiceTestImpl;
import ch.niceideas.bigdata.test.services.OperationsMonitoringServiceTestImpl;
import ch.niceideas.bigdata.test.services.SystemServiceTestImpl;
import ch.niceideas.bigdata.types.Node;
import ch.niceideas.bigdata.types.Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@ContextConfiguration(classes = BigDataApplication.class)
@SpringBootTest(classes = BigDataApplication.class)
@TestPropertySource("classpath:application-test.properties")
@ActiveProfiles({"no-web-stack", "test-conf", "test-operations", "test-system", "test-services", "test-kube"})
public class SystemAdminControllerTest {

    @Autowired
    private SystemAdminController sac;

    @Autowired
    private OperationsMonitoringServiceTestImpl operationsMonitoringServiceTest;

    @Autowired
    private ConfigurationServiceTestImpl configurationServiceTest;

    @Autowired
    private SystemServiceTestImpl systemServiceTest;


    @BeforeEach
    public void testSetup() {

        configurationServiceTest.reset();

        systemServiceTest.reset();

        operationsMonitoringServiceTest.reset();

        SecurityContextHelper.loginAdmin();

        sac.setDemoMode(false);
    }

    @Test
    public void testInterruptProcessing() {

        assertEquals ("{\"status\": \"OK\"}", sac.interruptProcessing());

        operationsMonitoringServiceTest.setInterruptProcessingError();

        assertEquals ("{\n" +
                "  \"error\": \"Test Error\",\n" +
                "  \"status\": \"KO\"\n" +
                "}", sac.interruptProcessing());
    }

    @Test
    public void testShowJournal() {

        assertEquals ("{\n" +
                "  \"messages\": \"cluster-manager journal display from 192.168.10.11.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.showJournal("cluster-manager", "192.168.10.11"));

        assertEquals ("{\n" +
                "  \"messages\": \"Successfully shown journal of user-console.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.showJournal("user-console", "192.168.10.11"));
    }

    @Test
    public void testStartService() {

        assertEquals ("{\n" +
                "  \"messages\": \"cluster-manager has been started successfully on 192.168.10.11.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.startService("cluster-manager", "192.168.10.11"));

        systemServiceTest.setStartServiceError();

        assertEquals ("{\n" +
                "  \"error\": \"Test Error\",\n" +
                "  \"status\": \"KO\"\n" +
                "}", sac.startService("cluster-manager", "192.168.10.11"));

        assertEquals ("{\n" +
                "  \"messages\": \"user-console has been started successfully on kubernetes.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.startService("user-console", "192.168.10.11"));
    }

    @Test
    public void testStopService() {

        assertEquals ("{\n" +
                "  \"messages\": \"cluster-manager has been stopped successfully on 192.168.10.11.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.stopService("cluster-manager", "192.168.10.11"));

        assertEquals ("{\n" +
                "  \"messages\": \"user-console has been stopped successfully on kubernetes.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.stopService("user-console", "192.168.10.11"));
    }

    @Test
    public void testRestartService() {

        assertEquals ("{\n" +
                "  \"messages\": \"cluster-manager has been restarted successfully on 192.168.10.11.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.restartService("cluster-manager", "192.168.10.11"));

        assertEquals ("{\n" +
                "  \"messages\": \"user-console has been restarted successfully on kubernetes.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.restartService("user-console", "192.168.10.11"));
    }

    @Test
    public void testServiceActionCustom() {

        assertEquals ("{\n" +
                "  \"messages\": \"command show_log for cluster-manager has been executed successfully on 192.168.10.11.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.serviceActionCustom("show_log", "cluster-manager", "192.168.10.11"));

        assertEquals ("{\n" +
                "  \"messages\": \"command show_log for user-console has been executed successfully on 192.168.10.11.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.serviceActionCustom("show_log", "user-console", "192.168.10.11"));
    }

    @Test
    public void testReinstallService_demoMode() {

        sac.setDemoMode(true);

        assertEquals ("{\n" +
                "  \"messages\": \"Unfortunately, re-installing a service is not possible in DEMO mode.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.reinstallService("cluster-manager", "192.168.10.13"));
    }

    @Test
    public void testReinstallService_processingPending() throws Exception {

        operationsMonitoringServiceTest.startCommand(new SimpleOperationCommand(
                SimpleOperationCommand.SimpleOperation.COMMAND, Service.from("test"), Node.fromAddress("192.168.10.15")));

        assertEquals ("{\n" +
                "  \"messages\": \"Some backend operations are currently running. Please retry after they are completed.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.reinstallService("cluster-manager", "192.168.10.13"));
    }

    @Test
    public void testReinstallService() throws Exception {

        configurationServiceTest.setStandard2NodesSetup();
        configurationServiceTest.setStandard2NodesInstallStatus();
        configurationServiceTest.setStandardKubernetesConfig();

        assertEquals ("OK", configurationServiceTest.loadServicesInstallationStatus().getValueForPathAsString("cluster-manager_installed_on_IP_192-168-10-13"));

        assertEquals ("{\n" +
                "  \"messages\": \"cluster-manager has been reinstalled successfully on 192.168.10.13.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.reinstallService("cluster-manager", "192.168.10.13"));

        // should have been remove prior to reinstall and not set back since reinstall is mocked (No-Op)
        assertNull (configurationServiceTest.loadServicesInstallationStatus().getValueForPathAsString("cluster-manager_installed_on_IP_192-168-10-13"));

        assertTrue(configurationServiceTest.isSaveServicesInstallationStatusCalled());

        systemServiceTest.setStandard2NodesStatus();

        assertEquals ("{\n" +
                "  \"messages\": \"user-console has been reinstalled successfully on kubernetes.\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", sac.reinstallService("user-console", "192.168.10.13"));
    }

}

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
import ch.niceideas.bigdata.test.infrastructure.SecurityContextHelper;
import ch.niceideas.bigdata.test.services.OperationsMonitoringServiceTestImpl;
import ch.niceideas.bigdata.test.services.SetupServiceTestImpl;
import ch.niceideas.bigdata.test.services.SystemServiceTestImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ContextConfiguration(classes = BigDataApplication.class)
@SpringBootTest(classes = BigDataApplication.class)
@TestPropertySource("classpath:application-test.properties")
@ActiveProfiles({"no-web-stack", "test-app-status", "test-master", "test-operations", "test-system", "test-setup"})
public class SystemStatusControllerTest {

    @Autowired
    private SystemStatusController systemStatusController;

    @Autowired
    private OperationsMonitoringServiceTestImpl operationsMonitoringServiceTest;

    @Autowired
    private SystemServiceTestImpl systemServiceTest;

    @Autowired
    private SetupServiceTestImpl setupServiceTest;

    @BeforeEach
    public void setUp() {
        operationsMonitoringServiceTest.endCommand(true);
    }

    @Test
    public void testGetContext() {
        SecurityContextHelper.loginAdmin();
        assertEquals("{\n" +
                "    \"version\": \"@project.version@\",\n" +
                "    \"user\": \"test\",\n" +
                "    \"roles\": [\"ADMIN\"],\n" +
                "    \"status\": \"OK\"\n" +
                "}", systemStatusController.getContext());
    }

    @Test
    public void testgetLastOperationResult() {

        assertEquals ("{\n" +
                "  \"success\": true,\n" +
                "  \"status\": \"OK\"\n" +
                "}", systemStatusController.getLastOperationResult());

        operationsMonitoringServiceTest.setLastOperationSuccessError();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> systemStatusController.getLastOperationResult());

        assertEquals ("Test Error", exception.getMessage());
    }

    @Test
    public void testGetStatus() {

        systemServiceTest.setReturnOKSystemStatus();

        assertEquals ("{\n" +
                "  \"nodeServicesStatus\": {\"status\": \"OK\"},\n" +
                "  \"processingPending\": false,\n" +
                "  \"systemStatus\": {\"status\": \"OK\"},\n" +
                "  \"status\": \"OK\"\n" +
                "}", systemStatusController.getStatus());

        systemServiceTest.setReturnEmptySystemStatus();

        assertEquals ("{\n" +
                "  \"clear\": \"nodes\",\n" +
                "  \"processingPending\": false,\n" +
                "  \"systemStatus\": {\"status\": \"OK\"},\n" +
                "  \"status\": \"OK\"\n" +
                "}", systemStatusController.getStatus());

        setupServiceTest.setSetupError();

        assertEquals ("{\n" +
                "  \"clear\": \"setup\",\n" +
                "  \"processingPending\": false,\n" +
                "  \"status\": \"OK\"\n" +
                "}", systemStatusController.getStatus());

        setupServiceTest.setSetupCompleted();;

        systemServiceTest.setThrowStatusWrapperException();

        assertEquals ("{\n" +
                "  \"clear\": \"nodes\",\n" +
                "  \"processingPending\": false,\n" +
                "  \"systemStatus\": {\"status\": \"OK\"},\n" +
                "  \"status\": \"OK\"\n" +
                "}", systemStatusController.getStatus());
    }
}

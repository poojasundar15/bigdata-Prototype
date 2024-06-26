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

import ch.niceideas.bigdata.AbstractBaseSSHTest;
import ch.niceideas.bigdata.BigDataApplication;
import ch.niceideas.bigdata.terminal.ScreenImage;
import ch.niceideas.bigdata.test.services.ConfigurationServiceTestImpl;
import ch.niceideas.bigdata.test.services.ConnectionManagerServiceTestImpl;
import ch.niceideas.bigdata.utils.OSDetector;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ContextConfiguration(classes = BigDataApplication.class)
@SpringBootTest(classes = BigDataApplication.class)
@TestPropertySource("classpath:application-test.properties")
@ActiveProfiles({"no-web-stack", "test-ssh", "test-conf", "test-connection-manager", "test-system", "test-operation"})
public class TerminalServiceTest extends AbstractBaseSSHTest {

    @Override
    protected CommandFactory getSShSubsystemToUse() {
        return new ProcessShellCommandFactory();
    }

    /** Run Test on Linux only */
    @BeforeEach
    public void beforeMethod() {
        Assumptions.assumeTrue(OSDetector.isPosix());
    }

    @Autowired
    private ConnectionManagerServiceTestImpl connectionManagerServiceTest;

    @Autowired
    private ConfigurationServiceTestImpl configurationServiceTest;

    @Autowired
    private TerminalService terminalService = null;

    @BeforeEach
    public void setUp() throws Exception {

        connectionManagerServiceTest.reset();

        configurationServiceTest.saveSetupConfig("{ \"" + SetupService.SSH_USERNAME_FIELD + "\" : \"test\" }");

        connectionManagerServiceTest.setPrivateSShKeyContent(privateKeyRaw);
        connectionManagerServiceTest.setSShPort(getSShPort());
    }

    @Test
    public void testNominal() throws Exception {

        ScreenImage si = terminalService.postUpdate("node=localhost&s=699156997&w=80&h=25&c=1&k=&t=0");

        assertTrue (si.screen.startsWith("<pre class='term '><span class='"), si.screen);

        si = terminalService.postUpdate("node=localhost&s=699156997&w=80&h=25&c=1&k=&t=2000");
        assertTrue (si.screen.startsWith("<idem/>"), si.screen);

        si = terminalService.postUpdate("node=localhost&s=699156997&w=80&h=25&c=1&k=&t=2000");
        assertTrue (si.screen.startsWith("<idem/>"), si.screen);

        si = terminalService.postUpdate("node=localhost&s=699156997&w=80&h=25&c=1&k=l&t=2001");
        assertTrue (si.screen.startsWith("<pre class='term '><span class='"), si.screen);

        si = terminalService.postUpdate("node=localhost&s=699156997&w=80&h=25&c=1&k=&t=2002");
        assertTrue (si.screen.startsWith("<pre class='term '><span class='"), si.screen);

        si = terminalService.postUpdate("node=localhost&s=699156997&w=80&h=25&c=1&k=%0D&t=2003");
        assertTrue (si.screen.startsWith("<pre class='term '><span class='"), si.screen);

        terminalService.removeTerminal("699156997");

        // make sure removing unknown terminal doesn't cause any error
        terminalService.removeTerminal("ABCD1234");
    }

    @Test
    public void testRemoveExpiredTerminals() throws Exception {

        assertTrue (terminalService instanceof TerminalServiceImpl);
        TerminalServiceImpl impl = (TerminalServiceImpl) terminalService;

        ScreenImage si = terminalService.postUpdate("node=localhost&s=699156997&w=80&h=25&c=1&k=&t=0");

        impl.removeExpiredTerminals (1000);

        assertEquals(1, impl.getSessions().size());

        impl.removeExpiredTerminals (0);

        assertEquals(0, impl.getSessions().size());
    }
}

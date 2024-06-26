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
import ch.niceideas.bigdata.model.SSHConnection;
import ch.niceideas.bigdata.model.service.proxy.ProxyTunnelConfig;
import ch.niceideas.bigdata.test.services.ConfigurationServiceTestImpl;
import ch.niceideas.bigdata.test.services.ConnectionManagerServiceTestImpl;
import ch.niceideas.bigdata.test.services.ProxyManagerServiceTestImpl;
import ch.niceideas.bigdata.types.Node;
import ch.niceideas.bigdata.types.Service;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.DefaultErrorHandler;
import org.apache.logging.log4j.core.layout.MessageLayout;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;

import static org.apache.logging.log4j.core.config.Configurator.setLevel;
import static org.junit.jupiter.api.Assertions.*;

@ContextConfiguration(classes = BigDataApplication.class)
@SpringBootTest(classes = BigDataApplication.class)
@TestPropertySource("classpath:application-test.properties")
@ActiveProfiles({"no-web-stack", "test-setup", "test-conf", "test-proxy", "test-connection-manager"})
public class ConnectionManagerServiceTest extends AbstractBaseSSHTest {

    @Override
    protected CommandFactory getSShSubsystemToUse() {
        return new ProcessShellCommandFactory();
    }

    @Autowired
    private ProxyManagerServiceTestImpl proxyManagerServiceTest;

    @Autowired
    private ConnectionManagerServiceTestImpl connectionManagerServiceTest;

    @Autowired
    private ConfigurationServiceTestImpl configurationServiceTest;

    @BeforeEach
    public void setUp() throws Exception {
        connectionManagerServiceTest.reset();
        proxyManagerServiceTest.reset();

        configurationServiceTest.saveSetupConfig("{ \"" + SetupService.SSH_USERNAME_FIELD + "\" : \"test\" }");

        connectionManagerServiceTest.setPrivateSShKeyContent(privateKeyRaw);
        connectionManagerServiceTest.setSShPort(getSShPort());
    }

    @Test
    public void testNominal() throws Exception {
        assertNotNull (sshd);

        // create a connection to localhost
        SSHConnection connection = connectionManagerServiceTest.getSharedConnection(Node.fromName("localhost"));
        assertNotNull(connection);

        // get a second connection and make sure it matches
        SSHConnection second = connectionManagerServiceTest.getSharedConnection(Node.fromName("localhost"));
        assertNotNull(second);
        assertSame(connection, second);

        // close connection and make sure it gets properly recreated
        second.close();

        SSHConnection newOne = connectionManagerServiceTest.getSharedConnection(Node.fromName("localhost"));
        assertNotNull(newOne);
        assertNotSame (newOne, second);
    }

    @Test
    public void testDumpPortForwardersMap() throws Exception {

        Logger testLogger = LogManager.getLogger(ConnectionManagerServiceImpl.class.getName());
        try {
            setLevel(ConnectionManagerServiceImpl.class.getName(), Level.DEBUG);

            StringBuilder builder = new StringBuilder();

            Appender testAppender = (Appender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Appender.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isStarted":
                        return true;
                    case "getName":
                        return "test";
                    case "append":
                        org.apache.logging.log4j.core.impl.Log4jLogEvent event = (org.apache.logging.log4j.core.impl.Log4jLogEvent) args[0];
                        builder.append(event.getMessage().getFormattedMessage());
                        builder.append("\n");
                        return null;
                    case "getHandler":
                        return new DefaultErrorHandler(ConsoleAppender.createDefaultAppenderForLayout(new MessageLayout()));
                    case "stop":
                        return null;
                }
                throw new UnsupportedOperationException("Unsupported method : " + method.getName());
            });

            ((org.apache.logging.log4j.core.Logger) testLogger).addAppender(testAppender);

            testLocalPortForwarderWrapper();

            //System.err.println(builder.toString());

            String result = builder.toString();

            assertTrue(result.contains("------ BEFORE ---- recreateTunnels (localhost) ----------- "));

            assertTrue(result.contains("------ AFTER ---- recreateTunnels (localhost) ----------- \n" +
                    " - localhost\n" +
                    "   + dummyService - from 6123 to localhost:123\n" +
                    "   + dummyService - from 6124 to localhost:124\n" +
                    "   + dummyService - from 6125 to localhost:125"));

            assertTrue(result.contains("------ BEFORE ---- recreateTunnels (localhost) ----------- \n" +
                    " - localhost\n" +
                    "   + dummyService - from 6123 to localhost:123\n" +
                    "   + dummyService - from 6124 to localhost:124\n" +
                    "   + dummyService - from 6125 to localhost:125"));

        } finally {

            setLevel(ConnectionManagerService.class.getName(), Level.INFO);
        }
    }

    @Test
    public void testLocalPortForwarderWrapper() throws Exception {

        proxyManagerServiceTest.setForwarderConfigForHosts(Node.fromName("localhost"), new ArrayList<>(){{
            add (new ProxyTunnelConfig(Service.from("dummyService"), 6123, Node.fromName("localhost"), 123));
            add (new ProxyTunnelConfig(Service.from("dummyService"),6124, Node.fromName("localhost"), 124));
            add (new ProxyTunnelConfig(Service.from("dummyService"),6125, Node.fromName("localhost"), 125));
        }});

        SSHConnection connection = connectionManagerServiceTest.getSharedConnection(Node.fromName("localhost"));

        assertEquals(3, connectionManagerServiceTest.getCreateCallFor().size());
        assertEquals("123,124,125", String.join(",", connectionManagerServiceTest.getCreateCallFor()));

        assertEquals(1, connectionManagerServiceTest.getDropCallFor().size());

        assertNotNull(connection);

        connectionManagerServiceTest.resetCountersOnly();

        connectionManagerServiceTest.recreateTunnels(Node.fromName("localhost"));

        // nothing actually recreated since nothing changed
        assertEquals(0, connectionManagerServiceTest.getCreateCallFor().size());
        assertEquals(1, connectionManagerServiceTest.getDropCallFor().size());

        proxyManagerServiceTest.reset();
        proxyManagerServiceTest.setForwarderConfigForHosts(Node.fromName("localhost"), new ArrayList<>(){{
            add (new ProxyTunnelConfig(Service.from("dummyService"), 20123, Node.fromName("localhost"), 11123));
            add (new ProxyTunnelConfig(Service.from("dummyService"),20124, Node.fromName("localhost"), 11124));
            add (new ProxyTunnelConfig(Service.from("dummyService"),20125, Node.fromName("localhost"), 11125));
        }});


        connectionManagerServiceTest.resetCountersOnly();

        connectionManagerServiceTest.recreateTunnels(Node.fromName("localhost"));

        assertEquals(3, connectionManagerServiceTest.getCreateCallFor().size());
        assertEquals(1, connectionManagerServiceTest.getDropCallFor().size());
    }
}

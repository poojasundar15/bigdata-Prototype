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
import ch.niceideas.common.utils.ResourceUtils;
import ch.niceideas.common.utils.StreamUtils;
import ch.niceideas.common.utils.StringUtils;
import ch.niceideas.bigdata.model.SSHConnection;
import ch.niceideas.bigdata.types.Node;
import ch.niceideas.bigdata.utils.PumpThread;
import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
@Profile("!test-ssh")
public class SSHCommandServiceImpl implements SSHCommandService {

    private static final Logger logger = Logger.getLogger(SSHCommandServiceImpl.class);

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired
    private ConfigurationService configurationService;

    @Override
    public String runSSHScript(SSHConnection connection, String script) throws SSHCommandException {
        return runSSHScript(connection, script, true);
    }

    @Override
    public String runSSHScript(Node node, String script) throws SSHCommandException {
        return runSSHScript(node, script, true);
    }

    @Override
    public String runSSHCommand(SSHConnection connection, String[] command) throws SSHCommandException {
        StringBuilder sb = new StringBuilder();
        for (String cmd : command) {
            sb.append (cmd).append (" ");
        }
        return runSSHCommand(connection, sb.toString());
    }

    @Override
    public String runSSHScriptPath(SSHConnection connection, String scriptName) throws SSHCommandException {
        return runSSHScript(connection, getScriptContent(scriptName));
    }

    @Override
    public String runSSHScriptPath(Node node, String scriptName) throws SSHCommandException {
        return runSSHScript(node, getScriptContent(scriptName));
    }

    String getScriptContent(String scriptName) throws SSHCommandException {
        try {
            return StreamUtils.getAsString(Optional.ofNullable(ResourceUtils.getResourceAsStream(scriptName))
                        .orElseThrow(() -> new SSHCommandException("Impossible to load script " + scriptName)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SSHCommandException (e);
        }
    }

    @Override
    public String runSSHScript(Node node, String script, boolean throwsException) throws SSHCommandException {
        try {
            return runSSHScript(connectionManagerService.getSharedConnection(node), script, throwsException);
        } catch (ConnectionManagerException e) {
            logger.error(e.getMessage());
            logger.debug(e, e);
            throw new SSHCommandException(e);
        }
    }

    @Override
    public String runSSHScript(SSHConnection connection, String script, boolean throwsException) throws SSHCommandException {

        Session session = null;
        try (ByteArrayOutputStream baosOut = new ByteArrayOutputStream();
             ByteArrayOutputStream baosErr = new ByteArrayOutputStream()) {

            session = connection.openSession();
            session.execCommand("bash --login -s");

            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(session.getStdin()));

            out.write(script);
            out.close();

            try (PumpThread ignored1 = new PumpThread(session.getStdout(), baosOut);
                 PumpThread ignored2 = new PumpThread(session.getStderr(), baosErr)) {
                session.getStdin().close();
            }

            // wait for some time since the delivery of the exit status often gets delayed
            session.waitForCondition(ChannelCondition.EXIT_STATUS, connection.getReadTimeout());
            int retValue = Optional.ofNullable(session.getExitStatus())
                    .orElseThrow(() -> new IOException("Could not get a return status within " + connection.getReadTimeout() + " milliseconds"));

            String outResult = baosOut.toString();
            String errResult = baosErr.toString();

            String result = outResult
                    + (StringUtils.isNotBlank(outResult) && StringUtils.isNotBlank(errResult) ? "\n" : "")
                    + errResult;

            if (retValue == 0 || !throwsException) {
                return result;
            } else {
                throw new SSHCommandException(result);
            }

        } catch (InterruptedException e) {
            logger.error(e, e);
            Thread.currentThread().interrupt();
            throw new SSHCommandException(e);

        } catch (IOException e) {
            logger.error(e, e);
            throw new SSHCommandException(e);

        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    @Override
    public String runSSHCommand(Node node, String command) throws SSHCommandException {
        try {
            SSHConnection connection = connectionManagerService.getSharedConnection(node);
            return runSSHCommand(connection, command);
        } catch (ConnectionManagerException e) {
            logger.error (e, e);
            throw new SSHCommandException(e);
        }
    }

    @Override
    public String runSSHCommand(SSHConnection connection, String command) throws SSHCommandException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int retValue = connection.exec(command, baos);

            if (retValue == 0) {
                return baos.toString();
            } else {
                throw new SSHCommandException("Command exited with return code " + retValue + "\n" + baos);
            }

        } catch (InterruptedException | IOException e) {
            logger.error (e, e);
            Thread.currentThread().interrupt();
            throw new SSHCommandException(e);
        }
    }

    @Override
    public void copySCPFile(Node node, String filePath) throws SSHCommandException {
        try {
            SSHConnection connection = connectionManagerService.getSharedConnection(node);
            copySCPFile(connection, filePath);

        } catch (ConnectionManagerException e) {
            logger.error (e, e);
            throw new SSHCommandException(e);
        }
    }

    @Override
    public void copySCPFile(SSHConnection connection, String filePath) throws SSHCommandException {

        try {
            JsonWrapper systemConfig = configurationService.loadSetupConfig();

            SCPClient scp = connection.createSCPClient();

            scp.put(filePath, "/home/" + systemConfig.getValueForPath(SetupService.SSH_USERNAME_FIELD), "0755");

            // scp is stateless and doesn't need to be closed

        } catch (IOException  | FileException | JSONException | SetupException e) {
            logger.error (e, e);
            throw new SSHCommandException(e);
        }
    }

}

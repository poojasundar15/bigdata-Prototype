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

import ch.niceideas.common.utils.FileUtils;
import ch.niceideas.common.utils.Pair;
import ch.niceideas.bigdata.AbstractBaseSSHTest;
import ch.niceideas.bigdata.BigDataApplication;
import ch.niceideas.bigdata.test.infrastructure.HttpObjectsHelper;
import ch.niceideas.bigdata.test.services.ConfigurationServiceTestImpl;
import ch.niceideas.bigdata.test.services.ConnectionManagerServiceTestImpl;
import ch.niceideas.bigdata.test.services.SSHCommandServiceTestImpl;
import ch.niceideas.bigdata.types.Node;
import ch.niceideas.bigdata.utils.OSDetector;
import com.trilead.ssh2.SFTPv3Client;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

@ContextConfiguration(classes = BigDataApplication.class)
@SpringBootTest(classes = BigDataApplication.class)
@TestPropertySource("classpath:application-test.properties")
@ActiveProfiles({"no-web-stack", "test-setup", "test-conf", "test-proxy", "test-ssh", "test-connection-manager"})
public class FileManagerServiceTest extends AbstractBaseSSHTest {

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
    private SSHCommandServiceTestImpl sshCommandServiceTest;

    @Autowired
    private ConfigurationServiceTestImpl configurationServiceTest;

    @Autowired
    private FileManagerService fileManagerService = null;

    @BeforeEach
    public void setUp() throws Exception {

        /*
        fileManagerService = new FileManagerServiceImpl();
        fileManagerService.setConnectionManagerService(connectionManagerServiceTest);
        fileManagerService.setSshCommandService(sshCommandServiceTest);
        */

        configurationServiceTest.reset();

        configurationServiceTest.saveSetupConfig("{ \"" + SetupService.SSH_USERNAME_FIELD + "\" : \"test\" }");

        connectionManagerServiceTest.reset();

        connectionManagerServiceTest.setPrivateSShKeyContent(privateKeyRaw);
        connectionManagerServiceTest.setSShPort(getSShPort());
    }

    @Test
    public void testConnectSftp() throws Exception {

        Pair<String, JSONObject> result = fileManagerService.navigateFileManager(Node.fromName("localhost"), "/", ".");

        assertEquals ("/", result.getKey());

        JSONObject dir = result.getValue();

        assertNotNull (dir);

        assertTrue(dir.getJSONObject(".").toString().contains("\"size\":\"4096\",\"user\":\"root\",\"permissions\""));
    }

    @Test
    public void testNavigateSftp() throws Exception {

        Pair<String, JSONObject> result = fileManagerService.navigateFileManager(Node.fromName("localhost"), "/", "boot");

        assertEquals ("/boot", result.getKey());

        JSONObject dir = result.getValue();

        assertNotNull (dir);

        assertTrue(dir.getJSONObject(".").toString().contains("\"size\":\"4096\",\"user\":\"root\",\"permissions\""));
    }

    @Test
    public void testIsTextMimeType() {

        assertTrue (fileManagerService instanceof FileManagerServiceImpl);
        FileManagerServiceImpl impl = (FileManagerServiceImpl) fileManagerService;

        assertTrue(impl.isTextMimeType("text/plain"));
        assertTrue(impl.isTextMimeType("text/csv"));

        assertTrue(impl.isTextMimeType("application/resource-lists-diff+xml"));
        assertTrue(impl.isTextMimeType("application/xslt+xml"));

        assertTrue(impl.isTextMimeType("application/javascript"));

        assertFalse(impl.isTextMimeType("application/x-xpinstall"));
    }

    @Test
    public void testDeleteFile() throws Exception {

        File tempFile = File.createTempFile("test-", "test");
        FileUtils.writeFile(tempFile, "Test File Content");
        assertTrue(tempFile.exists());

        fileManagerService.deletePath(Node.fromName("localhost"), tempFile.getParent(), tempFile.getName());

        assertFalse(tempFile.exists());
    }

    @Test
    public void testDownloadFile() throws Exception {

        File tempFile = File.createTempFile("test-", "test");
        FileUtils.writeFile(tempFile, "Test File Content");
        assertTrue(tempFile.exists());

        OutputStream testStream = new ByteArrayOutputStream();

        ServletOutputStream streamWrapper = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int b) throws IOException {
                testStream.write(b);
            }
        };

        HttpServletResponse proxyResponse = HttpObjectsHelper.createHttpServletResponse(new HashMap<>(), streamWrapper);

        sshCommandServiceTest.setResult("plain/text");

        fileManagerService.downloadFile(Node.fromName("localhost"), tempFile.getParent(), tempFile.getName(), new FileManagerServiceImpl.HttpServletResponseAdapter(){

            @Override
            public void setContentType(String type) {
                proxyResponse.setContentType(type);
            }

            @Override
            public ServletOutputStream getOutputStream() throws IOException {
                return proxyResponse.getOutputStream();
            }
        });

        String downloadedContent = testStream.toString();
        String originalContent = FileUtils.readFile(tempFile);

        assertEquals (originalContent, downloadedContent);

        FileUtils.delete(tempFile);
    }

    void buildtestFileManager(String mimeType) {
        fileManagerService = new FileManagerServiceImpl() {

            @Override
            SFTPv3Client getClient(@RequestParam("address") Node node) throws ConnectionManagerException, IOException {
                return new SFTPv3Client(connectionManagerServiceTest.getSharedConnection(Node.fromName("localhost")).getUnder());
            }
            @Override
            String getFileMimeType(Node node, String newPath) {
                return mimeType;
            }
            @Override
            public Pair<String, JSONObject> navigateFileManager(Node node, String folder, String subFolder) {
                return new Pair<>("/test", new JSONObject(new HashMap<String, Object>() {{

                    put ("test", new JSONObject(new HashMap<String, Object>() {{
                        put ("permissions", "rwxrwxrwx");
                        put ("count", "2");
                        put ("user", "badtrash");
                        put ("group", "badtrash");
                        put ("size", "1024");
                        put ("timestamp", "2019-11-11 08:30:00");
                    }}));

                }}));
            }
        };
        FileManagerServiceImpl impl = (FileManagerServiceImpl) fileManagerService;
        impl.setConnectionManagerService(connectionManagerServiceTest);
        impl.setSshCommandService(sshCommandServiceTest);
    }

    @Test
    public void testOpenFileDirectory() {

        buildtestFileManager("inode/directory");

        JSONObject result = fileManagerService.openFile(Node.fromName("localhost"), "/etc", "passwd");

        assertEquals ("{\n" +
                "  \"content\": {\"test\": {\n" +
                "    \"count\": \"2\",\n" +
                "    \"size\": \"1024\",\n" +
                "    \"user\": \"badtrash\",\n" +
                "    \"permissions\": \"rwxrwxrwx\",\n" +
                "    \"group\": \"badtrash\",\n" +
                "    \"timestamp\": \"2019-11-11 08:30:00\"\n" +
                "  }},\n" +
                "  \"folder\": \"/test\",\n" +
                "  \"status\": \"OK\"\n" +
                "}", result.toString(2));
    }

    @Test
    public void testOpenFileNoPermission() {

        buildtestFileManager("no read permission");

        JSONObject result = fileManagerService.openFile(Node.fromName("localhost"), "/etc", "passwd");

        assertEquals ("{\n" +
                "  \"accessible\": false,\n" +
                "  \"fileViewable\": false,\n" +
                "  \"status\": \"OK\"\n" +
                "}", result.toString(2));
    }

    @Test
    public void testOpenFileBinaryFile() throws Exception {

        File tempFile = File.createTempFile("test", "sftp");
        FileUtils.writeFile(tempFile, "ABCD");

        buildtestFileManager("application/binary");

        JSONObject result = fileManagerService.openFile(Node.fromName("localhost"), tempFile.getParent(), tempFile.getName());

        assertEquals ("{\n" +
                "  \"accessible\": true,\n" +
                "  \"fileViewable\": false,\n" +
                "  \"status\": \"OK\"\n" +
                "}", result.toString(2));

        FileUtils.delete(tempFile);
    }

    @Test
    public void testOpenFileTextSmallFile() throws Exception {

        File tempFile = File.createTempFile("test", "sftp");
        FileUtils.writeFile(tempFile, "ABCD");

        buildtestFileManager("text/plain");

        JSONObject result = fileManagerService.openFile(Node.fromName("localhost"), tempFile.getParent(), tempFile.getName());

        assertEquals (tempFile.getAbsolutePath(), result.getString("fileName"));
        assertEquals (Base64.getEncoder().encodeToString("ABCD".getBytes()), result.getString("fileContent"));

        assertTrue (tempFile.delete());
    }

    @Test
    public void testOpenFileTextBigFile() throws Exception {

        File tempFile = File.createTempFile("test_big", "sftp");
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tempFile)));
        for (int i = 0; i < 2000000; i++) {
            bw.write("NEW_LINE_" + ThreadLocalRandom.current().nextInt());
        }
        bw.close();

        buildtestFileManager("text/plain");

        JSONObject result = fileManagerService.openFile(Node.fromName("localhost"), tempFile.getParent(), tempFile.getName());

        assertEquals ("{\n" +
                "  \"accessible\": true,\n" +
                "  \"fileViewable\": false,\n" +
                "  \"status\": \"OK\"\n" +
                "}", result.toString(2));

        assertTrue (tempFile.delete());
    }

    @Test
    public void testCreateFile() throws Exception {

        File tempFile = File.createTempFile("test", "sftp");

        assertTrue (tempFile.delete());

        fileManagerService.createFile(Node.fromName("localhost"), "/tmp/", tempFile.getName());

        assertTrue(tempFile.exists());

        IOException exception = assertThrows(IOException.class, () -> fileManagerService.createFile(Node.fromName("localhost"), "/", tempFile.getName()));

        assertTrue(exception.getMessage().contains("Permission denied"));
    }

    @Test
    public void testUploadFile() throws Exception {
        File sourceFile = File.createTempFile("test", "sftp");
        FileUtils.writeFile(sourceFile, "test content");

        File destFile = File.createTempFile("test", "sftp");
        assertTrue (destFile.delete());

        fileManagerService.uploadFile(Node.fromName("localhost"), destFile.getParent(), destFile.getName(), new FileInputStream(sourceFile));

        assertTrue(destFile.exists());
        assertEquals ("test content", FileUtils.readFile(destFile));
    }

    @Test
    public void testRemoveFileManager() throws Exception {

        assertEquals (0, connectionManagerServiceTest.getSharedConnectionCalledFor().size());

        testConnectSftp();

        assertEquals (1, connectionManagerServiceTest.getSharedConnectionCalledFor().size());

        testConnectSftp();

        assertEquals (1, connectionManagerServiceTest.getSharedConnectionCalledFor().size());

        fileManagerService.removeFileManager(Node.fromName("localhost"));

        testConnectSftp();

        assertEquals (2, connectionManagerServiceTest.getSharedConnectionCalledFor().size());
    }

}

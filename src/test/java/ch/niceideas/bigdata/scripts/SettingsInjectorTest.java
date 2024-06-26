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

package ch.niceideas.bigdata.scripts;

import ch.niceideas.common.utils.FileUtils;
import ch.niceideas.common.utils.ProcessHelper;
import ch.niceideas.common.utils.ResourceUtils;
import ch.niceideas.bigdata.utils.OSDetector;
import org.apache.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.StringTokenizer;

import static org.junit.jupiter.api.Assertions.*;

public class SettingsInjectorTest {

    private static final Logger logger = Logger.getLogger(SettingsInjectorTest.class);

    private String tempFolder = null;
    private File settingsInjectorScriptFile = null;
    private File settingsFile = null;

    private File sparkFile;
    private File kafkaFile;
    private File esFile;
    private File grafanaFile;
    private File kibanaFile;
    private File cerebroJVMFile;
    private File cerebroJVMOptsFile;
    private File egmiFile;

    /** Run Test on Linux only */
    @BeforeEach
    public void beforeMethod() {
        Assumptions.assumeTrue(OSDetector.isPosix());
    }
    
    public static String getCmdPathFileStr(String command) {
        String rtn = null;
        if (command != null) {
            String[] sCmdExts = { "", ".com", ".exe", ".bat" };
            StringTokenizer st = new StringTokenizer(System.getenv("PATH"), File.pathSeparator);
            String path, sFile;
            while (st.hasMoreTokens()) {
                path = st.nextToken();
                for (String sExt : sCmdExts) {
                    sFile = path + File.separator + command + sExt;
                    if (new File(sFile).isFile()) {
                        rtn = sFile;
                        break;
                    }
                }
            }
        }
        return rtn;
    }

    @BeforeEach
    public void setUp() throws Exception {
    
        // ensure presence of jq command !!!
        assertNotNull(getCmdPathFileStr("jq"), "'jq' command is found in path");

        // Create temp folder
        File tempFile = File.createTempFile("settings_injector_", "");
        assertTrue (tempFile.delete());

        assertTrue (tempFile.mkdirs());
        tempFolder = tempFile.getCanonicalPath();

        // Copy configuration files to tempFolder
        sparkFile = copyFile(tempFile, "spark/conf/spark-defaults.conf");
        kafkaFile = copyFile(tempFile, "kafka/config/eskimo-memory.opts");
        egmiFile = copyFile(tempFile, "egmi/conf/egmi.properties");
        esFile = copyFile (tempFile, "elasticsearch/config/elasticsearch.yml");
        grafanaFile = copyFile (tempFile, "grafana/conf/defaults.ini");

        kibanaFile = copyFile (tempFile, "kibana/config/node.options");

        cerebroJVMFile = copyFile (tempFile, "cerebro/config/eskimo.options");
        cerebroJVMOptsFile = copyFile (tempFile, "cerebro2/config/JVM_OPTS.sh");

        settingsInjectorScriptFile = new File ("services_setup/base-eskimo/settingsInjector.sh");
        //System.out.println(System.getProperty("user.dir"));
        assertTrue (settingsInjectorScriptFile.exists());

        settingsFile = ResourceUtils.getFile("classpath:settingsInjector/testConfig.json");
        assertTrue(settingsFile.exists());
    }

    File copyFile(File tempFile, String file) throws IOException {
        File esFile = ResourceUtils.getFile("classpath:settingsInjector/usr_local_lib/" + file);
        assertTrue(esFile.exists());

        File targetEsFile = new File(tempFile, "usr_local_lib/" + file);
        assertTrue (targetEsFile.getParentFile().mkdirs());
        assertFalse(targetEsFile.exists());

        FileUtils.copy(esFile, targetEsFile);
        assertTrue(targetEsFile.exists());

        return targetEsFile;
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (tempFolder != null) {
            FileUtils.delete(new File (tempFolder));
        }
    }

    @Test
    public void testEgmiSettings() throws Exception {
        String result = runSettingsInjectorOnService("gluster");
        logger.info(result);

        String egmiFileContent = FileUtils.readFile(egmiFile);

        //System.err.println (egmiFileContent);

        assertTrue(egmiFileContent.contains("zookeeper.urls=\n"));
        assertTrue(egmiFileContent.contains("master=false"));
        assertTrue(egmiFileContent.contains("\ntarget.predefined-ip-addresses=192.168.56.21,192.168.56.22,192.168.56.23,192.168.56.24"));

        assertFalse(egmiFileContent.contains("#target.predefined-ip-addresses="));
    }

    @Test
    public void testKafkaJavaOpts() throws Exception {

        String result = runSettingsInjectorOnService("kafka");
        logger.info(result);

        // ensure properties were found
        assertTrue(result.contains("== adding not found variable"));

        String kafkaFileContent = FileUtils.readFile(kafkaFile);
        //System.err.println (kafkaFileContent);

        assertTrue(kafkaFileContent.contains("Xms1234m"));
        assertTrue(kafkaFileContent.contains("Xms1234m"));
    }

    @Test
    public void testKibanaNodeOptions() throws Exception {

        String result = runSettingsInjectorOnService("kibana");
        logger.info(result);

        // ensure properties were found
        assertTrue(result.contains("== Found property max-old-space-size : 1234"));

        String kibanaFileContent = FileUtils.readFile(kibanaFile);
        //System.err.println (kibanaFileContent);

        assertTrue(kibanaFileContent.contains("--max-old-space-size=1234"));
    }

    private String runSettingsInjectorOnService(String service) throws ProcessHelper.ProcessHelperException, IOException {
        return ProcessHelper.exec(new String[]{
                "bash",
                "-c",
                "export SETTING_INJECTOR_DEBUG=1 && " +
                        "export SETTING_ROOT_FOLDER=" + tempFolder + "/usr_local_lib/ && " +
                        "bash " +
                        settingsInjectorScriptFile.getCanonicalPath() +
                        " " + service + " " +
                        settingsFile.getCanonicalPath() +
                        " ; " +
                        "exit $?"}, true);
    }

    @Test
    public void testCerebroSearchJVMOptions() throws Exception {

        String result = runSettingsInjectorOnService("cerebro2");
        logger.info(result);

        // ensure properties were found
        assertTrue(result.contains("== Found property Xms : 600m"));
        assertTrue(result.contains("== Found property Xmx : 600m"));

        String cerebroJVMFileContent = FileUtils.readFile(cerebroJVMOptsFile);
        //System.err.println (cerebroJVMFileContent);

        assertTrue(cerebroJVMFileContent.contains("-Xms600m"));
        assertTrue(cerebroJVMFileContent.contains("-Xmx600m"));
        assertTrue(cerebroJVMFileContent.contains("JAVA_OPTS=-Xmx600m -Xms600m"));
    }

    @Test
    public void testElasticSearchJVMOptions() throws Exception {

        String result = runSettingsInjectorOnService("cerebro");
        logger.info(result);

        // ensure properties were found
        assertTrue(result.contains("== Found property Xms : 600m"));
        assertTrue(result.contains("== Found property Xmx : 600m"));

        String cerebroJVMFileContent = FileUtils.readFile(cerebroJVMFile);

        assertTrue(cerebroJVMFileContent.contains("-Xms600m"));
        assertTrue(cerebroJVMFileContent.contains("-Xmx600m"));
        assertTrue(cerebroJVMFileContent.contains("# Eskimo memory settings"));
    }

    @Test
    public void testNominalSparkConfig() throws Exception {

        String result = runSettingsInjectorOnService("spark-runtime");
        logger.info(result);

        // ensure properties were found
        assertTrue(result.contains("= Found property spark.locality.wait : 40s"));
        assertTrue(result.contains("= Found property spark.dynamicAllocation.executorIdleTimeout : 300s"));

        String sparkFileContent = FileUtils.readFile(sparkFile);
        //System.err.println (sparkFileContent);

        assertTrue (sparkFileContent.contains("spark.locality.wait=40s"));
        assertFalse (sparkFileContent.contains("#spark.locality.wait=40s"));

        assertTrue (sparkFileContent.contains("spark.dynamicAllocation.executorIdleTimeout=300s"));
        assertFalse (sparkFileContent.contains("#spark.dynamicAllocation.executorIdleTimeout=300s"));

        assertTrue (sparkFileContent.contains("spark.eskimo.isTest=true"));
        assertFalse (sparkFileContent.contains("#spark.eskimo.isTest=true"));

        assertTrue (sparkFileContent.contains("spark.executor.memory=1872m"));
    }

    @Test
    public void testNominalGrafanaConfig() throws Exception {

        String result = runSettingsInjectorOnService("grafana");
        //logger.info(result);

        // ensure properties were found
        assertTrue(result.contains("= Found property admin_user : test_eskimo"));
        assertTrue(result.contains("= Found property admin_password : test_password"));

        String grafanaFileContent = FileUtils.readFile(grafanaFile);
        //System.err.println (grafanaFileContent);

        assertTrue (grafanaFileContent.contains("admin_user = test_eskimo"));
        assertFalse (grafanaFileContent.contains("admin_user = eskimo"));

        assertTrue (grafanaFileContent.contains("admin_password = test_password"));
        assertFalse (grafanaFileContent.contains("admin_password = eskimo"));
    }

    @Test
    public void testNominalESConfig() throws Exception {

        String result = runSettingsInjectorOnService("elasticsearch");
        //logger.info(result);

        // ensure properties were found
        assertTrue(result.contains("= Found property bootstrap.memory_lock : true"));
        assertTrue(result.contains("= Found property action.destructive_requires_name : false"));

        String esFileContent = FileUtils.readFile(esFile);
        //System.err.println (esFileContent);

        assertTrue (esFileContent.contains("bootstrap.memory_lock: true"));
        assertFalse (esFileContent.contains("#bootstrap.memory_lock: true"));

        assertTrue (esFileContent.contains("action.destructive_requires_name: false"));
        assertFalse (esFileContent.contains("#action.destructive_requires_name: false"));
    }

    @Test
    public void testServiceWithoutConfig() throws Exception {

        String result = runSettingsInjectorOnService("ntp");
        logger.info(result);

        // ensure nothing's found
        assertTrue(result.contains("== finding filenames for ntp in "));
        assertTrue(result.endsWith("test-classes/settingsInjector/testConfig.json\n"));
    }


}

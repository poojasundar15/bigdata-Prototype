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

package ch.niceideas.bigdata.html;

import ch.niceideas.common.utils.ResourceUtils;
import ch.niceideas.common.utils.StreamUtils;
import ch.niceideas.bigdata.model.NodesConfigWrapper;
import ch.niceideas.bigdata.test.StandardSetupHelpers;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EskimoNodesConfigTest extends AbstractWebTest {

    @BeforeEach
    public void setUp() throws Exception {

        String jsonServices = StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoServicesSelectionTest/testServices.json"), StandardCharsets.UTF_8);

        loadScript("eskimoUtils.js");
        loadScript("eskimoNodesConfigurationChecker.js");
        loadScript("eskimoNodesConfig.js");

        waitForDefinition("window.eskimo");
        waitForDefinition("window.eskimo.NodesConfig");

        js("window.UNIQUE_SERVICES = [\"zookeeper\", \"kube-master\", ];");
        js("window.MULTIPLE_SERVICES = [\"ntp\", \"prom-node-exporter\", \"etcd\", \"kube-slave\", \"gluster\" ];");
        js("window.MANDATORY_SERVICES = [\"ntp\", \"gluster\"];");
        js("window.CONFIGURED_SERVICES = UNIQUE_SERVICES.concat(MULTIPLE_SERVICES);");

        js("window.SERVICES_CONFIGURATION = " + jsonServices + ";");

        // instantiate test object
        js("eskimoNodesConfig = new eskimo.NodesConfig()");
        js("eskimoNodesConfig.eskimoMain = eskimoMain");
        js("eskimoNodesConfig.eskimoServicesSelection =  eskimoServicesSelection");
        js("eskimoNodesConfig.eskimoServices = eskimoServices");
        js("eskimoNodesConfig.eskimoOperationsCommand = eskimoOperationsCommand");

        js("$.ajaxPost = function(callback) { console.log(callback); }");

        js("" +
                "$.ajaxGet = function (object) {\n" +
                "    if (object.url === 'list-config-services') {" +
                "        object.success({" +
                "            status: 'OK', " +
                "            uniqueServices: window.UNIQUE_SERVICES, " +
                "            multipleServices: window.MULTIPLE_SERVICES, " +
                "            mandatoryServices: window.MANDATORY_SERVICES, " +
                "            servicesConfigurations: window.SERVICES_CONFIGURATION" +
                "        });\n" +
                "    } else if (object.url === 'get-services-dependencies') {" +
                "        object.success({status: 'OK', servicesDependencies : {}});\n" +
                "    } else {\n" +
                "        console.log(object); " +
                "    } \n" +
                "}");

        js("eskimoNodesConfig.initialize()");

        waitForElementIdInDOM("reset-nodes-config");

        js("$('#inner-content-nodes-config').css('display', 'inherit')");
        js("$('#inner-content-nodes-config').css('visibility', 'visible')");
    }

    @Test
    public void testServicesConfigMethods() {

        assertJavascriptEquals("images/kube-slave-logo.png", "eskimoNodesConfig.getServiceLogoPath('kube-slave')");
        assertJavascriptEquals("images/kube-slave-icon.png", "eskimoNodesConfig.getServiceIconPath('kube-slave')");
        assertJavascriptEquals("true", "eskimoNodesConfig.isServiceUnique('zookeeper')");

        assertJavascriptEquals("false", "eskimoNodesConfig.isServiceUnique('gluster')");

        assertJavascriptEquals("undefined", "eskimoNodesConfig.getServiceIconPath('test-none')");
    }

    @Test
    public void testShowServiceSelection() {
        testRenderNodesConfig();

        getElementById("configure1").click();

        assertJavascriptEquals("1-function-false", "window.showServiceSelectionCalled");
    }

    @Test
    public void testRenderNodesConfig() {

        NodesConfigWrapper nodesConfig = StandardSetupHelpers.getStandard2NodesSetupRealWorld();

        js("eskimoNodesConfig.renderNodesConfig("+nodesConfig.getFormattedValue()+");");

        // test a few nodes
        assertJavascriptEquals("1", "$('#ntp1:checked').length");
        assertJavascriptEquals("1", "$('#etcd1:checked').length");
        assertJavascriptEquals("1", "$('#kube-slave1:checked').length");

        assertJavascriptEquals("1", "$('#ntp2:checked').length");
        assertJavascriptEquals("1", "$('#etcd2:checked').length");
        assertJavascriptEquals("1", "$('#kube-slave2:checked').length");

        assertJavascriptEquals("0", "$('#zookeeper1:checked').length");
        assertJavascriptEquals("1", "$('#zookeeper2:checked').length");

        assertJavascriptEquals("1", "$('#kube-master1:checked').length");
        assertJavascriptEquals("0", "$('#kube-master2:checked').length");
    }

    @Test
    public void testSaveNodesButton() {
        testRenderNodesConfig();

        js("window.checkNodesSetup = function (nodeSetup) {" +
                "    window.nodeSetup = nodeSetup" +
                "}");

        getElementById("save-nodes-btn").click();

        //System.err.println(js("JSON.stringify (window.nodeSetup)"));

        JSONObject expectedResult = new JSONObject("" +
                "{\"etcd1\":\"on\"," +
                "\"zookeeper\":\"2\"," +
                "\"etcd2\":\"on\"," +
                "\"node_id1\":\"192.168.10.11\"," +
                "\"node_id2\":\"192.168.10.13\"," +
                "\"kube-master\":\"1\"," +
                "\"ntp1\":\"on\"," +
                "\"prom-node-exporter2\":\"on\"," +
                "\"prom-node-exporter1\":\"on\"," +
                "\"gluster1\":\"on\"," +
                "\"ntp2\":\"on\"," +
                "\"kube-slave1\":\"on\"," +
                "\"kube-slave2\":\"on\"," +
                "\"gluster2\":\"on\"}");

        JSONObject actualResult = new JSONObject((String)js("return JSON.stringify (window.nodeSetup)"));

        System.err.println (actualResult);

        assertTrue(expectedResult.similar(actualResult));
    }

    @Test
    public void testShowNodesConfig() {
        js("eskimoNodesConfig.showNodesConfig();");

        assertCssEquals("visible" , "#inner-content-nodes-config", "visibility");
        assertCssEquals("block", "#inner-content-nodes-config", "display");
    }

    @Test
    public void testShowNodesConfigWithResetButton() {

        js("eskimoMain.isSetupDone = function () { return true; }");

        // test clear = missing
        js("$.ajaxGet = function (object) {" +
                "    object.success ({clear: \"missing\"});" +
                "}");

        getElementById("reset-nodes-config").click();

        assertTrue(getElementById("nodes-placeholder").getText().contains("(No nodes / services configured yet)"));

        // test clear = setup
        js("$.ajaxGet = function (object) {" +
                "    object.success ({clear: \"setup\"});" +
                "}");

        js("eskimoMain.handleSetupNotCompleted = function () { window.handleSetupNotCompletedCalled = true; }");

        getElementById("reset-nodes-config").click();

        assertTrue((boolean)js("return window.handleSetupNotCompletedCalled"));

        // test OK
        js("$.ajaxGet = function (object) {" +
                "    object.success ({result: \"OK\"});" +
                "}");

        js("eskimoNodesConfig.renderNodesConfig = function (config) { window.nodesConfig = config; }");

        getElementById("reset-nodes-config").click();

        assertJavascriptEquals("{\"result\":\"OK\"}", "JSON.stringify (window.nodesConfig)");
    }

    @Test
    public void testOnServiceSelectedForNode() {

        NodesConfigWrapper nodesConfig = StandardSetupHelpers.getStandard2NodesSetupRealWorld();

        js("eskimoNodesConfig.renderNodesConfig(" + nodesConfig.getFormattedValue() + ");");

        js("eskimoNodesConfig.onServicesSelectedForNode({\n" +
                "\"kube-master\": \"2\",\n" +
                "\"gluster2\": \"on\",\n" +
                "\"etcd2\": \"on\",\n" +
                "\"kube-slave2\": \"on\",\n" +
                "\"ntp2\": \"on\",\n" +
                "\"prom-node-exporter2\": \"on\",\n" +
                "}, 2)");

        assertJavascriptEquals("1", "$('#prom-node-exporter2:checked').length");

        assertJavascriptEquals("1", "$('#ntp2:checked').length");
    }


        @Test
    public void testRemoveNode() {

        // add two nodes
        js("eskimoNodesConfig.addNode()");
        js("eskimoNodesConfig.addNode()");

        // manipulate node 2
        js("$('#node_id2').attr('value', '192.168.10.11')");
        js("$('#zookeeper2').get(0).checked = true");
        js("$('#ntp2').get(0).checked = true");

        // remove node 1
        js("eskimoNodesConfig.removeNode ('remove1')");

        // ensure values are found in node 2 now as node 1
        assertAttrEquals("192.168.10.11", "#node_id1", "value");

        assertJavascriptEquals("true", "$('#zookeeper1').get(0).checked");
        assertJavascriptEquals("true", "$('#ntp1').get(0).checked");
    }

    @Test
    public void testAddNode() {

        js("eskimoNodesConfig.addNode()");

        assertTrue(getElementById("label1").getText().contains(" Node no \n" +
                "1"));

        assertNotNull (getElementById("node_id1"));
        assertTagNameEquals("input", "node_id1");

        assertNotNull (getElementById("zookeeper1"));
        assertTagNameEquals("input", "zookeeper1");

        assertNotNull (getElementById("gluster1"));
        assertTagNameEquals("input", "gluster1");

        assertNotNull (getElementById("kube-master1"));
        assertTagNameEquals("input", "kube-master1");

        assertNotNull (getElementById("etcd1"));
        assertTagNameEquals("input", "etcd1");

        assertJavascriptEquals ("1", "eskimoNodesConfig.getNodesCount()");

    }
}

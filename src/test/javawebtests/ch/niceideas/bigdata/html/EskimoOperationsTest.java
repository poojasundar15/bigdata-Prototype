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
import ch.niceideas.bigdata.utils.ActiveWaiter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.fail;

public class EskimoOperationsTest extends AbstractWebTest {

    @BeforeEach
    public void setUp() throws Exception {

        loadScript(findVendorLib ("bootstrap"));
        loadScript("eskimoOperations.js");

        waitForDefinition("window.eskimo");
        waitForDefinition("window.eskimo.Operations");

        js("window.errorHandler = function () {};");

        // instantiate test object
        js("eskimoOperations = new eskimo.Operations()");

        js("eskimoOperations.eskimoMain = eskimoMain");

        js("eskimoOperations.initialize();");

        waitForElementIdInDOM("operation-log-button-cancel");
    }

    @Test
    public void testShowOperations() {
        js("eskimoOperations.showOperations();");

        assertJavascriptEquals("true", "window.hideProgressbarCalled");

        assertCssEquals("visible", "#inner-content-operations", "visibility");
        assertCssEquals("block", "#inner-content-operations", "display");
    }

    @Test
    public void testSetOperationInProgress() {
        js("eskimoOperations.setOperationInProgress(false);");
        assertClassEquals("btn btn-danger disabled", "#interrupt-operations-btn");

        js("eskimoOperations.setOperationInProgress(true);");
        assertClassEquals("btn btn-danger", "#interrupt-operations-btn");
    }

    @Test
    public void testInterruptOperations() {

        js("$.ajaxGet = function(obj) { window.objUrl = obj.url }");

        getElementById("interrupt-operations-btn").click();

        assertJavascriptEquals("interrupt-processing", "window.objUrl ");
    }

    @Test
    public void testUpdateGlobalMessages() throws Exception {
        js("eskimoOperations.updateGlobalMessages("
                + new JSONObject(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/globalMessages.json"), StandardCharsets.UTF_8))
                + ");");

        assertJavascriptEquals(
                "--&gt; Done : Installation of Topology (All Nodes) on marathon\n" +
                        "-------------------------------------------------------------------------------\n" +
                        "--&gt; Completed Successfuly.\n",
                "$('#operations-global-messages').html()");
    }

    @Test
    public void testUpdateMessagesAndShowLogs() throws Exception {
        testRenderLabels();

        js("eskimoOperations.showLogs('Installation_Topology-All-Nodes');");

        assertJavascriptEquals("(no log received yet for operation)", "$('#log-message-content').html()");

        js("eskimoOperations.updateMessages(" +
                "[\n" +
                "  {\n" +
                "    \"operation\": \"Installation_Topology-All-Nodes\",\n" +
                "    \"label\": \"Installation of Topology (All Nodes) on kubernetes\"\n" +
                "  }]" +
                ", " +
                " { \"Installation_Topology-All-Nodes\": {\n" +
                "    \"lines\": \"\",\n" +
                "    \"lastLine\": 0\n" +
                "  } " +
                "}" +
                ");");

        js("eskimoOperations.showLogs('Installation_Topology-All-Nodes');");

        assertJavascriptEquals("(no log received yet for operation)", "$('#log-message-content').html()");

        js("eskimoOperations.updateMessages("
                + new JSONArray(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/labels.json"), StandardCharsets.UTF_8))
                + ", "
                + new JSONObject(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/messages.json"), StandardCharsets.UTF_8))
                + ");");

        js("eskimoOperations.showLogs('Installation_Topology-All-Nodes');");

        assertJavascriptEquals(
                "--&gt; Done : Installation of Topology (All Nodes) on marathon\n" +
                        "-------------------------------------------------------------------------------\n" +
                        "--&gt; Completed Successfuly.\n",
                "$('#log-message-content').html()");

        assertJavascriptEquals("true", "$('#operation-log-modal').is(':visible')");

        js("eskimoOperations.hideLogs();");

        ActiveWaiter.wait(() -> js("return $('#operation-log-modal').is(':visible')")  == Boolean.FALSE);

        assertJavascriptEquals("false", "$('#operation-log-modal').is(':visible')");
    }

    @Test
    public void testRenderStatus() throws Exception {
        testRenderLabels();

        js("eskimoOperations.renderStatus("
                + new JSONArray(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/labels.json"), StandardCharsets.UTF_8))
                + ", "
                + new JSONObject(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/status.json"), StandardCharsets.UTF_8))
                + ");");

        assertJavascriptEquals("100% (Complete)", "$('#Installation_Topology-All-Nodes-progress').html()");
    }

    @Test
    public void testRenderLabels() throws Exception {

        js("eskimoOperations.renderLabels("
                + new JSONArray(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/labels.json"), StandardCharsets.UTF_8))
                + ");");

        assertJavascriptEquals(
                "<div id=\"Installation_Topology-All-Nodes-progress\" class=\"progress-bar progress-bar-striped progress-bar-animated bg-info\" role=\"progressbar\" aria-valuenow=\"0\" aria-valuemin=\"0\" aria-valuemax=\"100\">\n" +
                        "              </div>",
                "$('#Installation_Topology-All-Nodes-progress-wrapper').html().trim()");

        assertJavascriptEquals(
                "7",
                "$('.progress-operation-wrapper').length");
    }

    @Test
    public void testFetchOperationStatus() throws Exception {

        js("$.ajaxGet = function(obj) { " +
                "    window.objUrl = obj.url;" +
                "    if (obj.url.indexOf('fetch-operations-status?last-lines=') > -1) {" +
                "        obj.success({" +
                "            result: 'OK'," +
                "            labels: " + new JSONArray(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/labels.json"), StandardCharsets.UTF_8)) +
                "                ," +
                "            globalMessages: " + new JSONObject(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/globalMessages.json"), StandardCharsets.UTF_8)) +
                "                ," +
                "            messages: " + new JSONObject(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/messages.json"), StandardCharsets.UTF_8)) +
                "                ," +
                "            status: " + new JSONObject(StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoOperationsTest/status.json"), StandardCharsets.UTF_8)) +
                "        });" +
                "    } " +
                "}");

        js("eskimoOperations.fetchOperationStatus()");

        assertJavascriptEquals("100% (Complete)", "$('#Installation_Topology-All-Nodes-progress').html()");

        assertJavascriptEquals(
                "--&gt; Done : Installation of Topology (All Nodes) on marathon\n" +
                        "-------------------------------------------------------------------------------\n" +
                        "--&gt; Completed Successfuly.\n",
                "$('#operations-global-messages').html()");

        js("eskimoOperations.showLogs('Installation_Topology-All-Nodes');");

        assertJavascriptEquals(
                "--&gt; Done : Installation of Topology (All Nodes) on marathon\n" +
                        "-------------------------------------------------------------------------------\n" +
                        "--&gt; Completed Successfuly.\n",
                "$('#log-message-content').html()");
    }

    @Test
    public void testGetLastLines() throws Exception {
        testFetchOperationStatus();

        assertJavascriptEquals("{" +
                "\"Installation_Topology-All-Nodes\":4," +
                "\"installation_cerebro\":3," +
                "\"installation_grafana\":0," +
                "\"installation_kafka-manager\":0," +
                "\"installation_kibana\":0," +
                "\"installation_spark-console\":0," +
                "\"installation_zeppelin\":0," +
                "\"global\":4" +
                "}", "JSON.stringify(eskimoOperations.getLastLines())");
    }

    @Test
    public void testStartStopOperationInprogress() {

        js("eskimoOperations.fetchOperationStatus = function(callback) {" +
                "    if (callback != null) {callback(); }" +
                "};");

        js("eskimoOperations.startOperationInProgress();");

        assertJavascriptEquals("<h3>Operations pending ....</h3>", "$('#operations-title').html()");

        js("eskimoOperations.stopOperationInProgress(false);");

        assertJavascriptEquals("<h3><span class=\"processing-error\">Operations completed in error !</span></h3>",
                "$('#operations-title').html()");

        js("eskimoOperations.startOperationInProgress();");

        assertJavascriptEquals("<h3>Operations pending ....</h3>", "$('#operations-title').html()");

        js("eskimoOperations.stopOperationInProgress(true);");

        assertJavascriptEquals("<h3>Operations completed successfully.</h3>",
                "$('#operations-title').html()");
    }

}


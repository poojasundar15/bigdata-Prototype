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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EskimoUtilsTest extends AbstractWebTest {

    @BeforeEach
    public void setUp() throws Exception {
        loadScript("eskimoUtils.js");

        waitForDefinition("window.defaultSuccess");
    }

    @Test
    public void testErrorHandler() {
        js("errorHandler({responseJSON : {message : 'test message'}})");
        assertJavascriptEquals("3 : fail : test message", "window.lastAlert");

        js("errorHandler({responseJSON : {error : 'test error'}})");
        assertJavascriptEquals("3 : fail : test error", "window.lastAlert");

        js("errorHandler({}, 'test status')");
        String consoleLogs = driver.manage().logs().get(LogType.BROWSER).getAll().stream()
                .map(LogEntry::getMessage)
                .collect(Collectors.joining(","));
        assertTrue (consoleLogs.contains("test status"));
    }

    @Test
    public void testDefaultSuccess() {
        js("defaultSuccess()");
        assertJavascriptEquals("3 : no data received - status is undefined", "window.lastAlert");

        js("defaultSuccess({error: 'test error'})");
        assertJavascriptEquals("3 : test error", "window.lastAlert");
    }

    @Test
    public void testGetHyphenSeparated() {
        assertJavascriptEquals("test-a-string", "getHyphenSeparated('testAString')");
        assertJavascriptEquals("test", "getHyphenSeparated('test')");
    }

    @Test
    public void testGetCamelCase() {
        assertJavascriptEquals("testAString", "getCamelCase('test-a-string')");
        assertJavascriptEquals("test", "getCamelCase('test')");
    }

    @Test
    public void testSerializeObject() throws Exception {

        String testForm = StreamUtils.getAsString(ResourceUtils.getResourceAsStream("EskimoUtilsTest/testForm.html"), StandardCharsets.UTF_8);
        js("$('#main-content').html('"+testForm.replace("\n", "").replace("\r", "")+"')");

        String serializedForm = js("return JSON.stringify($('form#testForm').serializeObject())").toString();
        assertEquals("{\"text1\":\"test1\",\"text2\":\"test2\",\"hidden1\":\"testHidden1\"}", serializedForm);
    }

    @Test
    public void testIsFunction() {
        assertJavascriptEquals("false", "isFunction(new Array())");
        assertJavascriptEquals("false", "isFunction(null)");
        assertJavascriptEquals("false", "isFunction(0)");
        assertJavascriptEquals("false", "isFunction(\"test\")");
        assertJavascriptEquals("false", "isFunction('test')");
        assertJavascriptEquals("false", "isFunction({})");

        assertJavascriptEquals("true", "isFunction(new Function())");
    }

}


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

import ch.niceideas.common.json.JsonWrapper;
import ch.niceideas.bigdata.model.MasterStatusWrapper;
import ch.niceideas.bigdata.model.SystemStatusWrapper;
import ch.niceideas.bigdata.security.AuthorizationException;
import ch.niceideas.bigdata.security.SecurityHelper;
import ch.niceideas.bigdata.services.*;
import ch.niceideas.bigdata.utils.ReturnStatusHelper;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.stream.Collectors;


@Controller
public class SystemStatusController {

    private static final Logger logger = Logger.getLogger(SystemStatusController.class);
    public static final String MASTERS = "masters";

    @Autowired
    private SetupService setupService;

    @Autowired
    private SystemService systemService;

    @Autowired
    private ApplicationStatusService statusService;

    @Autowired
    private MasterService masterService;

    @Autowired
    protected OperationsMonitoringService operationsMonitoringService;

    @Value("${build.version}")
    private String buildVersion = "DEV-SNAPSHOT";

    @GetMapping("/get-last-operation-result")
    @ResponseBody
    public String getLastOperationResult() {
        return ReturnStatusHelper.createOKStatus(map -> map.put("success", operationsMonitoringService.getLastOperationSuccess()));
    }

    @GetMapping("/context")
    @ResponseBody
    public String getContext() {

        try {
            JsonWrapper retObject = new JsonWrapper(new JSONObject(new HashMap<>() {{
                put("status", "OK");
                put("version", buildVersion);
                put("user", SecurityHelper.getUserId());
                put("roles", new JSONArray(SecurityHelper.getuserRoles().stream()
                        .map(Enum::name)
                        .collect(Collectors.toList())
                ));
            }}));

            return retObject.getFormattedValue();

        } catch (AuthorizationException e) {
            return ReturnStatusHelper.createErrorStatus(e);
        }
    }

    @GetMapping("/get-status")
    @ResponseBody
    public String getStatus() {

        try {
            setupService.ensureSetupCompleted();

            SystemStatusWrapper nodeServicesStatus = systemService.getStatus();

            JsonWrapper systemStatus = statusService.getStatus();

            // 6. Inject identified masters
            MasterStatusWrapper masterStatus = masterService.getMasterStatus();

            return ReturnStatusHelper.createOKStatus(map -> {
                if (nodeServicesStatus == null || nodeServicesStatus.isEmpty()) {
                    map.put("clear", "nodes");
                } else {
                    map.put("nodeServicesStatus", nodeServicesStatus.getJSONObject());
                }
                if (masterStatus.hasPath(MASTERS)) {
                    map.put(MASTERS, masterStatus.getJSONObject().getJSONObject(MASTERS));
                }
                map.put("systemStatus", systemStatus.getJSONObject());
                map.put("processingPending", operationsMonitoringService.isProcessingPending());
            });

        } catch (SetupException e) {

            // this is OK. means application is not yet initialized
            logger.debug (e.getCause(), e.getCause());
            return ReturnStatusHelper.createClearStatus("setup", operationsMonitoringService.isProcessingPending());

        } catch (SystemService.StatusExceptionWrapperException | MasterService.MasterExceptionWrapperException e) {

            if (e.getCause() instanceof SetupException) {
                // this is OK. means application is not yet initialized
                logger.debug (e.getCause(), e.getCause());
                return ReturnStatusHelper.createClearStatus("setup", operationsMonitoringService.isProcessingPending());
            } else {
                logger.debug(e.getCause(), e.getCause());
                return ReturnStatusHelper.createErrorStatus ((Exception)e.getCause());
            }
        }
    }
}

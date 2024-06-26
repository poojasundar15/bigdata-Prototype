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

package ch.niceideas.bigdata.model;

import ch.niceideas.common.json.JsonWrapper;
import ch.niceideas.bigdata.services.ServiceDefinitionException;
import ch.niceideas.bigdata.services.SystemException;
import ch.niceideas.bigdata.services.satellite.NodesConfigurationException;
import ch.niceideas.bigdata.services.satellite.ServicesInstallationSorter;
import ch.niceideas.bigdata.types.Operation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractServiceOperationsCommand
        <       T extends OperationId<? extends Operation>,
                U extends JsonWrapper>
        extends JSONInstallOpCommand<T> implements Serializable {

    private final U rawConfig;

    AbstractServiceOperationsCommand(U rawConfig) {
        this.rawConfig = rawConfig;
    }

    public U getRawConfig() {
        return rawConfig;
    }

    @Override
    public List<T> getAllOperationsInOrder
            (OperationsContext context)
            throws ServiceDefinitionException, NodesConfigurationException, SystemException {

        List<T> allOpList = new ArrayList<>(getNodesCheckOperation());
        getOperationsGroupInOrder(context.getServicesInstallationSorter(), context.getNodesConfig()).forEach(allOpList::addAll);
        return allOpList;
    }

    public abstract List<T> getNodesCheckOperation();

    public List<List<T>> getOperationsGroupInOrder
            (ServicesInstallationSorter sorter, NodesConfigWrapper nodesConfigWrapper)
            throws ServiceDefinitionException, NodesConfigurationException, SystemException {

        List<T> allOpList = new ArrayList<>();

        allOpList.addAll(getInstallations());
        allOpList.addAll(getUninstallations());
        allOpList.addAll(getRestarts());

        return sorter.orderOperations(allOpList, nodesConfigWrapper);
    }

}

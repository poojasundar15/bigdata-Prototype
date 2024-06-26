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


package ch.niceideas.bigdata.test.services;

import ch.niceideas.common.utils.FileException;
import ch.niceideas.bigdata.model.KubernetesServicesConfigWrapper;
import ch.niceideas.bigdata.model.NodesConfigWrapper;
import ch.niceideas.bigdata.model.Topology;
import ch.niceideas.bigdata.model.service.ServiceDefinition;
import ch.niceideas.bigdata.model.service.UIConfig;
import ch.niceideas.bigdata.services.ServiceDefinitionException;
import ch.niceideas.bigdata.services.ServicesDefinition;
import ch.niceideas.bigdata.services.ServicesDefinitionImpl;
import ch.niceideas.bigdata.services.SetupException;
import ch.niceideas.bigdata.services.satellite.NodesConfigurationException;
import ch.niceideas.bigdata.types.Node;
import ch.niceideas.bigdata.types.Service;
import org.json.JSONException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON, proxyMode = ScopedProxyMode.TARGET_CLASS)
@Profile("test-services")
public class ServicesDefinitionTestImpl extends ServicesDefinitionImpl implements ServicesDefinition {

    private boolean error = false;

    protected String getServicesDefinitionFile() {
        return "classpath:services-test.json";
    }

    public void reset () {
        error = false;
    }

    public void setError() {
        error = true;
    }

    protected String getKubeMasterServuce() {
        return "cluster-master";
    }

    @Override
    public void executeInEnvironmentLock(EnvironmentOperation operation) throws FileException, ServiceDefinitionException, SetupException {
        if (error) {
            throw new ServiceDefinitionException("Test error");
        }
        super.executeInEnvironmentLock(operation);
    }

    @Override
    public String getAllServicesString() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.getAllServicesString();
    }

    @Override
    public Topology getTopology(NodesConfigWrapper nodesConfig, KubernetesServicesConfigWrapper kubeServicesConfig, Node currentNode) throws ServiceDefinitionException, NodesConfigurationException {
        if (error) {
            throw new ServiceDefinitionException("Test error");
        }
        return super.getTopology(nodesConfig, kubeServicesConfig, currentNode);
    }

    @Override
    public ServiceDefinition getServiceDefinition(Service service) {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.getServiceDefinition(service);
    }

    @Override
    public Service[] listAllServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listAllServices();
    }

    @Override
    public Service[] listAllNodesServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listAllNodesServices();
    }

    @Override
    public long countAllNodesServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.countAllNodesServices();
    }

    @Override
    public Service[] listMultipleServicesNonKubernetes() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listMultipleServicesNonKubernetes();
    }

    @Override
    public Service[] listMultipleServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listMultipleServices();
    }

    @Override
    public Service[] listMandatoryServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listMandatoryServices();
    }

    @Override
    public Service[] listUniqueServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listUniqueServices();
    }

    @Override
    public Service[] listKubernetesServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listKubernetesServices();
    }

    @Override
    public long countKubernetesServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.countKubernetesServices();
    }

    @Override
    public Service[] listProxiedServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listProxiedServices();
    }

    @Override
    public Service[] listUIServices() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listUIServices();
    }

    @Override
    public Map<Service, UIConfig> getUIServicesConfig() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.getUIServicesConfig();
    }

    @Override
    public Service[] listServicesInOrder() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listServicesInOrder();
    }

    @Override
    public Service[] listServicesOrderedByDependencies() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listServicesOrderedByDependencies();
    }

    @Override
    public Service[] listKubernetesServicesOrderedByDependencies() {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.listKubernetesServicesOrderedByDependencies();
    }

    @Override
    public int compareServices(ServiceDefinition one, ServiceDefinition other) {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.compareServices(one, other);
    }

    @Override
    public int compareServices(Service servOne, Service servOther) {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.compareServices(servOne, servOther);
    }

    @Override
    public Collection<Service> getDependentServices(Service service) {
        if (error) {
            throw new JSONException("Test error");
        }
        return super.getDependentServices(service);
    }
}

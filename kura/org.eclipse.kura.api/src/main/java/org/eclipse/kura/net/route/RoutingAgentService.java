/*******************************************************************************
 * Copyright (c) 2011, 2024 Eurotech and/or its affiliates and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Eurotech
 ******************************************************************************/
package org.eclipse.kura.net.route;

import java.util.Map;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.net.NetInterfaceConfig;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Routing agent service is used to control the static routing table. The
 * service is used to specify
 * which interfaces should be used in considering routes and what their
 * priorities should be.
 *
 * @noimplement This interface is not intended to be implemented by clients.
 * @deprecated since 3.0
 */
@ProviderType
@Deprecated
public interface RoutingAgentService {

    /**
     * Sets interface priorities
     *
     * @param priorities
     *                   - list of interface priorities as {@link Map}
     */
    public void setPriorities(Map<String, Integer> priorities);

    /**
     * Adds interface to RoutingAgent
     *
     * @param netIfaceConfig
     *                       - interface configuration as {@link NetInterfaceConfig}
     * @throws KuraException
     */
    @SuppressWarnings("rawtypes")
    public void addInterface(NetInterfaceConfig netIfaceConfig) throws KuraException;

    /**
     * Removes interface from RoutingAgent
     *
     * @param interfaceName
     *                      - interface name as {@link String}
     * @throws KuraException
     */
    void removeInterface(String interfaceName) throws KuraException;

    /**
     * Get the default gateway
     *
     * @return interfaceName - interface name as {@link String}
     * @throws KuraException
     */
    public String getDefaultGateway() throws KuraException;
}

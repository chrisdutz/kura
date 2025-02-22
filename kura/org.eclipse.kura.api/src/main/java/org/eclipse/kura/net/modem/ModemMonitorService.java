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
package org.eclipse.kura.net.modem;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Marker interface for the ModemMonitor
 *
 * @noimplement This interface is not intended to be implemented by clients.
 * @deprecated since 3.0
 */
@ProviderType
@Deprecated
public interface ModemMonitorService {

    public void registerListener(ModemMonitorListener listener);

    public void unregisterListener(ModemMonitorListener listener);
}

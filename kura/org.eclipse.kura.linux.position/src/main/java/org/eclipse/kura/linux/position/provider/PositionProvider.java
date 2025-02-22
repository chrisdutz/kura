/*******************************************************************************
 * Copyright (c) 2022, 2024 Eurotech and/or its affiliates and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Eurotech
 *******************************************************************************/

package org.eclipse.kura.linux.position.provider;

import java.time.LocalDateTime;
import java.util.Set;

import org.eclipse.kura.linux.position.options.PositionServiceOptions;
import org.eclipse.kura.position.GNSSType;
import org.eclipse.kura.position.NmeaPosition;
import org.osgi.util.position.Position;

public interface PositionProvider {

    public void start();

    public void stop();

    public Position getPosition();

    public NmeaPosition getNmeaPosition();

    public String getNmeaTime();

    public String getNmeaDate();

    public LocalDateTime getDateTime();

    public boolean isLocked();

    public String getLastSentence();

    public void init(PositionServiceOptions configuration, LockStatusListener gpsDeviceListener,
            GpsDeviceAvailabilityListener gpsDeviceAvailabilityListener);

    public PositionProviderType getType();

    public Set<GNSSType> getGnssTypes();

}

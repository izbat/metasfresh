package org.adempiere.location.geocoding.interceptor;

import com.google.common.base.Joiner;
import de.metas.adempiere.model.I_C_Location;
import de.metas.event.IEventBusFactory;
import de.metas.event.Topic;
import de.metas.util.Services;
import org.adempiere.ad.modelvalidator.annotations.Interceptor;
import org.adempiere.ad.modelvalidator.annotations.ModelChange;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.location.LocationId;
import org.adempiere.location.geocoding.GeographicalCoordinates;
import org.adempiere.location.geocoding.GeographicalCoordinatesProvider;
import org.adempiere.location.geocoding.GeographicalCoordinatesRequest;
import org.compiere.model.ModelValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/*
 * #%L
 * metasfresh-pharma
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Component("org.adempiere.location.geocoding.interceptor.C_Location")
@Interceptor(I_C_Location.class)
public class C_Location
{
	static final Topic EVENTS_TOPIC = Topic.remote("org.adempiere.location.geocoding.events");

	private final IEventBusFactory eventBusFactory;

	public C_Location(final IEventBusFactory eventBusFactory)
	{
		this.eventBusFactory = eventBusFactory;
	}

	@ModelChange(timings = ModelValidator.TYPE_AFTER_NEW)
	public void onNewLocation(final I_C_Location locationRecord)
	{
		final LocationId locationId = LocationId.ofRepoId(locationRecord.getC_Location_ID());

		Services.get(ITrxManager.class)
				.runAfterCommit(()-> fireLocationGeoUpdateRequest(locationId));
	}

	private void fireLocationGeoUpdateRequest(final LocationId locationId)
	{
		eventBusFactory
				.getEventBus(EVENTS_TOPIC)
				.postObject(LocationGeoUpdateRequest.of(locationId));
	}
}

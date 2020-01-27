package de.metas.rest_api.ordercandidates.impl;

import static de.metas.util.Check.isEmpty;
import static de.metas.util.lang.CoalesceUtil.coalesce;
import static org.adempiere.model.InterfaceWrapperHelper.isNew;
import static org.adempiere.model.InterfaceWrapperHelper.newInstance;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.I_AD_User;
import org.compiere.model.I_C_BPartner;
import org.compiere.model.I_C_BPartner_Location;
import org.compiere.model.I_C_Location;
import de.metas.bpartner.BPartnerContactId;
import de.metas.bpartner.BPartnerId;
import de.metas.bpartner.BPartnerLocationId;
import de.metas.bpartner.GLN;
import de.metas.bpartner.service.BPartnerInfo;
import de.metas.bpartner.service.BPartnerQuery;
import de.metas.bpartner.service.IBPartnerDAO;
import de.metas.bpartner.service.IBPartnerDAO.BPartnerLocationQuery;
import de.metas.bpartner.service.IBPartnerDAO.BPartnerLocationQuery.Type;
import de.metas.cache.CCache;
import de.metas.location.CountryId;
import de.metas.location.ICountryDAO;
import de.metas.location.ILocationDAO;
import de.metas.location.LocationCreateRequest;
import de.metas.location.LocationId;
import de.metas.organization.IOrgDAO;
import de.metas.organization.OrgId;
import de.metas.organization.OrgInfoUpdateRequest;
import de.metas.rest_api.bpartner.impl.BPartnerMasterDataContext;
import de.metas.rest_api.bpartner.request.JsonRequestBPartner;
import de.metas.rest_api.bpartner.request.JsonRequestContact;
import de.metas.rest_api.bpartner.request.JsonRequestLocation;
import de.metas.rest_api.bpartner.response.JsonResponseBPartner;
import de.metas.rest_api.bpartner.response.JsonResponseContact;
import de.metas.rest_api.bpartner.response.JsonResponseLocation;
import de.metas.rest_api.common.JsonExternalId;
import de.metas.rest_api.common.MetasfreshId;
import de.metas.rest_api.common.SyncAdvise;
import de.metas.rest_api.ordercandidates.request.JsonRequestBPartnerLocationAndContact;
import de.metas.rest_api.utils.JsonExternalIds;
import de.metas.rest_api.utils.MissingPropertyException;
import de.metas.rest_api.utils.MissingResourceException;
import de.metas.rest_api.utils.PermissionService;
import de.metas.util.Check;
import de.metas.util.Services;
import de.metas.util.lang.ExternalId;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * de.metas.ordercandidate.rest-api-impl
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

final class BPartnerMasterDataProvider
{
	//
	// Services
	private final IBPartnerDAO bpartnerDAO = Services.get(IBPartnerDAO.class);
	private final ILocationDAO locationsRepo = Services.get(ILocationDAO.class);
	private final ICountryDAO countryRepo = Services.get(ICountryDAO.class);
	private final IOrgDAO orgDAO = Services.get(IOrgDAO.class);
	private final PermissionService permissionService;

	//
	// Caches
	private final Map<ExternalIdAndGLN, BPartnerLocationId> bpartnerLocationIdsByExternalIdAndGLN = new HashMap<>();

	/** Caching key for bpartnerLocationIdsByExternalIdAndGLN */
	@Value
	private static class ExternalIdAndGLN
	{
		JsonExternalId externalId;
		GLN gln;
	}

	private final Map<JsonExternalId, BPartnerContactId> bpartnerContactIdsByExternalId = new HashMap<>();

	BPartnerMasterDataProvider(@NonNull final PermissionService permissionService)
	{
		this.permissionService = permissionService;
	}

	public BPartnerInfo getCreateOrgBPartnerInfo(
			@NonNull final JsonRequestBPartnerLocationAndContact jsonBPartnerInfo,
			@NonNull final OrgId orgId)
	{
		final BPartnerMasterDataContext context = BPartnerMasterDataContext
				.builder()
				.orgId(orgId)
				.bPartnerIsOrgBP(true)
				.build();

		return handleBPartnerInfoWithContext(jsonBPartnerInfo, context);
	}

	public BPartnerInfo getCreateBPartnerInfo(
			@Nullable final JsonRequestBPartnerLocationAndContact jsonBPartnerInfo,
			@Nullable final OrgId orgId)
	{
		if (jsonBPartnerInfo == null)
		{
			return null;
		}

		final BPartnerMasterDataContext context = BPartnerMasterDataContext.ofOrg(orgId);

		return handleBPartnerInfoWithContext(jsonBPartnerInfo, context);
	}

	@Value
	private static class CachingKey
	{
		OrgId orgId;
		JsonRequestBPartnerLocationAndContact jsonBPartnerInfo;
	}

	private final CCache<CachingKey, BPartnerInfo> bpartnerInfoCache = CCache
			.<CachingKey, BPartnerInfo> builder()
			.cacheName(this.getClass().getSimpleName() + "-BPartnerInfoCache")
			.build();

	private BPartnerInfo handleBPartnerInfoWithContext(
			@NonNull final JsonRequestBPartnerLocationAndContact jsonBPartnerInfo,
			@NonNull final BPartnerMasterDataContext context)
	{
		final CachingKey key = new CachingKey(context.getOrgId(), jsonBPartnerInfo);
		return bpartnerInfoCache.getOrLoad(key, () -> handleBPartnerInfoWithContext0(jsonBPartnerInfo, context));
	}

	private BPartnerInfo handleBPartnerInfoWithContext0(
			@NonNull final JsonRequestBPartnerLocationAndContact jsonBPartnerInfo,
			@NonNull final BPartnerMasterDataContext context)
	{
		try
		{
			final BPartnerId bpartnerId = handleBPartner(
					jsonBPartnerInfo,
					context.withSyncAdviseIfNotNull(jsonBPartnerInfo.getSyncAdvise()));

			final BPartnerLocationId locationId = handleLocation(
					jsonBPartnerInfo,
					context
							.withSyncAdviseIfNotNull(jsonBPartnerInfo.getSyncAdvise())
							.withBPartnerIdIfNotNull(bpartnerId));

			final BPartnerContactId contactId = handleContact(
					jsonBPartnerInfo.getContact(),
					context
							.withSyncAdviseIfNotNull(jsonBPartnerInfo.getSyncAdvise())
							.withBPartnerIdIfNotNull(bpartnerId));

			return BPartnerInfo.builder()
					.bpartnerId(bpartnerId)
					.bpartnerLocationId(locationId)
					.contactId(contactId)
					.build();
		}
		catch (final MissingResourceException e)
		{
			throw e.setParameter("parentResource", jsonBPartnerInfo); // augment and rethrow
		}
	}

	private BPartnerId handleBPartner(
			@NonNull final JsonRequestBPartnerLocationAndContact jsonBPartnerInfo, // both bpartner and location might be needed for lookup
			@NonNull final BPartnerMasterDataContext context)
	{
		final JsonRequestBPartner jsonBPartner = jsonBPartnerInfo.getBpartner();
		final SyncAdvise syncAdviseEff = coalesce(jsonBPartner.getSyncAdvise(), context.getSyncAdvise());

		final BPartnerId bpartnerId = lookupBPartnerIdOrNull(jsonBPartnerInfo, context);
		if (bpartnerId == null)
		{
			switch (syncAdviseEff.getIfNotExists())
			{
				case FAIL:
					throw MissingResourceException.builder().resourceName("bpartner").build();
				case CREATE:
					return getCreateBPartnerId(jsonBPartner, context);
				default:
					Check.fail("Unexpected IfNotExists={}", syncAdviseEff.getIfNotExists());
					return null;
			}
		}
		else
		{
			switch (syncAdviseEff.getIfExists())
			{
				case DONT_UPDATE:
					return bpartnerId;
				case UPDATE_MERGE:
				case UPDATE_REMOVE:
					return getCreateBPartnerId(jsonBPartner,
							context
									.withBPartnerIdIfNotNull(bpartnerId)
									.withSyncAdviseIfNotNull(syncAdviseEff));
				default:
					Check.fail("Unexpected IfNotExists={}", syncAdviseEff.getIfNotExists());
					return null;
			}
		}
	}

	/**
	 * @param jsonLocation if it is {@code null}, metasfresh will try to supplement an existing location from masterdata
	 */
	private BPartnerLocationId handleLocation(
			@Nullable final JsonRequestBPartnerLocationAndContact jsonBPartnerInfo,
			@NonNull final BPartnerMasterDataContext context)
	{
		final JsonRequestLocation jsonLocation = jsonBPartnerInfo.getLocation();

		final SyncAdvise syncAdviseEff = coalesce(
				jsonLocation == null ? null : jsonLocation.getSyncAdvise(),
				context.getSyncAdvise());

		final BPartnerLocationId locationId = lookupBPartnerLocationIdOrNull(jsonLocation, context);
		if (locationId == null)
		{
			switch (syncAdviseEff.getIfNotExists())
			{
				case FAIL:
					throw MissingResourceException.builder().resourceName("location").build();
				case CREATE:
					if (jsonLocation == null)
					{
						throw new MissingPropertyException("location", jsonBPartnerInfo);
					}
					return getCreateBPartnerLocationId(jsonLocation, context);
				default:
					Check.fail("Unexpected IfNotExists={}", syncAdviseEff.getIfNotExists());
					return null;
			}
		}
		else
		{
			switch (syncAdviseEff.getIfExists())
			{
				case DONT_UPDATE:
					return locationId;
				case UPDATE_MERGE:
				case UPDATE_REMOVE:
					return getCreateBPartnerLocationId(
							jsonLocation,
							context
									.withSyncAdviseIfNotNull(syncAdviseEff)
									.withLocationIdIfNotNull(locationId));
				default:
					Check.fail("Unexpected IfNotExists={}", syncAdviseEff.getIfNotExists());
					return null;
			}
		}
	}

	private BPartnerContactId handleContact(
			@Nullable final JsonRequestContact jsonContact,
			@NonNull final BPartnerMasterDataContext context)
	{
		if (jsonContact == null)
		{
			return null;
		}

		final SyncAdvise syncAdviseEff = coalesce(jsonContact.getSyncAdvise(), context.getSyncAdvise());

		final BPartnerContactId contactId = lookupContactIdOrNull(jsonContact, context);
		if (contactId == null)
		{
			switch (syncAdviseEff.getIfNotExists())
			{
				case FAIL:
					throw MissingResourceException.builder().resourceName("contact").build();
				case CREATE:
					return getCreateBPartnerContactId(jsonContact, context);
				default:
					Check.fail("Unexpected IfNotExists={}", syncAdviseEff.getIfNotExists());
					return null;
			}
		}
		else
		{
			switch (syncAdviseEff.getIfExists())
			{
				case DONT_UPDATE:
					return contactId;
				case UPDATE_MERGE:
				case UPDATE_REMOVE:
					return getCreateBPartnerContactId(
							jsonContact,
							context
									.withSyncAdviseIfNotNull(syncAdviseEff)
									.withContactIdIfNotNull(contactId));
				default:
					Check.fail("Unexpected IfNotExists={}", syncAdviseEff.getIfNotExists());
					return null;
			}
		}
	}

	private BPartnerId lookupBPartnerIdOrNull(
			@NonNull final JsonRequestBPartnerLocationAndContact jsonBPartnerInfo,
			@NonNull final BPartnerMasterDataContext context)
	{
		final BPartnerQuery query = createBPartnerQuery(jsonBPartnerInfo, context.getOrgId());
		{
			final BPartnerId bpartnerId = bpartnerDAO.retrieveBPartnerIdBy(query).orElse(null);
			if (bpartnerId != null)
			{
				return bpartnerId;
			}
		}

		if (!query.getGlns().isEmpty())
		{
			final BPartnerQuery queryWithoutGLN = query.withNoGLNs();
			final BPartnerId bpartnerId = !queryWithoutGLN.isEmpty()
					? bpartnerDAO.retrieveBPartnerIdBy(query).orElse(null)
					: null;
			if (bpartnerId != null)
			{
				return bpartnerId;
			}
		}

		return null;
	}

	private static BPartnerQuery createBPartnerQuery(
			@NonNull final JsonRequestBPartnerLocationAndContact jsonBPartnerInfo,
			@NonNull final OrgId orgId)
	{
		final JsonRequestBPartner bpartnerJson = jsonBPartnerInfo.getBpartner();

		final SyncAdvise syncAdvise = jsonBPartnerInfo.getSyncAdvise();

		final BPartnerQuery.BPartnerQueryBuilder query = BPartnerQuery.builder()
				.onlyOrgId(orgId)
				.onlyOrgId(OrgId.ANY)
				.outOfTrx(syncAdvise.isLoadReadOnly())
				.failIfNotExists(syncAdvise.isFailIfNotExists());

		if (bpartnerJson != null && bpartnerJson.getExternalId() != null)
		{
			final ExternalId externalId = JsonExternalIds.toExternalIdOrNull(bpartnerJson.getExternalId());
			query.externalId(externalId);
		}
		if (bpartnerJson != null && bpartnerJson.getCode() != null)
		{
			query.bpartnerValue(bpartnerJson.getCode());
		}

		final JsonRequestLocation jsonLocation = jsonBPartnerInfo.getLocation();
		if (jsonLocation != null && !isEmpty(jsonLocation.getGln(), true))
		{
			query.gln(GLN.ofString(jsonLocation.getGln()));
		}

		return query.build();
	}

	private BPartnerLocationId lookupBPartnerLocationIdOrNull(
			@Nullable final JsonRequestLocation jsonBPartnerLocation,
			@NonNull final BPartnerMasterDataContext context)
	{
		final BPartnerId bpartnerId = context.getBpartnerId();

		if (jsonBPartnerLocation == null) // no JSON-location-spec was provided at all
		{
			final BPartnerLocationId ctxLocationId = context.getLocationId();
			if (ctxLocationId != null)
			{
				return ctxLocationId; // we already have one in our ctx, so let's go with that
			}

			final BPartnerLocationId bpLocationId = bpartnerDAO // see if we can find something in the DB
					.retrieveBPartnerLocationId(BPartnerLocationQuery.builder()
							.bpartnerId(bpartnerId)
							.type(Type.SHIP_TO)
							.applyTypeStrictly(false) // if there is no "ShipTo", then take what we get
							.build());
			return bpLocationId; // we don't have anything else, so return it even if null
		}

		BPartnerLocationId existingBPLocationId = null;

		if (jsonBPartnerLocation != null
				&& jsonBPartnerLocation.getExternalId() != null)
		{
			existingBPLocationId = bpartnerDAO
					.getBPartnerLocationIdByExternalId(
							bpartnerId,
							JsonExternalIds.toExternalIdOrNull(jsonBPartnerLocation.getExternalId()))
					.orElse(null);
		}
		if (existingBPLocationId == null // locationId not yet found
				&& jsonBPartnerLocation != null
				&& jsonBPartnerLocation.getGln() != null)
		{
			existingBPLocationId = bpartnerDAO
					.getBPartnerLocationIdByGln(
							bpartnerId,
							GLN.ofString(jsonBPartnerLocation.getGln()))
					.orElse(null);
		}

		return existingBPLocationId;
	}

	private BPartnerContactId lookupContactIdOrNull(
			@Nullable final JsonRequestContact jsonBPartnerContact,
			@NonNull final BPartnerMasterDataContext context)
	{
		final BPartnerId bpartnerId = context.getBpartnerId();

		final BPartnerContactId existingContactId;
		if (context.getContactId() != null)
		{
			existingContactId = context.getContactId();
		}
		else if (jsonBPartnerContact != null && jsonBPartnerContact.getExternalId() != null)
		{
			existingContactId = bpartnerDAO
					.getContactIdByExternalId(
							bpartnerId,
							JsonExternalIds.toExternalIdOrNull(jsonBPartnerContact.getExternalId()))
					.orElse(null);
		}
		else
		{
			existingContactId = null;
		}
		return existingContactId;
	}

	private BPartnerId getCreateBPartnerId(
			@NonNull final JsonRequestBPartner json,
			@NonNull final BPartnerMasterDataContext context)
	{
		final BPartnerId existingBPartnerId = context.getBpartnerId();

		final I_C_BPartner bpartnerRecord;
		if (existingBPartnerId != null)
		{
			bpartnerRecord = bpartnerDAO.getByIdInTrx(existingBPartnerId);
			if (bpartnerRecord == null)
			{
				throw new AdempiereException("@NotFound@ @C_BPartner_ID@: " + existingBPartnerId);
			}
		}
		else
		{
			bpartnerRecord = newInstance(I_C_BPartner.class);
			bpartnerRecord.setAD_Org_ID(context.getOrgId().getRepoId());
			if (context.isBPartnerIsOrgBP())
			{
				bpartnerRecord.setAD_OrgBP_ID(context.getOrgId().getRepoId());
			}
		}

		updateBPartnerRecord(bpartnerRecord, json);
		permissionService.assertCanCreateOrUpdate(bpartnerRecord);
		bpartnerDAO.save(bpartnerRecord);

		return BPartnerId.ofRepoId(bpartnerRecord.getC_BPartner_ID());
	}

	private void updateBPartnerRecord(
			@NonNull final I_C_BPartner bpartnerRecord,
			@NonNull final JsonRequestBPartner from)
	{
		final JsonExternalId externalId = from.getExternalId();
		if (externalId != null)
		{
			bpartnerRecord.setExternalId(externalId.getValue());
		}

		final String code = from.getCode();
		final boolean isNew = isNew(bpartnerRecord);

		if (!Check.isEmpty(code, true))
		{
			bpartnerRecord.setValue(code);
		}

		final String name = from.getName();
		if (!Check.isEmpty(name, true))
		{
			bpartnerRecord.setName(name);
			if (Check.isEmpty(bpartnerRecord.getCompanyName(), true))
			{
				bpartnerRecord.setCompanyName(name);
			}
		}
		else if (isNew)
		{
			throw new MissingPropertyException("JsonBPartner.name", from);
		}

		bpartnerRecord.setIsCustomer(true);
	}

	public JsonResponseBPartner getJsonBPartnerById(@NonNull final BPartnerId bpartnerId)
	{
		final I_C_BPartner record = bpartnerDAO.getById(bpartnerId);
		Check.assumeNotNull(record, "bpartner shall exist for {}", bpartnerId);

		return JsonResponseBPartner.builder()
				.metasfreshId(MetasfreshId.of(record.getC_BPartner_ID()))
				.code(record.getValue())
				.externalId(JsonExternalId.ofOrNull(record.getExternalId()))
				.name(record.getName())
				.companyName(record.getCompanyName())
				.active(record.isActive())
				.vendor(record.isVendor())
				.customer(record.isCustomer())
				.build();
	}

	private BPartnerLocationId getCreateBPartnerLocationId(
			@Nullable final JsonRequestLocation json,
			@NonNull final BPartnerMasterDataContext context)
	{
		if (json == null)
		{
			return null;
		}
		final ExternalIdAndGLN cachingKey = new ExternalIdAndGLN(json.getExternalId(), GLN.ofNullableString(json.getGln()));
		return bpartnerLocationIdsByExternalIdAndGLN
				.compute(
						cachingKey,
						(key, existingBPLocationId) -> createOrUpdateBPartnerLocationId(json, context.withLocationIdIfNotNull(existingBPLocationId)));

	}

	private BPartnerLocationId createOrUpdateBPartnerLocationId(
			@NonNull final JsonRequestLocation jsonBPartnerLocation,
			@NonNull final BPartnerMasterDataContext context)
	{
		final BPartnerId bpartnerId = Check.assumeNotNull(context.getBpartnerId(),
				"The given context needs to contain a bpartnerId when this method is called; context={}, jsonBPartnerLocation={}",
				context, jsonBPartnerLocation);

		final BPartnerLocationId existingBPLocationId = context.getLocationId();
		final OrgId orgId = context.getOrgId();

		final I_C_BPartner_Location bpLocationRecord;
		if (existingBPLocationId != null)
		{
			bpLocationRecord = bpartnerDAO.getBPartnerLocationByIdInTrx(existingBPLocationId);
		}
		else
		{
			bpLocationRecord = newInstance(I_C_BPartner_Location.class);
			bpLocationRecord.setAD_Org_ID(orgId.getRepoId());
		}

		updateBPartnerLocationRecord(bpLocationRecord, context, jsonBPartnerLocation);
		permissionService.assertCanCreateOrUpdate(bpLocationRecord);
		bpartnerDAO.save(bpLocationRecord);
		final BPartnerLocationId bpartnerLocationId = BPartnerLocationId.ofRepoId(bpartnerId, bpLocationRecord.getC_BPartner_Location_ID());

		if (context.isBPartnerIsOrgBP())
		{
			orgDAO.createOrUpdateOrgInfo(OrgInfoUpdateRequest.builder()
					.orgId(orgId)
					.orgBPartnerLocationId(Optional.of(bpartnerLocationId))
					.build());
		}

		return bpartnerLocationId;
	}

	private void updateBPartnerLocationRecord(
			@NonNull final I_C_BPartner_Location bpLocationRecord,
			@NonNull final BPartnerMasterDataContext context,
			@NonNull final JsonRequestLocation jsonBPartnerLocation)
	{
		bpLocationRecord.setC_BPartner_ID(context.getBpartnerId().getRepoId());
		bpLocationRecord.setIsShipTo(true);
		bpLocationRecord.setIsBillTo(true);

		final boolean isUpdateRemove = context.getSyncAdvise().getIfExists().isUpdateRemove();

		// name
		if (!isEmpty(jsonBPartnerLocation.getName(), true))
		{
			bpLocationRecord.setName(jsonBPartnerLocation.getName().trim());
		}
		else if (isUpdateRemove)
		{
			bpLocationRecord.setName(null);
		}

		// bpartnerName
		if (!isEmpty(jsonBPartnerLocation.getBpartnerName(), true))
		{
			bpLocationRecord.setBPartnerName(jsonBPartnerLocation.getBpartnerName().trim());
		}
		else if (isUpdateRemove)
		{
			bpLocationRecord.setBPartnerName(null);
		}

		bpLocationRecord.setGLN(jsonBPartnerLocation.getGln());
		if (jsonBPartnerLocation.getExternalId() != null)
		{
			bpLocationRecord.setExternalId(jsonBPartnerLocation.getExternalId().getValue());
		}

		final String countryCode = jsonBPartnerLocation.getCountryCode();
		if (Check.isEmpty(countryCode))
		{
			throw new MissingPropertyException("JsonBPartnerLocation.countryCode", jsonBPartnerLocation);
		}
		final CountryId countryId = countryRepo.getCountryIdByCountryCode(countryCode);

		final LocationId locationId = locationsRepo.createLocation(LocationCreateRequest.builder()
				.address1(jsonBPartnerLocation.getAddress1())
				.address2(jsonBPartnerLocation.getAddress2())
				.address3(jsonBPartnerLocation.getAddress3())
				.address4(jsonBPartnerLocation.getAddress4())
				.postal(jsonBPartnerLocation.getPostal())
				.city(jsonBPartnerLocation.getCity())
				.countryId(countryId)
				.build());

		bpLocationRecord.setC_Location_ID(locationId.getRepoId());
	}

	public JsonResponseLocation getJsonBPartnerLocationById(final BPartnerLocationId bpartnerLocationId)
	{
		if (bpartnerLocationId == null)
		{
			return null;
		}

		final I_C_BPartner_Location bpLocationRecord = bpartnerDAO.getBPartnerLocationById(bpartnerLocationId);
		if (bpLocationRecord == null)
		{
			return null;
		}

		return toJsonBPartnerLocation(bpLocationRecord);
	}

	private JsonResponseLocation toJsonBPartnerLocation(@NonNull final I_C_BPartner_Location record)
	{
		final I_C_Location location = record.getC_Location();
		Check.assumeNotNull(location, "The given bpLocationRecord needs to have a C_Location; bpLocationRecord={}", record);

		final CountryId countryId = CountryId.ofRepoId(location.getC_Country_ID());
		final String countryCode = countryRepo.retrieveCountryCode2ByCountryId(countryId);

		return JsonResponseLocation
				.builder()
				.metasfreshId(MetasfreshId.of(record.getC_BPartner_Location_ID()))
				.externalId(JsonExternalId.ofOrNull(record.getExternalId()))
				.gln(record.getGLN())
				.address1(location.getAddress1())
				.address2(location.getAddress2())
				.postal(location.getPostal())
				.city(location.getCity())
				.region(location.getRegionName())
				.countryCode(countryCode)
				.bpartnerName(record.getBPartnerName())
				//
				.active(record.isActive())
				//
				.build();
	}

	private BPartnerContactId getCreateBPartnerContactId(
			@Nullable final JsonRequestContact json,
			@NonNull final BPartnerMasterDataContext context)
	{
		if (json == null)
		{
			return null;
		}

		return bpartnerContactIdsByExternalId
				.compute(
						json.getExternalId(),
						(externalId, existingContactId) -> createOrUpdateBPartnerContactId(json, context.withContactIdIfNotNull(existingContactId)));
	}

	private BPartnerContactId createOrUpdateBPartnerContactId(
			@NonNull final JsonRequestContact jsonBPartnerContact,
			@NonNull final BPartnerMasterDataContext context)
	{
		final BPartnerId bpartnerId = Check.assumeNotNull(context.getBpartnerId(),
				"The given context needs to contain a bpartnerId when this method is called; context={}, jsonBPartnerLocation={}",
				context, jsonBPartnerContact);

		final BPartnerContactId existingContactId = context.getContactId();

		I_AD_User contactRecord;
		if (existingContactId != null)
		{
			contactRecord = bpartnerDAO.getContactByIdInTrx(existingContactId);
		}
		else
		{
			contactRecord = newInstance(I_AD_User.class);
			contactRecord.setAD_Org_ID(context.getOrgId().getRepoId());
		}

		updateBPartnerContactRecord(contactRecord, bpartnerId, jsonBPartnerContact);
		permissionService.assertCanCreateOrUpdate(contactRecord);
		bpartnerDAO.save(contactRecord);

		return BPartnerContactId.ofRepoId(bpartnerId, contactRecord.getAD_User_ID());
	}

	private void updateBPartnerContactRecord(final I_AD_User bpContactRecord, final BPartnerId bpartnerId, final JsonRequestContact json)
	{
		bpContactRecord.setC_BPartner_ID(bpartnerId.getRepoId());
		bpContactRecord.setName(json.getName());
		bpContactRecord.setEMail(json.getEmail());
		bpContactRecord.setPhone(json.getPhone());
		bpContactRecord.setExternalId(json.getExternalId().getValue());
		bpContactRecord.setFirstname(json.getFirstName());
		bpContactRecord.setLastname(json.getLastName());
		bpContactRecord.setPhone(json.getPhone());
	}

	public JsonResponseContact getJsonBPartnerContactById(final BPartnerContactId bpartnerContactId)
	{
		if (bpartnerContactId == null)
		{
			return null;
		}

		final I_AD_User bpContactRecord = bpartnerDAO.getContactById(bpartnerContactId);
		if (bpContactRecord == null)
		{
			return null;
		}

		return JsonResponseContact
				.builder()
				.externalId(JsonExternalId.ofOrNull(bpContactRecord.getExternalId()))
				.name(bpContactRecord.getName())
				.email(bpContactRecord.getEMail())
				.phone(bpContactRecord.getPhone())
				.build();
	}
}

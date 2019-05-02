package de.metas.dataentry.rest_api;

import com.google.common.collect.ImmutableList;
import de.metas.Profiles;
import de.metas.bpartner.BPartnerId;
import de.metas.bpartner.service.IBPartnerDAO;
import de.metas.dataentry.DataEntrySubGroupId;
import de.metas.dataentry.data.DataEntryRecord;
import de.metas.dataentry.data.DataEntryRecordRepository;
import de.metas.dataentry.data.DataEntryRecordRepository.DataEntryRecordQuery;
import de.metas.dataentry.layout.DataEntryField;
import de.metas.dataentry.layout.DataEntryGroup;
import de.metas.dataentry.layout.DataEntryLayoutRepository;
import de.metas.dataentry.layout.DataEntryLine;
import de.metas.dataentry.layout.DataEntrySection;
import de.metas.dataentry.layout.DataEntrySubGroup;
import de.metas.dataentry.rest_api.dto.JsonData;
import de.metas.dataentry.rest_api.dto.JsonDataEntryField;
import de.metas.dataentry.rest_api.dto.JsonDataEntryGroup;
import de.metas.dataentry.rest_api.dto.JsonDataEntryLine;
import de.metas.dataentry.rest_api.dto.JsonDataEntrySection;
import de.metas.dataentry.rest_api.dto.JsonDataEntrySubGroup;
import de.metas.util.Services;
import de.metas.util.web.MetasfreshRestAPIConstants;
import lombok.NonNull;
import org.adempiere.ad.element.api.AdWindowId;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.model.I_C_BPartner;
import org.compiere.util.Env;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

@Profile(Profiles.PROFILE_App)
@RestController
@RequestMapping(DataEntryRestController.ENDPOINT)
public class DataEntryRestController
{
	public static final String ENDPOINT = MetasfreshRestAPIConstants.ENDPOINT_API + "/dataentry";

	private static AdWindowId bpartnerWindowId = AdWindowId.ofRepoId(123); // FIXME: HARDCODED for now

	private final DataEntryLayoutRepository layoutRepo;
	private final DataEntryRecordRepository recordRepo;

	public DataEntryRestController(
			@NonNull final DataEntryLayoutRepository layoutRepo,
			@NonNull final DataEntryRecordRepository recordRepo)
	{
		this.layoutRepo = layoutRepo;
		this.recordRepo = recordRepo;
	}

	@GetMapping("/byBPartnerValue/{bpartnerValue}")
	public JsonData getByBPartnerValue(@PathVariable("bpartnerValue") final String bpartnerValue)
	{
		final String adLanguage = Env.getAD_Language();

		final ImmutableList<DataEntryGroup> layout = layoutRepo.getByWindowId(bpartnerWindowId);

		final BPartnerId bpartnerId = Services.get(IBPartnerDAO.class).getBPartnerIdByValue(bpartnerValue);

		////		JSONObjectMapper.forClass()
		//
		//		JsonDataEntryGroup groupDTO = new JsonDataEntryGroup();
		//		groupDTO
		//

		final ImmutableList<JsonDataEntryGroup> groups = getJsonDataEntryGroups(adLanguage, layout, bpartnerId);

		JsonData jd = JsonData.builder()
				.groups(groups)
				.build();

		return jd;
	}

	@NonNull private ImmutableList<JsonDataEntryGroup> getJsonDataEntryGroups(final String adLanguage, final List<DataEntryGroup> layout, final BPartnerId bpartnerId)
	{
		final ImmutableList.Builder<JsonDataEntryGroup> groups = ImmutableList.builder();
		for (final DataEntryGroup layoutGroup : layout)
		{
			final ImmutableList<JsonDataEntrySubGroup> subGroups = getJsonDataEntrySubGroups(adLanguage, layoutGroup, bpartnerId);

			final JsonDataEntryGroup group = JsonDataEntryGroup.builder()
					.id(layoutGroup.getId())
					.caption(layoutGroup.getCaption().translate(adLanguage))
					.description(layoutGroup.getDescription().translate(adLanguage))
					.documentLinkColumnName(layoutGroup.getDocumentLinkColumnName())
					.subGroups(subGroups)
					.build();
			groups.add(group);
		}
		return groups.build();
	}

	@NonNull private ImmutableList<JsonDataEntrySubGroup> getJsonDataEntrySubGroups(final String adLanguage, final DataEntryGroup layoutGroup, final BPartnerId bpartnerId)
	{
		final ImmutableList.Builder<JsonDataEntrySubGroup> subGroups = ImmutableList.builder();
		for (final DataEntrySubGroup layoutSubGroup : layoutGroup.getDataEntrySubGroups())
		{
			final ImmutableList<JsonDataEntrySection> sections = getJsonDataEntrySections(adLanguage, layoutSubGroup, bpartnerId);

			final JsonDataEntrySubGroup subGroup = JsonDataEntrySubGroup.builder()
					.id(layoutSubGroup.getId())
					.caption(layoutSubGroup.getCaption().translate(adLanguage))
					.description(layoutSubGroup.getDescription().translate(adLanguage))
					.jsonDataEntrySections(sections)
					.build();
			subGroups.add(subGroup);
		}
		return subGroups.build();
	}

	@NonNull private ImmutableList<JsonDataEntrySection> getJsonDataEntrySections(final String adLanguage, final DataEntrySubGroup layoutSubGroup, final BPartnerId bpartnerId)
	{
		final TableRecordReference bpartnerRef = TableRecordReference.of(I_C_BPartner.Table_Name, bpartnerId);
		final DataEntryRecord record = getDataEntryRecordForSubGroup(bpartnerRef, layoutSubGroup);

		final ImmutableList.Builder<JsonDataEntrySection> sections = ImmutableList.builder();
		for (final DataEntrySection layoutSection : layoutSubGroup.getDataEntrySections())
		{
			final ImmutableList<JsonDataEntryLine> lines = getJsonDataEntryLines(adLanguage, record, layoutSection);

			JsonDataEntrySection.builder()
					.id(layoutSection.getId())
					.caption(layoutSection.getCaption().translate(adLanguage))
					.description(layoutSection.getDescription().translate(adLanguage))
					.initiallyClosed(layoutSection.isInitallyClosed())
					.lines(lines)
					.build();
		}
		return sections.build();
	}

	private ImmutableList<JsonDataEntryLine> getJsonDataEntryLines(final String adLanguage, final DataEntryRecord record, final DataEntrySection layoutSection)
	{
		final ImmutableList.Builder<JsonDataEntryLine> lines = ImmutableList.builder();
		for (final DataEntryLine layoutLine : layoutSection.getDataEntryLines())
		{
			final ImmutableList<JsonDataEntryField> fields = getJsonDataEntryFields(adLanguage, record, layoutLine);

			JsonDataEntryLine.builder()
					.fields(fields)
					.build();
		}
		return lines.build();
	}

	@NonNull private ImmutableList<JsonDataEntryField> getJsonDataEntryFields(final String adLanguage, final DataEntryRecord record, final DataEntryLine layoutLine)
	{
		final ImmutableList.Builder<JsonDataEntryField> fields = ImmutableList.builder();
		for (final DataEntryField layoutField : layoutLine.getDataEntryFields())
		{
			final JsonDataEntryField field = JsonDataEntryField.builder()
					.id(layoutField.getId())
					.caption(layoutField.getCaption().translate(adLanguage))
					.description(layoutField.getDescription().translate(adLanguage))
					// fixme: FieldType is not the same as what the customer sends.
					// 		the customer sends 2 character strings, so maybe we should also return the same?
					.type(layoutField.getType())
					.mandatory(layoutField.isMandatory())
					// fixme: what should we return for listvalues?
					.listValues(layoutField.getListValues())
					.value(record.getFieldValue(layoutField.getId()).orElse(null))
					.build();
			fields.add(field);
		}
		return fields.build();
	}

	private DataEntryRecord getDataEntryRecordForSubGroup(final TableRecordReference bpartnerRef, final DataEntrySubGroup layoutSubGroup)
	{
		final DataEntrySubGroupId subGroupId = layoutSubGroup.getId();

		return recordRepo.getBy(new DataEntryRecordQuery(subGroupId, bpartnerRef))
				.orElseGet(() -> DataEntryRecord.builder()
						.mainRecord(bpartnerRef)
						.dataEntrySubGroupId(subGroupId)
						.build());
	}
}


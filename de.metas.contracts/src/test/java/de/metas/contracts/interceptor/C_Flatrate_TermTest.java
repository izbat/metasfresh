package de.metas.contracts.interceptor;

import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.save;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.service.ISysConfigBL;
import org.adempiere.test.AdempiereTestHelper;
import org.adempiere.util.Services;
import org.junit.Before;
import org.junit.Test;

import de.metas.contracts.model.I_C_Flatrate_Conditions;
import de.metas.contracts.model.I_C_Flatrate_Term;
import de.metas.contracts.model.X_C_Flatrate_Conditions;
import de.metas.contracts.model.X_C_Flatrate_Term;

/*
 * #%L
 * de.metas.contracts
 * %%
 * Copyright (C) 2017 metas GmbH
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

public class C_Flatrate_TermTest
{
	@Before
	public void init()
	{
		AdempiereTestHelper.get().init();
	}

	@Test
	public void prohibitReactivatingUnlessAllowed_wrong_term_throws_exception()
	{
		setupAllowProcurementReactivate();

		final I_C_Flatrate_Term emptyTerm = newInstance(I_C_Flatrate_Term.class);
		invokeMethodAndAssertExceptionThrown(emptyTerm);

		final I_C_Flatrate_Term subscriptionTerm = newInstance(I_C_Flatrate_Term.class);
		subscriptionTerm.setType_Conditions(X_C_Flatrate_Term.TYPE_CONDITIONS_Subscription);
		save(subscriptionTerm);

		invokeMethodAndAssertExceptionThrown(subscriptionTerm);
	}

	public void invokeMethodAndAssertExceptionThrown(final I_C_Flatrate_Term term)
	{
		try
		{
			C_Flatrate_Term.INSTANCE.prohibitReactivatingUnlessAllowed(term);
			fail("Expected an AdempiereExeception");
		}
		catch (AdempiereException ae)
		{
			assertThat(ae.getMessage()).isEqualTo("@" + MainValidator.MSG_FLATRATE_REACTIVATE_DOC_ACTION_NOT_SUPPORTED_0P + "@");
		}
	}

	@Test
	public void prohibitReactivatingUnlessAllowed_procurement_term_allowed()
	{
		setupAllowProcurementReactivate();

		final I_C_Flatrate_Term term = newInstance(I_C_Flatrate_Term.class);
		term.setType_Conditions(X_C_Flatrate_Term.TYPE_CONDITIONS_Procurement);
		save(term);

		C_Flatrate_Term.INSTANCE.prohibitReactivatingUnlessAllowed(term); // shall return with no exception
	}

	public void setupAllowProcurementReactivate()
	{
		final String sysConfigName = "de.metas.contracts.C_Flatrate_Term.allow_reactivate_" + X_C_Flatrate_Term.TYPE_CONDITIONS_Procurement;
		Services.get(ISysConfigBL.class)
				.setValue(sysConfigName, true, 0);
	}
	
	@Test
	public void prohibitCompletingConditionsWithNoTransition()
	{

		final I_C_Flatrate_Conditions conditions = newInstance(I_C_Flatrate_Conditions.class);
		conditions.setType_Conditions(X_C_Flatrate_Conditions.TYPE_CONDITIONS_Subscription);
		save(conditions);

		invokeMethodAndAssertExceptionThrown(subscriptionTerm);
	}

	
	
}

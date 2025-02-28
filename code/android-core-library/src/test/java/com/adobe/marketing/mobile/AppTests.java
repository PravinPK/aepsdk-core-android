/*
  Copyright 2022 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
 */
 
package com.adobe.marketing.mobile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

@SuppressWarnings("all")
@RunWith(MockitoJUnitRunner.Silent.class)
public class AppTests {

	private static final String DATASTORE_KEY_LARGE_ICON = "LARGE_ICON_RESOURCE_ID";
	private static final String DATASTORE_KEY_SMALL_ICON = "SMALL_ICON_RESOURCE_ID";



	@Mock
	Context mockContext;

	@Mock
	SharedPreferences mockSharedPreferences;

	@Mock
	SharedPreferences.Editor mockPreferenceEditor;

	@Mock
	Application mockApplication;

	@Before
	public void beforeEach() {
		when(mockPreferenceEditor.putInt(anyString(), anyInt())).thenReturn(mockPreferenceEditor);
		when(mockSharedPreferences.edit()).thenReturn(mockPreferenceEditor);
		when(mockContext.getApplicationContext()).thenReturn(mockContext);
		when(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockSharedPreferences);
		App.setAppContext(mockContext);
	}

	@Test
	public void testSetLargeIconResourceId_ValidIdSet() {
		//Setup
		final int expectedValueStored = 123456;
		when(mockPreferenceEditor.putInt(eq(DATASTORE_KEY_LARGE_ICON),
		anyInt())).thenAnswer(new Answer<SharedPreferences.Editor>() {
			@Override
			public SharedPreferences.Editor answer(InvocationOnMock invocation) throws Throwable {
				int actualValueStored = invocation.getArgument(1);
				assertEquals(expectedValueStored, actualValueStored);
				return mockPreferenceEditor;
			}
		});

		//Test
		App.setLargeIconResourceID(expectedValueStored);

	}

	@Test
	public void testSetLargeIconResourceId_ValidIdSetTwice() {
		//Setup
		final int expectedValueStored = 123456;
		App.setLargeIconResourceID(111111);

		when(mockPreferenceEditor.putInt(eq(DATASTORE_KEY_LARGE_ICON),
		anyInt())).thenAnswer(new Answer<SharedPreferences.Editor>() {
			@Override
			public SharedPreferences.Editor answer(InvocationOnMock invocation) throws Throwable {
				int actualValueStored = invocation.getArgument(1);
				assertEquals(expectedValueStored, actualValueStored);
				return mockPreferenceEditor;
			}
		});

		//Test
		App.setLargeIconResourceID(expectedValueStored);

	}

	@Test
	public void testSetSmallIconResourceId_ValidIdSet() {
		//Setup
		final int expectedValueStored = 123456;
		when(mockPreferenceEditor.putInt(eq(DATASTORE_KEY_SMALL_ICON),
		anyInt())).thenAnswer(new Answer<SharedPreferences.Editor>() {
			@Override
			public SharedPreferences.Editor answer(InvocationOnMock invocation) throws Throwable {
				int actualValueStored = invocation.getArgument(1);
				assertEquals(expectedValueStored, actualValueStored);
				return mockPreferenceEditor;
			}
		});

		//Test
		App.setSmallIconResourceID(expectedValueStored);

	}

	@Test
	public void testSetSmallIconResourceId_ValidIdSetTwice() {
		//Setup
		final int expectedValueStored = 123456;
		App.setSmallIconResourceID(11111);

		when(mockPreferenceEditor.putInt(eq(DATASTORE_KEY_SMALL_ICON),
		anyInt())).thenAnswer(new Answer<SharedPreferences.Editor>() {
			@Override
			public SharedPreferences.Editor answer(InvocationOnMock invocation) throws Throwable {
				int actualValueStored = invocation.getArgument(1);
				assertEquals(expectedValueStored, actualValueStored);
				return mockPreferenceEditor;
			}
		});

		//Test
		App.setSmallIconResourceID(expectedValueStored);

	}

	@Test
	public void testClearAppResourcesClearsApplicationReference() {
		App.setApplication(mockApplication);
		assertNotNull(App.getApplication());
		String mockAppClass = mockApplication.getClass().toString();
		String retrievedAppClass = App.getApplication().getClass().toString();
		assertEquals(mockAppClass, retrievedAppClass);
		App.clearAppResources();
		assertNull(App.getApplication());
	}
}
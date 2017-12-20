/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.locksettings.recoverablekeystore;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.KeyStore;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PlatformKeyManagerTest {

    private static final int USER_AUTHENTICATION_VALIDITY_DURATION_SECONDS = 15;
    private static final int USER_ID_FIXTURE = 42;
    private static final String TEST_SHARED_PREFS_NAME = "PlatformKeyManagerTestPrefs";

    @Mock private Context mContext;
    @Mock private KeyStoreProxy mKeyStoreProxy;
    @Mock private KeyguardManager mKeyguardManager;

    @Captor private ArgumentCaptor<KeyStore.ProtectionParameter> mProtectionParameterCaptor;
    @Captor private ArgumentCaptor<KeyStore.Entry> mEntryArgumentCaptor;

    private SharedPreferences mSharedPreferences;
    private PlatformKeyManager mPlatformKeyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context testContext = InstrumentationRegistry.getTargetContext();
        mSharedPreferences = testContext.getSharedPreferences(
                TEST_SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        mPlatformKeyManager = new PlatformKeyManager(
                USER_ID_FIXTURE, mContext, mKeyStoreProxy, mSharedPreferences);

        when(mContext.getSystemService(anyString())).thenReturn(mKeyguardManager);
        when(mContext.getSystemServiceName(any())).thenReturn("test");
        when(mKeyguardManager.isDeviceSecure(USER_ID_FIXTURE)).thenReturn(true);
    }

    @After
    public void tearDown() {
        mSharedPreferences.edit().clear().commit();
    }

    @Test
    public void init_createsEncryptKeyWithCorrectAlias() throws Exception {
        mPlatformKeyManager.init();

        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"),
                any(),
                any());
    }

    @Test
    public void init_createsEncryptKeyWithCorrectPurposes() throws Exception {
        mPlatformKeyManager.init();

        assertEquals(KeyProperties.PURPOSE_ENCRYPT, getEncryptKeyProtection().getPurposes());
    }

    @Test
    public void init_createsEncryptKeyWithCorrectPaddings() throws Exception {
        mPlatformKeyManager.init();

        assertArrayEquals(
                new String[] { KeyProperties.ENCRYPTION_PADDING_NONE },
                getEncryptKeyProtection().getEncryptionPaddings());
    }

    @Test
    public void init_createsEncryptKeyWithCorrectBlockModes() throws Exception {
        mPlatformKeyManager.init();

        assertArrayEquals(
                new String[] { KeyProperties.BLOCK_MODE_GCM },
                getEncryptKeyProtection().getBlockModes());
    }

    @Test
    public void init_createsEncryptKeyWithoutAuthenticationRequired() throws Exception {
        mPlatformKeyManager.init();

        assertFalse(getEncryptKeyProtection().isUserAuthenticationRequired());
    }

    @Test
    public void init_createsDecryptKeyWithCorrectAlias() throws Exception {
        mPlatformKeyManager.init();

        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"),
                any(),
                any());
    }

    @Test
    public void init_createsDecryptKeyWithCorrectPurposes() throws Exception {
        mPlatformKeyManager.init();

        assertEquals(KeyProperties.PURPOSE_DECRYPT, getDecryptKeyProtection().getPurposes());
    }

    @Test
    public void init_createsDecryptKeyWithCorrectPaddings() throws Exception {
        mPlatformKeyManager.init();

        assertArrayEquals(
                new String[] { KeyProperties.ENCRYPTION_PADDING_NONE },
                getDecryptKeyProtection().getEncryptionPaddings());
    }

    @Test
    public void init_createsDecryptKeyWithCorrectBlockModes() throws Exception {
        mPlatformKeyManager.init();

        assertArrayEquals(
                new String[] { KeyProperties.BLOCK_MODE_GCM },
                getDecryptKeyProtection().getBlockModes());
    }

    @Test
    public void init_createsDecryptKeyWithAuthenticationRequired() throws Exception {
        mPlatformKeyManager.init();

        assertTrue(getDecryptKeyProtection().isUserAuthenticationRequired());
    }

    @Test
    public void init_createsDecryptKeyWithAuthenticationValidFor15Seconds() throws Exception {
        mPlatformKeyManager.init();

        assertEquals(
                USER_AUTHENTICATION_VALIDITY_DURATION_SECONDS,
                getDecryptKeyProtection().getUserAuthenticationValidityDurationSeconds());
    }

    @Test
    public void init_createsDecryptKeyBoundToTheUsersAuthentication() throws Exception {
        mPlatformKeyManager.init();

        assertEquals(
                USER_ID_FIXTURE,
                getDecryptKeyProtection().getBoundToSpecificSecureUserId());
    }

    @Test
    public void init_createsBothKeysWithSameMaterial() throws Exception {
        mPlatformKeyManager.init();

        verify(mKeyStoreProxy, times(2)).setEntry(any(), mEntryArgumentCaptor.capture(), any());
        List<KeyStore.Entry> entries = mEntryArgumentCaptor.getAllValues();
        assertArrayEquals(
                ((KeyStore.SecretKeyEntry) entries.get(0)).getSecretKey().getEncoded(),
                ((KeyStore.SecretKeyEntry) entries.get(1)).getSecretKey().getEncoded());
    }

    @Test
    public void init_setsGenerationIdTo1() throws Exception {
        mPlatformKeyManager.init();

        assertEquals(1, mPlatformKeyManager.getGenerationId());
    }

    @Test
    public void getDecryptKey_getsDecryptKeyWithCorrectAlias() throws Exception {
        mPlatformKeyManager.getDecryptKey();

        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"),
                any());
    }

    @Test
    public void getEncryptKey_getsDecryptKeyWithCorrectAlias() throws Exception {
        mPlatformKeyManager.getEncryptKey();

        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"),
                any());
    }

    @Test
    public void regenerate_incrementsTheGenerationId() throws Exception {
        mPlatformKeyManager.init();

        mPlatformKeyManager.regenerate();

        assertEquals(2, mPlatformKeyManager.getGenerationId());
    }

    @Test
    public void regenerate_generatesANewEncryptKeyWithTheCorrectAlias() throws Exception {
        mPlatformKeyManager.init();

        mPlatformKeyManager.regenerate();

        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/encrypt"),
                any(),
                any());
    }

    @Test
    public void regenerate_generatesANewDecryptKeyWithTheCorrectAlias() throws Exception {
        mPlatformKeyManager.init();

        mPlatformKeyManager.regenerate();

        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/decrypt"),
                any(),
                any());
    }

    private KeyProtection getEncryptKeyProtection() throws Exception {
        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"),
                any(),
                mProtectionParameterCaptor.capture());
        return (KeyProtection) mProtectionParameterCaptor.getValue();
    }

    private KeyProtection getDecryptKeyProtection() throws Exception {
        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"),
                any(),
                mProtectionParameterCaptor.capture());
        return (KeyProtection) mProtectionParameterCaptor.getValue();
    }
}

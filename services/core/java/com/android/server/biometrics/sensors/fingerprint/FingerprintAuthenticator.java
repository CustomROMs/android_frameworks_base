/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint;

import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.fingerprint.IFingerprintService;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.biometrics.SensorConfig;
import com.android.server.biometrics.sensors.LockoutTracker;

/**
 * Shim that converts IFingerprintService into a common reusable IBiometricAuthenticator interface.
 */
public final class FingerprintAuthenticator extends IBiometricAuthenticator.Stub {
    private final IFingerprintService mFingerprintService;

    public FingerprintAuthenticator(IFingerprintService fingerprintService, SensorConfig config)
            throws RemoteException {
        mFingerprintService = fingerprintService;
        mFingerprintService.initializeConfiguration(config.id, config.strength);
    }

    @Override
    public void prepareForAuthentication(boolean requireConfirmation, IBinder token,
            long operationId, int userId, IBiometricSensorReceiver sensorReceiver,
            String opPackageName, int cookie, int callingUid, int callingPid, int callingUserId)
            throws RemoteException {
        mFingerprintService.prepareForAuthentication(token, operationId, userId, sensorReceiver,
                opPackageName, cookie, callingUid, callingPid, callingUserId);
    }

    @Override
    public void startPreparedClient(int cookie) throws RemoteException {
        mFingerprintService.startPreparedClient(cookie);
    }

    @Override
    public void cancelAuthenticationFromService(IBinder token, String opPackageName, int callingUid,
            int callingPid, int callingUserId) throws RemoteException {
        mFingerprintService.cancelAuthenticationFromService(token, opPackageName, callingUid,
                callingPid, callingUserId);
    }

    @Override
    public boolean isHardwareDetected(String opPackageName) throws RemoteException {
        return mFingerprintService.isHardwareDetected(opPackageName);
    }

    @Override
    public boolean hasEnrolledTemplates(int userId, String opPackageName) throws RemoteException {
        return mFingerprintService.hasEnrolledFingerprints(userId, opPackageName);
    }

    @Override
    public @LockoutTracker.LockoutMode int getLockoutModeForUser(int userId)
            throws RemoteException {
        return mFingerprintService.getLockoutModeForUser(userId);
    }

    @Override
    public long getAuthenticatorId(int callingUserId) throws RemoteException {
        return mFingerprintService.getAuthenticatorId(callingUserId);
    }
}

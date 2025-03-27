package org.thoughtcrime.securesms.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.session.libsignal.utilities.Log;

/**
 * This class has been modified to disable automatic updates from Session Manager.
 * The app is now fully decoupled from the original Session app.
 */
public class UpdateApkReadyListener extends BroadcastReceiver {

  private static final String TAG = UpdateApkReadyListener.class.getSimpleName();

  @Override
  public void onReceive(Context context, Intent intent) {
    Log.i(TAG, "Update checks disabled - app is decoupled from Session Manager");
    // Do nothing - updates are disabled
  }
}

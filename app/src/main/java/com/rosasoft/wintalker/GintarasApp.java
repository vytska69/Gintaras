package com.rosasoft.wintalker;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Application entry point. Enables Material You "dynamic color": on Android 12+
 * every activity is themed from the user's wallpaper-derived palette. On older
 * versions this is a no-op and the static amber theme is used.
 */
public class GintarasApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}

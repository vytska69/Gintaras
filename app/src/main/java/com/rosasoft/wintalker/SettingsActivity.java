package com.rosasoft.wintalker;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

/**
 * Modern settings screen (from scratch, androidx.preference). Replaces the old
 * PreferenceActivity. No licensing / IMEI / activation entries.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.general_settings);
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            // Live summaries for the list preferences.
            for (String key : new String[]{
                    getString(R.string.voice_lang), "punctuation", "numgroup",
                    "pitch", "pause_word", "pause_sentence"}) {
                ListPreference lp = findPreference(key);
                if (lp != null) {
                    lp.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                }
            }

            Preference dict = findPreference("modify_dictionary");
            if (dict != null) {
                dict.setOnPreferenceClickListener(p -> {
                    startActivity(new Intent(requireContext(), ModifyDictionary.class));
                    return true;
                });
            }
        }
    }
}

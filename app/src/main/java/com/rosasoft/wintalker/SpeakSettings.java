package com.rosasoft.wintalker;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/** Settings screen (referenced by tts_engine.xml settingsActivity). */
public class SpeakSettings extends PreferenceActivity {

    void changeSummary(String id) {
        ListPreference lp = (ListPreference) findPreference(id);
        if (lp == null) {
            return;
        }
        lp.setSummary(lp.getEntry());
        lp.setOnPreferenceChangeListener((preference, newVal) -> {
            String textValue = newVal.toString();
            ListPreference listPreference = (ListPreference) preference;
            int index = listPreference.findIndexOfValue(textValue);
            CharSequence[] entries = listPreference.getEntries();
            if (index >= 0) {
                preference.setSummary(entries[index]);
            }
            return true;
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File storage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(storage.getPath(), getString(R.string.act_file));
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        } catch (IOException ignored) {
        }
        String activationKey = text.toString();

        addPreferencesFromResource(R.xml.preferences);
        changeSummary(getString(R.string.voice_lang));
        changeSummary("punctuation");
        changeSummary("numgroup");
        changeSummary("pitch");
        changeSummary("pause_word");
        changeSummary("pause_sentence");

        // Device id is no longer broadly available; fall back to ANDROID_ID.
        String deviceId = Settings.Secure.getString(
                getApplicationContext().getContentResolver(), Settings.Secure.ANDROID_ID);

        EditTextPreference etp = (EditTextPreference) findPreference(getString(R.string.imei_choice));
        if (etp != null) {
            etp.setSummary(getString(R.string.imei) + " " + deviceId);
            if (!activationKey.isEmpty()) {
                etp.setText(activationKey);
            }
            etp.setOnPreferenceChangeListener((preference, newVal) -> {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File out = new File(dir.getPath(), getString(R.string.act_file));
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(out))) {
                    writer.write((String) newVal);
                } catch (Exception ignored) {
                }
                return true;
            });
        }

        EditTextPreference imeiPref = (EditTextPreference) findPreference(getString(R.string.imei_value));
        if (imeiPref != null) {
            imeiPref.setText(deviceId);
            imeiPref.setSummary(deviceId);
        }

        Preference modify = findPreference("modify_dictionary");
        if (modify != null) {
            modify.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(getApplicationContext(), ModifyDictionary.class));
                return true;
            });
        }
    }
}

package com.rosasoft.wintalker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** User dictionary editor. Persists pronunciations to stdlit.dct (UTF-16LE). */
public class ModifyDictionary extends Activity {
    private final ArrayList<String> list1 = new ArrayList<>();
    private final ArrayList<String> list2 = new ArrayList<>();
    private Button mAddButton;
    private ArrayAdapter<String> mArrayAdapter;
    private EditText mEditText;
    private ListView mListView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        loadWords();

        mAddButton = findViewById(R.id.add_button);
        mEditText = findViewById(R.id.editText);
        mListView = findViewById(R.id.listView);
        mArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, list1);
        mListView.setAdapter(mArrayAdapter);

        mAddButton.setOnClickListener(v -> alert(-1));
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            mEditText.setText(list1.get(position));
            alert(position);
        });
    }

    private void alert(final int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(position != -1 ? R.string.modify : R.string.add);

        final EditText input = new EditText(this);
        if (position != -1) {
            input.setText(list2.get(position));
        }
        input.setInputType(1);
        builder.setView(input);

        if (position != -1) {
            builder.setPositiveButton(R.string.delete, (dialog, which) -> {
                list1.remove(position);
                list2.remove(position);
                mArrayAdapter.notifyDataSetChanged();
                writeData();
                mEditText.setText(null);
            });
        }

        builder.setNeutralButton(position != -1 ? R.string.modify : R.string.add, (dialog, which) -> {
            String s1 = mEditText.getText().toString();
            String s2 = input.getText().toString();
            if (!s1.isEmpty() && !s2.isEmpty()) {
                if (position == -1) {
                    list1.add(s1);
                    list2.add(s2);
                    mArrayAdapter.notifyDataSetChanged();
                } else {
                    list2.set(position, s2);
                }
                writeData();
            }
        });

        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void writeData() {
        StringBuilder string = new StringBuilder();
        for (int i = 0; i < list1.size(); i++) {
            string.append(list1.get(i)).append(" ").append(list2.get(i)).append("\n");
        }
        byte[] bytes = string.toString().getBytes(StandardCharsets.UTF_16LE);
        writeFile(getString(R.string.dict), bytes);
        Intent intent = new Intent(getApplicationContext(), TtsService.class);
        intent.putExtra("Service.data", "UPDATE");
        startService(intent);
    }

    private void writeFile(String filename, byte[] data) {
        try (FileOutputStream outputStream = openFileOutput(filename, 0)) {
            outputStream.write(data);
        } catch (Exception ignored) {
        }
    }

    private byte[] readFile(String filename) {
        try (FileInputStream inputStream = openFileInput(filename)) {
            int size = inputStream.available();
            byte[] buffer = new byte[size];
            inputStream.read(buffer);
            return buffer;
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] readAsset(String asset) {
        AssetManager assetManager = getAssets();
        try (InputStream stream = assetManager.open(asset)) {
            int size = stream.available();
            byte[] buffer = new byte[size];
            stream.read(buffer);
            return buffer;
        } catch (IOException e) {
            return null;
        }
    }

    private void loadWords() {
        byte[] data = readFile(getString(R.string.dict));
        if (data == null) {
            data = readAsset(getString(R.string.dict));
            if (data != null) {
                writeFile(getString(R.string.dict), data);
            }
        }
        if (data == null) {
            return;
        }
        String decoded = new String(data, StandardCharsets.UTF_16LE);
        String[] lines = TextUtils.split(decoded, "\n");
        for (String str : lines) {
            String[] words = TextUtils.split(str, " ");
            if (words.length >= 2) {
                list1.add(words[0]);
                StringBuilder pron = new StringBuilder();
                for (int j = 1; j < words.length; j++) {
                    if (j > 1) {
                        pron.append(" ");
                    }
                    pron.append(words[j]);
                }
                list2.add(pron.toString());
            }
        }
    }
}

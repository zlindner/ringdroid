/*
 * Copyright (C) 2008 Google Inc.
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

package com.ringdroid;

import java.util.ArrayList;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.os.Message;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

public class FileSaveDialog extends Dialog {

    // File kinds - these should correspond to the order in which
    // they're presented in the spinner control
    public static final int FILE_KIND_MUSIC = 0;
    public static final int FILE_KIND_ALARM = 1;
    public static final int FILE_KIND_NOTIFICATION = 2;
    public static final int FILE_KIND_RINGTONE = 3;

    private Spinner typeSpinner;
    private EditText fileName;
    private Message messageResponse;
    private String standardName;
    private ArrayList<String> typeArray;
    private int previousSelection;

    /**
     * Return a human-readable name for a kind (music, alarm, ringtone, ...).
     * These won't be displayed on-screen (just in logs) so they shouldn't
     * be translated.
     */
    public static String KindToName(int kind) {
        switch(kind) {
        default:
            return "Unknown";
        case FILE_KIND_MUSIC:
            return "Music";
        case FILE_KIND_ALARM:
            return "Alarm";
        case FILE_KIND_NOTIFICATION:
            return "Notification";
        case FILE_KIND_RINGTONE:
            return "Ringtone";
        }
    }

    public FileSaveDialog(Context context,
                          Resources resources,
                          String originalName,
                          Message response) {
        super(context);

        // Inflate our UI from its XML layout description.
        setContentView(R.layout.file_save);

        setTitle(resources.getString(R.string.file_save_title));

        typeArray = new ArrayList<String>();
        typeArray.add(resources.getString(R.string.type_music));
        typeArray.add(resources.getString(R.string.type_alarm));
        typeArray.add(resources.getString(R.string.type_notification));
        typeArray.add(resources.getString(R.string.type_ringtone));

        fileName = (EditText)findViewById(R.id.filename);
        standardName = originalName;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            context, android.R.layout.simple_spinner_item, typeArray);
        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        typeSpinner = (Spinner) findViewById(R.id.ringtone_type);
        typeSpinner.setAdapter(adapter);
        typeSpinner.setSelection(FILE_KIND_RINGTONE);
        previousSelection = FILE_KIND_RINGTONE;

        setFilenameEditBoxFromName(false);

        typeSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent,
                                           View v,
                                           int position,
                                           long id) {
                    setFilenameEditBoxFromName(true);
                }
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

        Button save = (Button)findViewById(R.id.save);
        save.setOnClickListener(saveListener);
        Button cancel = (Button)findViewById(R.id.cancel);
        cancel.setOnClickListener(cancelListener);
        messageResponse = response;
    }

    private void setFilenameEditBoxFromName(boolean onlyIfNotEdited) {
        if (onlyIfNotEdited) {
            CharSequence currentText = fileName.getText();
            String expectedText = standardName + " " +
                typeArray.get(previousSelection);

            if (!expectedText.contentEquals(currentText)) {
                return;
            }
        }

        int newSelection = typeSpinner.getSelectedItemPosition();
        String newSuffix = typeArray.get(newSelection);
        fileName.setText(standardName + " " + newSuffix);
        previousSelection = typeSpinner.getSelectedItemPosition();
    }

    private View.OnClickListener saveListener = new View.OnClickListener() {
            public void onClick(View view) {
                messageResponse.obj = fileName.getText();
                messageResponse.arg1 = typeSpinner.getSelectedItemPosition();
                messageResponse.sendToTarget();
                dismiss();
            }
        };

    private View.OnClickListener cancelListener = new View.OnClickListener() {
            public void onClick(View view) {
                dismiss();
            }
        };
}

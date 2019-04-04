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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import android.app.ActivityOptions;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.LoaderManager;
import android.bluetooth.BluetoothAdapter;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MergeCursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.SearchView;
import android.widget.SimpleCursorAdapter;
import android.widget.Toast;

import com.ringdroid.soundfile.SoundFile;

/**
 * Main screen that shows up when you launch Ringdroid. Handles selecting
 * an audio file or using an intent to record a new one, and then
 * launches RingdroidEditActivity from here.
 */
public class RingdroidSelectActivity
    extends ListActivity
    implements LoaderManager.LoaderCallbacks<Cursor>
{
    private SearchView searchViewFilter;
    private SimpleCursorAdapter cursorAdapter;
    private boolean wasGetContentIntent;
    private boolean showAll;
    private Cursor internalCursor;
    private Cursor externalCursor;

    // Result codes
    private static final int REQUEST_CODE_EDIT = 1;
    private static final int REQUEST_CODE_CHOOSE_CONTACT = 2;
    private static final int REQUEST_BLU = 1;

    // Context menu
    private static final int CMD_EDIT = 4;
    private static final int CMD_DELETE = 5;
    private static final int CMD_SET_AS_DEFAULT = 6;
    private static final int CMD_SET_AS_CONTACT = 7;
    private static final int CMD_SHARE_BT = 8;
    private static final int CMD_SHARE_NFC = 9;

    // Bluetooth and NFC requirements
    private static final int DISCOVER_DURATION = 300;
    NfcAdapter nfcAdapter;

    private String SORT_TYPE = "title";


    public RingdroidSelectActivity() {
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);


        showAll = false;

        String status = Environment.getExternalStorageState();
        if (status.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
            showFinalAlert(getResources().getText(R.string.sdcard_readonly));
            return;
        }
        if (status.equals(Environment.MEDIA_SHARED)) {
            showFinalAlert(getResources().getText(R.string.sdcard_shared));
            return;
        }
        if (!status.equals(Environment.MEDIA_MOUNTED)) {
            showFinalAlert(getResources().getText(R.string.no_sdcard));
            return;
        }

        Intent intent = getIntent();
        wasGetContentIntent = Objects.equals(intent.getAction(), Intent.ACTION_GET_CONTENT);

        // Inflate our UI from its XML layout description.
        setContentView(R.layout.media_select);

        try {
            cursorAdapter = new SimpleCursorAdapter(
                    this,
                    // Use a template that displays a text view
                    R.layout.media_select_row,
                    null,
                    // Map from database columns...
                    new String[] {
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.MediaColumns.DATE_MODIFIED,},
                        // To widget ids in the row layout...
                    new int[] {
                        R.id.row_artist,
                        R.id.row_album,
                        R.id.row_title,
                        R.id.row_icon,
                        R.id.row_options_button,
                        R.id.row_size,
                        R.id.row_date_modified},
                    0);

            setListAdapter(cursorAdapter);

            getListView().setItemsCanFocus(true);

            // Normal click - open the editor
            getListView().setOnItemClickListener(new OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent,
                        View view,
                        int position,
                        long id) {
                    startRingdroidEditor();
                }
            });

            internalCursor = null;
            externalCursor = null;
            getLoaderManager().initLoader(INTERNAL_CURSOR_ID,  null, this);
            getLoaderManager().initLoader(EXTERNAL_CURSOR_ID,  null, this);

        } catch (SecurityException | IllegalArgumentException e) {
            Log.e("RingdroidSelectActivity", "onCreate() invalid permissions to retrieve audio");
            return;
        }

        cursorAdapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                if (view.getId() == R.id.row_options_button){
                    // Get the arrow ImageView and set the onClickListener to open the context menu.
                    ImageView iv = (ImageView)view;
                    iv.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            openContextMenu(v);
                        }
                    });
                    return true;
                } else if (view.getId() == R.id.row_icon) {
                    setSoundIconFromCursor((ImageView) view, cursor);
                    return true;
                }

                return false;
            }
        });

        // Long-press opens a context menu
        registerForContextMenu(getListView());
    }

    private void setSoundIconFromCursor(ImageView view, Cursor cursor) {
        if (0 != cursor.getInt(cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.IS_RINGTONE))) {
            view.setImageResource(R.drawable.type_ringtone);
            ((View) view.getParent()).setBackgroundColor(
                    getResources().getColor(R.color.type_bkgnd_ringtone));
        } else if (0 != cursor.getInt(cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.IS_ALARM))) {
            view.setImageResource(R.drawable.type_alarm);
            ((View) view.getParent()).setBackgroundColor(
                    getResources().getColor(R.color.type_bkgnd_alarm));
        } else if (0 != cursor.getInt(cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.IS_NOTIFICATION))) {
            view.setImageResource(R.drawable.type_notification);
            ((View) view.getParent()).setBackgroundColor(
                    getResources().getColor(R.color.type_bkgnd_notification));
        } else if (0 != cursor.getInt(cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.IS_MUSIC))) {
            view.setImageResource(R.drawable.type_music);
            ((View) view.getParent()).setBackgroundColor(
                    getResources().getColor(R.color.type_bkgnd_music));
        }

        String filename = cursor.getString(cursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.DATA));
        if (!SoundFile.isFilenameSupported(filename)) {
            ((View) view.getParent()).setBackgroundColor(
                    getResources().getColor(R.color.type_bkgnd_unsupported));
        }
    }

    /** Called with an Activity we started with an Intent returns. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent dataIntent) {

        if (resultCode == DISCOVER_DURATION && requestCode == REQUEST_BLU) {

            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.setType("*/*");

            Cursor c = cursorAdapter.getCursor();
            int dataIndex = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            String soundFile = c.getString(dataIndex);
            Log.d("onActivityResult", "File Name:" + soundFile);

            File newFile = new File(soundFile);
            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(newFile));

            PackageManager pm = getPackageManager();
            List<ResolveInfo> appsList = pm.queryIntentActivities(intent, 0);

            if (appsList.size() > 0) {
                String packageName = null;
                String className = null;
                boolean found = false;

                for (ResolveInfo info : appsList) {
                    packageName = info.activityInfo.packageName;
                    if (packageName.equals("com.android.bluetooth")) {
                        className = info.activityInfo.name;
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    Toast.makeText(this, "Bluetooth hasn't been found", Toast.LENGTH_LONG).show();
                } else {
                    intent.setClassName(packageName, className);
                    startActivity(intent, ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.select_options, menu);

        searchViewFilter = (SearchView) menu.findItem(R.id.action_search_filter).getActionView();
        if (searchViewFilter != null) {
            searchViewFilter.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                public boolean onQueryTextChange(String newText) {
                    refreshListView();
                    return true;
                }
                public boolean onQueryTextSubmit(String query) {
                    refreshListView();
                    return true;
                }
            });
        }

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(R.id.action_about).setVisible(true);
        menu.findItem(R.id.action_record).setVisible(true);
        // TODO(nfaralli): do we really need a "Show all audio" item now?
        menu.findItem(R.id.action_show_all_audio).setVisible(true);
        menu.findItem(R.id.action_show_all_audio).setEnabled(!showAll);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.action_about:
            RingdroidEditActivity.onAbout(this);
            return true;
        case R.id.action_record:
            onRecord();
            return true;
        case R.id.action_show_all_audio:
            showAll = true;
            refreshListView();

            return true;
        case R.id.sort_by_last_modified_asc:
            SORT_TYPE = "date_modified ASC";
            refreshListView();
            return true;
        case R.id.sort_by_last_modified_desc:
            SORT_TYPE = "date_modified DESC";
            refreshListView();
            return true;
        case R.id.sort_by_duration_asc:
            SORT_TYPE = "duration ASC";
            refreshListView();
            return true;
        case R.id.sort_by_duration_desc:
            SORT_TYPE = "duration DESC";
            refreshListView();
            return true;
        case R.id.sort_by_title_asc:
            SORT_TYPE = "title ASC";
            refreshListView();
            return true;
        case R.id.sort_by_title_desc:
            SORT_TYPE = "title DESC";
            refreshListView();
            return true;
        case R.id.sort_by_artist_asc:
            SORT_TYPE = "artist ASC";
            refreshListView();
            return true;
        case R.id.sort_by_artist_desc:
            SORT_TYPE = "artist DESC";
            refreshListView();
            return true;
            case R.id.action_help:
                onHelp(this);
                return true;
        default:
            return false;
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu,
            View v,
            ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        Cursor c = cursorAdapter.getCursor();
        String title = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));

        menu.setHeaderTitle(title);

        menu.add(0, CMD_EDIT, 0, R.string.context_menu_edit);
        menu.add(0, CMD_DELETE, 0, R.string.context_menu_delete);
        menu.add(0, CMD_SHARE_BT, 0, R.string.context_menu_bluetooth);
        menu.add(0, CMD_SHARE_NFC, 0, "Share through NFC");

        // Add items to the context menu item based on file type
        if (0 != c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE))) {
            menu.add(0, CMD_SET_AS_DEFAULT, 0, R.string.context_menu_default_ringtone);
            menu.add(0, CMD_SET_AS_CONTACT, 0, R.string.context_menu_contact);
        } else if (0 != c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION))) {
            menu.add(0, CMD_SET_AS_DEFAULT, 0, R.string.context_menu_default_notification);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case CMD_EDIT:
            startRingdroidEditor();
            return true;
        case CMD_DELETE:
            confirmDelete();
            return true;
        case CMD_SET_AS_DEFAULT:
            setAsDefaultRingtoneOrNotification();
            return true;
        case CMD_SET_AS_CONTACT:
            return chooseContactForRingtone(item);
        case CMD_SHARE_BT:
            checkBluetooth();
            return true;
        case CMD_SHARE_NFC:
            PackageManager pm = this.getPackageManager();

            if(!pm.hasSystemFeature(PackageManager.FEATURE_NFC)){
                //NFC is not available on this device
                Toast.makeText(this, "This device does not have NFC hardware", Toast.LENGTH_SHORT).show();
            } else {
                sendNFC();
            }

            return true;
        default:
            return super.onContextItemSelected(item);
        }
    }

    private void setAsDefaultRingtoneOrNotification(){
        Cursor c = cursorAdapter.getCursor();

        // If the item is a ringtone then set the default ringtone,
        // otherwise it has to be a notification so set the default notification sound
        if (0 != c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE))){
            RingtoneManager.setActualDefaultRingtoneUri(
                    RingdroidSelectActivity.this,
                    RingtoneManager.TYPE_RINGTONE,
                    getUri());
            Toast.makeText(
                    RingdroidSelectActivity.this,
                    R.string.default_ringtone_success_message,
                    Toast.LENGTH_SHORT)
                    .show();
        } else {
            RingtoneManager.setActualDefaultRingtoneUri(
                    RingdroidSelectActivity.this,
                    RingtoneManager.TYPE_NOTIFICATION,
                    getUri());
            Toast.makeText(
                    RingdroidSelectActivity.this,
                    R.string.default_notification_success_message,
                    Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private int getUriIndex(Cursor c) {
        int uriIndex;
        String[] columnNames = {
                MediaStore.Audio.Media.INTERNAL_CONTENT_URI.toString(),
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString(),
        };

        for (String columnName : columnNames) {
            uriIndex = c.getColumnIndex(columnName);
            if (uriIndex >= 0) {
                return uriIndex;
            }
            // On some phones and/or Android versions, the column name includes the double quotes.
            uriIndex = c.getColumnIndex("\"" + columnName + "\"");
            if (uriIndex >= 0) {
                return uriIndex;
            }
        }
        return -1;
    }

    private Uri getUri(){
        //Get the uri of the item that is in the row
        Cursor c = cursorAdapter.getCursor();
        int uriIndex = getUriIndex(c);
        if (uriIndex == -1) {

            return null;
        }
        String itemUri = c.getString(uriIndex) + "/" +
        c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
        return (Uri.parse(itemUri));
    }

    private void checkBluetooth(){
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        //bluetoothAdapter will only be null if the device does not support bluetooth
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show();
        } else {
            enableBluetooth();
        }
    }

    private void enableBluetooth(){
        Intent discoverIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVER_DURATION);
        startActivityForResult(discoverIntent, REQUEST_BLU);
    }

    private void sendNFC(){
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if(!nfcAdapter.isEnabled()){
            // NFC is disabled, show the setting UI to enable NFC
            Toast.makeText(this, "Please enable NFC.", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        } else if(!nfcAdapter.isNdefPushEnabled()){
            // Android Beam is disabled, show the setting UI to enable Beam
            Toast.makeText(this, "Please enable Android Beam.", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_NFCSHARING_SETTINGS));
        } else {
            // NFC and Android Beam both are enabled
            Cursor c = cursorAdapter.getCursor();
            int dataIndex = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            String soundFile = c.getString(dataIndex);
            Log.d("sendNFC","File Name:" + soundFile);

            File newFile = new File(soundFile);
            newFile.setReadable(true, false);

            Toast.makeText(this,"Please put devices together to beam", Toast.LENGTH_SHORT).show();
            nfcAdapter.setBeamPushUris(new Uri[]{Uri.fromFile(newFile)},this);
        }
    }

    private boolean chooseContactForRingtone(MenuItem item){
        try {
            //Go to the choose contact activity
            Intent intent = new Intent(Intent.ACTION_EDIT, getUri());
            intent.setClassName(
                    "com.ringdroid",
            "com.ringdroid.ChooseContactActivity");
            startActivityForResult(intent, REQUEST_CODE_CHOOSE_CONTACT, ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
        } catch (Exception e) {
            Log.e("RingdroidSelectActivity", "choosContactForRingtone() error opening choose contact window\n" + e.toString());
        }
        return true;
    }

    private void confirmDelete() {
        // See if the selected list item was created by Ringdroid to
        // determine which alert message to show
        Cursor c = cursorAdapter.getCursor();
        String artist = c.getString(c.getColumnIndexOrThrow(
                MediaStore.Audio.Media.ARTIST));
        CharSequence ringdroidArtist =
            getResources().getText(R.string.artist_name);

        CharSequence message;
        if (artist.equals(ringdroidArtist)) {
            message = getResources().getText(
                    R.string.confirm_delete_ringdroid);
        } else {
            message = getResources().getText(
                    R.string.confirm_delete_non_ringdroid);
        }

        CharSequence title;
        if (0 != c.getInt(c.getColumnIndexOrThrow(
                MediaStore.Audio.Media.IS_RINGTONE))) {
            title = getResources().getText(R.string.delete_ringtone);
        } else if (0 != c.getInt(c.getColumnIndexOrThrow(
                MediaStore.Audio.Media.IS_ALARM))) {
            title = getResources().getText(R.string.delete_alarm);
        } else if (0 != c.getInt(c.getColumnIndexOrThrow(
                MediaStore.Audio.Media.IS_NOTIFICATION))) {
            title = getResources().getText(R.string.delete_notification);
        } else if (0 != c.getInt(c.getColumnIndexOrThrow(
                MediaStore.Audio.Media.IS_MUSIC))) {
            title = getResources().getText(R.string.delete_music);
        } else {
            title = getResources().getText(R.string.delete_audio);
        }

        new AlertDialog.Builder(RingdroidSelectActivity.this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(
                R.string.delete_ok_button,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,
                            int whichButton) {
                        onDelete();
                    }
                })
            .setNegativeButton(
                R.string.delete_cancel_button,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,
                            int whichButton) {
                    }
                })
            .setCancelable(true)
            .show();
    }

    private void onDelete() {
        Cursor c = cursorAdapter.getCursor();
        int dataIndex = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
        String filename = c.getString(dataIndex);

        int uriIndex = getUriIndex(c);
        if (uriIndex == -1) {
            showFinalAlert(getResources().getText(R.string.delete_failed));
            return;
        }

        if (!new File(filename).delete()) {
            showFinalAlert(getResources().getText(R.string.delete_failed));
        }

        String itemUri = c.getString(uriIndex) + "/" +
        c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
        getContentResolver().delete(Uri.parse(itemUri), null, null);
    }

    private void showFinalAlert(CharSequence message) {
        new AlertDialog.Builder(RingdroidSelectActivity.this)
        .setTitle(getResources().getText(R.string.alert_title_failure))
        .setMessage(message)
        .setPositiveButton(
                R.string.alert_ok_button,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,
                            int whichButton) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void onRecord() {
        try {
            Intent intent = new Intent(Intent.ACTION_EDIT, Uri.parse("record"));
            intent.putExtra("was_get_content_intent", wasGetContentIntent);
            intent.setClassName( "com.ringdroid", "com.ringdroid.RingdroidEditActivity");
            startActivityForResult(intent, REQUEST_CODE_EDIT, ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
        } catch (Exception e) {
            Log.e("RingdroidSelectActivity", "onRecord() error starting editor\n" + e.toString());
        }
    }

    private void startRingdroidEditor() {
        Cursor c = cursorAdapter.getCursor();
        int dataIndex = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
        String filename = c.getString(dataIndex);
        try {
            Intent intent = new Intent(Intent.ACTION_EDIT, Uri.parse(filename));
            intent.putExtra("was_get_content_intent", wasGetContentIntent);
            intent.setClassName( "com.ringdroid", "com.ringdroid.RingdroidEditActivity");
            startActivityForResult(intent, REQUEST_CODE_EDIT, ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
        } catch (Exception e) {
            Log.e("RingdroidSelectActivity", "startRingdroidEditor() error starting editor\n" + e.toString());
        }
    }

    private void refreshListView() {
        internalCursor = null;
        externalCursor = null;
        Bundle args = new Bundle();
        args.putString("filter", searchViewFilter.getQuery().toString());
        getLoaderManager().restartLoader(INTERNAL_CURSOR_ID,  args, this);
        getLoaderManager().restartLoader(EXTERNAL_CURSOR_ID,  args, this);
        getListView().smoothScrollToPosition(0);
    }

    private void onHelp(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.help_title)
                .setMessage(R.string.select_activity_help)
                .setPositiveButton(R.string.alert_ok_button, null)
                .setCancelable(false)
                .show();
    }

    private static final String[] INTERNAL_COLUMNS = new String[] {
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.IS_RINGTONE,
        MediaStore.Audio.Media.IS_ALARM,
        MediaStore.Audio.Media.IS_NOTIFICATION,
        MediaStore.Audio.Media.IS_MUSIC,
        "\"" + MediaStore.Audio.Media.INTERNAL_CONTENT_URI + "\"",
        MediaStore.Audio.Media.DURATION,
        MediaStore.MediaColumns.DATE_MODIFIED,
    };

    private static final String[] EXTERNAL_COLUMNS = new String[] {
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.IS_RINGTONE,
        MediaStore.Audio.Media.IS_ALARM,
        MediaStore.Audio.Media.IS_NOTIFICATION,
        MediaStore.Audio.Media.IS_MUSIC,
        "\"" + MediaStore.Audio.Media.EXTERNAL_CONTENT_URI + "\"",
        MediaStore.Audio.Media.DURATION,
        MediaStore.MediaColumns.DATE_MODIFIED,

    };

    private static final int INTERNAL_CURSOR_ID = 0;
    private static final int EXTERNAL_CURSOR_ID = 1;

    /* Implementation of LoaderCallbacks.onCreateLoader */
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        ArrayList<String> selectionArgsList = new ArrayList<String>();
        String selection;
        Uri baseUri;
        String[] projection;

        switch (id) {
        case INTERNAL_CURSOR_ID:
            baseUri = MediaStore.Audio.Media.INTERNAL_CONTENT_URI;
            projection = INTERNAL_COLUMNS;
            break;
        case EXTERNAL_CURSOR_ID:
            baseUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            projection = EXTERNAL_COLUMNS;
            break;
        default:
            return null;
        }

        if (showAll) {
            selection = "(_DATA LIKE ?)";
            selectionArgsList.add("%");
        } else {
            selection = "(";
            for (String extension : SoundFile.getSupportedExtensions()) {
                selectionArgsList.add("%." + extension);
                if (selection.length() > 1) {
                    selection += " OR ";
                }
                selection += "(_DATA LIKE ?)";
            }
            selection += ")";

            selection = "(" + selection + ") AND (_DATA NOT LIKE ?)";
            selectionArgsList.add("%espeak-data/scratch%");
        }

        String filter = args != null ? args.getString("filter") : null;
        if (filter != null && filter.length() > 0) {
            filter = "%" + filter + "%";
            selection =
                "(" + selection + " AND " +
                "((TITLE LIKE ?) OR (ARTIST LIKE ?) OR (ALBUM LIKE ?)))";
            selectionArgsList.add(filter);
            selectionArgsList.add(filter);
            selectionArgsList.add(filter);
        }



        String[] selectionArgs =
                selectionArgsList.toArray(new String[selectionArgsList.size()]);
        return new CursorLoader(
                this,
                baseUri,
                projection,
                selection,
                selectionArgs,
                SORT_TYPE
                );
    }

    /* Implementation of LoaderCallbacks.onLoadFinished */
    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        switch (loader.getId()) {
        case INTERNAL_CURSOR_ID:
            internalCursor = data;
            break;
        case EXTERNAL_CURSOR_ID:
            externalCursor = data;
            break;
        default:
            return;
        }
        // TODO: should I use a mutex/synchronized block here?
        if (internalCursor != null && externalCursor != null) {
            Cursor mergeCursor = new MergeCursor(new Cursor[] {internalCursor, externalCursor});
            cursorAdapter.swapCursor(mergeCursor);
        }
    }

    /* Implementation of LoaderCallbacks.onLoaderReset */
    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        // This is called when the last Cursor provided to onLoadFinished()
        // above is about to be closed.  We need to make sure we are no
        // longer using it.
        cursorAdapter.swapCursor(null);
    }
}

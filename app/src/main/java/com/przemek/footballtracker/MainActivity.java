package com.przemek.footballtracker;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_PICKER_REQUEST_CODE = 200;
    private static final int SETTINGS_REQUEST_CODE = 300;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 400;

    private WebView webView;
    private boolean permissionsChecked = false;
    private static final String TAG = "FootballTracker";

    // SharedPreferences for persistent storage
    private static final String PREFS_NAME = "FootballTrackerPrefs";
    private static final String PREF_SOUNDS = "saved_sounds";
    private static final String PREF_ASSIGNMENTS = "sound_assignments";
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        webView = findViewById(R.id.webview);
        setupWebView();
        loadApp();

        // Check permissions after WebView loads
        webView.postDelayed(this::checkAndRequestPermissions, 1500);
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        // Fix: Remove duplicate cache settings
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // Enable debugging for WebView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // Clear cache once during setup
        webView.clearCache(true);
        webView.clearHistory();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "WebView page finished loading");

                // Auto-load saved sounds after page loads
                loadSavedSoundsToWebView();
            }
        });

        webView.addJavascriptInterface(new AndroidInterface(this), "AndroidInterface");
    }

    private void loadApp() {
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void checkAndRequestPermissions() {
        if (permissionsChecked) return;
        permissionsChecked = true;

        List<String> permissionsNeeded = new ArrayList<>();

        // For Android 14 (API 34), we need more specific permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            // Legacy storage permissions for Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        // Check for WRITE permission for saving reports
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            showPermissionDialog(permissionsNeeded);
        } else {
            Log.d(TAG, "All permissions granted");
            Toast.makeText(this, "✅ App ready! All permissions granted.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPermissionDialog(List<String> permissionsNeeded) {
        String message = "🎵 Football Tracker needs these permissions:\n\n" +
                "📱 Audio Access:\n" +
                "• Select MP3/audio files from your device\n" +
                "• Assign custom sounds to teams and players\n\n" +
                "💾 Storage Access:\n" +
                "• Save match reports to Downloads folder\n\n" +
                "🔒 Privacy: We only access files you specifically choose.";

        new AlertDialog.Builder(this)
                .setTitle("🔐 Permissions Required")
                .setMessage(message)
                .setPositiveButton("Grant Permissions", (dialog, which) -> {
                    String[] permissions = permissionsNeeded.toArray(new String[0]);
                    ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
                })
                .setNegativeButton("Skip Audio Features", (dialog, which) -> {
                    Toast.makeText(this, "⚠️ Audio upload disabled. You can enable it later in Settings.", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Open Settings", (dialog, which) -> {
                    openAppSettings();
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean hasAudioPermission = false;
            boolean hasStoragePermission = false;

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    String permission = permissions[i];

                    if (permission.equals(Manifest.permission.READ_MEDIA_AUDIO) ||
                            permission.equals(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        hasAudioPermission = true;
                        Log.d(TAG, "Audio permission granted: " + permission);
                    }

                    if (permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        hasStoragePermission = true;
                        Log.d(TAG, "Storage permission granted: " + permission);
                    }
                } else {
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                }
            }

            StringBuilder message = new StringBuilder();
            if (hasAudioPermission) {
                message.append("✅ Audio permission granted! You can now upload sounds.\n");
            }
            if (hasStoragePermission || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                message.append("✅ Storage access ready for saving reports.\n");
            }

            if (message.length() > 0) {
                Toast.makeText(this, message.toString(), Toast.LENGTH_LONG).show();
            } else {
                showPermissionDeniedDialog();
            }
        }
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Permissions Required")
                .setMessage("Some permissions were not granted.\n\n" +
                        "For full functionality:\n" +
                        "• Audio permission: Upload goal sounds\n" +
                        "• Storage permission: Save match reports\n\n" +
                        "You can enable these later in Settings > Permissions.")
                .setPositiveButton("Open Settings", (dialog, which) -> openAppSettings())
                .setNegativeButton("Continue Anyway", (dialog, which) -> {
                    Toast.makeText(this, "Limited functionality. Enable permissions in Settings for full features.", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, SETTINGS_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open app settings", e);
            Toast.makeText(this, "Cannot open settings. Please go to Settings > Apps > Football Tracker manually.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri selectedFileUri = data.getData();
            if (selectedFileUri != null) {
                Log.d(TAG, "File selected: " + selectedFileUri.toString());
                processSelectedAudioFile(selectedFileUri);
            }
        } else if (requestCode == SETTINGS_REQUEST_CODE) {
            // User returned from settings, check permissions again
            permissionsChecked = false;
            checkAndRequestPermissions();
        }
    }

    private void processSelectedAudioFile(Uri uri) {
        Log.d(TAG, "Processing audio file: " + uri.toString());

        try {
            String fileName = getFileName(uri);
            Log.d(TAG, "File name: " + fileName);

            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Log.e(TAG, "Cannot open input stream for URI: " + uri);
                Toast.makeText(this, "❌ Cannot read selected file", Toast.LENGTH_SHORT).show();
                return;
            }

            byte[] fileBytes = readFileBytes(inputStream);
            inputStream.close();

            Log.d(TAG, "File size: " + fileBytes.length + " bytes");

            // Check file size limit (5MB)
            if (fileBytes.length > 5 * 1024 * 1024) {
                Toast.makeText(this, "❌ File too large. Please choose a file under 5MB.", Toast.LENGTH_LONG).show();
                return;
            }

            String mimeType = getContentResolver().getType(uri);
            if (mimeType == null || !mimeType.startsWith("audio/")) {
                mimeType = "audio/mpeg"; // Default to MP3
            }

            Log.d(TAG, "MIME type: " + mimeType);

            String base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
            String dataUrl = "data:" + mimeType + ";base64," + base64Data;

            // Escape quotes and backslashes for JavaScript
            String escapedFileName = fileName.replace("\\", "\\\\").replace("'", "\\'");
            String escapedDataUrl = dataUrl.replace("'", "\\'");

            String jsCode = String.format(
                    "try { " +
                            "if (typeof addSoundFromNative === 'function') { " +
                            "addSoundFromNative('%s', '%s', %d); " +
                            "console.log('Sound added successfully'); " +
                            "} else { " +
                            "console.error('addSoundFromNative function not found'); " +
                            "} " +
                            "} catch(e) { " +
                            "console.error('Error adding sound:', e); " +
                            "}",
                    escapedFileName,
                    escapedDataUrl,
                    fileBytes.length
            );

            runOnUiThread(() -> {
                webView.evaluateJavascript(jsCode, result -> {
                    Log.d(TAG, "JavaScript execution result: " + result);
                    Toast.makeText(this, "🎵 Sound uploaded: " + fileName, Toast.LENGTH_SHORT).show();
                });
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing audio file", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "❌ Error uploading sound: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        }
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private String getFileName(Uri uri) {
        String fileName = "audio_file_" + System.currentTimeMillis() + ".mp3";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);
                    if (name != null && !name.isEmpty()) {
                        fileName = name;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file name", e);
        }
        Log.d(TAG, "Resolved file name: " + fileName);
        return fileName;
    }

    private byte[] readFileBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        return buffer.toByteArray();
    }

    // Save sounds to SharedPreferences - Modified to use single parameter
    private void saveSoundsToStorage(String soundsJson) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PREF_SOUNDS, soundsJson);
            boolean success = editor.commit();

            Log.d(TAG, "Sounds saved to SharedPreferences: " + success);

            if (success) {
                runOnUiThread(() -> Toast.makeText(this, "💾 Sounds saved successfully!", Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() -> Toast.makeText(this, "❌ Error saving sounds", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving sounds to SharedPreferences", e);
            runOnUiThread(() -> Toast.makeText(this, "❌ Error saving sounds: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    // Load sounds from SharedPreferences
    private void loadSavedSoundsToWebView() {
        try {
            String soundsJson = sharedPreferences.getString(PREF_SOUNDS, "");

            if (!soundsJson.isEmpty()) {
                Log.d(TAG, "Loading saved sounds to WebView");

                String jsCode = String.format(
                        "try { " +
                                "if (typeof loadSoundsFromAndroid === 'function') { " +
                                "loadSoundsFromAndroid('%s', ''); " +
                                "console.log('Sounds loaded from Android storage'); " +
                                "} else { " +
                                "console.error('loadSoundsFromAndroid function not found'); " +
                                "} " +
                                "} catch(e) { " +
                                "console.error('Error loading sounds:', e); " +
                                "}",
                        soundsJson.replace("'", "\\'")
                );

                runOnUiThread(() -> {
                    webView.evaluateJavascript(jsCode, result -> {
                        Log.d(TAG, "Load sounds JavaScript result: " + result);
                    });
                });
            } else {
                Log.d(TAG, "No saved sounds found");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading sounds from SharedPreferences", e);
        }
    }

    // Clear all saved sounds
    private void clearAllSavedSounds() {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.remove(PREF_SOUNDS);
            boolean success = editor.commit();

            Log.d(TAG, "All sounds cleared from storage: " + success);

            if (success) {
                runOnUiThread(() -> Toast.makeText(this, "🗑️ All saved sounds cleared", Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() -> Toast.makeText(this, "❌ Error clearing sounds", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing sounds", e);
            runOnUiThread(() -> Toast.makeText(this, "❌ Error clearing sounds: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    // Get storage info - RENAMED to avoid confusion
    private void showStorageInfoDialog() {
        try {
            String soundsJson = sharedPreferences.getString(PREF_SOUNDS, "");

            int savedSoundsCount = 0;
            long totalSize = 0;
            int assignmentsCount = 0;

            if (!soundsJson.isEmpty()) {
                try {
                    JSONObject data = new JSONObject(soundsJson);

                    if (data.has("sounds")) {
                        JSONArray soundsArray = data.getJSONArray("sounds");
                        savedSoundsCount = soundsArray.length();

                        for (int i = 0; i < soundsArray.length(); i++) {
                            JSONObject sound = soundsArray.getJSONObject(i);
                            if (sound.has("size")) {
                                totalSize += sound.getLong("size");
                            }
                        }
                    }

                    if (data.has("soundAssignments")) {
                        JSONObject assignmentsObj = data.getJSONObject("soundAssignments");
                        assignmentsCount = assignmentsObj.length();
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing saved sounds JSON", e);
                }
            }

            double sizeMB = totalSize / (1024.0 * 1024.0);

            String info = String.format(
                    "🔊 Storage Information:\n\n" +
                            "💾 Saved sounds: %d\n" +
                            "🎯 Saved assignments: %d\n" +
                            "📊 Total size: %.2f MB\n\n" +
                            "💡 Storage location: App internal storage\n" +
                            "🔒 Data persists between app sessions",
                    savedSoundsCount, assignmentsCount, sizeMB
            );

            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setTitle("📊 Storage Information")
                        .setMessage(info)
                        .setPositiveButton("OK", null)
                        .show();
            });

        } catch (Exception e) {
            Log.e(TAG, "Error getting storage info", e);
            runOnUiThread(() -> Toast.makeText(this, "❌ Error getting storage info", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // JavaScript Interface
    public class AndroidInterface {
        private MainActivity context;

        AndroidInterface(MainActivity context) {
            this.context = context;
        }

        @JavascriptInterface
        public void openSoundPicker() {
            Log.d(TAG, "Opening sound picker");

            if (!hasAudioPermission()) {
                Log.w(TAG, "Audio permission not granted");
                runOnUiThread(() -> {
                    new AlertDialog.Builder(context)
                            .setTitle("🔐 Permission Required")
                            .setMessage("Audio permission is needed to browse and select sound files.\n\nWould you like to grant permission now?")
                            .setPositiveButton("Grant Permission", (dialog, which) -> {
                                permissionsChecked = false;
                                checkAndRequestPermissions();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
                return;
            }

            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("audio/*");

                    // Add multiple MIME types for better compatibility
                    String[] mimeTypes = {
                            "audio/mpeg", "audio/mp3", "audio/wav", "audio/ogg",
                            "audio/mp4", "audio/aac", "audio/flac", "audio/x-wav",
                            "audio/3gpp", "audio/amr"
                    };
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

                    Log.d(TAG, "Starting file picker intent");
                    startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
                } catch (Exception e) {
                    Log.e(TAG, "Error opening file picker", e);
                    Toast.makeText(context, "❌ Cannot open file picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void saveMatchReport(String reportContent, String filename) {
            Log.d(TAG, "Saving match report: " + filename);

            runOnUiThread(() -> {
                try {
                    saveReportToDownloads(reportContent, filename);
                } catch (Exception e) {
                    Log.e(TAG, "Error saving report", e);
                    Toast.makeText(context, "❌ Error saving report: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> {
                Log.d(TAG, "Toast: " + message);
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void logMessage(String message) {
            Log.d(TAG, "JS: " + message);
        }

        @JavascriptInterface
        public void requestPermissions() {
            Log.d(TAG, "Permissions requested from JS");
            runOnUiThread(() -> {
                permissionsChecked = false;
                checkAndRequestPermissions();
            });
        }

        @JavascriptInterface
        public void playGoalSound(String team, String player) {
            Log.d(TAG, "Goal sound played for: " + team + " - " + player);
            // This method is called from JavaScript when a goal sound is played
            // You can add additional native sound handling here if needed
        }

        @JavascriptInterface
        public void playSound(String soundName, String description) {
            Log.d(TAG, "Sound played: " + soundName + " (" + description + ")");
            // This method is called from JavaScript when any sound is played
            // You can add additional native sound handling here if needed
        }

        @JavascriptInterface
        public void playCustomSound(int soundNumber, String soundName) {
            Log.d(TAG, "Custom sound played: " + soundName + " (Button " + soundNumber + ")");
            // This method is called from JavaScript when a custom sound is played
            // You can add additional native sound handling here if needed
        }

        // Modified: Save sounds to Android storage - now accepts single parameter
        @JavascriptInterface
        public void saveSounds(String soundsJson, String unused) {
            Log.d(TAG, "Saving sounds to Android storage");
            saveSoundsToStorage(soundsJson);
        }

        // Load sounds from Android storage
        @JavascriptInterface
        public void loadSounds() {
            Log.d(TAG, "Loading sounds from Android storage");
            loadSavedSoundsToWebView();
        }

        @JavascriptInterface
        public void clearAllSoundFiles(String[] uriStrings) {
            ContentResolver resolver = getApplicationContext().getContentResolver();
            int deletedCount = 0;

            for (String uriStr : uriStrings) {
                try {
                    Uri uri = Uri.parse(uriStr);
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    boolean deleted = DocumentsContract.deleteDocument(resolver, uri);
                    if (deleted) deletedCount++;
                } catch (Exception e) {
                    Log.e("WebApp", "Failed to delete URI: " + uriStr, e);
                }
            }

            Log.d("WebApp", "Deleted " + deletedCount + " sound files.");
        }


        // FIXED: Get storage information - calls renamed method
        @JavascriptInterface
        public void getStorageInfo() {
            Log.d(TAG, "Getting storage information");
            showStorageInfoDialog();
        }
    }

    private void saveReportToDownloads(String content, String filename) {
        try {
            ContentResolver resolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            }

            Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues);

            if (uri != null) {
                try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                    if (outputStream != null) {
                        outputStream.write(content.getBytes());
                        outputStream.flush();

                        Log.d(TAG, "Report saved successfully: " + filename);
                        Toast.makeText(this, "✅ Report saved to Downloads: " + filename, Toast.LENGTH_LONG).show();
                    } else {
                        throw new Exception("Cannot write to file");
                    }
                }
            } else {
                throw new Exception("Cannot create file in Downloads");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving report", e);
            Toast.makeText(this, "❌ Error saving report. Check app permissions in Settings.", Toast.LENGTH_LONG).show();
        }
    }
}
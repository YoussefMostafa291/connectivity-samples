/*
 * Copyright (C) 2018 Google Inc. All Rights Reserved.
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
package com.example.android.wifirttscan;

import android.Manifest.permission;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Bundle;
import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Displays ranging information about a particular access point chosen by the user. Uses {@link
 * Handler} to trigger new requests based on
 */
public class AccessPointRangingResultsActivity extends AppCompatActivity {
    private static final String TAG = "APRRActivity";

    public static final String SCAN_RESULT_EXTRA =
            "com.example.android.wifirttscan.extra.SCAN_RESULT";

    private static final int SAMPLE_SIZE_DEFAULT = 50;
    private static final int CREATE_CSV_FILE_REQUEST = 4102;
    private static final int MILLISECONDS_DELAY_BEFORE_NEW_RANGING_REQUEST_DEFAULT = 1000;

    // UI Elements.
    private TextView mSsidTextView;
    private TextView mBssidTextView;

    private TextView mRangeTextView;
    private TextView mRangeMeanTextView;
    private TextView mRangeSDTextView;
    private TextView mRangeSDMeanTextView;
    private TextView mRssiTextView;
    private TextView mSuccessesInBurstTextView;
    private TextView mSuccessRatioTextView;
    private TextView mNumberOfRequestsTextView;

    private EditText mSampleSizeEditText;
    private EditText mMillisecondsDelayBeforeNewRangingRequestEditText;
    private EditText mCoordinateXEditText;
    private EditText mCoordinateYEditText;
    private EditText mServerUrlEditText;
    private TextView mServerStatusTextView;
    private final ExecutorService mUploadExecutor = Executors.newSingleThreadExecutor();
    private final ArrayList<String> mCsvRows = new ArrayList<>();

    // Non UI variables.
    private ScanResult mScanResult;
    private String mMAC;

    private int mNumberOfRangeRequests;
    private int mNumberOfSuccessfulRangeRequests;

    private int mMillisecondsDelayBeforeNewRangingRequest;

    // Max sample size to calculate average for
    // 1. Distance to device (getDistanceMm) over time
    // 2. Standard deviation of the measured distance to the device (getDistanceStdDevMm) over time
    // Note: A RangeRequest result already consists of the average of 7 readings from a burst,
    // so the average in (1) is the average of these averages.
    private int mSampleSize;

    // Used to loop over a list of distances to calculate averages (ensures data structure never
    // get larger than sample size).
    private int mStatisticRangeHistoryEndIndex;
    private ArrayList<Integer> mStatisticRangeHistory;

    // Used to loop over a list of the standard deviation of the measured distance to calculate
    // averages  (ensures data structure never get larger than sample size).
    private int mStatisticRangeSDHistoryEndIndex;
    private ArrayList<Integer> mStatisticRangeSDHistory;

    private WifiRttManager mWifiRttManager;
    private RttRangingResultCallback mRttRangingResultCallback;

    // Triggers additional RangingRequests with delay (mMillisecondsDelayBeforeNewRangingRequest).
    final Handler mRangeRequestDelayHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_access_point_ranging_results);

        // Initializes UI elements.
        mSsidTextView = findViewById(R.id.ssid);
        mBssidTextView = findViewById(R.id.bssid);

        mRangeTextView = findViewById(R.id.range_value);
        mRangeMeanTextView = findViewById(R.id.range_mean_value);
        mRangeSDTextView = findViewById(R.id.range_sd_value);
        mRangeSDMeanTextView = findViewById(R.id.range_sd_mean_value);
        mRssiTextView = findViewById(R.id.rssi_value);
        mSuccessesInBurstTextView = findViewById(R.id.successes_in_burst_value);
        mSuccessRatioTextView = findViewById(R.id.success_ratio_value);
        mNumberOfRequestsTextView = findViewById(R.id.number_of_requests_value);

        mSampleSizeEditText = findViewById(R.id.stats_window_size_edit_value);
        mSampleSizeEditText.setText(SAMPLE_SIZE_DEFAULT + "");

        mCoordinateXEditText = findViewById(R.id.coordinate_x_edit);
        mCoordinateYEditText = findViewById(R.id.coordinate_y_edit);
        mServerUrlEditText = findViewById(R.id.server_url_edit);
        mServerStatusTextView = findViewById(R.id.server_status);
        mCsvRows.clear();
        mCsvRows.add("timestamp,x_m,y_m,rtt_m,rssi_dbm,rtt_stddev_m,bssid,ssid");

        mMillisecondsDelayBeforeNewRangingRequestEditText =
                findViewById(R.id.ranging_period_edit_value);
        mMillisecondsDelayBeforeNewRangingRequestEditText.setText(
                MILLISECONDS_DELAY_BEFORE_NEW_RANGING_REQUEST_DEFAULT + "");

        // Retrieve ScanResult from Intent.
        Intent intent = getIntent();
        mScanResult = intent.getParcelableExtra(SCAN_RESULT_EXTRA);

        if (mScanResult == null) {
            finish();
        }

        mMAC = mScanResult.BSSID;

        mSsidTextView.setText(mScanResult.SSID);
        mBssidTextView.setText(mScanResult.BSSID);

        mWifiRttManager = (WifiRttManager) getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
        mRttRangingResultCallback = new RttRangingResultCallback();

        // Used to store range (distance) and rangeSd (standard deviation of the measured distance)
        // history to calculate averages.
        mStatisticRangeHistory = new ArrayList<>();
        mStatisticRangeSDHistory = new ArrayList<>();

        resetData();

        startRangingRequest();
    }

    private void resetData() {
        mSampleSize = Integer.parseInt(mSampleSizeEditText.getText().toString());

        mMillisecondsDelayBeforeNewRangingRequest =
                Integer.parseInt(
                        mMillisecondsDelayBeforeNewRangingRequestEditText.getText().toString());

        mNumberOfSuccessfulRangeRequests = 0;
        mNumberOfRangeRequests = 0;

        mStatisticRangeHistoryEndIndex = 0;
        mStatisticRangeHistory.clear();

        mStatisticRangeSDHistoryEndIndex = 0;
        mStatisticRangeSDHistory.clear();
    }

    private void startRangingRequest() {
        // Permission for fine location should already be granted via MainActivity (you can't get
        // to this class unless you already have permission. If they get to this class, then disable
        // fine location permission, we kick them back to main activity.
        if (ActivityCompat.checkSelfPermission(this, permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            finish();
        }

        mNumberOfRangeRequests++;

        RangingRequest rangingRequest =
                new RangingRequest.Builder().addAccessPoint(mScanResult).build();

        mWifiRttManager.startRanging(
                rangingRequest, getApplication().getMainExecutor(), mRttRangingResultCallback);
    }

    // Calculates average distance based on stored history.
    private float getDistanceMean() {
        float distanceSum = 0;

        for (int distance : mStatisticRangeHistory) {
            distanceSum += distance;
        }

        return distanceSum / mStatisticRangeHistory.size();
    }

    // Adds distance to history. If larger than sample size value, loops back over and replaces the
    // oldest distance record in the list.
    private void addDistanceToHistory(int distance) {

        if (mStatisticRangeHistory.size() >= mSampleSize) {

            if (mStatisticRangeHistoryEndIndex >= mSampleSize) {
                mStatisticRangeHistoryEndIndex = 0;
            }

            mStatisticRangeHistory.set(mStatisticRangeHistoryEndIndex, distance);
            mStatisticRangeHistoryEndIndex++;

        } else {
            mStatisticRangeHistory.add(distance);
        }
    }

    // Calculates standard deviation of the measured distance based on stored history.
    private float getStandardDeviationOfDistanceMean() {
        float distanceSdSum = 0;

        for (int distanceSd : mStatisticRangeSDHistory) {
            distanceSdSum += distanceSd;
        }

        return distanceSdSum / mStatisticRangeHistory.size();
    }

    // Adds standard deviation of the measured distance to history. If larger than sample size
    // value, loops back over and replaces the oldest distance record in the list.
    private void addStandardDeviationOfDistanceToHistory(int distanceSd) {

        if (mStatisticRangeSDHistory.size() >= mSampleSize) {

            if (mStatisticRangeSDHistoryEndIndex >= mSampleSize) {
                mStatisticRangeSDHistoryEndIndex = 0;
            }

            mStatisticRangeSDHistory.set(mStatisticRangeSDHistoryEndIndex, distanceSd);
            mStatisticRangeSDHistoryEndIndex++;

        } else {
            mStatisticRangeSDHistory.add(distanceSd);
        }
    }

    public void onResetButtonClick(View view) {
        resetData();
    }

    public void onExportCsvButtonClick(View view) {
        if (mCsvRows.size() <= 1) {
            Toast.makeText(this, "No successful measurements recorded yet. Enter X/Y and wait for RTT results.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "wifi_rtt_measurements.csv");
        startActivityForResult(intent, CREATE_CSV_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_CSV_FILE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
                 OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8")) {
                for (String row : mCsvRows) {
                    writer.write(row);
                    writer.write("\n");
                }
                writer.flush();
                Toast.makeText(this, "CSV exported: " + (mCsvRows.size() - 1) + " measurements", Toast.LENGTH_LONG).show();
            } catch (IOException | NullPointerException e) {
                Log.e(TAG, "Unable to export CSV", e);
                Toast.makeText(this, "Could not save CSV file", Toast.LENGTH_LONG).show();
            }
        }
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private void recordMeasurement(final RangingResult result) {
        final String xText = mCoordinateXEditText.getText().toString().trim();
        final String yText = mCoordinateYEditText.getText().toString().trim();
        final String serverUrl = mServerUrlEditText.getText().toString().trim();
        if (xText.isEmpty() || yText.isEmpty()) {
            setServerStatus("Enter X and Y to send measurements.");
            return;
        }
        if (serverUrl.isEmpty()) {
            setServerStatus("Enter laptop server URL, e.g. http://192.168.1.10:5000/measurements");
            return;
        }
        try {
            final double x = Double.parseDouble(xText);
            final double y = Double.parseDouble(yText);
            if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)) {
                setServerStatus("X and Y must be finite numbers.");
                return;
            }
            final String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
            final double rttMeters = result.getDistanceMm() / 1000.0;
            final double stdDevMeters = result.getDistanceStdDevMm() / 1000.0;
            final String ssid = mScanResult != null ? mScanResult.SSID : "";
            final String bssid = mMAC;
            mCsvRows.add(csvEscape(timestamp) + "," + x + "," + y + "," + rttMeters + ","
                    + result.getRssi() + "," + stdDevMeters + "," + csvEscape(bssid) + "," + csvEscape(ssid));
            final JSONObject payload = new JSONObject();
            payload.put("timestamp", timestamp);
            payload.put("x_m", x);
            payload.put("y_m", y);
            payload.put("rtt_m", rttMeters);
            payload.put("rssi_dbm", result.getRssi());
            payload.put("rtt_stddev_m", stdDevMeters);
            payload.put("bssid", bssid);
            payload.put("ssid", ssid);
            mUploadExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection connection = null;
                    try {
                        URL url = new URL(serverUrl);
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("POST");
                        connection.setConnectTimeout(3000);
                        connection.setReadTimeout(3000);
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                        connection.setRequestProperty("Accept", "application/json");
                        byte[] body = payload.toString().getBytes("UTF-8");
                        connection.setFixedLengthStreamingMode(body.length);
                        OutputStream output = connection.getOutputStream();
                        try {
                            output.write(body);
                            output.flush();
                        } finally {
                            output.close();
                        }
                        int status = connection.getResponseCode();
                        if (status >= 200 && status < 300) {
                            setServerStatus("Server connected — last upload HTTP " + status);
                        } else {
                            setServerStatus("Server returned HTTP " + status + ". Check endpoint/logs.");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Measurement upload failed", e);
                        setServerStatus("Upload failed: " + e.getClass().getSimpleName()
                                + ". Check URL, Wi-Fi and laptop firewall.");
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                }
            });
        } catch (NumberFormatException e) {
            setServerStatus("X and Y must be valid numbers.");
        } catch (JSONException e) {
            Log.e(TAG, "Could not create measurement JSON", e);
            setServerStatus("Could not prepare measurement.");
        }
    }

    private void setServerStatus(final String message) {
        if (mServerStatusTextView == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mServerStatusTextView.setText(message);
            }
        });
    }

    // Class that handles callbacks for all RangingRequests and issues new RangingRequests.
    private class RttRangingResultCallback extends RangingResultCallback {

        private void queueNextRangingRequest() {
            mRangeRequestDelayHandler.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            startRangingRequest();
                        }
                    },
                    mMillisecondsDelayBeforeNewRangingRequest);
        }

        @Override
        public void onRangingFailure(int code) {
            Log.d(TAG, "onRangingFailure() code: " + code);
            queueNextRangingRequest();
        }

        @Override
        public void onRangingResults(@NonNull List<RangingResult> list) {
            Log.d(TAG, "onRangingResults(): " + list);

            // Because we are only requesting RangingResult for one access point (not multiple
            // access points), this will only ever be one. (Use loops when requesting RangingResults
            // for multiple access points.)
            if (list.size() == 1) {

                RangingResult rangingResult = list.get(0);

                if (mMAC.equals(rangingResult.getMacAddress().toString())) {

                    if (rangingResult.getStatus() == RangingResult.STATUS_SUCCESS) {

                        mNumberOfSuccessfulRangeRequests++;

                        mRangeTextView.setText((rangingResult.getDistanceMm() / 1000f) + "");
                        addDistanceToHistory(rangingResult.getDistanceMm());
                        mRangeMeanTextView.setText((getDistanceMean() / 1000f) + "");

                        mRangeSDTextView.setText(
                                (rangingResult.getDistanceStdDevMm() / 1000f) + "");
                        addStandardDeviationOfDistanceToHistory(
                                rangingResult.getDistanceStdDevMm());
                        mRangeSDMeanTextView.setText(
                                (getStandardDeviationOfDistanceMean() / 1000f) + "");

                        mRssiTextView.setText(rangingResult.getRssi() + "");
                        recordMeasurement(rangingResult);
                        mSuccessesInBurstTextView.setText(
                                rangingResult.getNumSuccessfulMeasurements()
                                        + "/"
                                        + rangingResult.getNumAttemptedMeasurements());

                        float successRatio =
                                ((float) mNumberOfSuccessfulRangeRequests
                                                / (float) mNumberOfRangeRequests)
                                        * 100;
                        mSuccessRatioTextView.setText(successRatio + "%");

                        mNumberOfRequestsTextView.setText(mNumberOfRangeRequests + "");

                    } else if (rangingResult.getStatus()
                            == RangingResult.STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC) {
                        Log.d(TAG, "RangingResult failed (AP doesn't support IEEE80211 MC.");

                    } else {
                        Log.d(TAG, "RangingResult failed.");
                    }

                } else {
                    Toast.makeText(
                                    getApplicationContext(),
                                    R.string
                                            .mac_mismatch_message_activity_access_point_ranging_results,
                                    Toast.LENGTH_LONG)
                            .show();
                }
            }

            queueNextRangingRequest();
        }
    }
}

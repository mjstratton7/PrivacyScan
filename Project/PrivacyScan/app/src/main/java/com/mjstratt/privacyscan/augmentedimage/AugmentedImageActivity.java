/*
 * Copyright 2018 Google LLC
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

package com.mjstratt.privacyscan.augmentedimage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.android.material.snackbar.Snackbar;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.mjstratt.privacyscan.augmentedimage.rendering.AugmentedImageRenderer;
import com.neovisionaries.bluetooth.ble.advertising.ADPayloadParser;
import com.neovisionaries.bluetooth.ble.advertising.ADStructure;
import com.neovisionaries.bluetooth.ble.advertising.EddystoneTLM;
import com.neovisionaries.bluetooth.ble.advertising.EddystoneUID;
import com.neovisionaries.bluetooth.ble.advertising.EddystoneURL;

/**
 * This app is based in part on the augmented_image_java sample project provided by Google in the ARCore
 * SDK, with some functionality removed, and some extended further.
 *
 * <p>PrivacyScan currently assumes all images are static or moving slowly with a large occupation of
 * the device screen. If the target is actively moving, check AugmentedImage.getTrackingMethod()
 * and render only when the tracking method equals to FULL_TRACKING. See details in <a
 * href="https://developers.google.com/ar/develop/java/augmented-images/">Recognize and Augment
 * Images</a>.
 */
public class AugmentedImageActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = AugmentedImageActivity.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;
  private ImageView fitToScanView;
  private RequestManager glideRequestManager;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final AugmentedImageRenderer augmentedImageRenderer = new AugmentedImageRenderer();

  private boolean shouldConfigureSession = false;

  // Augmented image and its associated center pose anchor, keyed by index of the augmented image in
  // the database.
  private final Map<Integer, Pair<AugmentedImage, Anchor>> augmentedImageMap = new HashMap<>();

  // Bluetooth LE Scanning Related ----------------------------------------------------------
  private BluetoothAdapter mBluetoothAdapter;
  private BluetoothLeScanner mLEScanner;
  private ScanSettings settings;
  private List<ScanFilter> filters;
  private boolean mScanning;
  private Handler mHandler;

  private static final int REQUEST_ENABLE_BT = 1;
  // Stops scanning after 10 seconds.
  private static final long SCAN_PERIOD = 10000;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    surfaceView.setOnLongClickListener(new View.OnLongClickListener() {
      @Override
      public boolean onLongClick(View v) {
        // Restart App
        recreate();

        return true;
      }
    });

    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    fitToScanView = findViewById(R.id.image_view_fit_to_scan);
    glideRequestManager = Glide.with(this);
    glideRequestManager
            .load(Uri.parse("file:///android_asset/fit_to_scan.png"))
            .into(fitToScanView);

    // BTLE Scanning -------------------------------------------------------------------------------
    mHandler = new Handler();

    // Use this check to determine whether BLE is supported on the device.  Then you can
    // selectively disable BLE-related features.
    if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      Toast.makeText(this, "Bluetooth Low Energy not available on this device.", Toast.LENGTH_SHORT).show();
      finish();
    }

    // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
    // BluetoothAdapter through BluetoothManager.
    final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(this.BLUETOOTH_SERVICE);
    mBluetoothAdapter = bluetoothManager.getAdapter();
    // Checks if Bluetooth is supported on the device.
    if (mBluetoothAdapter == null) {
      Toast.makeText(this, "Bluetooth not available on this device.", Toast.LENGTH_SHORT).show();
      finish();
      return;
    }

    installRequested = false;
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @SuppressLint("MissingPermission")
  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        session = new Session(/* context = */ this);
      } catch (UnavailableArcoreNotInstalledException
              | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (Exception e) {
        message = "This device does not support AR";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }

      shouldConfigureSession = true;
    }

    if (shouldConfigureSession) {
      configureSession();
      shouldConfigureSession = false;
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }
    surfaceView.onResume();
    displayRotationHelper.onResume();

    fitToScanView.setVisibility(View.VISIBLE);
    //updateDetailDisplay("", "", "", new String[0]);

    // BTLE Scan
    mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();
    settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build();
    filters = new ArrayList<ScanFilter>();

    scanLeDevice(true);
  }

//  @Override
//  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
//    // User chose not to enable Bluetooth.
//    if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
//      finish();
//      return;
//    }
//
//    super.onActivityResult(requestCode, resultCode, data);
//  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();

      // Disable BT Scanning
      scanLeDevice(false);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(
                      this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG)
              .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }

      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(/*context=*/ this);
      augmentedImageRenderer.createOnGlThread(/*context=*/ this);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame);

      // Get projection matrix.
      float[] projmtx = new float[16];
      camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

      // Get camera matrix and draw.
      float[] viewmtx = new float[16];
      camera.getViewMatrix(viewmtx, 0);

      // Compute lighting from average intensity of the image.
      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      // Visualize augmented images.
      drawAugmentedImages(frame, projmtx, viewmtx, colorCorrectionRgba);

    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  private void configureSession() {
    Config config = new Config(session);
    config.setFocusMode(Config.FocusMode.AUTO);
    if (!setupAugmentedImageDatabase(config)) {
      messageSnackbarHelper.showError(this, "Could not setup augmented image database");
    }
    session.configure(config);
  }

  private void drawAugmentedImages(Frame frame, float[] projmtx, float[] viewmtx, float[] colorCorrectionRgba) {
    Collection<AugmentedImage> updatedAugmentedImages =
            frame.getUpdatedTrackables(AugmentedImage.class);

    // Iterate to update augmentedImageMap, remove elements we cannot draw.
    for (AugmentedImage augmentedImage : updatedAugmentedImages) {
      switch (augmentedImage.getTrackingState()) {
        case PAUSED:
          // When an image is in PAUSED state, but the camera is not PAUSED, it has been detected,
          // but not yet tracked. Stops updating when tracked, to be removed eventually.

          // Printout Active Image Data (Debug)
          // String text = String.format("Detected Image %d \nData: %s", augmentedImage.getIndex(), augmentedImage.getName());
          // messageSnackbarHelper.showMessage(this, text);

          // Process Data for QR Device
          processQRData(augmentedImage);

          break;

        case TRACKING:
          // Have to switch to UI Thread to update View.
          this.runOnUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      fitToScanView.setVisibility(View.GONE);
                    }
                  });

          // Create a new anchor for newly found images.
          if (!augmentedImageMap.containsKey(augmentedImage.getIndex())) {
            Anchor centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.getCenterPose());
            augmentedImageMap.put(augmentedImage.getIndex(), Pair.create(augmentedImage, centerPoseAnchor));
          }
          break;

        case STOPPED:
          augmentedImageMap.remove(augmentedImage.getIndex());
          break;

        default:
          break;
      }
    }

    // Draw all images in augmentedImageMap
    for (Pair<AugmentedImage, Anchor> pair : augmentedImageMap.values()) {
      AugmentedImage augmentedImage = pair.first;
      Anchor centerAnchor = augmentedImageMap.get(augmentedImage.getIndex()).second;

      switch (augmentedImage.getTrackingState()) {
        case TRACKING:
          // Not tracking a static object necessarily, only render when fully tracking object.
          if (augmentedImage.getTrackingMethod() == AugmentedImage.TrackingMethod.FULL_TRACKING) {
            augmentedImageRenderer.draw(
                    viewmtx, projmtx, augmentedImage, centerAnchor, colorCorrectionRgba);
          } else {
            // Do nothing
          }
          break;

        default:
          break;
      }
    }
  }

  private boolean setupAugmentedImageDatabase(Config config) {
    AugmentedImageDatabase augmentedImageDatabase;

    // load a pre-existing augmented image database of device labels.
    try (InputStream is = getAssets().open("deviceLabels.imgdb")) {
      augmentedImageDatabase = AugmentedImageDatabase.deserialize(session, is);
    } catch (IOException e) {
      Log.e(TAG, "IO exception loading augmented image database.", e);
      return false;
    }

    config.setAugmentedImageDatabase(augmentedImageDatabase);
    return true;
  }

  private void processQRData(AugmentedImage input) {
    // Create Data Structure to hold processed device rawdata.
    // Fields: Type Brand Model
    String[] qrData = new String[3];

    // Data to be parsed
    // Ex. '[TYPE][BRAND][MODEL][DATA].png'
    String rawdata = input.getName();

    // Remove first'[' and last '].png'
    rawdata = rawdata.substring(1, rawdata.length() - 5);

    // Seperate by '][' delimiter to access individual fields
    String[] proc_data = rawdata.split("]\\[");

    String packageName = getPackageName();

    // Process type field, do string value lookup
    int typeID = getResources().getIdentifier(proc_data[0].toString(), "string", packageName);
    String typeValue = this.getString(typeID);
    // Save type to result data
    qrData[0] = typeValue;

    // Process brand field, do string value lookup
    int brandID = getResources().getIdentifier(proc_data[1].toString(), "string", packageName);
    String brandValue = this.getString(brandID);
    // Save brand to result data
    qrData[1] = brandValue;

    // Save model to result data
    qrData[2] = proc_data[2].toString();

    // Process data type field(s), do string value lookup

    // First split all rawdata values in original rawdata
    String[] data_split = proc_data[3].split(",");

    // Lookup all individual data type values, store in array
    String[] data_parsed = new String[data_split.length];
    for (int i = 0; i < data_split.length; i++) {
      int dataID = getResources().getIdentifier(data_split[i], "string", packageName);
      data_parsed[i] = this.getString(dataID);
    }

    // Debug
    // String outputText = "Type: " + getResources().getString(typeID) +
    //          "\nBrand: " + getResources().getString(brandID) +
    //          "\nModel: " + proc_data[2].toString() +
    //          "\nData: " + getResources().getString(dataID);

    // Build Output String
    String foundDevice = getResources().getString(R.string.device_found) + " \n" + getResources().getString(brandID) + " " + proc_data[2].toString();

    // Show a graphical indication of this device being found and pass device data
    buildSnackbarDisplay(foundDevice, qrData, data_parsed);
  }

  private void processBTLEData(BluetoothDevice device, byte[] scanRecord) {
    String[] device_types = { "Unknown", "Camera", "Doorbell", "Light", "Speaker", "Switch", "Thermostat" };
    String[] device_brands = { "Unknown", "Arlo", "Nest", "Ring", "Hue", "Google" };
    String[] device_models = { "Unknown", "Arlo", "Wired", "Video", "A19", "GU10", "Lightstrip", "Home Max", "Mini", "Dimmer", "Learning", "Thermostat" };
    String[] device_data_types = { "None", "Personally Identifiable Data", "Audio", "Video", "Presence", "Information", "Location" };

    // Create Data Structure to hold processed device data.
    // Fields: Type Brand Model
    String[] btleData = new String[3];
    String[] deviceDataTypes = null;

    // Build Output String
    String foundDevice = getResources().getString(R.string.device_found_btle) + " \n" + device.getName() + " (" + device.getAddress() + ")";

    // Parse the payload of the advertisement packet
    // as a list of AD structures.
    // SRC: https://stackoverflow.com/questions/32836728/how-to-identify-eddystone-url-and-uid
    List<ADStructure> structures = ADPayloadParser.getInstance().parse(scanRecord);

    // For each AD structure contained in the advertisement packet.
    for (ADStructure structure : structures) {
      // If the AD structure represents Eddystone UID.
      if (structure instanceof EddystoneUID) {
        // Eddystone UID
        EddystoneUID es = (EddystoneUID) structure;

        // Get string data of the beacon
        String namespaceIdStr = es.getNamespaceIdAsString();  // 0x626c7565636861726d31
        String instanceIdStr = es.getInstanceIdAsString();    // 0x000000000001

        // Cannot use namespaceID for anything useful, must be 10 byte UUID namespace
        // Can use InstanceID to hold usable data, though

        // Process InstanceID for device information
        //    0x 00 0 00 0000000
        // Field 1  2 3  4
        //
        // Field 1 - Device Type (Int index)
        // Field 2 - Brand       (Int Index)
        // Field 3 - Model       (Int Index)
        // Field 4 - Data Types  (Flags)

        // Get Type from InstanceID
        String tIndex = instanceIdStr.substring(0, instanceIdStr.length() - 10);
        btleData[0] = device_types[Integer.parseInt(tIndex)];

        // Get Brand from InstanceID
        String bIndex = instanceIdStr.substring(2, instanceIdStr.length() - 9);
        btleData[1] = device_brands[Integer.parseInt(bIndex)];

        // Get Model from InstanceID
        String mIndex = instanceIdStr.substring(3, instanceIdStr.length() - 7);
        btleData[2] = device_models[Integer.parseInt(mIndex)];

        // Get Data Types flags from InstanceID
        String dFlags = instanceIdStr.substring(5);

        // Get amount of permissions (1's) in flags
        int type_amount = instanceIdStr.length() - instanceIdStr.replaceAll("1", "").length();

        // Then set size of return data type string array based on that amount
        deviceDataTypes = new String[type_amount];

        // Check all flags
        int count = 0;
        for (int i = 0; i < dFlags.length(); i++) {
          // Flag (char) set to true (set to 1)
          if (dFlags.charAt(i) == '1') {
            // Add the data type from the reference list to the current device list
            deviceDataTypes[count] = device_data_types[i];

            // Increment counter / index of return string array
            count++;
          }
        }
      }
      // If the AD structure represents Eddystone URL.
      else if (structure instanceof EddystoneURL)
      {
        // Eddystone URL (backup plan)
        EddystoneURL es = (EddystoneURL)structure;

        String namespaceURLStr = es.getURL().toString();
      }
    }

    // Show a graphical indication of this device being found and pass device data
    buildSnackbarDisplay(foundDevice, btleData, deviceDataTypes);
  }

  private void buildSnackbarDisplay(String displayString, String[] deviceData, String[] deviceDataTypes) {
    // Show a graphical indication of passed in data
    Snackbar snackbar = Snackbar
            .make(fitToScanView, displayString, Snackbar.LENGTH_INDEFINITE)
            .setAction("More Info", new View.OnClickListener() {
              @Override
              public void onClick(View view) {

                if ( (deviceData != null) && (deviceDataTypes != null) ) {
                  // Open Device Information Dialog
                  buildDeviceInfoDisplay(deviceData, deviceDataTypes);
                }
                else {
                  // Print Error Text
                  Toast.makeText(AugmentedImageActivity.this,
                                 getResources().getString(R.string.device_data_error),
                                 Toast.LENGTH_LONG).show();
                }

              }
            });

    snackbar.show();
  }

  private void buildDeviceInfoDisplay (String[] deviceData, String[] deviceDataTypes) {
    // Fields: Type Brand Model
    String outputText = "\nType:  " + deviceData[0] +
              "\nBrand: " + deviceData[1] +
              "\nModel: " + deviceData[2] +
              "\nData: \n";

    // Go through all data types passed in
    for (String i : deviceDataTypes) {
      // Build name of description string resource
      String descLookup = i.toUpperCase(Locale.ROOT) + "_desc";
      // Lookup description string resource
      int typeID = getResources().getIdentifier(descLookup, "string", this.getPackageName());
      // Store it
      String description = this.getString(typeID);

      // Print out data type and description
      outputText += "\n" + i + ": " + description + "\n";
    }

    // Create the object of AlertDialog Builder class
    AlertDialog.Builder builder = new AlertDialog.Builder(AugmentedImageActivity.this);

    // Set Alert Title
    builder.setTitle(getResources().getString(R.string.device_info));

    // Set the message show for the Alert time
    builder.setMessage(outputText);

    // Set Cancelable false for when the user clicks on the outside the Dialog Box then it will remain show
    builder.setCancelable(false);

    // Set the positive button with yes name Lambda OnClickListener method is use of DialogInterface interface.
    builder.setPositiveButton("Okay", (DialogInterface.OnClickListener) (dialog, which) -> {
      // When the user click yes button then app will close
      // Do nothing, just dismiss
    });

    // Create the Alert dialog
    AlertDialog alertDialog = builder.create();
    // Show the Alert Dialog box
    alertDialog.show();
  }

  @SuppressLint("MissingPermission")
  private void scanLeDevice(final boolean enable) {
    if (enable) {
      // Stops scanning after a pre-defined scan period.
      mHandler.postDelayed(new Runnable() {
        public void run() {
          mScanning = false;
          mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
      }, SCAN_PERIOD);
      mScanning = true;
      mBluetoothAdapter.startLeScan(mLeScanCallback);
    } else {
      mScanning = false;
      mBluetoothAdapter.stopLeScan(mLeScanCallback);
    }
  }

  private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
    private boolean found = false;

    @Override
    public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          // BT LE Device Found

          // Build and print Output String (Debug)
          //String foundDevice = R.string.device_found_btle + device.getName() + " (" + device.getAddress() + ")";
          //Toast.makeText(AugmentedImageActivity.this, foundDevice, Toast.LENGTH_LONG).show();

          // Determine if this device is one we are looking for.
          // Check that name of the device is not NULL, not same as last scanned device, and contains 'IDENT' in name
          if ( (device.getName() != null) && (device.getName().toUpperCase(Locale.ROOT).contains("IDENT")) ) {

              // Process Data for BTLE Device
              processBTLEData(device, scanRecord);

          }

        }
      });

    }

  };

}


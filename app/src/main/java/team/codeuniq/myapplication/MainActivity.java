package team.codeuniq.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity implements SensorEventListener, LocationListener {

    private static final String TAG = "PotholeDetect";
    private static final int PERMISSION_REQUEST_CODE = 123;

    // UI Elements
    private TextView statusText, sensorDataText, speedText, detectionCountText;
    private ToggleButton detectionToggle;
    private Button settingsBtn, historyBtn, logoutBtn, dummyDataBtn, addPhotoBtn, captureButton;
    private PreviewView viewFinder;
    private View overlayBox;

    // Sensors and Location
    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope;
    private LocationManager locationManager;
    private Location currentLocation;
    private FusedLocationProviderClient fusedLocationClient;

    // Camera
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ImageCapture imageCapture;

    // Firebase
    private FirebaseAuth firebaseAuth;
    private FirebaseFirestore firestore;
    private StorageReference storageRef;

    // Detection Variables
    private boolean isDetectionActive = false;
    private boolean isDriving = false;
    private boolean isPhoneInUse = false;
    private float currentSpeed = 0f;
    private int detectionCount = 0;

    // Sensor Data
    private float[] accelerometerValues = new float[3];
    private float[] gyroscopeValues = new float[3];

    // Detection Thresholds
    private static final float POTHOLE_THRESHOLD = 12.0f;
    private static final float SPEED_THRESHOLD = 10.0f; // km/h
    private static final int CONFIDENCE_THRESHOLD = 75;

    private long lastDetectionTime = 0; // milliseconds
    private static final long DETECTION_COOLDOWN_MS = 5000; // e.g., 5 seconds gap

    // Handler for UI updates
    private Handler uiHandler = new Handler();

    // Required permissions
    private final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        initializeSensors();
        initializeFirebase();
        initializeCamera();
        checkPermissions();
        setupEventListeners();

        // Initially hide camera UI
        hideCameraUI();
    }

    private void initializeViews() {
        statusText = findViewById(R.id.statusText);
        sensorDataText = findViewById(R.id.sensorDataText);
        speedText = findViewById(R.id.speedText);
        detectionCountText = findViewById(R.id.detectionCountText);
        detectionToggle = findViewById(R.id.detectionToggle);
        settingsBtn = findViewById(R.id.settingsBtn);
        historyBtn = findViewById(R.id.historyBtn);
        logoutBtn = findViewById(R.id.logoutBtn);
        dummyDataBtn = findViewById(R.id.dummyDataBtn);
        addPhotoBtn = findViewById(R.id.addPhotoBtn);
        captureButton = findViewById(R.id.captureButton);
        viewFinder = findViewById(R.id.viewFinder);
        overlayBox = findViewById(R.id.overlayBox);
    }

    private void initializeSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    private void initializeFirebase() {
        firebaseAuth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();
        storageRef = FirebaseStorage.getInstance().getReference();

        // Check if user is logged in
        FirebaseUser currentUser = firebaseAuth.getCurrentUser();
        if (currentUser == null) {
            // Redirect to login activity
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        }
    }

    private void initializeCamera() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
    }

    private void checkPermissions() {
        // Only request permissions that are actually needed and not granted
        List<String> permissionsToRequest = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        // Note: WRITE_EXTERNAL_STORAGE is not needed for app's cache directory
        // Remove it if you're only saving to cache/internal storage
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void setupEventListeners() {
        detectionToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startDetection();
            } else {
                stopDetection();
            }
        });

        addPhotoBtn.setOnClickListener(v -> {
            if (hasAllPermissions()) {
                startCameraPreview();
                showCameraUI();
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
            }
        });

        captureButton.setOnClickListener(v -> capturePhoto());

        settingsBtn.setOnClickListener(v -> openSettings());
        historyBtn.setOnClickListener(v -> openHistory());
        logoutBtn.setOnClickListener(v -> logout());

        if(dummyDataBtn != null) {
            dummyDataBtn.setOnClickListener(v -> pushDummyPothole());
        }
    }

    private boolean hasAllPermissions() {
        // Check essential permissions only
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        return cameraGranted && locationGranted;
    }

    private void startCameraPreview() {
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
    }

    private void capturePhoto() {
        if (imageCapture == null) return;

        File photoFile = new File(getCacheDir(), "pothole_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        // Compress image before upload
                        File compressedFile = new File(getCacheDir(), "pothole_compressed_" + System.currentTimeMillis() + ".jpg");
                        compressImageFile(photoFile, compressedFile);

                        // Delete original file if desired
                        photoFile.delete();

                        getCurrentLocationAndUpload(compressedFile);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(MainActivity.this, "Photo capture failed: " +
                                exception.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }
    private void compressImageFile(File originalFile, File compressedFile) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(originalFile.getAbsolutePath());

            // Resize bitmap if desired (e.g. max 720x720)
            int maxWidth = 720;
            int maxHeight = 720;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            float scale = Math.min((float)maxWidth/width, (float)maxHeight/height);

            if (scale < 1.0f) {
                int newWidth = Math.round(scale * width);
                int newHeight = Math.round(scale * height);
                bitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
            }

            // Compress and save with lower quality (e.g. 70%)
            FileOutputStream fos = new FileOutputStream(compressedFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos);
            fos.close();

        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Image compression failed: " + e.getMessage());
        }
    }



    private void getCurrentLocationAndUpload(File photoFile) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        uploadPhotoAndSaveData(photoFile, location);
                    } else {
                        Toast.makeText(this, "Could not get location", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void uploadPhotoAndSaveData(File photoFile, Location location) {
        String userId = firebaseAuth.getCurrentUser().getUid();
        String docId = firestore.collection("potholes").document().getId();

        StorageReference imageRef = storageRef.child(userId + "/" + docId + ".jpg");

        imageRef.putFile(android.net.Uri.fromFile(photoFile))
                .addOnSuccessListener(taskSnapshot -> {
                    imageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                        Map<String, Object> potholeData = new HashMap<>();
                        potholeData.put("timestamp", FieldValue.serverTimestamp());
                        potholeData.put("imageUrl", uri.toString());
                        potholeData.put("latitude", location.getLatitude());
                        potholeData.put("longitude", location.getLongitude());
                        potholeData.put("user_id", userId);
                        potholeData.put("confidence", 0);
                        potholeData.put("speed", 0);
                        // Store location in accelerometer_data field as requested
                        Map<String, Object> accelerometerData = new HashMap<>();
                        accelerometerData.put("x", location.getLatitude());
                        accelerometerData.put("y", location.getLongitude());
                        accelerometerData.put("z", location.getAltitude());
                        potholeData.put("accelerometer_data", accelerometerData);

                        firestore.collection("potholes").document(docId).set(potholeData)
                                .addOnSuccessListener(aVoid -> {
                                    Toast.makeText(MainActivity.this,
                                            "Photo and data uploaded successfully!", Toast.LENGTH_SHORT).show();
                                    hideCameraUI();
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(MainActivity.this,
                                            "Failed to save record", Toast.LENGTH_SHORT).show();
                                    Log.e(TAG, "Firestore save failed", e);
                                });
                    });
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(MainActivity.this, "Image upload failed", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Image upload failed", e);
                });
    }

    private void showCameraUI() {
        viewFinder.setVisibility(View.VISIBLE);
        overlayBox.setVisibility(View.VISIBLE);
        captureButton.setVisibility(View.VISIBLE);
    }

    private void hideCameraUI() {
        viewFinder.setVisibility(View.GONE);
        overlayBox.setVisibility(View.GONE);
        captureButton.setVisibility(View.GONE);
    }

    // ------ DUMMY TEST DATA CREATOR ------
    private void pushDummyPothole() {
        FirebaseUser dummyUser = firebaseAuth.getCurrentUser();
        if (dummyUser == null) {
            Toast.makeText(this, "Login required to send dummy data.", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Object> acceleration = new HashMap<>();
        acceleration.put("x", 2.1f);
        acceleration.put("y", 8.3f);
        acceleration.put("z", 0.4f);

        Map<String, Object> dummyData = new HashMap<>();
        dummyData.put("timestamp", FieldValue.serverTimestamp());
        dummyData.put("latitude", 19.0760);      // Example: Mumbai
        dummyData.put("longitude", 72.8777);
        dummyData.put("confidence", 95);
        dummyData.put("speed", 44.2);
        dummyData.put("user_id", dummyUser.getUid());
        dummyData.put("accelerometer_data", acceleration);

        firestore.collection("potholes")
                .add(dummyData)
                .addOnSuccessListener(ref -> {
                    Toast.makeText(this, "Dummy pothole added! ID: " + ref.getId(), Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to add dummy pothole.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Failed to send dummy data", e);
                });
    }

    private void startDetection() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "Location permission required for detection", Toast.LENGTH_LONG).show();
            detectionToggle.setChecked(false);
            return;
        }
        isDetectionActive = true;
        statusText.setText("Detection Active - Monitoring for potholes...");

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1, this);
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
        Log.d(TAG, "Pothole detection started");
    }

    private void stopDetection() {
        isDetectionActive = false;
        statusText.setText("Detection Stopped");

        sensorManager.unregisterListener(this);

        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
        Log.d(TAG, "Pothole detection stopped");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isDetectionActive) return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accelerometerValues = event.values.clone();
            updateSensorDisplay();

            if (shouldDetectPothole()) {
                checkForPothole();
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroscopeValues = event.values.clone();
        }
    }

    private void updateSensorDisplay() {
        uiHandler.post(() -> {
            String sensorData = String.format(
                    "Accelerometer:\nX: %.2f m/s²\nY: %.2f m/s²\nZ: %.2f m/s²\n\n" +
                            "Speed: %.1f km/h\n" +
                            "Driving: %s\n" +
                            "Phone in use: %s",
                    accelerometerValues[0], accelerometerValues[1], accelerometerValues[2],
                    currentSpeed,
                    isDriving ? "Yes" : "No",
                    isPhoneInUse ? "Yes" : "No"
            );
            sensorDataText.setText(sensorData);
            speedText.setText(String.format("%.1f km/h", currentSpeed));
        });
    }

    private boolean shouldDetectPothole() {
        checkDrivingStatus();
        checkPhoneUsage();
        return isDetectionActive && isDriving && !isPhoneInUse;
    }

    private void checkDrivingStatus() {
        isDriving = currentSpeed > SPEED_THRESHOLD;
    }

    private void checkPhoneUsage() {
        isPhoneInUse = false;
    }

    private void checkForPothole() {
        float magnitude = (float) Math.sqrt(
                accelerometerValues[0] * accelerometerValues[0] +
                        accelerometerValues[1] * accelerometerValues[1] +
                        accelerometerValues[2] * accelerometerValues[2]
        );
        long now = System.currentTimeMillis();
        if (magnitude > POTHOLE_THRESHOLD && (now - lastDetectionTime > DETECTION_COOLDOWN_MS)) {
            if (validatePotholeDetection(magnitude)) {
                lastDetectionTime = now;
                onPotholeDetected(magnitude);
            }
        }
    }

    private boolean validatePotholeDetection(float magnitude) {
        float confidence = Math.min(100, (magnitude / POTHOLE_THRESHOLD) * 100);
        return confidence >= CONFIDENCE_THRESHOLD;
    }

    private void onPotholeDetected(float magnitude) {
        if (currentLocation == null) return;

        detectionCount++;
        float confidence = Math.min(100, (magnitude / POTHOLE_THRESHOLD) * 100);

        Map<String, Object> potholeData = new HashMap<>();
        potholeData.put("timestamp", FieldValue.serverTimestamp());
        potholeData.put("latitude", currentLocation.getLatitude());
        potholeData.put("longitude", currentLocation.getLongitude());
        potholeData.put("confidence", Math.round(confidence));
        potholeData.put("speed", currentSpeed);
        potholeData.put("user_id", firebaseAuth.getCurrentUser().getUid());

        Map<String, Object> accelerometerData = new HashMap<>();
        accelerometerData.put("x", accelerometerValues[0]);
        accelerometerData.put("y", accelerometerValues[1]);
        accelerometerData.put("z", accelerometerValues[2]);
        potholeData.put("accelerometer_data", accelerometerData);

        savePotholeToFirebase(potholeData);

        uiHandler.post(() -> {
            detectionCountText.setText("Detected: " + detectionCount);
            Toast.makeText(MainActivity.this,
                    String.format("Pothole detected! Confidence: %.0f%%", confidence),
                    Toast.LENGTH_SHORT).show();
        });

        Log.d(TAG, "Pothole detected with confidence: " + confidence + "%");
    }

    private void savePotholeToFirebase(Map<String, Object> potholeData) {
        firestore.collection("potholes")
                .add(potholeData)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "Pothole saved with ID: " + documentReference.getId());
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Error adding pothole", e);
                });
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        currentLocation = location;
        currentSpeed = location.getSpeed() * 3.6f;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onProviderEnabled(@NonNull String provider) {}

    @Override
    public void onProviderDisabled(@NonNull String provider) {}

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openSettings() {
        Toast.makeText(this, "Settings clicked", Toast.LENGTH_SHORT).show();
    }

    private void openHistory() {
        Toast.makeText(this, "History clicked", Toast.LENGTH_SHORT).show();
    }

    private void logout() {
        firebaseAuth.signOut();
        startActivity(new Intent(MainActivity.this, LoginActivity.class));
        finish();
        Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isDetectionActive) {
            startDetection();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isDetectionActive) {
            stopDetection();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Debug: Log the results
            Log.d(TAG, "Permission results:");
            for (int i = 0; i < permissions.length; i++) {
                String status = (i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED)
                        ? "GRANTED" : "DENIED";
                Log.d(TAG, permissions[i] + ": " + status);
            }

            // Check each permission individually
            boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            boolean locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean coarseLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean storageGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;

            // Log current permission status
            Log.d(TAG, "Current permission status:");
            Log.d(TAG, "Camera: " + cameraGranted);
            Log.d(TAG, "Fine Location: " + locationGranted);
            Log.d(TAG, "Coarse Location: " + coarseLocationGranted);
            Log.d(TAG, "Storage: " + storageGranted);

            // Check if essential permissions are granted (camera and at least one location permission)
            boolean essentialPermissionsGranted = cameraGranted && (locationGranted || coarseLocationGranted);

            if (!essentialPermissionsGranted) {
                // Show specific message about which permissions are missing
                StringBuilder missingPermissions = new StringBuilder("Missing permissions: ");
                if (!cameraGranted) missingPermissions.append("Camera ");
                if (!locationGranted && !coarseLocationGranted) missingPermissions.append("Location ");
                if (!storageGranted) missingPermissions.append("Storage ");

                Toast.makeText(this, missingPermissions.toString(), Toast.LENGTH_LONG).show();

                // Optionally show system settings to manually grant permissions
                showPermissionSettingsDialog();
            } else {
                Toast.makeText(this, "Permissions granted successfully!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showPermissionSettingsDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("This app needs Camera and Location permissions to function properly. Please grant them in Settings.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}

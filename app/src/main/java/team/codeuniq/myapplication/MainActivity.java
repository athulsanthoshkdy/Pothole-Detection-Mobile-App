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
import android.os.Build;
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
import java.util.UUID;
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

    // RoadSurP Paper Implementation Variables
    private ArrayList<Float> zAxisBuffer = new ArrayList<>();
    private static final int BUFFER_SIZE = 50; // ~1 second at 50Hz
    private static final float BASE_THRESHOLD = 8.0f; // T0 - base threshold
    private static final float SPEED_SCALING_FACTOR = 0.1f; // S - speed scaling
    private static final float SPEED_OFFSET = 5.0f; // L - speed offset
    private long lastDetectionTime = 0;
    private static final long DETECTION_COOLDOWN_MS = 3000; // 3 seconds debounce
    private String currentSessionId; // Session/trip identifier

    // Sensor Data
    private float[] accelerometerValues = new float[3];
    private float[] gyroscopeValues = new float[3];

    // Detection Thresholds
    private static final float SPEED_THRESHOLD = 10.0f; // km/h
    private static final int CONFIDENCE_THRESHOLD = 75;

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

        // Generate session ID for this trip
        currentSessionId = UUID.randomUUID().toString();

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
                        File compressedFile = new File(getCacheDir(), "pothole_compressed_" + System.currentTimeMillis() + ".jpg");
                        compressImageFile(photoFile, compressedFile);
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

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from file");
                return;
            }

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

            FileOutputStream fos = new FileOutputStream(compressedFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos);
            fos.close();
            bitmap.recycle();

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
                        // Create unified data structure for image-based detection
                        Map<String, Object> eventData = createUnifiedEventData("IMAGE");
                        eventData.put("imageUrl", uri.toString());
                        eventData.put("latitude", location.getLatitude());
                        eventData.put("longitude", location.getLongitude());
                        eventData.put("speed", currentSpeed);

                        firestore.collection("potholes").document(docId).set(eventData)
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

    // MODIFIED: Research paper implementation for sensor detection

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isDetectionActive) return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accelerometerValues = event.values.clone();

            // Add Z-axis to buffer for feature extraction
            addToZAxisBuffer(accelerometerValues[2]);

            updateSensorDisplay();

            if (shouldDetectPothole()) {
                checkForPotholeRoadSurP();
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroscopeValues = event.values.clone();
        }
    }

    private void addToZAxisBuffer(float zValue) {
        zAxisBuffer.add(zValue);
        if (zAxisBuffer.size() > BUFFER_SIZE) {
            zAxisBuffer.remove(0); // Remove oldest value
        }
    }

    private float calculateDynamicThreshold() {
        // T_t = T_0 + S × (V_t - L) - Dynamic threshold formula from paper
        return BASE_THRESHOLD + SPEED_SCALING_FACTOR * (currentSpeed - SPEED_OFFSET);
    }

    private void checkForPotholeRoadSurP() {
        if (zAxisBuffer.size() < 10) return; // Need minimum buffer

        float currentZ = accelerometerValues[2];
        float dynamicThreshold = calculateDynamicThreshold();
        long now = System.currentTimeMillis();

        // Check if threshold exceeded and cooldown period passed
        if (Math.abs(currentZ) > dynamicThreshold &&
                (now - lastDetectionTime > DETECTION_COOLDOWN_MS)) {

            // Extract features as per paper
            PotholeFeatures features = extractFeatures(currentZ);

            if (features != null) {
                lastDetectionTime = now;
                onPotholeDetectedRoadSurP(features, dynamicThreshold);
            }
        }
    }

    private PotholeFeatures extractFeatures(float zt) {
        try {
            int currentIndex = zAxisBuffer.size() - 1;

            // Find local min/max before and after current point
            float zPrev = findLocalExtrema(currentIndex - 5, currentIndex, true);
            float zNext = findLocalExtrema(currentIndex, Math.min(currentIndex + 5, zAxisBuffer.size() - 1), false);

            // Calculate interval since last detection
            long intervalSinceLastDetection = System.currentTimeMillis() - lastDetectionTime;

            return new PotholeFeatures(zt, zPrev, zNext, intervalSinceLastDetection, currentSpeed);
        } catch (Exception e) {
            Log.e(TAG, "Error extracting features: " + e.getMessage());
            return null;
        }
    }

    private float findLocalExtrema(int startIdx, int endIdx, boolean isPrevious) {
        if (startIdx < 0 || endIdx >= zAxisBuffer.size()) return 0f;

        float extrema = zAxisBuffer.get(startIdx);
        for (int i = startIdx; i <= endIdx; i++) {
            float value = zAxisBuffer.get(i);
            if (Math.abs(value) > Math.abs(extrema)) {
                extrema = value;
            }
        }
        return extrema;
    }

    private void onPotholeDetectedRoadSurP(PotholeFeatures features, float threshold) {
        if (currentLocation == null) return;

        detectionCount++;

        // Create unified data structure for sensor-based detection
        Map<String, Object> eventData = createUnifiedEventData("SENSOR");

        // Add RoadSurP paper specific fields
        eventData.put("latitude", currentLocation.getLatitude());
        eventData.put("longitude", currentLocation.getLongitude());
        eventData.put("speed", features.speed);
        eventData.put("zt_peak", features.zt);
        eventData.put("z_prev_extrema", features.zPrev);
        eventData.put("z_next_extrema", features.zNext);
        eventData.put("interval_since_last_detection", features.intervalSinceLastDetection);
        eventData.put("dynamic_threshold", threshold);
        eventData.put("base_threshold", BASE_THRESHOLD);

        // Raw signature window for ML
        List<Float> signatureWindow = new ArrayList<>();
        int startIdx = Math.max(0, zAxisBuffer.size() - 20);
        int endIdx = Math.min(zAxisBuffer.size(), zAxisBuffer.size());
        for (int i = startIdx; i < endIdx; i++) {
            signatureWindow.add(zAxisBuffer.get(i));
        }
        eventData.put("raw_signature_window", signatureWindow);

        savePotholeToFirebase(eventData);

        uiHandler.post(() -> {
            detectionCountText.setText("Detected: " + detectionCount);
            Toast.makeText(MainActivity.this,
                    "Pothole detected! Z: " + String.format("%.2f", features.zt),
                    Toast.LENGTH_SHORT).show();
        });

        Log.d(TAG, "RoadSurP detection - Z: " + features.zt + ", Threshold: " + threshold);
    }

    // Unified data structure for both image and sensor detections
    private Map<String, Object> createUnifiedEventData(String detectionType) {
        Map<String, Object> eventData = new HashMap<>();
        String userId = firebaseAuth.getCurrentUser().getUid();

        // Common fields for both detection types
        eventData.put("timestamp", FieldValue.serverTimestamp());
        eventData.put("user_id", userId);
        eventData.put("session_id", currentSessionId);
        eventData.put("detection_type", detectionType); // "SENSOR" or "IMAGE"

        // Device and environment context
        eventData.put("device_model", Build.MODEL);
        eventData.put("device_manufacturer", Build.MANUFACTURER);
        eventData.put("vehicle_type", "unknown"); // Could be set from user preferences
        eventData.put("phone_placement", "unknown"); // Could be detected or set by user

        // Location and motion (if available)
        if (currentLocation != null) {
            eventData.put("latitude", currentLocation.getLatitude());
            eventData.put("longitude", currentLocation.getLongitude());
            eventData.put("altitude", currentLocation.getAltitude());
            eventData.put("gps_accuracy", currentLocation.getAccuracy());
        } else {
            eventData.put("latitude", null);
            eventData.put("longitude", null);
            eventData.put("altitude", null);
            eventData.put("gps_accuracy", null);
        }

        eventData.put("speed", currentSpeed);

        // Sensor-specific fields (null for image detections)
        eventData.put("zt_peak", null);
        eventData.put("z_prev_extrema", null);
        eventData.put("z_next_extrema", null);
        eventData.put("interval_since_last_detection", null);
        eventData.put("dynamic_threshold", null);
        eventData.put("base_threshold", null);
        eventData.put("raw_signature_window", null);

        // Image-specific fields (null for sensor detections)
        eventData.put("imageUrl", null);
        eventData.put("confidence", null); // For manual/ML confidence scoring

        // Traditional accelerometer data (for backward compatibility)
        Map<String, Object> accelerometerData = new HashMap<>();
        accelerometerData.put("x", accelerometerValues[0]);
        accelerometerData.put("y", accelerometerValues[1]);
        accelerometerData.put("z", accelerometerValues[2]);
        eventData.put("accelerometer_data", accelerometerData);

        return eventData;
    }

    // Helper class for pothole features
    private static class PotholeFeatures {
        final float zt;
        final float zPrev;
        final float zNext;
        final long intervalSinceLastDetection;
        final float speed;

        PotholeFeatures(float zt, float zPrev, float zNext, long interval, float speed) {
            this.zt = zt;
            this.zPrev = zPrev;
            this.zNext = zNext;
            this.intervalSinceLastDetection = interval;
            this.speed = speed;
        }
    }

    // ------ DUMMY TEST DATA CREATOR ------
    private void pushDummyPothole() {
        FirebaseUser dummyUser = firebaseAuth.getCurrentUser();
        if (dummyUser == null) {
            Toast.makeText(this, "Login required to send dummy data.", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Object> dummyData = createUnifiedEventData("SENSOR");
        dummyData.put("latitude", 19.0760);
        dummyData.put("longitude", 72.8777);
        dummyData.put("speed", 44.2f);
        dummyData.put("zt_peak", 15.5f);
        dummyData.put("z_prev_extrema", 8.2f);
        dummyData.put("z_next_extrema", 9.1f);
        dummyData.put("interval_since_last_detection", 5000L);
        dummyData.put("dynamic_threshold", 12.0f);
        dummyData.put("base_threshold", BASE_THRESHOLD);

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

        // Reset session ID for new detection session
        currentSessionId = UUID.randomUUID().toString();
        zAxisBuffer.clear();

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
        Log.d(TAG, "RoadSurP pothole detection started");
    }

    private void stopDetection() {
        isDetectionActive = false;
        statusText.setText("Detection Stopped");

        sensorManager.unregisterListener(this);
        zAxisBuffer.clear();

        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
        Log.d(TAG, "RoadSurP pothole detection stopped");
    }

    private void updateSensorDisplay() {
        uiHandler.post(() -> {
            float dynamicThreshold = calculateDynamicThreshold();
            String sensorData = String.format(
                    "Accelerometer:\nX: %.2f m/s²\nY: %.2f m/s²\nZ: %.2f m/s²\n\n" +
                            "Speed: %.1f km/h\n" +
                            "Dynamic Threshold: %.1f\n" +
                            "Buffer Size: %d\n" +
                            "Driving: %s",
                    accelerometerValues[0], accelerometerValues[1], accelerometerValues[2],
                    currentSpeed,
                    dynamicThreshold,
                    zAxisBuffer.size(),
                    isDriving ? "Yes" : "No"
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
            Log.d(TAG, "Permission results:");
            for (int i = 0; i < permissions.length; i++) {
                String status = (i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED)
                        ? "GRANTED" : "DENIED";
                Log.d(TAG, permissions[i] + ": " + status);
            }

            boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            boolean locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean coarseLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean storageGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;

            Log.d(TAG, "Current permission status:");
            Log.d(TAG, "Camera: " + cameraGranted);
            Log.d(TAG, "Fine Location: " + locationGranted);
            Log.d(TAG, "Coarse Location: " + coarseLocationGranted);
            Log.d(TAG, "Storage: " + storageGranted);

            boolean essentialPermissionsGranted = cameraGranted && (locationGranted || coarseLocationGranted);

            if (!essentialPermissionsGranted) {
                StringBuilder missingPermissions = new StringBuilder("Missing permissions: ");
                if (!cameraGranted) missingPermissions.append("Camera ");
                if (!locationGranted && !coarseLocationGranted) missingPermissions.append("Location ");
                if (!storageGranted) missingPermissions.append("Storage ");

                Toast.makeText(this, missingPermissions.toString(), Toast.LENGTH_LONG).show();
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

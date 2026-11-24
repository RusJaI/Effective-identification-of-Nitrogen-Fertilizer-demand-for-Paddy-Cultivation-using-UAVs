package com.example.smart_rice_care;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.view.PreviewView;
import androidx.exifinterface.media.ExifInterface;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.maps.android.PolyUtil;
import com.loopj.android.http.RequestParams;
import com.loopj.android.http.SyncHttpClient;
import com.loopj.android.http.TextHttpResponseHandler;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cz.msebera.android.httpclient.Header;

public class ImageCaptureOfflineActivity extends AppCompatActivity implements SensorEventListener, LocationListener {

    private int REQUEST_CODE_PERMISSIONS = 101;
    private String[] REQUIRED_PERMISSSIONS = new String[]{Manifest.permission.CAMERA};
    String folderName;
    Integer delayTime;
    PreviewView txvCamera;
    Button btStop;
    TextView tvColorLevel, tvLongitude, tvLatitude;
    ImageButton btCaptureImage;
    FrameLayout frameLayout;
    ArrayList<Long> fileNames = new ArrayList<>();

    private SensorManager sensorMan;
    private Sensor accelerometer;
    private boolean sensorRegistered;

    private float[] mGravity;
    private double mAccel;
    private double mAccelCurrent;
    private double mAccelLast;

    private int hitCount = 0;
    private double hitSum = 0;
    private double hitResult = 0;

    private final int SAMPLE_SIZE = 5; // change this sample size as you want, higher is more precise but slow measure.
    private double THRESHOLD = 0; // change this threshold as you want, higher is more spike movement

    private CameraCaptureController cameraController;

    MediaPlayer shutterSound;
    MediaPlayer music;
    MediaPlayer success;
    MediaPlayer error;

    private boolean captured = false;
    private boolean countdownWaiting = true;
    private boolean cameraWaiting = false;
    private boolean responseWaiting = false;
    private boolean isWithinBoundary = false;

    private FusedLocationProviderClient fusedLocationClient;
    protected LocationManager locationManager;
    protected LocationListener locationListener;

    protected String latitude, longitude;
    protected boolean gps_enabled, network_enabled;

    Location currentLocation;
    List<LatLng> polygonList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_capture);

        music = MediaPlayer.create(ImageCaptureOfflineActivity.this, R.raw.music);
        success = MediaPlayer.create(ImageCaptureOfflineActivity.this, R.raw.success);
        error = MediaPlayer.create(ImageCaptureOfflineActivity.this, R.raw.error);
        music.start();

        Intent getIntent = getIntent();
        folderName = getIntent.getStringExtra("folderName");
        delayTime = getIntent.getIntExtra("delayTime", 0);

        Bundle args = getIntent.getBundleExtra("BUNDLE");
        if (args != null && args.getSerializable("polygonList") != null) {
            polygonList = (List<LatLng>) args.getSerializable("polygonList");
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        requestCapturePermissionsOrInitialize();

        shutterSound = MediaPlayer.create(ImageCaptureOfflineActivity.this, R.raw.shutter);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String threshold = preferences.getString("SENSORTHRESHOLD", "");
        if (!threshold.equalsIgnoreCase("")) {
            THRESHOLD = Double.parseDouble(threshold);
        }

        sensorMan = (SensorManager) this.getSystemService(ImageCaptureOfflineActivity.this.SENSOR_SERVICE);
        accelerometer = sensorMan.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mAccel = 0.00f;
        mAccelCurrent = SensorManager.GRAVITY_EARTH;
        mAccelLast = SensorManager.GRAVITY_EARTH;
        sensorMan.registerListener(this, accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL);
        sensorRegistered = true;

        getSupportActionBar().hide();
        frameLayout = findViewById(R.id.frameLayout);
        txvCamera = findViewById(R.id.txvCamera);
        btCaptureImage = findViewById(R.id.btCaptureImage);
        tvColorLevel = findViewById(R.id.tvColorLevel);
        tvLongitude = findViewById(R.id.tvLongitude);
        tvLatitude = findViewById(R.id.tvLatitude);
        btStop = findViewById(R.id.btStop);

        btStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(ImageCaptureOfflineActivity.this, DeleteImageActivity.class);
                intent.putExtra("fileNames", fileNames);
                intent.putExtra("folderName", folderName);
                intent.putExtra("approach", "offline");
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                music.stop();
                error.stop();
                success.stop();
                startActivity(intent);
                finish();
            }
        });

        tvColorLevel.setText("Delaying...");

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                tvColorLevel.setText("Ready...");
                music.stop();
                countdownWaiting = false;
            }
        }, delayTime*1000);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

    }

    private void startCamera() {
        cameraController = new CameraCaptureController(this, this, txvCamera);
        cameraController.start();
        btCaptureImage.setOnClickListener(v -> captureImage());
    }

    private File captureImage() {
        if (cameraController == null) return null;
        responseWaiting = true;
        long currentTimestamp = System.currentTimeMillis();
        File dir = ImageStorage.getFolder(this, folderName);
        if (!dir.exists() && !dir.mkdirs()) {
            responseWaiting = false;
            Toast.makeText(this, "Unable to create image folder", Toast.LENGTH_LONG).show();
            return null;
        }
        File file = new File(dir, currentTimestamp + ".jpg");
        cameraController.capture(file, new CameraCaptureController.CaptureCallback() {
            @Override
            public void onSaved(File savedFile) {
                ImageSharpness.measureAsync(savedFile, new ImageSharpness.Callback() {
                    @Override
                    public void onMeasured(double variance) {
                        Log.i("ImageCaptureOffline", "Captured image VoL=" + variance);
                        if (variance < ImageSharpness.MINIMUM_ACCEPTABLE_VOL) {
                            discardRejectedImage(savedFile, "Image is blurry (VoL " + String.format(java.util.Locale.US, "%.1f", variance) + " < 60); rejected.");
                            return;
                        }
                        acceptCapturedImage(savedFile, currentTimestamp);
                    }

                    @Override
                    public void onError() {
                        discardRejectedImage(savedFile, "Could not check image sharpness; image was discarded.");
                    }
                });
            }

            @Override
            public void onError(ImageCaptureException exception) {
                responseWaiting = false;
                Toast.makeText(ImageCaptureOfflineActivity.this, "Pic capture failed: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        return file;
    }

    private void acceptCapturedImage(File savedFile, long currentTimestamp) {
        fileNames.add(currentTimestamp);
        tvColorLevel.setText("Saving...");
        try {
            ExifInterface exif = new ExifInterface(savedFile.getAbsolutePath());
            if (currentLocation != null) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, GPS.convert(currentLocation.getLatitude()));
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, GPS.convert(currentLocation.getLongitude()));
            }
            exif.saveAttributes();
            success.start();
            new Handler().postDelayed(() -> {
                tvColorLevel.setText("Ready...");
                responseWaiting = false;
            }, 2000);
            Toast.makeText(ImageCaptureOfflineActivity.this, "Pic captured at " + savedFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            responseWaiting = false;
            Toast.makeText(ImageCaptureOfflineActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void discardRejectedImage(File image, String message) {
        if (image.exists() && !image.delete()) {
            Log.e("ImageCaptureOffline", "Unable to delete rejected image " + image.getAbsolutePath());
        }
        captured = false;
        responseWaiting = false;
        tvColorLevel.setText("Blurry image rejected · ready to retry");
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private boolean allPermissionGranted() {
        boolean hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasLocation) return false;
        for (String permission: REQUIRED_PERMISSSIONS) {
            if(ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestCapturePermissionsOrInitialize() {
        boolean hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (hasLocation && hasCamera) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionGranted()) {
                requestCapturePermissionsOrInitialize();
            } else {
                Toast.makeText(this, "Camera and location permissions are required for geotagged captures", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            mGravity = event.values.clone();
            // Shake detection
            double x = mGravity[0];
            double y = mGravity[1];
            double z = mGravity[2];
            mAccelLast = mAccelCurrent;
            mAccelCurrent = Math.sqrt(x * x + y * y + z * z);
            double delta = mAccelCurrent - mAccelLast;
            mAccel = mAccel * 0.9f + delta;

            if (hitCount < SAMPLE_SIZE) {
                hitCount++;
                hitSum += Math.abs(mAccel);
            } else {
                hitResult = hitSum / SAMPLE_SIZE;

                if (hitResult > THRESHOLD) {
//                    Moving
                    captured = false;
                }

                if(hitResult <= THRESHOLD && !captured && !cameraWaiting && !responseWaiting && isWithinBoundary && !countdownWaiting){
                    tvColorLevel.setText("Capturing...");
                    captured = true;
                    cameraWaiting = true;
                    captureImage();

                    Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            cameraWaiting = false;
                        }
                    }, 3000);
                }

                hitCount = 0;
                hitSum = 0;
                hitResult = 0;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        currentLocation = location;
        tvLatitude.setText("Latitude: "+location.getLatitude());
        tvLongitude.setText("Longitude: "+location.getLongitude());

        if(polygonList.size()>2){
            if (PolyUtil.containsLocation(currentLocation.getLatitude(), currentLocation.getLongitude(), polygonList, true)) {
                isWithinBoundary = true;
            }
            else{
                isWithinBoundary = false;
                if(!countdownWaiting) {
                    error.start();
                }
            }
        }
        else{
            isWithinBoundary = true;
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        music.stop();
        error.stop();
        success.stop();
    }
}

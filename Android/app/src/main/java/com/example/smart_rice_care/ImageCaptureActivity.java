package com.example.smart_rice_care;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.view.PreviewView;
import androidx.exifinterface.media.ExifInterface;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.maps.android.PolyUtil;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ImageCaptureActivity extends AppCompatActivity implements SensorEventListener, LocationListener {

    private int REQUEST_CODE_PERMISSIONS = 101;
    private String[] REQUIRED_PERMISSSIONS = new String[]{Manifest.permission.CAMERA};
    private String requestId, fieldId, farmerId;
    private Integer delayTime;
    private PreviewView txvCamera;
    private TextView tvColorLevel, tvLongitude, tvLatitude;
    private ImageButton btCaptureImage;
    private FrameLayout frameLayout;
    private Button btStop;


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
    MediaPlayer beep;

    private boolean captured = false;
    private long latestCaptureTimestamp = 0L;
    private boolean cameraWaiting = false;
    private boolean countdownWaiting = true;
    private boolean isWithinBoundary = false;

    private FusedLocationProviderClient fusedLocationClient;
    protected LocationManager locationManager;
    protected LocationListener locationListener;

    protected String latitude, longitude;
    protected boolean gps_enabled, network_enabled;

    Location currentLocation;
    List<LatLng> polygonList = new ArrayList<>();

    ArrayList<Long> fileNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_capture);

        music = MediaPlayer.create(ImageCaptureActivity.this, R.raw.music);
        success = MediaPlayer.create(ImageCaptureActivity.this, R.raw.success);
        error = MediaPlayer.create(ImageCaptureActivity.this, R.raw.error);
        beep = MediaPlayer.create(ImageCaptureActivity.this, R.raw.beep);
        music.start();


        Intent getIntent = getIntent();
        requestId = getIntent.getStringExtra("requestId");
        fieldId = getIntent.getStringExtra("fieldId");
        farmerId = getIntent.getStringExtra("farmerId");
        delayTime = getIntent.getIntExtra("delayTime", 0);
        Bundle args = getIntent.getBundleExtra("BUNDLE");
        if (args != null && args.getSerializable("polygonList") != null) {
            polygonList = (List<LatLng>) args.getSerializable("polygonList");
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            System.out.println("testing=== requesting permission");
            ActivityCompat.requestPermissions(ImageCaptureActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},225);
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, ImageCaptureActivity.this);


        shutterSound = MediaPlayer.create(ImageCaptureActivity.this, R.raw.shutter);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String threshold = preferences.getString("SENSORTHRESHOLD", "");
        if (!threshold.equalsIgnoreCase("")) {
            THRESHOLD = Double.parseDouble(threshold);
        }

        sensorMan = (SensorManager) this.getSystemService(ImageCaptureActivity.this.SENSOR_SERVICE);
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

        tvColorLevel.setText("Delaying...");

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                tvColorLevel.setText("Starting...");
                music.stop();
                countdownWaiting = false;
            }
        }, delayTime*1000);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
//        frameLayout.addView(btCaptureImage);


        if (allPermissionGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSSIONS, REQUEST_CODE_PERMISSIONS);
        }

        btStop = findViewById(R.id.btStop);
        btStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateCurrentFertilizerAmount();
            }
        });
    }

    private void updateCurrentFertilizerAmount() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        DocumentReference docRef = db.collection("FieldRequests").document(requestId);
        docRef.get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
            @Override
            public void onComplete(@NonNull @NotNull Task<DocumentSnapshot> task) {
                if (task.isSuccessful()) {
                    DocumentSnapshot document = task.getResult();
                    if (document.exists()) {
                        String division = document.get("division").toString();
                        Long plantAge = (Long) document.get("plantAge");
                        db.collection("LCCDetails")
                                .whereEqualTo("division", division)
                                .get()
                                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                                    @Override
                                    public void onComplete(@NonNull @NotNull Task<QuerySnapshot> task2) {
                                        if(task2.isSuccessful()){
                                            if(task2.getResult().size()==0){
                                                // Get general LCC details, if specific data not available
                                                db.collection("LCCDetails")
                                                        .whereEqualTo("division", "ALL")
                                                        .get()
                                                        .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                                                            @Override
                                                            public void onComplete(@NonNull @NotNull Task<QuerySnapshot> task3) {
                                                                if(task3.isSuccessful()){
                                                                    Long fertilizer2=0L, fertilizer3=0L, fertilizer4=0L;
                                                                    for (QueryDocumentSnapshot document : task3.getResult()) {
                                                                        ArrayList<HashMap> data = (ArrayList<HashMap>) document.get("weekDetails");
                                                                        HashMap<String, Long> weekData = data.get(plantAge.intValue()-1);
                                                                        fertilizer2 = weekData.get("levelTwo");
                                                                        fertilizer3 = weekData.get("levelThree");
                                                                        fertilizer4 = weekData.get("levelFour");
                                                                    }

                                                                    Map<String, Object> fertilizerAmount = new HashMap<>();
                                                                    fertilizerAmount.put("requestId", requestId);
                                                                    fertilizerAmount.put("level2", fertilizer2);
                                                                    fertilizerAmount.put("level3", fertilizer3);
                                                                    fertilizerAmount.put("level4", fertilizer4);
                                                                    db.collection("Fertilizer")
                                                                            .document(requestId)
                                                                            .set(fertilizerAmount)
                                                                            .addOnSuccessListener(new OnSuccessListener<Void>() {
                                                                                @Override
                                                                                public void onSuccess(Void unused) {
                                                                                    openDeleteImageIntent();
                                                                                }
                                                                            })
                                                                            .addOnFailureListener(new OnFailureListener() {
                                                                                @Override
                                                                                public void onFailure(@NonNull @NotNull Exception e) {
                                                                                    openDeleteImageIntent();
                                                                                    Toast.makeText(ImageCaptureActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                                                                                }
                                                                            });
                                                                }

                                                                else{
                                                                    openDeleteImageIntent();
                                                                    Log.d("LCCDetails error:", task3.getException().toString());
                                                                    Toast.makeText(ImageCaptureActivity.this, ""+task3.getException(), Toast.LENGTH_SHORT).show();
                                                                }
                                                            }
                                                        });
                                            }
                                            else{
//                                                 Get specific LCC details
                                                Long fertilizer2=0L, fertilizer3=0L, fertilizer4=0L;
                                                for (QueryDocumentSnapshot document : task2.getResult()) {
                                                    ArrayList<HashMap> data = (ArrayList<HashMap>) document.get("weekDetails");
                                                    HashMap<String, Long> weekData = data.get(plantAge.intValue()-1);
                                                    fertilizer2 = weekData.get("levelTwo");
                                                    fertilizer3 = weekData.get("levelThree");
                                                    fertilizer4 = weekData.get("levelFour");
                                                }

                                                Map<String, Object> fertilizerAmount = new HashMap<>();
                                                fertilizerAmount.put("requestId", requestId);
                                                fertilizerAmount.put("level2", fertilizer2);
                                                fertilizerAmount.put("level3", fertilizer3);
                                                fertilizerAmount.put("level4", fertilizer4);
                                                db.collection("Fertilizer")
                                                        .document(requestId)
                                                        .set(fertilizerAmount)
                                                        .addOnSuccessListener(new OnSuccessListener<Void>() {
                                                            @Override
                                                            public void onSuccess(Void unused) {
                                                                openDeleteImageIntent();
                                                           }
                                                        })
                                                        .addOnFailureListener(new OnFailureListener() {
                                                            @Override
                                                            public void onFailure(@NonNull @NotNull Exception e) {
                                                                openDeleteImageIntent();
                                                                Toast.makeText(ImageCaptureActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                                                            }
                                                        });
                                            }
                                        }
                                        else{
                                            openDeleteImageIntent();
                                            Log.d("LCCDetails error:", task.getException().toString());
                                            Toast.makeText(ImageCaptureActivity.this, ""+task.getException(), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                    } else {
                        openDeleteImageIntent();
                        Log.d("FieldRequests fetch: ", "No such document");
                    }
                } else {
                    openDeleteImageIntent();
                    Log.d("FieldRequests fetch: ", "get failed with ", task.getException());
                }
            }
        });
    }

    private void openDeleteImageIntent() {

        Toast.makeText(ImageCaptureActivity.this, "Stopped", Toast.LENGTH_SHORT).show();

        music.stop();
        error.stop();
        success.stop();
        shutterSound.stop();
        beep.stop();

        Intent intent = new Intent(ImageCaptureActivity.this, DeleteImageActivity.class);
        intent.putExtra("requestId", requestId);
        intent.putExtra("fileNames", fileNames);
        intent.putExtra("folderName", requestId);
        intent.putExtra("approach", "online");
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void startCamera() {
        cameraController = new CameraCaptureController(this, this, txvCamera);
        cameraController.start();
        btCaptureImage.setOnClickListener(v -> captureImage());
    }

    private File captureImage() {
        if (cameraController == null) return null;
        if (currentLocation == null) {
            Toast.makeText(this, "Waiting for a GPS fix", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (shutterSound != null) shutterSound.start();
        long currentTimestamp = System.currentTimeMillis();
        latestCaptureTimestamp = currentTimestamp;
        Location captureLocation = new Location(currentLocation);
        File dir = ImageStorage.getFolder(this, requestId);
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "Unable to create image folder", Toast.LENGTH_LONG).show();
            return null;
        }
        File file = new File(dir, currentTimestamp + ".jpg");
        cameraController.capture(file, new CameraCaptureController.CaptureCallback() {
            @Override
            public void onSaved(File savedFile) {
                fileNames.add(currentTimestamp);
                try {
                    ExifInterface exif = new ExifInterface(savedFile.getAbsolutePath());
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, GPS.convert(captureLocation.getLatitude()));
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, GPS.convert(captureLocation.getLongitude()));
                    exif.saveAttributes();
                } catch (IOException e) {
                    Log.e("ImageCapture", "Unable to save image metadata", e);
                }
                processImage(savedFile, captureLocation, currentTimestamp);
            }

            @Override
            public void onError(ImageCaptureException exception) {
                Toast.makeText(ImageCaptureActivity.this, "Pic capture failed: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        return file;
    }

    private boolean allPermissionGranted() {
        for (String permission: REQUIRED_PERMISSSIONS) {
            if(ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSSIONS,50);
                return false;
            }
        }
        return true;
    }

    private void processImage(File file, Location location, Long currentTimestamp) {
        if (location == null) {
            Toast.makeText(this, "Image saved, but no GPS fix was available. It was not queued for prediction.", Toast.LENGTH_LONG).show();
            return;
        }
        tvColorLevel.setText("Saved locally · predicting...");
        CaptureSyncManager.saveAndUpload(this, file, requestId,
                location.getLatitude(), location.getLongitude(), currentTimestamp,
                new CaptureSyncManager.Callback() {
                    @Override
                    public void onPredicted(int level) {
                        if (currentTimestamp.longValue() != latestCaptureTimestamp) return;
                        tvColorLevel.setText("LCC level " + level);
                        if (success != null) success.start();
                        Toast.makeText(ImageCaptureActivity.this, "Prediction ready: LCC " + level, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onQueued() {
                        if (currentTimestamp.longValue() != latestCaptureTimestamp) return;
                        tvColorLevel.setText("Saved · process after inspection");
                        Toast.makeText(ImageCaptureActivity.this,
                                "Image saved for processing after the inspection ends.",
                                Toast.LENGTH_LONG).show();
                    }
                });
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

            if (hitCount <= SAMPLE_SIZE) {
                hitCount++;
                hitSum += Math.abs(mAccel);
            } else {
                hitResult = hitSum / SAMPLE_SIZE;

                if (hitResult > THRESHOLD) {
//                    Moving
                    captured = false;
                }


                if(hitResult <= THRESHOLD && !captured && !cameraWaiting && isWithinBoundary && !countdownWaiting){
                    captured = true;
                    cameraWaiting = true;
                    if (captureImage() == null) captured = false;

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
        shutterSound.stop();
        beep.stop();
    }
}

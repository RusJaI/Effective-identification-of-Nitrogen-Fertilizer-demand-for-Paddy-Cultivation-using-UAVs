package com.example.smart_rice_care;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;

public class DeleteImageActivity extends AppCompatActivity {

    ArrayList<Long> fileNames = new ArrayList<>();
    TextView tvImageCount;
    Integer currentIndex;
    ImageView ivImage, ivNext, ivBack;
    Button btDelete, btFinish;
    TextView tvSyncReport;
    String folderName, approach;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delete_image);

        Intent getIntent = getIntent();
        fileNames = (ArrayList<Long>) getIntent.getSerializableExtra("fileNames");
        if (fileNames == null) fileNames = new ArrayList<>();
        folderName =  getIntent.getStringExtra("folderName");
        approach = getIntent.getStringExtra("approach");
        ivImage = findViewById(R.id.ivImage);
        ivNext = findViewById(R.id.ivNext);
        ivBack = findViewById(R.id.ivBack);
        btDelete = findViewById(R.id.btDelete);
        btFinish = findViewById(R.id.btFinish);
        tvSyncReport = findViewById(R.id.tvSyncReport);
        tvImageCount = findViewById(R.id.tvImageCount);
        currentIndex = fileNames.size()-1;

        if ("online".equals(approach)) {
            startDeferredProcessing(getIntent.getStringExtra("requestId"));
        }

        showImage();

        ivBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(currentIndex>0){
                    currentIndex--;
                    showImage();
                }
            }
        });

        ivNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(currentIndex<fileNames.size()){
                    currentIndex++;
                    showImage();
                }
            }
        });

        btDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if(approach.equals("online")){
                    if(fileNames.size()>1){
                        // Remove responses from the DB in online approach
                        FirebaseFirestore db = FirebaseFirestore.getInstance();
                        db.collection("FieldData")
                            .whereEqualTo("timestamp", fileNames.get(currentIndex))
                            .get()
                            .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                                @Override
                                public void onComplete(@NonNull @NotNull Task<QuerySnapshot> task) {
                                    if (task.isSuccessful()) {
                                        for (QueryDocumentSnapshot document : task.getResult()) {
                                            String docId = document.getId();
                                            db.collection("FieldData").document(docId)
                                                    .delete()
                                                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                                                        @Override
                                                        public void onSuccess(Void unused) {
                                                            Log.d("Deleting :", "DocumentSnapshot successfully deleted!");
                                                        }
                                                    })
                                                    .addOnFailureListener(new OnFailureListener() {
                                                        @Override
                                                        public void onFailure(@NonNull @NotNull Exception e) {
                                                            Log.w("Deleting :", "Error deleting document", e);
                                                        }
                                                    });
                                        }
                                    } else {
                                        Log.d("Fetching :", "Error getting documents: ", task.getException());
                                    }
                                }
                            });
                    }
                    else{
                        Toast.makeText(DeleteImageActivity.this, "You can not delete all the images", Toast.LENGTH_SHORT).show();
                    }

                }

                if(fileNames.size()>1){
                    File imgFile = new File(ImageStorage.getFolder(this, folderName), fileNames.get(currentIndex)+".jpg");
                    if (imgFile.exists()) {
                        if (imgFile.delete()) {
                            System.out.println("testing==="+fileNames.size());
                            fileNames.remove(fileNames.get(currentIndex));
                            if ("online".equals(approach)) {
                                CaptureQueue queue = new CaptureQueue(DeleteImageActivity.this);
                                try {
                                    queue.removeForImage(folderName, Long.parseLong(imgFile.getName().replace(".jpg", "")));
                                } finally {
                                    queue.close();
                                }
                            }
                            if(currentIndex>0){
                                currentIndex--;
                            }
                            Toast.makeText(DeleteImageActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                            System.out.println("testing==="+fileNames.size());
                            showImage();
                        } else {
                            Toast.makeText(DeleteImageActivity.this, "Unable to delete the file", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                else{
                    Toast.makeText(DeleteImageActivity.this, "You can not delete all the images", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btFinish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(approach.equals("online")){
                    Intent intent = getIntent();
                    String requestId = intent.getStringExtra("requestId");
                    FirebaseFirestore db = FirebaseFirestore.getInstance();
                    DocumentReference docRef = db.collection("FieldRequests").document(requestId);
                    docRef.update("status", "completed")
                            .addOnSuccessListener(new OnSuccessListener<Void>() {
                                @Override
                                public void onSuccess(Void unused) {
                                    Log.d("Status Update: ", "DocumentSnapshot successfully updated!");
                                }
                            })
                            .addOnFailureListener(new OnFailureListener() {
                                @Override
                                public void onFailure(@NonNull @NotNull Exception e) {
                                    Log.w("Status Update: ", "Error updating document", e);
                                }
                            });
                } else {
                    exportImagesForWebUpload();
                }
                finish();
            }
        });


    }

    /** Process only failed captures, and only after the inspection has ended. */
    private void startDeferredProcessing(String requestId) {
        if (requestId == null || requestId.isEmpty()) return;
        btFinish.setEnabled(false);
        btDelete.setEnabled(false);
        tvSyncReport.setVisibility(View.VISIBLE);
        tvSyncReport.setText("Checking for images deferred during this inspection…");
        CaptureSyncManager.processPendingForInspection(this, requestId,
                (predictedCount, remaining, attempted) -> {
                    if (isFinishing() || isDestroyed()) return;
                    int exported = exportCapturesForWebUpload(remaining);
                    StringBuilder report = new StringBuilder("Deferred image report: ")
                            .append(predictedCount).append(" processed");
                    if (!remaining.isEmpty()) {
                        report.append(", ").append(remaining.size()).append(" still pending");
                        if (exported > 0) {
                            report.append(" (copied to Pictures/SmartRiceCare for web upload)");
                        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            report.append(" (still stored in the app; export requires Android 10+)");
                        }
                    } else if (predictedCount == 0) {
                        report = new StringBuilder("All images from this inspection already have results.");
                    }
                    if (!attempted && !remaining.isEmpty()) {
                        report.append(" No network was available at inspection end, so these images were not retried.");
                    }
                    tvSyncReport.setText(report.toString());
                    btFinish.setEnabled(true);
                    btDelete.setEnabled(true);
                });
    }

    private int exportCapturesForWebUpload(java.util.List<CaptureRecord> captures) {
        if (captures.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0;
        int exported = 0;
        for (CaptureRecord capture : captures) {
            File source = capture.getImageFile();
            if (!source.isFile()) continue;
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, folderName + "_" + capture.timestamp + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/SmartRiceCare/" + folderName);
            Uri destination = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) continue;
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new IOException("Unable to open image destination");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                exported++;
            } catch (IOException e) {
                getContentResolver().delete(destination, null, null);
                Log.e("ImageExport", "Unable to export " + source.getName(), e);
            }
        }
        return exported;
    }

    /** Copy offline captures into shared Pictures storage so the web upload picker can see them. */
    private void exportImagesForWebUpload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Use Android 10 or newer to export images from this screen", Toast.LENGTH_LONG).show();
            return;
        }
        int exported = 0;
        for (Long timestamp : fileNames) {
            File source = new File(ImageStorage.getFolder(this, folderName), timestamp + ".jpg");
            if (!source.isFile()) continue;
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, folderName + "_" + timestamp + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/SmartRiceCare/" + folderName);
            Uri destination = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) continue;
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new IOException("Unable to open image destination");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                exported++;
            } catch (IOException e) {
                getContentResolver().delete(destination, null, null);
                Log.e("ImageExport", "Unable to export " + source.getName(), e);
            }
        }
        Toast.makeText(this, exported + " image(s) exported to Pictures/SmartRiceCare for web upload", Toast.LENGTH_LONG).show();
    }

    private void showImage() {

        Integer labelIndex = currentIndex+1;

        tvImageCount.setText("Image "+ labelIndex +" of " + fileNames.size());

        ivNext.setVisibility(View.VISIBLE);
        ivBack.setVisibility(View.VISIBLE);

        if(currentIndex==0){
            ivBack.setVisibility(View.GONE);
        }

        if(currentIndex==fileNames.size()-1){
            ivNext.setVisibility(View.GONE);
        }

        if (fileNames.size()>0 && fileNames.size()>currentIndex){

            File imgFile = new File(ImageStorage.getFolder(this, folderName), fileNames.get(currentIndex) +".jpg");
            if(imgFile.exists()){
                Bitmap myBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                ivImage.setImageBitmap(myBitmap);
                ivImage.setRotation(90);
            };
        }

    }
}

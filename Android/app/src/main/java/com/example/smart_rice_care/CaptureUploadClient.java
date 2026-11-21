package com.example.smart_rice_care;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Performs prediction and idempotently writes the resulting point to Firestore. */
public final class CaptureUploadClient {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private CaptureUploadClient() { }

    public static int upload(Context context, CaptureRecord capture) throws Exception {
        if (!capture.getImageFile().isFile()) throw new IOException("Queued image is missing");
        RequestBody image = RequestBody.create(MediaType.parse("image/jpeg"), capture.getImageFile());
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", capture.getImageFile().getName(), image)
                .build();
        Request request = new Request.Builder()
                .url(BuildConfig.FLASK_SERVER_URL + "/svcprocess")
                .post(body)
                .build();

        final int level;
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Prediction server returned HTTP " + response.code());
            }
            try {
                level = Integer.parseInt(response.body().string().trim());
            } catch (NumberFormatException e) {
                throw new IOException("Prediction server returned an invalid LCC level", e);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("requestId", capture.requestId);
        result.put("latitude", capture.latitude);
        result.put("longitude", capture.longitude);
        result.put("officerId", capture.officerId);
        result.put("level", level);
        result.put("timestamp", capture.timestamp);

        Tasks.await(FirebaseFirestore.getInstance().collection("FieldData")
                .document(capture.id).set(result), 45, TimeUnit.SECONDS);

        CaptureQueue queue = new CaptureQueue(context);
        try {
            queue.remove(capture.id);
        } finally {
            queue.close();
        }
        return level;
    }
}

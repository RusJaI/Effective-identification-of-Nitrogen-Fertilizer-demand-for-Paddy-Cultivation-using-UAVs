package com.example.smart_rice_care;

import android.content.Context;
import java.io.File;

/** Private, app-owned storage for field images; no broad storage permission is needed. */
public final class ImageStorage {
    private ImageStorage() { }

    public static File getFolder(Context context, String folderName) {
        return new File(new File(context.getFilesDir(), "captures"), folderName);
    }
}

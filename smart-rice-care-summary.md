# Smart Rice Care: System Summary

## Purpose

Smart Rice Care supports site-specific nitrogen management in paddy fields. An agricultural instructor or farmer collects geotagged rice-crop images, the prediction service classifies an image into a Leaf Colour Chart (LCC) level, and field results can be viewed spatially. The system also supports farmer inspection requests and instructor scheduling.

The intended UAV workflow mounts an Android phone on the aircraft. The app waits for a sufficiently stable position, captures an image, records its location, and predicts when connectivity permits. Captures that cannot be processed during an inspection are retained for a later batch attempt or web upload.

## Repository components

| Component | Main technologies | Responsibilities |
| --- | --- | --- |
| `Android/` | Java, AndroidX, CameraX, Google Maps, Firebase Auth/Firestore | Farmer and instructor workflows, field/inspection requests, UAV image capture, location and boundary checks, result views, offline capture/export |
| `Web/` | Angular 11, AngularFire, Firebase Hosting | Instructor and administrator dashboards, image upload, field/farmer reports, LCC history, inspection request and appointment workflows |
| `PythonServer/` | Flask, OpenCV, NumPy/Pandas, scikit-learn, Firebase Admin | Image preprocessing and LCC classification; optional Firestore write route |

The Android, web, and Python components currently share Firebase data but do not use one unified application API. The Android and web clients access Firestore directly; they call the prediction service separately for image classification.

## Prediction and fertilizer workflow

1. The Python service decodes an uploaded image, creates an HSV mask for green vegetation, calculates RGB features, and extracts camera metadata used by the classifier.
2. A bundled scikit-learn SVC model returns an LCC level. The model artifact records scikit-learn version 1.0.2; the Python requirements pin that version.
3. Online Android capture stores the predicted level, request ID, officer ID, timestamp, latitude, and longitude in the `FieldData` Firestore collection.
4. The Android inspection workflow also reads `FieldRequests` and `LCCDetails`. It uses the request's division and plant age to find level-based fertilizer amounts and stores these in `Fertilizer`.
5. Field results are displayed as map points by LCC level. The web app provides additional field, farmer, request, and LCC reports.

The classifier returns an LCC category. The repository's fertilizer mapping is data-driven through Firestore `LCCDetails`; the model itself does not directly calculate a continuous nitrogen rate from pixels.

## Mobile capture behavior

### Online inspection

- Stable CameraX capture is lifecycle-bound to the activity.
- The accelerometer is used to estimate movement. Capture is allowed when movement is below the saved threshold, the camera cooldown has ended, the inspection delay has elapsed, and the current GPS point is inside the field boundary (when a boundary is available).
- Images are stored in app-private internal storage. Each online image is saved to a SQLite outbox with request ID, officer ID, GPS coordinates, and capture timestamp before its immediate prediction attempt.
- Each capture gets one immediate attempt when a network is available. If prediction or the Firestore result write fails, that image remains in the outbox. The app does not retry it while the UAV continues to later locations; later captures still receive their own immediate attempt.
- When the instructor stops the inspection, the review screen displays how many captures remain queued. It does not retry or export them automatically. The user can tap **Upload failed images** to start a batch prediction/Firestore attempt; remaining images stay in the SQLite outbox and are copied to `Pictures/SmartRiceCare/<requestId>` for web upload. The user can tap the button again when ready to retry.
- Successful point results are written to Firestore with a stable document ID, so an end-of-inspection batch result updates the same capture record rather than adding a duplicate.

### Offline batch inspection

- The separate offline workflow captures and reviews images without running an in-app prediction.
- On Android 10+, reviewed images are copied to shared Pictures storage so they can be selected in the web app's upload form. The web workflow is responsible for attaching the appropriate field metadata and processing the images.
- This is a manual batch handoff, separate from the online SQLite outbox. It does not automatically upload or predict from the mobile app.

### Sensor calibration

The calibration screen calibrates the movement threshold for stationary capture. It uses only the accelerometer's X/Y/Z values. It does not calibrate camera colour, GPS, or the LCC model. During the calibration window, the app updates the threshold to the largest movement value observed and saves it in shared preferences when stopped.

The sensor sampling bug has been corrected in the current working tree: a `SAMPLE_SIZE` of 5 now collects exactly five readings before averaging. Calibration continues to use only the accelerometer.

## Web and API behavior

- The web app uses Firebase/Firestore services for users, fields, requests, reports, and LCC details.
- `FieldDataService` sends images to the configured prediction service. `FieldVisitService` filters requests by status; the field-specific filters were corrected to use complete status arrays.
- The Python service supports `POST /svcprocess` and `POST /process` with multipart `image`, returning the LCC level as text. `POST /svcprocessfire` additionally accepts `field_data` and writes to `TestingFieldData` when Firebase credentials are available.
- The Android client uses `/svcprocess` and writes its operational result to `FieldData` itself. This differs from the server's optional `TestingFieldData` Firestore route.
- `PythonServer/Dockerfile` runs the main `server.py` service with Gunicorn and includes the model artifacts. Firebase Admin uses Application Default Credentials; local service-account files should remain outside the repository.

## Architecture assessment and direction

The existing separation between image capture and server-side prediction is practical for an initial deployment, especially because it avoids running the model on the phone. The main architectural weakness is that clients write operational data directly to Firestore while prediction is a separate HTTP call. This duplicates business rules and complicates authentication, retries, schema consistency, and inspection-level reporting.

A gradual improvement would be:

1. Keep the mobile outbox as the durable record of captures and geospatial metadata.
2. Send online captures immediately to one authenticated, versioned HTTPS API. Keep the current one-attempt-during-flight behavior and let the user explicitly start deferred processing from the inspection report.
3. Have the API validate the user and inspection, run the model, and persist the result through one server-side data path. Use a stable capture ID for idempotency.
4. Store original images in object storage and keep image URLs, capture metadata, LCC predictions, and nitrogen mapping results in Firestore or a relational database, rather than making clients responsible for cross-service consistency.
5. Make the instructor web app use the same API for uploads, appointment updates, and reports. Firebase can remain the identity provider and real-time client data layer where useful.
6. Consider on-device inference only if the requirement becomes “predict without any network at the UAV.” That requires converting and validating the model on representative phone hardware and comparing its outputs with the Python service.

## Configuration and deployment notes

- `Android/local.properties` supplies `GEO_MAP_API_KEY` and `FLASK_SERVER_URL`; `Android/app/google-services.json` is needed for Firebase-backed mobile features. These credentials are not included in the repository.
- The web app imports `Web/src/environments/environment`, but that directory was absent in the inspected checkout. Unless supplied separately, the web app may not build or initialize Firebase.
- The prediction endpoint URL in the web service is currently a source constant and should be moved to environment-specific configuration before deployment.
- The documented Cloud Run URL and historic route names have differed in the repository. Confirm that the deployed service runs the current `PythonServer/server.py` and exposes `/svcprocess` before field testing.
- The model pickle must only be loaded from a trusted build artifact. Its scikit-learn version must remain compatible with the pinned dependency or the model should be retrained and re-exported.

## Notes

- The online and offline capture activities now request camera and location permissions together, continue initializing the screen while the system prompt is open, and resume camera/GPS initialization after a grant. A denied permission leaves capture unavailable and shows a message.
- The offline mobile workflow copies captures to `Pictures/SmartRiceCare` on Finish; a person still selects and uploads those files from the web app. The mobile app does not automatically upload offline captures. The online failed-capture batch is a separate one-time post-inspection prediction attempt. This is intentional, as the user should be able to decide at which point they should sync the failed data.
- Firebase and Maps credentials are environment-specific and absent from this checkout.

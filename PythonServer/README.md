# Paddy image prediction API

The Flask service masks green pixels, calculates RGB features, reads camera exposure metadata, and classifies the image with the bundled scikit-learn SVC model. `svcmodel.pkl` was serialized with scikit-learn 1.0.2, so that version is pinned in `requirements.txt` for compatibility.

## API

- `POST /process` or `POST /svcprocess`: multipart field `image`; returns the predicted LCC level as text.
- `POST /svcprocessfire`: accepts `image` and a JSON `field_data` multipart file containing `longitude`, `latitude`, `officerId`, `requestId`, and `timestamp`; writes the prediction to the `TestingFieldData` Firestore collection and returns the level.
- `GET /`: basic service response.

Prediction-only routes work without Firebase credentials. The Firestore route uses Application Default Credentials. For local development, set `GOOGLE_APPLICATION_CREDENTIALS` to a service-account JSON file; keep that file outside the repository. In Cloud Run, attach a service account with the required Firestore access.

## Run locally

```sh
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python server.py
```

The local server listens on port 8080 by default. Build the container from this directory with `docker build -t paddy-prediction .`; it runs under Gunicorn and listens on `$PORT` (8080 by default).

The Android app sends `image` to `/svcprocess`. Configure its service base URL in `Android/local.properties` as `FLASK_SERVER_URL=https://your-service-host` (without a trailing slash). The web app should use the same HTTPS API URL; a `127.0.0.1` URL only refers to the user's own device.

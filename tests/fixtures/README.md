# Public test keys — NEVER use for deployment

These RSA keys are deliberately public fixtures copied from the backend's public
interop fixtures. They let CI test Java JWE round trips without any backend checkout,
production configuration, credentials or network access. They are not APK signing keys.

`generate-test-config.py` emits these constants only to `.cache/`, for JVM tests.
The APK build NEVER reads this directory or these fixtures. Its deployment
configuration is mandatory and must be provided separately.

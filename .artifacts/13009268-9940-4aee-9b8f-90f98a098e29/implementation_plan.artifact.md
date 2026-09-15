# Unify HTTP and HTSP Settings Management

The current implementation uses inconsistent `SharedPreferences` filenames and keys across different components (SetupActivity, TvheadendInputService, HtspProxyServer, SyncService, and TvhSettings). This causes settings changed in one place to be ignored by others. This plan unifies all components to use a single `TvhSettings` class as the source of truth.

## Proposed Changes

### [Core]

#### [MODIFY] [TvhSettings.kt](file:///C:/Users/nmacl/StudioProjects/android-tvheadend-tif/app/src/main/java/com/nmaclean/tvheadend/TvhSettings.kt)
Ensure this class is used as the central point for all settings and uses consistent keys.

### [UI]

#### [MODIFY] [SetupActivity.kt](file:///C:/Users/nmacl/StudioProjects/android-tvheadend-tif/app/src/main/java/com/nmaclean/tvheadend/SetupActivity.kt)
Update to use `TvhSettings` for reading and saving preferences, ensuring keys like `htsp_port`, `http_port`, `username`, and `password` are correctly mapped.

#### [MODIFY] [preferences.xml](file:///C:/Users/nmacl/StudioProjects/android-tvheadend-tif/app/src/main/res/xml/preferences.xml)
Add `htsp_port` to the settings screen if needed, though `SetupActivity` is the primary setup flow.

### [Background Services & Streaming]

#### [MODIFY] [TvheadendInputService.kt](file:///C:/Users/nmacl/StudioProjects/android-tvheadend-tif/app/src/main/java/com/nmaclean/tvheadend/TvheadendInputService.kt)
Replace direct `SharedPreferences` access with `TvhSettings`.

#### [MODIFY] [HtspProxyServer.kt](file:///C:/Users/nmacl/StudioProjects/android-tvheadend-tif/app/src/main/java/com/nmaclean/tvheadend/HtspProxyServer.kt)
Use `TvhSettings` and stop hardcoding the HTSP port.

#### [MODIFY] [SyncService.kt](file:///C:/Users/nmacl/StudioProjects/android-tvheadend-tif/app/src/main/java/com/nmaclean/tvheadend/SyncService.kt)
Use `TvhSettings` for channel synchronization.

#### [MODIFY] [HtspDataSource.kt](file:///C:/Users/nmacl/StudioProjects/android-tvheadend-tif/app/src/main/java/com/nmaclean/tvheadend/HtspDataSource.kt)
Use `TvhSettings` for ExoPlayer HTSP data source.

## Verification Plan

### Automated Tests
- I will verify that the project builds successfully after the changes.

### Manual Verification
1.  Open the Setup screen.
2.  Change the HTSP and HTTP ports.
3.  Save the settings.
4.  Verify (via logs or by testing playback) that the new ports are being used by `TvheadendInputService` and `HtspProxyServer`.

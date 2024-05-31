## Changelog

Note the first digit of every adapter version corresponds to the major version of the Chartboost Mediation SDK compatible with that adapter. 
Adapters are compatible with any Chartboost Mediation SDK version within that major version.

### 5.6.4.1.0
- This version of the adapter supports Chartboost Mediation SDK version 5.+.

### 4.6.4.1.1
- Fix for HyprMX not being capable of supporting ad queuing.

### 4.6.4.1.0
- This version of the adapter has been certified with HyprMX SDK 6.4.1.

### 4.6.4.0.0
- This version of the adapter has been certified with HyprMX SDK 6.4.0.

### 4.6.2.3.0
- This version of the adapter has been certified with HyprMX SDK 6.2.3.

### 4.6.2.0.5
- Add function allow publishers to set a boolean consent value for the HyprMX SDK consent info.

### 4.6.2.0.4
- Fix memory leaks that could occur when fullscreen ads are shown from an `Activity`.

### 4.6.2.0.3
- Only set user consent for HyprMX if their SDK has initialized. This prevents a `java.lang.Exception: HyprEvalError ReferenceError` from happening.

### 4.6.2.0.2
- Updated to handle recent AdFormat changes.

### 4.6.2.0.1
- Per HyprMX request: On initialization, always set ageRestrictedUser to true.

### 4.6.2.0.0
- This version of the adapter has been certified with HyprMX SDK 6.2.0.

### 4.6.0.3.0
- This version of the adapter has been certified with HyprMX SDK 6.0.3.

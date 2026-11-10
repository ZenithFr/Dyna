# Nothing Island keeps reflection-free code, so no custom rules are needed yet.
# Add rules here when new libraries are introduced.

# Accessibility and notification listener services; system binds by class name
-keep class com.zenith.nothingisland.service.IslandService { *; }
-keep class com.zenith.nothingisland.service.notification.IslandNotificationListener { *; }
# DataStore Preferences keys are referenced by string; keep generated Preferences classes
-keepnames class androidx.datastore.** { *; }

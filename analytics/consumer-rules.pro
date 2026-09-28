# Gson reads the payload fields by reflection. Keep them, and their names, when the app is minified.
-keepclassmembers class com.demo.analytics.internal.BatchPayload { <fields>; }
-keepclassmembers class com.demo.analytics.internal.EventPayload { <fields>; }

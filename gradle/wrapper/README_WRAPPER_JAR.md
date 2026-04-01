# Wrapper JAR placeholder

This repository snapshot intentionally uses a temporary placeholder `gradle-wrapper.jar` for PR flow constraints where binary files are being deferred.

To restore normal Gradle wrapper operation in a standard environment:

```bash
gradle wrapper --gradle-version 8.14.4
```

That command regenerates:
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar` (real binary)

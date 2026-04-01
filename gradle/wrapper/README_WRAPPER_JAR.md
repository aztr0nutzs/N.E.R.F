# Wrapper JAR placeholder

This repository snapshot uses a placeholder `gradle-wrapper.jar` file for review-only PR workflows where binary files are disallowed.

To restore normal Gradle wrapper operation in a standard environment:

```bash
gradle wrapper --gradle-version 8.14.4
```

That command regenerates:
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar` (real binary)

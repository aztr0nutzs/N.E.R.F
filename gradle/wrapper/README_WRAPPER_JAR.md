# Wrapper JAR placeholder (bootstrap blocked in this checkout)

In the **current checked-out tree**, `gradle/wrapper/gradle-wrapper.jar` is a temporary placeholder (not a valid wrapper binary), so Gradle wrapper bootstrap is blocked here.

Current observed behavior:

```bash
./gradlew --version
# Error: Invalid or corrupt jarfile /.../gradle/wrapper/gradle-wrapper.jar
```

To restore normal Gradle wrapper operation in a standard environment:

```bash
gradle wrapper --gradle-version 8.14.4
```

That command regenerates:
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar` (real binary)

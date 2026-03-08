# Running without kotlin-reflect

Android and desktop exclude `kotlin-reflect` from runtime configurations in `androidApp/build.gradle.kts`, `shared/build.gradle.kts` and `desktopApp/build.gradle.kts`. Ktor brings it in transitively, but the app does not use its reflective server paths.

## Requirements

Recheck the exclusion before upgrading Ktor or changing server initialization:

- `LocalDeviceServer` is the only production `embeddedServer` caller. Listen Together uses a WebSocket client.
- Build the server with `serverConfig { ... }` and direct lambda modules. Keep `MapApplicationConfig` empty so named `ktor.application.modules` cannot load.
- Force `developmentMode = false` and empty watch paths, even when a launcher sets `io.ktor.development=true`. This prevents reflective reload and lambda-to-method lookup.
- Use generated kotlinx.serialization serializers. Do not call reflective `receive(KClass)` or introduce reflective serialization.

`typeInfo<T>()`, stdlib `typeOf` and `kotlin.reflect.javaType` do not need the full library.

## Verification

Optional Ktor code still references `kotlin.reflect.full.KCallables`, `kotlin.reflect.full.KClasses` and `kotlin.reflect.jvm.ReflectJvmMapping`. ProGuard rules suppress those warnings and `KClassifiers` for the removed `receive(KClass)` path. Keep the suppression list narrow. A new call to the same missing helper may produce no new shrinker warning, so a successful build does not prove safety.

`:shared:testServerWithoutReflection` checks the embedded server with `ReflectionFactoryImpl` unavailable and the development property enabled. It runs through `:shared:desktopTest` with its own data directory. Select a suite with `:shared:testServerWithoutReflection --tests <suite>`.

Desktop package verification rejects reflection JARs or repackaged implementations. Check Android resolution with:

```bash
./gradlew :androidApp:dependencyInsight --configuration releaseRuntimeClasspath --dependency kotlin-reflect
```

It should find no dependency. After changing server behavior, also test Device Connect pairing/reconnect and Listen Together on a minified Android release. JVM tests cannot establish ART runtime behavior.

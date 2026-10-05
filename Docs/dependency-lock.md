# Dependency lock

| Thành phần | Version | Nơi khai báo |
|---|---|---|
| Unity | 6000.6.3f1 | `ProjectSettings/ProjectVersion.txt` |
| Gradle (Unity bundled) | 9.3.1 | Unity; `Native/StbHost/gradle/wrapper` |
| Android Gradle Plugin | 9.1.1 | Unity template; `Native/StbHost/build.gradle` |
| JDK (Unity bundled) | 17 | Unity `OpenJDK` |
| compileSdk / minSdk | 36 / 26 | `Native/StbHost/build.gradle`; Player Settings |
| Scripting / ABI | IL2CPP / ARM64 | Player Settings |
| androidx.media3 (exoplayer, exoplayer-hls) | 1.11.1 | `Native/StbHost/build.gradle` + `Assets/Plugins/Android/mainTemplate.gradle` |
| androidx.webkit | 1.17.1 | như trên |
| androidx.recyclerview | 1.4.0 | danh sách kênh native (StbHost) |
| UniTask | 2.5.11 (git tag) | `Packages/manifest.json` |
| com.unity.nuget.newtonsoft-json | 3.2.2 | `Packages/manifest.json` |

Đổi version Media3/webkit: sửa **cả hai** file Gradle, rồi `Native/StbHost/gradlew copyToUnity`.

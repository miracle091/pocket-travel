import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = ProjectConventions.COMPILE_SDK
            defaultConfig {
                minSdk = ProjectConventions.MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = ProjectConventions.JAVA_VERSION
                targetCompatibility = ProjectConventions.JAVA_VERSION
            }
            // APK dei test strumentati del modulo: come nell'app, queste librerie arrivano gia' senza simboli dalle
            // loro AAR (MapLibre, androidx.graphics, CameraX) e AGP segnala a ogni build che non riesce a "strippare".
            packaging {
                jniLibs {
                    keepDebugSymbols += setOf(
                        "**/libmaplibre.so",
                        "**/libandroidx.graphics.path.so",
                        "**/libimage_processing_util_jni.so",
                        "**/libsurface_util_jni.so",
                    )
                }
            }
        }
        configureKotlinJvmTarget()
        pluginManager.apply(DetektConventionPlugin::class.java)
    }
}

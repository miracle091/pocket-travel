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
        }
        configureKotlinJvmTarget()
        pluginManager.apply(DetektConventionPlugin::class.java)
    }
}
